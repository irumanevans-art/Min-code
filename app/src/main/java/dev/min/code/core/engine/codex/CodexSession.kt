package dev.min.code.core.engine.codex

import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.codex.CODEX_EFFORT_LEVELS
import dev.min.code.core.codex.CODEX_REASONING_SUMMARIES
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.orUnset
import dev.min.code.core.codex.withPermission
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.CONTINUE_PROMPT
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandDeduper
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StopAction
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.stopActionFor
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn

/**
 * 一个 Codex 会话对通用层的样子：包住单例 [CodexAppServerManager]（设计 §4.2）。manager 一次只承载一个会话
 * （`maxConcurrent = 1`），所以 `CodexSession` 的寿命是「这个 threadId 在 manager 里的那一段」。
 *
 * - 停止（[SessionCommand.Interrupt]）= `turn/interrupt`，只停本轮、进程不死（B8）。有排队的话默认把它们退回输入框
 *   （§10.2 拍板 2，经 [SessionSignal.Withdrawn]），显式的「打断后跑排队」由 manager 在收场后放出下一条；
 * - ▶（[SessionCommand.Resume]）= 代发 [CONTINUE_PROMPT]；进程没了（读回放的旧会话、app-server 退出）先 `thread/resume`
 *   接回进程（[relaunch]）。
 *
 * @param key threadId
 * @param externals 映射要的外部信息（标题、当前档…）
 * @param searchFiles `@` 补全（`WorkspaceRepository.mentionFiles`，与引擎无关）
 * @param relaunch 把这条线程的 app-server 接回来（`prepare` 写配置 + `start(resumeThreadId)` + 等就绪），由引擎提供：
 *   它要 `CodexRuntime` 的配置，会话自己不持有
 */
class CodexSession internal constructor(
    val manager: CodexAppServerManager,
    key: String,
    parentScope: CoroutineScope,
    private val externals: StateFlow<CodexMapContext> = MutableStateFlow(CodexMapContext()),
    private val metaStore: ClaudeCodeSessionMetaStore? = null,
    private val searchFiles: suspend (String, Int) -> List<String> = { _, _ -> emptyList() },
    private val relaunch: suspend (threadId: String) -> Boolean = { false },
) : AgentSession {

    /** 自己的子作用域：close 时只取消本会话订阅的流，manager 是单例，不能连带取消 */
    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    override val ref: SessionRef = CodexStateMapper.codexRef(key)
    override val capabilities: EngineCapabilities = EngineCapabilities.Codex

    private val deduper = CommandDeduper()

    /** 被关掉了（[close] / [release]）：状态流已经停了，引擎不能再把它当活的会话交出去 */
    @Volatile
    private var closed = false
    internal val isClosed: Boolean get() = closed

    /** 把进程接回来要一次只有一个人在做：并发的两句话 / ▶ 不能各自 `start`（第二个会被拒） */
    private val relaunchLock = Mutex()

    private fun fresh(): AgentSessionState = CodexStateMapper.map(ref, manager.state.value, externals.value)

    /**
     * 此刻的完整状态，现算、不经 [state] 那条流。引擎的在线摘要读它：会话被关掉时 [close] 立刻取消本会话的作用域，
     * [state] 就停在关之前的那一帧（Running），摘要要是读它，关掉的会话会永远显示成「在线、要保活」。
     */
    internal fun snapshot(): AgentSessionState = fresh()

    override val state: StateFlow<AgentSessionState> = combine(manager.state, externals) { s, c ->
        CodexStateMapper.map(ref, s, c)
    }.stateIn(scope, SharingStarted.Eagerly, CodexStateMapper.map(ref, manager.state.value, externals.value))

    override val stream: StateFlow<LiveStream> = manager.state
        .map(CodexStateMapper::mapLive)
        .stateIn(scope, SharingStarted.Eagerly, CodexStateMapper.mapLive(manager.state.value))

    private val _signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 4)

    /** 目前只有撤回的排队消息。Codex 没有预览 URL 之类的事件 */
    override val signals: SharedFlow<SessionSignal> = _signals.asSharedFlow()

    override suspend fun submit(command: SessionCommand): CommandResult {
        if (!capabilities.supports(command)) return CommandResult.Unsupported
        if (!deduper.firstSeen(command.commandId)) return CommandResult.Duplicate
        val result = execute(command)
        if (result is CommandResult.Rejected || result == CommandResult.Unsupported) deduper.forget(command.commandId)
        return result
    }

    private suspend fun execute(command: SessionCommand): CommandResult = when (command) {
        is SessionCommand.Send -> send(command)
        is SessionCommand.Interrupt -> interrupt(command)
        is SessionCommand.Resume -> resume(command)
        is SessionCommand.WithdrawQueued -> withdrawQueued(command)
        is SessionCommand.Answer -> answer(command)
        is SessionCommand.SetModel -> {
            // Codex 没有供应商档这个维度（档在启动时固化进 config.toml / env）
            if (command.profileId != null) {
                CommandResult.Rejected("Codex 的模型不分供应商档")
            } else {
                manager.updateSessionOptions { it.copy(model = command.modelId.orUnset()) }
                CommandResult.Accepted
            }
        }
        is SessionCommand.SetEffort -> {
            val effort = command.effortId.orUnset()
            if (effort != null && effort !in CODEX_EFFORT_LEVELS) {
                CommandResult.Rejected("不认识的强度：$effort")
            } else {
                manager.updateSessionOptions { it.copy(effort = effort) }
                CommandResult.Accepted
            }
        }
        is SessionCommand.SetMode -> {
            val preset = CodexPermissionPreset.entries.firstOrNull { it.id == command.modeId }
            if (preset == null) {
                CommandResult.Rejected("不认识的权限档：${command.modeId}")
            } else {
                manager.updateSessionOptions { it.withPermission(preset) }
                CommandResult.Accepted
            }
        }
        is SessionCommand.SetCwd -> {
            manager.updateSessionOptions { it.copy(cwd = CwdPath.normalize(command.path)) }
            CommandResult.Accepted
        }
        is SessionCommand.AddDirectory -> CommandResult.Unsupported
        is SessionCommand.SetEngineOption -> setEngineOption(command)
        is SessionCommand.Rename -> {
            val title = command.title.trim()
            if (title.isBlank()) {
                CommandResult.Rejected("标题不能为空")
            } else {
                // 只动 App 侧元数据，rollout 文件不碰
                metaStore?.update(ref.id) { it.copy(title = title) }
                CommandResult.Accepted
            }
        }
        // 没有任何东西可刷新：用量 / 计划 / 模型目录 Codex 都没有对应请求
        is SessionCommand.Refresh -> CommandResult.Accepted
        // 能力位为假的命令在 supports 里已经挡掉，走到这里说明能力表改了而这里没跟上
        is SessionCommand.RunShell, is SessionCommand.StopSubagent,
        is SessionCommand.StopTask, is SessionCommand.Revert, is SessionCommand.SetAutoReviewer, is SessionCommand.SetAdvisor,
        -> CommandResult.Unsupported
    }

    private suspend fun send(command: SessionCommand.Send): CommandResult {
        val input = command.input
        if (input.isBlank) return CommandResult.Rejected("没有内容可发送")
        if (command.target != SendTarget.Main) return CommandResult.Unsupported
        // 没有进程的会话（打开的旧会话是只读回放、app-server 退出了）：发一句话就是「接着聊」，先 thread/resume 接回进程；
        // 正在接回的（Starting）等它就绪再发，不然这句话会被 sendTurn 拒掉而丢在半路。
        // 原 Codex 页顶栏那个只做 thread/resume 的「继续」键，B9 并页时由这条路与 ▶ 取代
        if (!ensureRunning()) return CommandResult.Rejected(manager.state.value.errorMessage ?: "会话没能接回来，请稍后重试")
        // 忙时 manager 把它放进 queued，回合正常完成后放出；没起好时 sendTurn 返回 false
        return if (manager.sendTurn(input.text)) CommandResult.Accepted else CommandResult.Rejected("会话没在运行")
    }

    /**
     * ■：`turn/interrupt`，只停本轮（进程不死，应答与 `turn/completed` 实测 0.1 秒内到）。
     *
     * 排队的追加消息有三种去处，按 §10.2 拍板 2：默认（[StopAction.InterruptReturnQueued]）打断并把它们**退回输入框**
     * （一条一条取出来合成一次 [SessionSignal.Withdrawn]，用户改完再发）；显式的 [SessionCommand.Interrupt.runQueuedAfter]
     * 让 manager 在打断收场后照样放出下一条；两种都不会让排队的话丢掉（以前停止 = 杀进程，下次 `start` 状态一重置队列就没了）。
     */
    private fun interrupt(command: SessionCommand.Interrupt): CommandResult {
        val state = fresh()
        val action = stopActionFor(state.turn, state.queuedCount, capabilities)
        if (action == StopAction.Nothing) return CommandResult.Stale("没有在跑的回合")
        if (command.expectedTurnId != null && command.expectedTurnId != state.turn?.turnId) {
            return CommandResult.Stale("回合已经换了")
        }
        if (action == StopAction.InterruptReturnQueued && !command.runQueuedAfter) {
            val returned = generateSequence { manager.takeQueued(0) }.toList()
            if (returned.isNotEmpty()) _signals.tryEmit(SessionSignal.Withdrawn(UserInput(returned.joinToString("\n\n"))))
        }
        return if (manager.interruptTurn(runQueued = command.runQueuedAfter)) CommandResult.Accepted else CommandResult.Stale("没有在跑的回合")
    }

    /**
     * ▶（设计 §6.5）：代发 [CONTINUE_PROMPT]。进程还在（[ResumeMode.Prompted]）直接 `turn/start`；
     * 没了（读回放的旧会话、app-server 退出，[ResumeMode.RelaunchThenPrompted]）先 `thread/resume` 接回来。
     * `turn/start` 不接受空输入的假设没有验证（mock 全接受，真 OpenAI 多半拒），所以永远发这句非空的话。
     */
    private suspend fun resume(command: SessionCommand.Resume): CommandResult {
        val offer = fresh().resume ?: return CommandResult.Stale("没有可继续的回合")
        if (offer.offerId != command.offerId) return CommandResult.Stale("续跑提议已过期")
        if (offer.mode == ResumeMode.RelaunchThenPrompted && !ensureRunning()) {
            return CommandResult.Rejected(manager.state.value.errorMessage ?: "会话没能接回来，请稍后重试")
        }
        return if (manager.sendTurn(CONTINUE_PROMPT)) CommandResult.Accepted else CommandResult.Rejected("会话没在运行")
    }

    /**
     * 进程在跑就直接返回；没有进程就 [relaunch]；别人正在接回（Starting）就等他。
     * 串行化：并发的两句话各自 `start` 的话，第二个会被 manager 拒掉，话就丢了。
     */
    private suspend fun ensureRunning(): Boolean {
        if (manager.state.value.status == SessionStatus.Running) return true
        return relaunchLock.withLock {
            when (manager.state.value.status) {
                SessionStatus.Running -> true
                SessionStatus.Starting -> withTimeoutOrNull(RELAUNCH_WAIT_MS) {
                    manager.state.first { (it.status == SessionStatus.Running && it.threadId != null) || it.status == SessionStatus.Failed || it.status == SessionStatus.Closed }
                }?.status == SessionStatus.Running
                else -> relaunch(ref.id)
            }
        }
    }

    private fun withdrawQueued(command: SessionCommand.WithdrawQueued): CommandResult {
        val (index, hash) = CodexStateMapper.parseQueuedItemId(command.clientId)
            ?: return CommandResult.Stale("没有这条排队消息")
        val queued = manager.state.value.queued
        // 队头会被放出去、下标会移位：哈希对不上说明那一格已经不是它了
        if (queued.getOrNull(index)?.hashCode() != hash) return CommandResult.Stale("这条消息已经不在队列里")
        val text = manager.takeQueued(index) ?: return CommandResult.Stale("这条消息已经不在队列里")
        _signals.tryEmit(SessionSignal.Withdrawn(UserInput(text)))
        return CommandResult.Accepted
    }

    private fun answer(command: SessionCommand.Answer): CommandResult {
        val card = fresh().interactions.firstOrNull { it.id == command.interactionId }
            ?: return CommandResult.Stale("这张卡已经被答复")
        val choose = command.answer as? InteractionAnswer.Choose
            ?: return CommandResult.Rejected("审批卡要用 Choose 答复")
        val decision = CodexStateMapper.decisionOf(choose.optionId)
        val listed = (card as? dev.min.code.core.engine.PendingInteraction.Approval)?.options?.any { it.id == choose.optionId } == true
        if (decision == null || !listed) return CommandResult.Rejected("没有这个选项：${choose.optionId}")
        // 服务端在弹出之后改了可选项时 answerApproval 返回 false：请求还挂着，让人再选
        return if (manager.answerApproval(decision)) CommandResult.Accepted else CommandResult.Rejected("服务端不接受这个决定")
    }

    private fun setEngineOption(command: SessionCommand.SetEngineOption): CommandResult {
        if (command.key != CodexStateMapper.OPTION_SUMMARY) return CommandResult.Unsupported
        val summary = command.value.orUnset()
        if (summary != null && summary !in CODEX_REASONING_SUMMARIES) return CommandResult.Rejected("不认识的摘要档：$summary")
        manager.updateSessionOptions { it.copy(summary = summary) }
        return CommandResult.Accepted
    }

    override suspend fun searchFiles(query: String, limit: Int): List<String> = searchFiles.invoke(query, limit)

    /** manager 是单例：不能 close()（那会取消它自己的作用域），只停进程；会话的订阅随本会话作用域一起取消 */
    override suspend fun close(reason: CloseReason) {
        closed = true
        manager.stop()
        scope.cancel()
    }

    /** 只撤掉本包装自己的订阅，**不动 manager**：引擎换掉当前会话（打开了另一条）时调，免得订阅一直留着 */
    internal fun release() {
        closed = true
        scope.cancel()
    }
}

/** 等 app-server 接回来的上限：握手上限再加一点余量（与引擎的 `handshakeWaitMs` 同口径） */
private const val RELAUNCH_WAIT_MS = CodexAppServerManager.HANDSHAKE_TIMEOUT_MS + 5_000
