package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用户消息怎么发出去、什么时候交棒、按停止键时各档怎么收场：发送 / 排队 / 打断 / 撤回 / 草稿退还
 * 这一整套状态机，连同它独占的轮次记账（轮次号、被打断的是哪一轮、开启本轮的那条消息）。
 *
 * 从 [ClaudeCodeManager] 里整段搬出来的。这些逻辑互相咬得很紧 —— 轮次号在发送、收尾、打断兜底、托管 deny 的
 * 过期判断里都要比对，散在各处就没人说得清「这一轮是谁的」—— 所以收在一处，Manager 只在事件到达时
 * 调用它（result 收尾、`requesting` 状态、握手完成），并通过 [ClaudeCodeSendQueue] 的两格语义保证不丢不重。
 *
 * 依赖收窄成这条路真用到的几样：会话状态、写 stdin、进程是不是正在关、草稿盘。**不认识 CLI 进程**，
 * 所以超时兜底可以用短超时直接测（见 `ClaudeCodeSendCoordinatorTest`）。
 */
internal class ClaudeCodeSendCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClaudeCodeManager.SessionState>,
    /** 写一行到 CLI 的 stdin（入队即发，顺序就是字节顺序） */
    private val write: (String) -> Unit,
    /** 进程正在主动关闭：这时写出去的字节只会喂给一个死进程，见 [flushPendingSend] */
    private val isClosing: () -> Boolean,
    private val drafts: ComposerDraftStore,
    private val newId: () -> String,
    /** CLI 对打断一直不回 result 时，多久后强制解除 busy（见 [interruptWith]） */
    private val interruptTimeoutMs: Long = INTERRUPT_TIMEOUT_MS,
    /** 一轮收尾后，等 CLI 拿走已交棒消息的上限（见 [watchHandedOff]） */
    private val handoffTimeoutMs: Long = HANDOFF_TIMEOUT_MS,
) {
    /**
     * 被 Esc 撤回、要退还给输入框的消息。一次性事件，不能放进 [ClaudeCodeManager.SessionState] ——
     * 状态会被重放，输入框就会在每次重组时被重新塞满。
     */
    private val _withdrawnMessages = MutableSharedFlow<ComposerDraft>(extraBufferCapacity = 4)
    val withdrawnMessages: SharedFlow<ComposerDraft> = _withdrawnMessages.asSharedFlow()

    /**
     * 每轮任务自增，用于给中断兜底做"还是同一轮吗"的判断。
     * 收尾（readLoop 线程）和中断兜底（scope 上的计时协程）会同时动它，所以是原子的
     */
    private val turnSeq = AtomicInteger(0)

    /**
     * 停止键打断的是哪一轮（打断那一刻的 [turnSeq]），以及那次是不是撤回。
     *
     * CLI 对打断回的是 `is_error` 的 result（`error_during_execution`，实测 2.1.280），
     * 不认出来的话，用户自己按了停止，对话流里还要挂一条红字「任务失败」。
     */
    @Volatile
    var interruptedTurn: Int? = null
        private set

    @Volatile
    var interruptWithdrew: Boolean = false
        private set

    /** `!` 命令跑完攒下的上下文，等下一条消息带走 */
    private val shellContext = StringBuilder()

    /** 追加消息的调度台。两格的含义、以及为什么是交棒而不是攥着，见 [ClaudeCodeSendQueue] */
    private val sendQueue = ClaudeCodeSendQueue<PendingSend>()

    /** 当前这一轮是哪条消息开的头。按 Esc 撤回时要把它退还给输入框 */
    @Volatile
    private var inFlight: PendingSend? = null

    /** 当前轮次号：下一次 [endTurn] 会返回它 */
    val currentTurn: Int get() = turnSeq.get()

    /**
     * result 到了，这一轮收尾：返回刚结束的那一轮的轮次号（自增放在 update 外面，
     * `MutableStateFlow.update` 的 lambda 在 CAS 失败时会重跑）。
     */
    fun endTurn(): Int = turnSeq.getAndIncrement()

    /** 打断标记读完就清：留着的话下一轮收尾会被误认成「用户打断的」（见 [judgeTurnEnd]） */
    fun clearInterruptMark() {
        interruptedTurn = null
    }

    /** 这一刻有没有一条由我们发出、还没收尾的消息在跑 */
    val hasTurnInFlight: Boolean get() = inFlight != null

    fun clearInFlight() {
        inFlight = null
    }

    /**
     * 进程没了（[ClaudeCodeManager] 的 shutdown）：开启本轮的那条随它作废；
     * [clearQueue] 为真时排队的消息落进草稿盘（换档传 false，续的是同一个会话，见 [reclaimHandedOff]）。
     */
    fun onTeardown(clearQueue: Boolean) {
        inFlight = null
        if (clearQueue) persistStrandedQueue()
    }

    /** 换档：旧进程至死没看过的已交棒消息放回队首，等新进程握手完重发（见 relaunchWith 的 ③） */
    fun reclaimHandedOff() {
        sendQueue.reclaim()
    }

    fun send(text: String, images: List<ClaudeCodeImage>, relay: SubagentThread?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && images.isEmpty()) return
        val current = state.value
        val label = when {
            trimmed.isNotEmpty() && images.isNotEmpty() -> "$trimmed\n[${images.size} 张图片]"
            images.isNotEmpty() -> "[${images.size} 张图片]"
            else -> trimmed
        }
        // 已经关掉 / 起不来的会话没有 stdin 可写。文本不能就这么吞掉 —— 原样退回输入框，
        // 用户改改还能在下一个会话里发出去
        if (current.status != SessionStatus.Running && current.status != SessionStatus.Starting) {
            emitWithdrawn(PendingSend(newId(), trimmed, images, label).toComposerDraft())
            return
        }
        val pending = PendingSend(newId(), trimmed, images, label, takeShellContext(), relay)
        if (current.status != SessionStatus.Running) {
            // 启动中打的字：攥着，握手把 cwd 切好之后再发（handshake 末尾 flushPendingSend）
            sendQueue.hold(pending)
            state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label, queued = true, handoff = pending.handoff))
            }
            return
        }
        if (current.busy) {
            // 追加：只往对话流里放一条标着「排队中」的消息，不碰正在跑的那一轮的
            // 正文、计时和 token —— 否则界面上正在打的字突然消失，计时也从零开始。
            // 帧本身立刻出手，插入时机由 CLI 定。
            state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label, queued = true, handoff = pending.handoff))
            }
            handOff(pending)
        } else {
            state.update {
                it.copy(items = it.items + ChatItem.UserText(pending.itemId, label, handoff = pending.handoff))
            }
            dispatchSend(pending)
        }
    }

    /**
     * 真正把一条消息写出去，并把「新的一轮开始了」这件事落到状态上。
     *
     * 首发和排队消息轮到时走的是同一条路，所以轮次归零只需要写在这里一处。
     */
    private fun dispatchSend(pending: PendingSend) {
        inFlight = pending
        state.update {
            it.copy(
                busy = true,
                turnProduced = false,
                streamingText = "",
                streamingThinking = "",
                retryNotice = null,
                tasks = it.tasks.filter { t -> t.isRunning },
                turnStartedAt = System.currentTimeMillis(),
                outputTokensSettled = 0,
                outputTokensCurrent = 0,
                lastTurnDurationMs = null,
                lastTurnFinishedAt = null,
                lastTurnEnd = null,
                // 轮到它了，摘掉「排队中」的标记
                items = it.items.map { item ->
                    if (item.id == pending.itemId && item is ChatItem.UserText) item.copy(queued = false)
                    else item
                },
            )
        }
        write(encodeClaudeCodeUserMessage(pending.wireText, pending.images))
    }

    /** 还有没有没被模型看见的消息（攥在手里的 + 交棒了还没确认插入的） */
    fun hasQueuedWork(): Boolean = sendQueue.hasWork()

    /** 交棒：帧写进 stdin，副本留在调度台上等 CLI 确认 */
    private fun handOff(pending: PendingSend) {
        sendQueue.handOff(pending) { write(encodeClaudeCodeUserMessage(it.wireText, it.images)) }
    }

    /**
     * 把还攥在手里的消息全部交棒出去（会话刚就绪、或打断之后要回来重发）。
     *
     * 调用点必须保证此刻 `busy` 已经是「有活 → 仍为 true」，否则界面会闪一下空闲态。
     *
     * 进程正在关（[ClaudeCodeCliPipe.stopping]）就什么都别发：换档时 shutdown 会给旧 CLI 最多 4 秒自己退，
     * 它很可能在那期间把本轮跑完、吐一帧 `result`，而 result 的收尾正好会调到这里
     * （见 [ClaudeCodeManager] 的 onResult）。那时 writer 已经是 null，发出去只是把消息喂给一个死进程 ——
     * 队列留着不动，等新进程握手完再发才不会丢。
     */
    fun flushPendingSend(): Boolean {
        if (isClosing()) return false
        val queued = sendQueue.drainHeld()
        if (queued.isEmpty()) return false
        // 第一条要把「新的一轮开始了」落到状态上（计时、token 归零）；
        // 后面的只是追加，CLI 会把它们插进同一轮。
        dispatchSend(queued.first())
        queued.drop(1).forEach { handOff(it) }
        return true
    }

    /**
     * CLI 要发请求了 —— 队列就是在这一刻被排空的，所以此前交棒的都已经进了这次请求的上下文。
     * 把它们的「排队中」标记摘掉（Esc 不必再为它们重发）。
     */
    fun confirmHandedOff() {
        val confirmed = sendQueue.confirm()
        if (confirmed.isEmpty()) return
        val ids = confirmed.mapTo(mutableSetOf()) { it.itemId }
        state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item is ChatItem.UserText && item.id in ids && item.queued) {
                        item.copy(queued = false)
                    } else {
                        item
                    }
                },
            )
        }
    }

    /**
     * 一轮收尾时调度台上还有已交棒未确认的：我们赌 CLI 会拿它另起一轮，于是 busy 继续挂着。
     * 赌输了（协议漂移、进程半死）就不能让输入坞永远禁用 —— 这里是兜底。
     *
     * [seqAtResult] 是收尾那一刻的轮次号；期间真的开了新一轮的话它会变，兜底就不该动手。
     */
    fun watchHandedOff(seqAtResult: Int) {
        if (!hasQueuedWork()) return
        scope.launch {
            delay(handoffTimeoutMs)
            if (turnSeq.get() != seqAtResult || !hasQueuedWork()) return@launch
            Log.w(TAG, "handed-off message never started a turn, clearing busy")
            confirmHandedOff()
            state.update {
                it.copy(
                    busy = false,
                    items = it.items + ChatItem.Note(
                        newId(),
                        "追加的消息发出去了，但 CLI 一直没接手",
                        isError = true,
                    ),
                )
            }
        }
    }

    /**
     * 停止键 = 电脑上的 **Esc**。按官方终端的三档行为分派：
     *
     * 1. **有排队的消息** → 打断当前轮，排队那条紧接着开跑。终端里正是这样：
     *    任务跑着的时候补一句、等不及了按 Esc，它立刻掉头处理新的那条。
     * 2. **本轮还一个字都没产出** → 这条根本没开始，撤回它：对话流里摘掉那条消息，
     *    原文退还给输入框，界面回到发送前。
     * 3. **其余** → 就是打断。
     *
     * 追加的那条现在是**立刻交棒**给 CLI 的（见 [ClaudeCodeSendQueue]），但 interrupt 帧带
     * `cancel_queued`，CLI 侧队列会连同这一轮一起被清掉 —— 所以第 1 档要把我们留的副本
     * 要回来攥住，等这一轮收尾时原样重发。副本在 CLI 确认吃下之前一直留着
     * （[confirmHandedOff]），因此这条路不会丢消息，也不会重复发。
     *
     * 第 2 档撤回的消息则已经写进了 CLI 的 stdin：无头协议只有 interrupt，没有 rewind，
     * 所以 CLI 的上下文里很可能仍留着它。界面与模型记忆在这一点上可能对不齐，
     * 代价是下一轮模型也许会看到被撤回的那一版。
     *
     * CLI 正常会回一个 result 帧来解除 busy；万一没回（协议版本差异、进程半死），
     * 这里有个超时兜底，否则输入框会永久禁用。
     */
    fun interrupt() {
        val current = state.value
        interruptWith(
            escapeAction(
                busy = current.busy,
                hasQueued = hasQueuedWork(),
                turnProduced = current.turnProduced,
            ),
        )
    }

    /**
     * 停止键的另一种默认：**全停，排队的话退回输入框**（设计 §10.2 拍板 2，与 Codex 的 ■ 同口径），
     * 不是 [interrupt] 第 1 档那样「打断后排队的紧接着跑」。后者仍由 [interrupt] 保留，给显式的「打断后跑排队」用。
     *
     * 先把没被模型看见的消息（攥着的 + 已交棒未确认的）全部从发送队列取走，对话流里摘掉它们的「排队中」气泡，
     * 合成**一份**草稿交给 [emitWithdrawn]（没人收就落盘，话不会丢），再写 interrupt 帧：
     * 帧带 `cancel_queued`，CLI 侧队列随之清空，所以不会有哪一条还被偷偷跑掉，收尾时 [flushPendingSend] 也无副本可发。
     * 开启本轮的那条留着（与第 1 档一致，不是第 2 档的撤回）。
     *
     * 取走与 CLI 确认之间有一道窄缝：某条恰在取走后、interrupt 帧到达前被 CLI 吃进请求，它会既在上下文里又退回了输入框。
     * 比起「按了 ■ 排队的还照跑」这是更可接受的一边，且 [confirmHandedOff] 的窗口只有一个请求间隔。
     *
     * 没有排队的消息时就是普通的 [interrupt]（撤回 / 打断两档照旧）；没在跑则什么都不做。
     */
    fun interruptReturningQueued() {
        val current = state.value
        if (!current.busy) return
        val stranded = sendQueue.drain()
        if (stranded.isEmpty()) {
            interrupt()
            return
        }
        val ids = stranded.mapTo(mutableSetOf()) { it.itemId }
        state.update { st -> st.copy(items = st.items.filterNot { it.id in ids }) }
        // 它们从没被模型看见：带着的 `!` 输出放回去，等用户重发时再带走（与 refundHeldMessages 同口径）
        stashShellContext(stranded.joinToString("\n") { it.shellContext }.trim())
        emitWithdrawn(mergeDrafts(stranded.map { it.toComposerDraft() }))
        // 排队的已经取走：hasQueued 恒为假，这里只在「撤回开启本轮的那条」与「打断」之间选，
        // 而有排队时用户的意思是停这一轮，不是撤回它，所以一律按打断
        interruptWith(EscapeAction.Interrupt)
    }

    /** 几条排队消息合成一份草稿：正文空一行隔开，图片按合并后的次序重排名字 */
    private fun mergeDrafts(drafts: List<ComposerDraft>): ComposerDraft = ComposerDraft(
        text = drafts.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
        images = drafts.flatMap { it.images }.mapIndexed { i, img -> img.copy(name = "图片 ${i + 1}") },
    )

    private fun interruptWith(action: EscapeAction) {
        if (action == EscapeAction.Nothing) return
        if (action == EscapeAction.Withdraw) withdrawInFlight()
        if (action == EscapeAction.InterruptThenQueued) sendQueue.reclaim()
        interruptWithdrew = action == EscapeAction.Withdraw

        // 一次读完：两次 get 之间若 result 恰好落地，interruptedTurn 会标到下一轮头上
        val seq = turnSeq.get()
        interruptedTurn = seq
        write(encodeClaudeCodeInterrupt(UUID.randomUUID().toString()))
        scope.launch {
            delay(interruptTimeoutMs)
            // 比较和自增必须是一步：这期间正常收尾也可能恰好到了，两边只能有一边算数
            if (state.value.busy && turnSeq.compareAndSet(seq, seq + 1)) {
                Log.w(TAG, "interrupt timed out, force-clearing busy")
                inFlight = null
                val queuedNext = hasQueuedWork()
                val noteId = newId()
                val now = System.currentTimeMillis()
                state.update {
                    it.copy(
                        busy = queuedNext,
                        turnProduced = false,
                        permissionQueue = emptyList(),
                        // CLI 没回 result：按用户停止记（撤回的话那条消息已经退回输入框，不该出 ▶）
                        lastTurnEnd = ClaudeTurnEnd(
                            if (interruptWithdrew) ClaudeTurnEnd.Kind.Withdrawn else ClaudeTurnEnd.Kind.Interrupted, now,
                        ),
                        items = it.items + ChatItem.Note(noteId, "中断超时，已强制解除等待状态", isError = true),
                    )
                }
                // CLI 没应答也不能把排队的消息困死在队列里
                flushPendingSend()
            }
        }
    }

    /**
     * 把开启本轮的那条消息撤回输入框：对话流里摘掉它，原文交给 [withdrawnMessages]。
     *
     * 附件和折叠的粘贴在 [ComposerDraft] 里本来就是独立字段，但到了这一层只剩
     * 合成好的正文（见输入坞的 `composeMessage`），所以原样退回正文即可 ——
     * 退回去的东西和"再按一次发送"会发出去的东西完全一致，这比还原成几个 chip 更重要。
     */
    private fun withdrawInFlight() {
        val pending = inFlight ?: return
        inFlight = null
        state.update { st ->
            st.copy(items = st.items.filterNot { it.id == pending.itemId })
        }
        emitWithdrawn(pending.toComposerDraft())
    }

    /** 排队消息 → 输入框草稿，和「再按一次发送」会发出去的东西完全一致 */
    private fun PendingSend.toComposerDraft(): ComposerDraft = ComposerDraft(
        text = text,
        images = images.mapIndexed { i, img ->
            DraftImage(name = "图片 ${i + 1}", mediaType = img.mediaType, base64 = img.base64)
        },
    )

    /**
     * 把退还的草稿交给输入框；**没人收就落盘**，下次打开这个会话时草稿自然恢复。
     *
     * 为什么不能只看 `tryEmit` 的返回值：[withdrawnMessages] 是 replay=0 的 SharedFlow，
     * **没有订阅者时 tryEmit 照样返回 true，值直接蒸发**（会话页未组合 / 切后台就是这样）；
     * 订阅者太慢、积满 extraBufferCapacity 时才返回 false。而退还路径已经把对话流里的
     * 条目摘掉了，原文再丢就是真的没了 —— 所以这两种情况都转存 [ComposerDraftStore]。
     * 合并语义和输入框的 prepend 一致：退回来的接在已有草稿前面。
     */
    private fun emitWithdrawn(draft: ComposerDraft) {
        val received = _withdrawnMessages.subscriptionCount.value > 0 &&
            _withdrawnMessages.tryEmit(draft)
        if (received) return
        persistDraft(draft)
    }

    /**
     * teardown 清队列之前，把还没被模型看见的消息落进草稿盘。
     *
     * [ClaudeCodeManager.dispose]（通知「停止全部」、关会话）和 onCliExit（进程崩溃）都不走 [refundHeldMessages]：
     * 前者没有输入框可退，后者连 held 都捞不着。直接 [ClaudeCodeSendQueue.clear] 的话，
     * 这些话连 [emitWithdrawn] 的磁盘兜底都不经过，重开这个会话就没了。
     * 这里只落盘、不发 [withdrawnMessages]：teardown 时没有订阅者，发了也是蒸发。
     */
    private fun persistStrandedQueue() {
        val stranded = sendQueue.drain()
        if (stranded.isEmpty()) return
        stranded.asReversed().forEach { persistDraft(it.toComposerDraft()) }
        stashShellContext(stranded.joinToString("\n") { it.shellContext }.trim())
    }

    /** 把一份草稿按 [emitWithdrawn] 的合并规则写进草稿盘，不经过订阅者那一路 */
    private fun persistDraft(draft: ComposerDraft) {
        val sessionId = state.value.sessionId ?: return
        val existing = drafts.load(sessionId)
        val mergedText = when {
            draft.text.isBlank() -> existing.text
            existing.text.isBlank() -> draft.text
            else -> draft.text + "\n" + existing.text
        }
        drafts.save(sessionId, existing.copy(text = mergedText, images = draft.images + existing.images))
    }

    /**
     * 失败路径的兜底退还：进程起不来 / 握手失败时，把还攥在手里（held）的消息
     * 原样退回输入框，并摘掉对话流里对应的「排队中」条目 —— 不留永远发不出去的幽灵。
     *
     * 只碰 held：已交棒（handedOff）的帧字节已经进了 CLI 的 stdin，可能真被模型看见过，
     * 退回输入框会让用户重发一遍、上下文里出现两遍。
     *
     * **必须在 Manager 的 shutdown 之前调用**：shutdown 会 `sendQueue.clear()`，之后再捞就只剩空队列。
     */
    fun refundHeldMessages(note: String) {
        val stranded = sendQueue.drainHeld()
        if (stranded.isEmpty()) return
        val ids = stranded.mapTo(mutableSetOf()) { it.itemId }
        state.update { st ->
            st.copy(
                items = st.items.filterNot { it.id in ids } +
                    ChatItem.Note(newId(), note, isError = true),
            )
        }
        // 输入框是「接在已有内容前面」（见 ClaudeCodeInputBar），倒着发才保得住原来的先后
        stranded.asReversed().forEach { emitWithdrawn(it.toComposerDraft()) }
        // 它们从没写出去过：带着的 `!` 输出放回去，等用户重发时再带走
        stashShellContext(stranded.joinToString("\n") { it.shellContext }.trim())
    }

    fun stashShellContext(block: String) {
        if (block.isBlank()) return
        synchronized(shellContext) {
            if (shellContext.isNotEmpty()) shellContext.append('\n')
            shellContext.append(block)
        }
    }

    fun takeShellContext(): String = synchronized(shellContext) {
        shellContext.toString().also { shellContext.setLength(0) }
    }


    companion object {
        // 和 Manager 共用一个 TAG：排查时 `logcat -s ClaudeCodeManager` 一条命令就能看全
        private const val TAG = "ClaudeCodeManager"

        const val INTERRUPT_TIMEOUT_MS = 15_000L
        const val HANDOFF_TIMEOUT_MS = 20_000L
    }
}

/**
 * 一条用户消息在离开我们视线之前的样子。
 * [label] 是对话流里那条 [ChatItem.UserText] 的文本，撤回时要按 [itemId] 把它摘掉。
 */
internal data class PendingSend(
    val itemId: String,
    val text: String,
    val images: List<ClaudeCodeImage>,
    val label: String,
    /** 之前 `!` 命令攒下的输入输出，附在这条前面交给模型（见 UserShell.kt）；界面上不显示 */
    val shellContext: String = "",
    /** 这句是请主会话转交给某个已停的子 agent 的（见 [ClaudeCodeManager.sendToSubagent]）；null = 说给主会话 */
    val relay: SubagentThread? = null,
) {
    /** 真正写进 stdin 的正文 */
    val wireText: String get() {
        val body = relay?.agentId?.let { relayToSubagentPrompt(it, relay.agentType, text) } ?: text
        return if (shellContext.isEmpty()) body else "$shellContext\n\n$body"
    }

    /** 气泡上那行小字：转交给谁 */
    val handoff: ChatItem.Handoff?
        get() = relay?.let { ChatItem.Handoff(it.agentType, ChatItem.HandoffState.Relayed) }
}

/**
 * 按停止键（= 电脑上的 Esc）该做什么。三档的判定抽出来，是因为"哪一档"
 * 完全由这三个布尔决定，而三档的后果差别很大（撤回一条消息 vs 打断一轮任务）。
 */
internal fun escapeAction(
    busy: Boolean,
    hasQueued: Boolean,
    turnProduced: Boolean,
): EscapeAction = when {
    !busy -> EscapeAction.Nothing
    // 排队的消息等着 —— 打断当前轮，让它紧接着开跑
    hasQueued -> EscapeAction.InterruptThenQueued
    // 请求刚发出、一个字都还没吐：用户的意思是"这条我不发了"
    !turnProduced -> EscapeAction.Withdraw
    else -> EscapeAction.Interrupt
}

internal enum class EscapeAction {
    /** 没在跑，按了也不该有事发生 */
    Nothing,

    /** 打断当前轮，并把开启它的那条消息撤回输入框 */
    Withdraw,

    /** 打断当前轮，排队的那条接着跑 */
    InterruptThenQueued,

    /** 就是打断 */
    Interrupt,
}
