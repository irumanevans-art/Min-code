package dev.min.code.core.codex

import android.util.Log
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.session.appendProcessOutputLine
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.workspace.ProcessTreeKill

fun interface CodexProcessLauncher {
    fun start(): Process
}

/**
 * 管一个 `codex app-server` 进程，把它的 JSONL 事件流转成聊天条目。
 *
 * ## 为什么状态里是 items 而不是一根 streamingText
 *
 * 上一版的 State 只有一个 `streamingText: String`，每次 `sendTurn` 清空 ——
 * 于是发第二条消息时，第一条的回答连同中间跑过的命令一起消失了，整页只剩最后
 * 一段还在生成的文字。它压根不是个会话界面。现在和 Claude 那边同构：
 * 一条 [ChatItem] 列表，增量只驱动「最后那条正在生成的气泡」。
 *
 * ## item 的生命周期
 *
 * `item/started` 建条目、各路 delta 打字、`item/completed` **整条替换**。
 * 官方文档把 completed 称作权威最终态，且明说计划正文允许和增量拼出来的不一致，
 * 所以这里绝不把 delta 攒出来的字符串当结果留下。
 *
 * ## 进程生命周期不归界面管
 *
 * 这个类以前 new 在 CodexVM 里，转个屏或退出页面就 `onCleared` → 进程被杀，
 * 一轮跑到一半的活直接没了。现在它是 DI 里的单例，页面只是它的观察者。
 */
class CodexAppServerManager(
    private val processLauncher: CodexProcessLauncher,
) : AutoCloseable {

    /** 启动一条线程时要交代清楚的事。默认值是手机上唯一说得通的那套。 */
    data class Options(
        val cwd: String = DEFAULT_CWD,
        val model: String? = null,
        val effort: String? = null,
        /**
         * 只读的话 Codex 连文件都改不了，全放开等于把 rootfs 交出去 ——
         * 工作区可写是唯一说得通的默认。
         */
        val sandbox: CodexSandbox = CodexSandbox.WORKSPACE_WRITE,
        val writableRoots: List<String> = listOf(DEFAULT_CWD),
        /** 关掉的话 guest 里 npm / pip 全部失败，而失败信息只会写在命令输出里 */
        val networkAccess: Boolean = true,
        val approvalPolicy: CodexApprovalPolicy = CodexApprovalPolicy.ON_REQUEST,
    )

    data class State(
        val status: SessionStatus = SessionStatus.Idle,
        val items: List<ChatItem> = emptyList(),
        val threadId: String? = null,
        val turnId: String? = null,
        /** 正在生成的正文。整条消息 completed 时清空并转成正式条目，UI 不要和 items 叠加 */
        val streamingText: String = "",
        val streamingThinking: String = "",
        val pendingApproval: CodexEvent.ApprovalRequest? = null,
        val busy: Boolean = false,
        val errorMessage: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
        val contextWindow: Int? = null,
        /** `thread/status/changed` 的 activeFlags，例如 waitingOnApproval */
        val activeFlags: List<String> = emptyList(),
        val turnStartedAt: Long? = null,
        val lastTurnDurationMs: Long? = null,
        /**
         * 排着的追加消息，FIFO。busy 时按发送就进这里，当前轮 completed 后逐条放出去。
         * 一轮失败或被打断时**留在这儿**而不是继续烧——见 [drainQueuedLocked]。
         */
        val queued: List<String> = emptyList(),
        /** 这条会话当前用的模型 / 思考强度。null = 没指定，走 codex 自己的默认 */
        val model: String? = null,
        val effort: String? = null,
    ) {
        /**
         * 能不能往里打字、按发送。
         *
         * **busy 不再拦**：busy 时按发送的含义是排队，不是拒绝。以前这里带 `&& !busy`，
         * 结果是一轮跑起来整个输入框变灰，连草稿都存不住。
         */
        val canSend: Boolean get() = status == SessionStatus.Running && threadId != null
    }

    private enum class RequestKind { Initialize, Thread, Turn }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(0)
    private val lock = Any()
    private val pendingRequests = mutableMapOf<String, RequestKind>()
    private val _state = MutableStateFlow(State())

    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 进程还活着（含正在启动）。前台服务按它决定要不要保活 ——
     * app-server 是 App 的子进程，没有前台服务撑着，切出去接个电话回来它就没了。
     *
     * Eagerly 而不是 WhileSubscribed：这是保活的依据，不能因为一时没人订阅就塌回 false。
     */
    val isLive: StateFlow<Boolean> = state
        .map { it.status == SessionStatus.Running || it.status == SessionStatus.Starting }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    /** 用户主动停的，用来区分"关掉了"和"死掉了" */
    private var stopping = false
    private var options = Options()
    private var resumeThreadId: String? = null
    /** 命令输出按 itemId 攒，delta 到达时刷进对应那张工具卡 */
    private val outputBuffers = mutableMapOf<String, StringBuilder>()
    /** 正在流式生成的那条正文 / 思考的 itemId，completed 时用来判断该清哪个缓冲 */
    private var streamingTextItemId: String? = null
    private var streamingThinkingItemId: String? = null
    /** 最后那条「未知方法」灰字，同一个方法连着来时就地累加次数，见 [appendUnknownNote] */
    private var unknownNoteId: String? = null
    private var unknownNoteLabel: String? = null
    private var unknownNoteCount = 0

    /** 启动进程并走完 initialize → initialized → thread/start|resume。 */
    fun start(options: Options = Options(), resumeThreadId: String? = null): Boolean = synchronized(lock) {
        val status = _state.value.status
        if (process != null || status == SessionStatus.Starting || status == SessionStatus.Running) {
            return false
        }
        stopping = false
        this.options = options
        this.resumeThreadId = resumeThreadId
        outputBuffers.clear()
        streamingTextItemId = null
        streamingThinkingItemId = null
        _state.value = State(
            status = SessionStatus.Starting,
            threadId = resumeThreadId,
            // 接着上一条聊时，已经摆在屏幕上的历史要留着 —— 点一下「继续」就清屏，
            // 用户会以为历史没了。开新会话（resumeThreadId 为空）才从白纸开始
            items = if (resumeThreadId != null) _state.value.items else emptyList(),
            model = options.model,
            effort = options.effort,
        )

        val launched = runCatching { processLauncher.start() }.getOrElse { error ->
            failLocked(error.message ?: error::class.java.simpleName)
            return false
        }
        process = launched
        writer = BufferedWriter(OutputStreamWriter(launched.outputStream, Charsets.UTF_8))
        stdoutJob = readStdout(launched)
        stderrJob = readStderr(launched)
        waitJob = watchExit(launched)

        val id = nextRequestId()
        pendingRequests[id] = RequestKind.Initialize
        if (!writeLineLocked(encodeCodexInitialize(id))) {
            failLocked("写不进 initialize 请求")
            return false
        }
        true
    }

    /**
     * 把磁盘上读回来的历史摆到界面上，**不碰进程**。
     *
     * 重进 App 时先让人看见上次聊到哪儿，再决定要不要接着跑 —— 而不是一进来就
     * 拉起一个进程。会话正活着时不覆盖：那时屏幕上的才是实时的。
     */
    fun restore(items: List<ChatItem>, threadId: String?): Boolean = synchronized(lock) {
        if (process != null || _state.value.status == SessionStatus.Running ||
            _state.value.status == SessionStatus.Starting
        ) {
            return false
        }
        if (items.isEmpty() && threadId == null) return false
        _state.value = _state.value.copy(items = items, threadId = threadId)
        true
    }

    /**
     * 指定 thread 的**只读回放**还摆在屏幕上、且没有进程在跑时，把它摘掉。
     * 会话删除用：文件已经没了，屏幕不能再摆着一份幽灵历史。
     * 跑着的（Running/Starting）不动 —— 那份状态是实时的，删除路径会先 stop。
     */
    fun clearIfIdle(threadId: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.threadId != threadId) return false
        if (current.status == SessionStatus.Running || current.status == SessionStatus.Starting) return false
        _state.value = State(status = SessionStatus.Idle)
        true
    }

    /**
     * 发一轮，或者排进队列。初始化没走完（还没有 threadId）之前一律返回 false。
     *
     * busy 时排队而不是拒绝，照的是 codex 自己的产品逻辑：TUI 里 Tab 就是把输入压进
     * 一个 FIFO、当前轮跑完再逐条放出来，另有 `codex queue --thread --message` 这个
     * 非交互入口——排队在 codex 那边是一等公民，不是 TUI 的糖。
     *
     * 队列只能攒在客户端：app-server 一轮只能有一个 turn，busy 时把 turn/start 写过去
     * 不会排队，只会撞上。这点和 Claude 侧相反——那边写进 stdin 的 user 帧会被 CLI
     * 插进当前轮，所以 [ClaudeCodeSendQueue] 有「交棒」那一格，这里没有，也不需要。
     */
    fun sendTurn(input: String): Boolean = synchronized(lock) {
        val current = _state.value
        if (current.status != SessionStatus.Running || current.threadId == null || input.isBlank()) {
            return false
        }
        if (current.busy) {
            _state.value = current.copy(queued = current.queued + input)
            return true
        }
        startTurnLocked(input)
    }

    /**
     * 换这一条会话的模型 / 思考强度，**不重启进程**。
     *
     * `turn/start` 每轮都从 [options] 现取 model 与 effort，所以改完下一轮就生效，
     * 当前这一轮按它开始时的档跑完——一轮跑到一半换模型没有意义，也不是 codex 支持的事。
     *
     * 只动这条会话。连接配置里那一份是**新会话的默认**，不跟着变：
     * 「这一轮让它想久一点」和「我平时用这个档」是两件事。
     */
    fun setModelAndEffort(model: String?, effort: String?) = synchronized(lock) {
        options = options.copy(
            model = model?.trim()?.takeIf(String::isNotBlank),
            effort = effort?.trim()?.takeIf(String::isNotBlank),
        )
        _state.value = _state.value.copy(model = options.model, effort = options.effort)
    }

    /**
     * 取回排着的第 [index] 条（给界面上点一下放回输入框用）。越界返回 null。
     *
     * codex 自己的队列是只能看不能改的（openai/codex#28864 就是在提这件事），
     * 这里既然队列本来就在客户端手里，顺手让它可撤。
     */
    fun takeQueued(index: Int): String? = synchronized(lock) {
        val current = _state.value
        val item = current.queued.getOrNull(index) ?: return null
        _state.value = current.copy(queued = current.queued.filterIndexed { i, _ -> i != index })
        item
    }

    /** 真正把一轮写出去。调用方需持锁，且已确认 Running / 有 threadId / 不 busy */
    private fun startTurnLocked(input: String): Boolean {
        val current = _state.value
        val threadId = current.threadId ?: return false

        val id = nextRequestId()
        pendingRequests[id] = RequestKind.Turn
        // 用户那条先进列表，不等服务端回显 —— 手机上点了发送却半秒没动静会让人再点一次
        _state.value = current.copy(
            items = current.items + ChatItem.UserText(nextLocalId("user"), input),
            streamingText = "",
            streamingThinking = "",
            turnId = null,
            busy = true,
            errorMessage = null,
            turnStartedAt = System.currentTimeMillis(),
        )
        streamingTextItemId = null
        streamingThinkingItemId = null

        val line = encodeCodexTurnStart(
            requestId = id,
            threadId = threadId,
            text = input,
            model = options.model,
            effort = options.effort,
            sandbox = options.sandbox,
            writableRoots = options.writableRoots,
            networkAccess = options.networkAccess,
            approvalPolicy = options.approvalPolicy,
        )
        if (!writeLineLocked(line)) {
            pendingRequests.remove(id)
            failLocked("写不进 turn/start 请求")
            return false
        }
        return true
    }

    /**
     * 一轮跑完，放出排着的下一条。
     *
     * **只在 completed 之后放**。failed 的话继续放等于拿同一个错误把整个队列一条条烧掉
     * （401 这种会一路烧到底）；interrupted 是用户自己按的停，更不该接着跑。
     * 那两种情况队列原地留着，界面上看得见、点得动、删得掉——
     * openai/codex#37974 抱怨的 stranded queue 正是"留着但碰不到"。
     */
    private fun drainQueuedLocked() {
        val next = _state.value.queued.firstOrNull() ?: return
        _state.value = _state.value.copy(queued = _state.value.queued.drop(1))
        startTurnLocked(next)
    }

    /**
     * 应答审批。[decision] 必须在服务端给的 `availableDecisions` 里 ——
     * 回一个它不认的值不会报错，只会让这一轮一直停在那儿等。
     */
    fun answerApproval(decision: CodexDecision): Boolean = synchronized(lock) {
        val pending = _state.value.pendingApproval ?: return false
        val allowed = pending.availableDecisions
        if (allowed.isNotEmpty() && decision.wire !in allowed) return false
        // rawRequestId 是请求帧的原始 JSON 形态（整数 id 就回整数）；
        // 回成字符串在 Rust 侧是另一个值，回调表查不中，审批永远挂住
        if (!writeLineLocked(encodeCodexApprovalResponse(pending.rawRequestId, decision))) return false
        _state.value = _state.value.copy(pendingApproval = null)
        true
    }

    /**
     * 停掉进程。
     *
     * 杀进程树要走 /proc 遍历加轮询等待，是**阻塞**活儿；以前它在 `synchronized` 块里，
     * 而 stop() 是界面上那个按钮直接调的 —— 主线程拿着锁去遍历 /proc，就是一次 ANR。
     * 现在锁里只做状态交接，真正的 kill 丢到 IO 上。
     */
    fun stop() {
        val doomed = synchronized(lock) { detachLocked(failed = false, message = null) }
        doomed?.let { killAsync(it) }
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // 读循环
    // -----------------------------------------------------------------------

    private fun readStdout(activeProcess: Process): Job = scope.launch {
        runCatching {
            BufferedReader(InputStreamReader(activeProcess.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line -> parseCodexEvent(line)?.let(::handleEvent) }
            }
        }.onFailure { Log.w(TAG, "codex stdout 读取结束", it) }
    }

    /**
     * stderr 不能只是排干 —— 那是 app-server 唯一会说「我为什么起不来」的地方
     * （缺 node、认证过期、端口被占）。收进聊天流，默认折叠。
     */
    private fun readStderr(activeProcess: Process): Job = scope.launch {
        runCatching {
            BufferedReader(InputStreamReader(activeProcess.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    synchronized(lock) {
                        _state.value = _state.value.copy(
                            items = appendProcessOutputLine(_state.value.items, line, nextLocalId("stderr")),
                        )
                    }
                }
            }
        }.onFailure { Log.w(TAG, "codex stderr 读取结束", it) }
    }

    private fun watchExit(activeProcess: Process): Job = scope.launch {
        val exitCode = runCatching { activeProcess.waitFor() }.getOrNull() ?: return@launch
        synchronized(lock) {
            // 已经换了一个进程就不要去动新进程的状态
            if (process !== activeProcess) return@synchronized
            writer = null
            process = null
            pendingRequests.clear()
            _state.value = if (stopping) {
                _state.value.copy(status = SessionStatus.Closed, busy = false, pendingApproval = null)
            } else {
                _state.value.copy(
                    status = SessionStatus.Failed,
                    busy = false,
                    pendingApproval = null,
                    errorMessage = "codex app-server 退出，退出码 $exitCode",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 事件
    // -----------------------------------------------------------------------

    private fun handleEvent(event: CodexEvent) = synchronized(lock) {
        val current = _state.value
        when (event) {
            is CodexEvent.Response -> handleResponseLocked(event)

            is CodexEvent.ThreadStarted -> _state.value = current.copy(
                status = SessionStatus.Running,
                threadId = event.threadId,
                errorMessage = null,
            )

            is CodexEvent.TurnStarted -> _state.value = current.copy(
                turnId = event.turnId,
                busy = true,
                turnStartedAt = current.turnStartedAt ?: System.currentTimeMillis(),
            )

            is CodexEvent.TurnCompleted -> {
                _state.value = current.copy(
                    turnId = event.turnId ?: current.turnId,
                    busy = false,
                    // 一轮结束时把没来得及 completed 的增量收尾，不然它会一直挂在那儿
                    streamingText = "",
                    streamingThinking = "",
                    items = current.items.settleStreaming(current),
                    errorMessage = event.errorMessage ?: current.errorMessage,
                    lastTurnDurationMs = current.turnStartedAt?.let { System.currentTimeMillis() - it },
                    turnStartedAt = null,
                )
                streamingTextItemId = null
                streamingThinkingItemId = null
                // status ∈ completed / interrupted / failed，只有干净跑完才放下一条
                if (event.status == TURN_COMPLETED) drainQueuedLocked()
            }

            is CodexEvent.ItemStarted -> {
                if (event.item.type == USER_MESSAGE_ITEM) return@synchronized
                val chat = event.item.toChatItem(nextLocalId("item"))
                _state.value = current.copy(items = current.items.upsert(chat))
            }

            is CodexEvent.ItemCompleted -> {
                // 服务端把用户那句话也当成一个 item 回显，而 sendTurn 为了即时反馈
                // 已经在本地记过一条了 —— 两边都收就会看到自己说的话出现两遍
                if (event.item.type == USER_MESSAGE_ITEM) return@synchronized
                val chat = event.item.toChatItem(nextLocalId("item"))
                outputBuffers.remove(event.item.id)
                // 权威最终态到了，对应的增量缓冲作废
                val clearText = event.item.id == streamingTextItemId || event.item.type == ITEM_AGENT_MESSAGE
                val clearThinking = event.item.id == streamingThinkingItemId || event.item.type == ITEM_REASONING
                if (clearText) streamingTextItemId = null
                if (clearThinking) streamingThinkingItemId = null
                _state.value = current.copy(
                    items = current.items.upsert(chat),
                    streamingText = if (clearText) "" else current.streamingText,
                    streamingThinking = if (clearThinking) "" else current.streamingThinking,
                )
            }

            is CodexEvent.AgentMessageDelta -> {
                streamingTextItemId = event.itemId ?: streamingTextItemId
                _state.value = current.copy(streamingText = current.streamingText + event.delta)
            }

            is CodexEvent.ReasoningDelta -> {
                streamingThinkingItemId = event.itemId ?: streamingThinkingItemId
                _state.value = current.copy(streamingThinking = current.streamingThinking + event.delta)
            }

            is CodexEvent.ReasoningPartAdded -> {
                val thinking = current.streamingThinking
                // 摘要分段之间空一行，否则几段会连成一堵墙
                if (thinking.isNotBlank() && !thinking.endsWith("\n\n")) {
                    _state.value = current.copy(streamingThinking = thinking.trimEnd() + "\n\n")
                }
            }

            // 命令输出**不进正文**，按 itemId 刷进那张 Bash 卡
            is CodexEvent.CommandOutputDelta -> {
                val itemId = event.itemId ?: return@synchronized
                val buffer = outputBuffers.getOrPut(itemId) { StringBuilder() }.append(event.delta)
                _state.value = current.copy(
                    items = current.items.map { item ->
                        if (item.id == itemId && item is ChatItem.ToolCall) {
                            item.copy(result = buffer.toString())
                        } else {
                            item
                        }
                    },
                )
            }

            is CodexEvent.PlanDelta -> {
                // 计划增量同样要登记 itemId。不登记的话 plan 的 item/completed 既对不上
                // id、type 又不是 agentMessage，气泡里的计划文字清不掉，和正式条目
                // 重复显示到整轮结束；同一轮里再来的 agentMessage 还会带着它当前缀
                streamingTextItemId = event.itemId ?: streamingTextItemId
                _state.value = current.copy(streamingText = current.streamingText + event.delta)
            }

            is CodexEvent.ApprovalRequest ->
                _state.value = current.copy(pendingApproval = event)

            is CodexEvent.TokenUsage -> _state.value = current.copy(
                inputTokens = event.inputTokens ?: current.inputTokens,
                outputTokens = event.outputTokens ?: current.outputTokens,
                totalTokens = event.totalTokens ?: current.totalTokens,
                contextWindow = event.contextWindow ?: current.contextWindow,
            )

            is CodexEvent.ThreadStatus ->
                _state.value = current.copy(activeFlags = event.activeFlags)

            is CodexEvent.Unknown -> {
                Log.d(TAG, "未知的 codex 方法：${event.method}")
                // 协议层（CodexEvent.Unknown）的承诺：宁可在界面上留一条灰字，
                // 也别静默吞掉——凭空少一段可查才是最糟的
                _state.value = current.copy(items = appendUnknownNote(current.items, event.method))
            }
        }
    }

    private fun handleResponseLocked(response: CodexEvent.Response) {
        val kind = pendingRequests.remove(response.id) ?: return
        if (response.error != null) {
            val message = response.error.str("message") ?: "codex 请求 ${response.id} 失败"
            if (kind == RequestKind.Turn) {
                // 一轮失败不该把整条会话关掉，用户改一句还能接着问
                _state.value = _state.value.copy(busy = false, errorMessage = message)
            } else {
                failLocked(message)
            }
            return
        }
        when (kind) {
            RequestKind.Initialize -> {
                // 握手第二步：不发这条通知，后面每个请求都会被拒
                writeLineLocked(encodeCodexInitialized())
                val id = nextRequestId()
                pendingRequests[id] = RequestKind.Thread
                val request = resumeThreadId?.let { encodeCodexThreadResume(id, it) }
                    ?: encodeCodexThreadStart(
                        requestId = id,
                        cwd = options.cwd,
                        model = options.model,
                        effort = options.effort,
                        sandbox = options.sandbox,
                        writableRoots = options.writableRoots,
                        networkAccess = options.networkAccess,
                        approvalPolicy = options.approvalPolicy,
                    )
                if (!writeLineLocked(request)) failLocked("写不进 thread 请求")
            }

            RequestKind.Thread -> {
                val threadId = (response.result as? kotlinx.serialization.json.JsonObject)?.let { result ->
                    result.str("threadId") ?: result.obj("thread")?.str("id") ?: result.str("id")
                } ?: resumeThreadId
                if (threadId == null) {
                    failLocked("thread 应答里没有 id")
                } else {
                    _state.value = _state.value.copy(
                        status = SessionStatus.Running,
                        threadId = threadId,
                        errorMessage = null,
                    )
                }
            }

            RequestKind.Turn -> {
                val turnId = (response.result as? kotlinx.serialization.json.JsonObject)
                    ?.let { it.obj("turn")?.str("id") ?: it.str("turnId") }
                if (turnId != null) _state.value = _state.value.copy(turnId = turnId)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /**
     * 把进程和状态摘下来，返回那个待杀的进程。**只做交接，不做等待** ——
     * 调用方拿到之后在 IO 上杀，见 [stop]。
     */
    private fun detachLocked(failed: Boolean, message: String?): Process? {
        stopping = !failed
        val active = process
        process = null
        runCatching { writer?.close() }
        writer = null
        pendingRequests.clear()
        outputBuffers.clear()
        stdoutJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        stdoutJob = null
        stderrJob = null
        waitJob = null
        _state.value = _state.value.copy(
            status = if (failed) SessionStatus.Failed else SessionStatus.Closed,
            busy = false,
            pendingApproval = null,
            streamingText = "",
            streamingThinking = "",
            errorMessage = message,
        )
        return active
    }

    /**
     * 先按 /proc 的 ppid 自底向上杀 guest 进程树，再杀 proot 宿主。
     *
     * 只 destroy() 宿主是不够的：Android 上 `destroyForcibly` 只发 SIGTERM，
     * 而 proot 底下的 node 收不到，会连着端口一起留下来。这条教训见
     * .learnings/LRN-20260918-ANDROID-KILL-LIMITS。
     */
    private fun killAsync(doomed: Process) {
        scope.launch(NonCancellable) {
            runCatching {
                ProcessTreeKill.pidOf(doomed)?.let { pid ->
                    ProcessTreeKill.killTree(pid)
                    ProcessTreeKill.killHost(pid)
                }
            }.onFailure { Log.w(TAG, "杀 codex 进程树失败", it) }
            runCatching { doomed.destroy() }
        }
    }

    /**
     * 会话失败：状态**和进程一起**收掉。
     *
     * 以前这里只改 status。真机上的后果是：握手失败（配置非法、认证过期）之后界面显示
     * "failed"，而 proot → bash → node → codex 四层进程一个不少地留在进程表里，
     * 端口和内存都还占着，再点一次启动又叠一套上去。状态和事实必须一致。
     *
     * [killAsync] 只是往 IO 上扔一个任务，不阻塞，所以持锁调用是安全的。
     */
    private fun failLocked(message: String) {
        val doomed = detachLocked(failed = true, message = message)
        doomed?.let { killAsync(it) }
    }

    private fun writeLineLocked(line: String): Boolean = runCatching {
        val output = writer ?: return false
        output.write(line)
        output.newLine()
        output.flush()
    }.isSuccess

    private fun nextRequestId(): String = requestIds.incrementAndGet().toString()

    private fun nextLocalId(prefix: String): String = "$prefix-${requestIds.incrementAndGet()}"

    /**
     * 一轮结束时把还挂着的增量落成正式条目。
     *
     * 正常情况下每条 item 都会有 `item/completed` 收尾，这里是兜底：turn 被打断、
     * 或服务端漏发 completed 时，用户已经读到的那段文字不该凭空消失。
     */
    private fun List<ChatItem>.settleStreaming(current: State): List<ChatItem> {
        var result = this
        val thinking = current.streamingThinking.trim()
        if (thinking.isNotEmpty() && streamingThinkingItemId == null) {
            result = result.upsert(ChatItem.Thinking(nextLocalId("thinking"), thinking))
        }
        val text = current.streamingText.trim()
        if (text.isNotEmpty() && streamingTextItemId == null) {
            result = result.upsert(ChatItem.AssistantText(nextLocalId("assistant"), text))
        }
        return result
    }

    /**
     * 未知方法的灰字，连着来的同一个方法只占一行，后面挂个次数。
     *
     * 不认识的方法通常不是偶发而是成片来的：codex 换一个版本、多一类 item，
     * 同一个 method 一轮能刷几十上百条。一条一行的话正经内容全被顶出屏幕，
     * 而这些行彼此**没有新信息** —— 留一行加「×N」既说清了发生过什么、
     * 也说清了有多频繁，比刷屏可读得多。
     *
     * 只在它还是最后一条时合并。中间夹了真内容之后再来同一个方法，那是新的一次
     * ——回头去给上面那行加数字，会让计数跑到已经读过的位置去。
     */
    private fun appendUnknownNote(items: List<ChatItem>, method: String?): List<ChatItem> {
        val label = "未知的 codex 方法：${method ?: "(无方法名)"}"
        val last = items.lastOrNull()
        if (last is ChatItem.Note && last.id == unknownNoteId && unknownNoteLabel == label) {
            unknownNoteCount += 1
            return items.dropLast(1) + last.copy(text = "$label ×$unknownNoteCount")
        }
        val id = nextLocalId("unknown")
        unknownNoteId = id
        unknownNoteLabel = label
        unknownNoteCount = 1
        return items + ChatItem.Note(id, label)
    }

    /** 同 id 的整条替换，没有就追加 —— `item/completed` 靠它覆盖掉 started 那一版 */
    private fun List<ChatItem>.upsert(item: ChatItem): List<ChatItem> {
        val index = indexOfFirst { it.id == item.id }
        if (index < 0) return this + item
        val copy = toMutableList()
        copy[index] = item
        return copy
    }

    companion object {
        private const val TAG = "CodexAppServer"

        /** proot 的挂载点，和 Claude 那边同一个工作区 */
        const val DEFAULT_CWD = "/workspace"

        /** 服务端回显的用户消息 item，本地已经乐观插入过，见 handleEvent */
        private const val USER_MESSAGE_ITEM = "userMessage"

        /** `turn/completed` 里干净跑完的那个 status，另外两个是 interrupted / failed */
        private const val TURN_COMPLETED = "completed"
    }
}
