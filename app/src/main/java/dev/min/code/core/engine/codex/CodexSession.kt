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
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandDeduper
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StopAction
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.stopActionFor
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 一个 Codex 会话对通用层的样子：包住单例 [CodexAppServerManager]（设计 §4.2）。manager 一次只承载一个会话
 * （`maxConcurrent = 1`），所以 `CodexSession` 的寿命是「这个 threadId 在 manager 里的那一段」。
 *
 * **只转发，不改 manager**。manager 没有的能力返回 [CommandResult.Unsupported] / Rejected 并写明缺什么：
 * - `turn/interrupt` 没接：[SessionCommand.Interrupt] 只能停整个 app-server（[EngineCapabilities.interruptTurn] 为假，
 *   界面据此提示「停止会话」）；「打断后跑排队」做不到（进程都没了）；
 * - ▶ 续跑（[SessionCommand.Resume]）：能力位 `resume = None`，`supports` 直接挡掉。
 *
 * @param key threadId
 * @param externals 映射要的外部信息（标题、当前档…）
 * @param searchFiles `@` 补全（`WorkspaceRepository.mentionFiles`，与引擎无关）
 */
class CodexSession internal constructor(
    val manager: CodexAppServerManager,
    key: String,
    parentScope: CoroutineScope,
    private val externals: StateFlow<CodexMapContext> = MutableStateFlow(CodexMapContext()),
    private val metaStore: ClaudeCodeSessionMetaStore? = null,
    private val searchFiles: suspend (String, Int) -> List<String> = { _, _ -> emptyList() },
) : AgentSession {

    /** 自己的子作用域：close 时只取消本会话订阅的流，manager 是单例，不能连带取消 */
    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    override val ref: SessionRef = CodexStateMapper.codexRef(key)
    override val capabilities: EngineCapabilities = EngineCapabilities.Codex

    private val deduper = CommandDeduper()

    private fun fresh(): AgentSessionState = CodexStateMapper.map(ref, manager.state.value, externals.value)

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

    private fun execute(command: SessionCommand): CommandResult = when (command) {
        is SessionCommand.Send -> send(command)
        is SessionCommand.Interrupt -> interrupt(command)
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
        is SessionCommand.SetEngineOption -> setEngineOption(command)
        is SessionCommand.Rename -> {
            val title = command.title.trim()
            if (title.isBlank()) {
                CommandResult.Rejected("标题不能为空")
            } else {
                // 只动 App 侧元数据，rollout 文件不碰（与 CodexVM.renameSession 一致）
                metaStore?.update(ref.id) { it.copy(title = title) }
                CommandResult.Accepted
            }
        }
        // 没有任何东西可刷新：用量 / 计划 / 模型目录 Codex 都没有对应请求
        is SessionCommand.Refresh -> CommandResult.Accepted
        // 能力位为假的命令在 supports 里已经挡掉，走到这里说明能力表改了而这里没跟上
        is SessionCommand.Resume, is SessionCommand.RunShell, is SessionCommand.StopSubagent,
        is SessionCommand.StopTask, is SessionCommand.Revert, is SessionCommand.SetAutoReviewer,
        -> CommandResult.Unsupported
    }

    private fun send(command: SessionCommand.Send): CommandResult {
        val input = command.input
        if (input.isBlank) return CommandResult.Rejected("没有内容可发送")
        if (command.target != SendTarget.Main) return CommandResult.Unsupported
        // 忙时 manager 把它放进 queued，回合正常完成后放出；没起好 / 已退出时 sendTurn 返回 false。
        // 只读回放（打开的旧会话，没有进程）也是 false：接回进程（thread/resume）属 B8 的 RelaunchThenPrompted
        return if (manager.sendTurn(input.text)) CommandResult.Accepted else CommandResult.Rejected("会话没在运行")
    }

    /**
     * Codex 的停止键停的是**整个 app-server**（`turn/interrupt` 没接），所以「■ 只停本轮」做不到，
     * 命令的含义是 manager.stop()；界面从 [EngineCapabilities.interruptTurn] 为假知道这一点。
     * 排队的追加消息留在 manager 的 queued 里不会放出（见 `drainQueuedLocked`），重启后还在。
     * 「打断后跑排队」要进程活着才谈得上，这一档 Unsupported。
     */
    private fun interrupt(command: SessionCommand.Interrupt): CommandResult {
        val state = fresh()
        if (stopActionFor(state.turn, state.queuedCount, capabilities) == StopAction.Nothing) {
            return CommandResult.Stale("没有在跑的回合")
        }
        if (command.runQueuedAfter) return CommandResult.Unsupported
        if (command.expectedTurnId != null && command.expectedTurnId != state.turn?.turnId) {
            return CommandResult.Stale("回合已经换了")
        }
        manager.stop()
        return CommandResult.Accepted
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
        manager.stop()
        scope.cancel()
    }
}
