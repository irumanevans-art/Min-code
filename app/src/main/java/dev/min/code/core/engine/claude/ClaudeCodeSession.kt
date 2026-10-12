package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.ClaudeCodeConfigStore
import dev.min.code.core.claudecode.ClaudeCodeImage
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.ClaudeCodeSessionMetaStore
import dev.min.code.core.claudecode.OUTPUT_TAIL_LINES
import dev.min.code.core.claudecode.OutputTail
import dev.min.code.core.engine.AgentSession
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.CONTINUE_PROMPT
import dev.min.code.core.engine.CloseReason
import dev.min.code.core.engine.CommandDeduper
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.RefreshTarget
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.StopAction
import dev.min.code.core.engine.TaskOutputTail
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.stopActionFor
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个 Claude Code 会话对通用层的样子：包住一个 [ClaudeCodeManager]（一个 CLI 进程），
 * 把状态翻成 [AgentSessionState]、命令转成 manager 的现有方法（设计 §4.1 的命令表）。
 *
 * **只转发**：manager 没有的能力（按 id 撤回排队消息）这里返回
 * [CommandResult.Unsupported] 并在对应分支写明缺什么。停止时把排队输入退回输入框的 `interruptReturningQueued`
 * 与续跑（▶）要的只读字段 `lastTurnEnd` 是 manager 侧仅有的两处补充（只加不改既有行为）。
 *
 * @param key 注册表 key = CLI 的 session id；新建会话在 manager 起来之前就定好了
 * @param externals 映射要的外部信息（人手标题、进程表、当前供应商档…），变了会重算状态
 * @param stopHostedService 停进程表里托管的那一半后台 shell（`LocalServiceRegistry.stop`）
 * @param readShellOutput 读后台 shell 输出尾巴（`readShellOutput`，要 workspaceDir，所以由 Engine 提供）。第二个参数是 CLI 的
 *   session id：没有 outputFile 的运行中任务要靠它去 /tmp 下按 `<sid>/tasks/<taskId>` 找文件
 */
class ClaudeCodeSession internal constructor(
    val manager: ClaudeCodeManager,
    key: String,
    parentScope: CoroutineScope,
    private val externals: StateFlow<ClaudeMapContext> = MutableStateFlow(ClaudeMapContext()),
    private val metaStore: ClaudeCodeSessionMetaStore? = null,
    private val configStore: ClaudeCodeConfigStore? = null,
    private val stopHostedService: (String) -> Unit = {},
    private val readShellOutput: suspend (BackgroundShell, sessionId: String?) -> OutputTail? = { _, _ -> null },
) : AgentSession {

    /** 自己的子作用域：close 时只取消本会话订阅的那几条流，不牵连 Engine 的作用域 */
    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    override val ref: SessionRef = claudeRef(key)
    override val capabilities: EngineCapabilities = EngineCapabilities.ClaudeCode

    private val deduper = CommandDeduper()

    /** 命令判定用的现算状态：`state` 流在调度器上有一拍延迟，判「这张卡还在不在」不能读旧的 */
    private fun fresh(): AgentSessionState = ClaudeStateMapper.map(ref, manager.state.value, externals.value)

    // 逐字增量不进 state（在 [stream] 里）：先把它们从 manager 的状态里剥掉再判等，流式期间每个 token 都不会走到映射，
    // 也就不会为一个 token 把整张会话（条目、子 agent、后台任务…）重算一遍。manager 的状态对象每个 token 换一个，
    // 但 items 等大字段是同一个引用，剥完判等很便宜。
    // 初值同步算好：Eagerly 的 stateIn 在调度器上才开始收集，第一次读 value 不能是空壳
    override val state: StateFlow<AgentSessionState> = combine(
        manager.state.map(::withoutLiveText).distinctUntilChanged(), externals,
    ) { s, c ->
        ClaudeStateMapper.map(ref, s, c)
    }.stateIn(scope, SharingStarted.Eagerly, ClaudeStateMapper.map(ref, manager.state.value, externals.value))

    override val stream: StateFlow<LiveStream> = manager.state
        .map(ClaudeStateMapper::mapLive)
        .stateIn(scope, SharingStarted.Eagerly, ClaudeStateMapper.mapLive(manager.state.value))

    /**
     * WhileSubscribed：manager 的 `emitWithdrawn` 靠「有没有订阅者」决定要不要把退回的话落盘兜底，
     * 所以没人看 signals 时我们也不能订阅它，否则话会进我们这条流然后蒸发。
     */
    override val signals: SharedFlow<SessionSignal> = merge(
        manager.withdrawnMessages.map<_, SessionSignal> { draft ->
            SessionSignal.Withdrawn(UserInput(draft.text, draft.images.map { ImageInput(it.mediaType, it.base64) }))
        },
        manager.localPreviewUrls.map<_, SessionSignal> { SessionSignal.PreviewUrl(it) },
    ).shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    override suspend fun submit(command: SessionCommand): CommandResult {
        if (!capabilities.supports(command)) return CommandResult.Unsupported
        if (!deduper.firstSeen(command.commandId)) return CommandResult.Duplicate
        val result = execute(command)
        // 失败的命令把 id 还回去：用户改完再点一次不该被当成重复
        if (result is CommandResult.Rejected || result == CommandResult.Unsupported) deduper.forget(command.commandId)
        return result
    }

    private suspend fun execute(command: SessionCommand): CommandResult {
        val current = manager.state.value
        return when (command) {
            is SessionCommand.Send -> send(command, current)
            is SessionCommand.Interrupt -> interrupt(command, current)
            is SessionCommand.Resume -> resume(command)
            is SessionCommand.WithdrawQueued -> withdrawQueued(command)
            is SessionCommand.Answer -> answer(command, current)
            is SessionCommand.SetModel -> setModel(command)
            is SessionCommand.SetEffort -> setEffort(command)
            is SessionCommand.SetMode -> {
                val mode = ClaudeCodePermissionMode.fromWire(command.modeId)
                    ?: return CommandResult.Rejected("不认识的权限模式：${command.modeId}")
                manager.setPermissionMode(mode)
                CommandResult.Accepted
            }
            is SessionCommand.SetCwd -> { manager.setCwd(command.path); CommandResult.Accepted }
            // CC 的 /add-dir 在它自己的 CLI 里；能力位为假，submit 在到这之前就拦了
            is SessionCommand.AddDirectory -> CommandResult.Unsupported
            is SessionCommand.SetEngineOption -> setEngineOption(command, current)
            is SessionCommand.Rename -> {
                val title = command.title.trim()
                if (title.isBlank()) return CommandResult.Rejected("标题不能为空")
                // 人手改名先记在 App 侧（立刻显示），再发给 CLI（写进 transcript）：与 VM.renameSession 同序
                metaStore?.update(ref.id) { it.copy(title = title) }
                manager.renameSession(title)
                CommandResult.Accepted
            }
            is SessionCommand.RunShell -> { manager.runShell(command.command); CommandResult.Accepted }
            is SessionCommand.StopSubagent -> stopSubagent(command)
            is SessionCommand.StopTask -> stopTask(command, current)
            // 还原成功才算受理：面板据此才把它标成「已撤销」（原 VM.revertToolCall 的 onRestored 语义）
            is SessionCommand.Revert ->
                if (manager.revertToolCall(command.toolUseId)) CommandResult.Accepted else CommandResult.Rejected("没有这次改动的快照")
            is SessionCommand.Refresh -> refresh(command)
            // CC 的 auto 模式在它自己的 CLI 里，没有审查模型可选：能力位为假，supports 已挡掉
            is SessionCommand.SetAutoReviewer, is SessionCommand.SetAdvisor -> CommandResult.Unsupported
        }
    }

    // ---------------------------------------------------------------- 发送与停止

    private fun send(command: SessionCommand.Send, current: ClaudeCodeManager.SessionState): CommandResult {
        val input = command.input
        if (input.isBlank) return CommandResult.Rejected("没有内容可发送")
        // manager 对已经关掉的会话会把话「退回输入框」（emitWithdrawn），那是它不知道上层有没有输入框的做法；
        // 通用层有明确的返回值，直接拒绝，话留在输入框里
        if (current.status != SessionStatus.Running && current.status != SessionStatus.Starting) {
            return CommandResult.Rejected("会话没在运行")
        }
        return when (val target = command.target) {
            SendTarget.Main -> {
                manager.send(input.text, input.images.map { ClaudeCodeImage(it.mediaType, it.base64) })
                CommandResult.Accepted
            }
            is SendTarget.Subagent -> {
                val thread = fresh().threads.firstOrNull { it.threadId == target.threadId }
                    ?: return CommandResult.Stale("没有这个子 agent：${target.threadId}")
                // 拿不到 agentId（很老的历史会话）也照样转给 manager：它会在对话流里留一句「找不到这个子 agent 的 id」，
                // 那是界面上唯一说得清原因的地方；这里先拒绝的话，输入框清空了却什么说明都没有
                // 图片递不进子 agent（hook 只能带文字），manager 会补一条说明
                manager.sendToSubagent(target.threadId, input.text, dropImages = input.images.size)
                CommandResult.Accepted
            }
        }
    }

    /**
     * 停止键。「哪一档」由 [stopActionFor] 判（界面算按钮样子用的是同一个函数）。
     *
     * 有排队输入时（设计 §10.2 拍板 2，与 Codex 同口径）：
     * - 默认（[StopAction.InterruptReturnQueued]）：全停，排队的输入由 manager 取走、合成一次 [SessionSignal.Withdrawn]
     *   退回输入框（[ClaudeCodeManager.interruptReturningQueued]），不会有哪一条被放出去跑；
     * - 显式的 [SessionCommand.Interrupt.runQueuedAfter]：manager 原有的「打断后排队的紧接着跑」。
     * 没有排队时 manager 的 `interrupt()` 自己按 `escapeAction` 分撤回 / 打断，与 [stopActionFor] 一致。
     * 停止键必须能停：不论哪一档，都至少写出 interrupt 帧。
     */
    private fun interrupt(command: SessionCommand.Interrupt, current: ClaudeCodeManager.SessionState): CommandResult {
        val queued = current.items.count { it is ChatItem.UserText && it.queued }
        val action = if (command.runQueuedAfter && queued > 0) {
            StopAction.InterruptThenRunQueued
        } else {
            stopActionFor(fresh().turn, queued, capabilities)
        }
        if (action == StopAction.Nothing) return CommandResult.Stale("没有在跑的回合")
        if (action == StopAction.InterruptReturnQueued) manager.interruptReturningQueued() else manager.interrupt()
        return CommandResult.Accepted
    }

    /**
     * ▶（设计 §6.4）：代发 [CONTINUE_PROMPT]，与官方 CLI 的 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` 做的是同一件事，
     * 区别只是 Min 要等用户按了才发（开那个开关的话每次 `--resume` 都自动续，违背「不自动续跑」）。
     *
     * - [ResumeMode.Prompted]（进程还活着）：直接发；
     * - [ResumeMode.RelaunchThenPrompted]（进程死了 / 会话已关）：先 `openSession`（回放 transcript 加 `--resume <id>`）接回进程，
     *   等它就绪再发。这一段可能要十几秒，所以命令是挂起的，界面那头的 ▶ 一直转圈。
     *   接回失败（进程又起不来）返回 Rejected，不发那句话：会话没在跑，发出去只会被退回输入框。
     */
    private suspend fun resume(command: SessionCommand.Resume): CommandResult {
        val offer = fresh().resume ?: return CommandResult.Stale("没有可继续的回合")
        if (offer.offerId != command.offerId) return CommandResult.Stale("续跑提议已过期")
        if (offer.mode == ResumeMode.RelaunchThenPrompted) {
            val sessionId = manager.state.value.sessionId ?: ref.id
            val reopened = manager.openSession(sessionId)
            if (!awaitRunning(reopened)) return CommandResult.Rejected(manager.state.value.errorMessage ?: "会话没能接回来，请稍后重试")
        }
        manager.send(CONTINUE_PROMPT)
        return CommandResult.Accepted
    }

    /**
     * 等 `openSession` 把进程接回来：等它的 [Job]（回放完、进程拉起或失败时结束），再看状态是不是 Running。
     * 不盯状态流找 Starting 再找 Failed：StateFlow 会合并快速连变，`Starting → Failed` 可能一帧都看不到，那样会空等满上限。
     * Running 之后握手还在后台跑（首条消息会被 manager 攥着等握手收尾），不影响这里发话。
     */
    private suspend fun awaitRunning(reopened: Job): Boolean {
        withTimeoutOrNull(RELAUNCH_WAIT_MS) { reopened.join() } ?: return false
        return manager.state.value.status == SessionStatus.Running
    }

    /**
     * 撤回一条排队消息。manager 只有「停止时整体撤回」，没有按 id 撤回的入口（设计 §4.1 的「待确认」），
     * 所以这里只能认出「有没有这条」：没有 = Stale；有 = Unsupported。
     * 已交棒的那几条帧已经在 CLI 的 stdin 里，单条撤不回，只有 ■ 带的 `cancel_queued` 能整批清（见 [interrupt]）。
     */
    private fun withdrawQueued(command: SessionCommand.WithdrawQueued): CommandResult {
        val exists = manager.state.value.items.any { it is ChatItem.UserText && it.queued && it.id == command.clientId }
        return if (exists) CommandResult.Unsupported else CommandResult.Stale("这条消息已经不在队列里")
    }

    // ---------------------------------------------------------------- 交互

    private fun answer(command: SessionCommand.Answer, current: ClaudeCodeManager.SessionState): CommandResult {
        val request = current.permissionQueue.firstOrNull { it.requestId == command.interactionId }
            ?: return CommandResult.Stale("这张卡已经被答复或撤回")
        val card = fresh().interactions.firstOrNull { it.id == command.interactionId }
            ?: return CommandResult.Stale("这张卡已经被答复或撤回")
        val isHead = current.permissionQueue.first().requestId == request.requestId
        return when (val call = claudeAnswerCall(card, command.answer, request, isHead)) {
            is ClaudeAnswerCall.Rejected -> CommandResult.Rejected(call.message)
            is ClaudeAnswerCall.Questions -> { manager.answerQuestions(call.answers); CommandResult.Accepted }
            is ClaudeAnswerCall.Permission -> {
                // expectedRequestId：只应答这一张。按下去的那一刻挂着的可能已经换了，不核对会放行一条没见过的调用
                val ok = manager.answerPermission(call.allow, call.denyMessage, call.suggestion, call.requestId)
                if (ok) CommandResult.Accepted else CommandResult.Stale("这张卡已经被答复或撤回")
            }
        }
    }

    // ---------------------------------------------------------------- 设置

    private suspend fun setModel(command: SessionCommand.SetModel): CommandResult {
        // CC 没有「供应商档」这个维度（档在启动时固化进 env），跨供应商换模型对它没有意义
        if (command.profileId != null) return CommandResult.Rejected("Claude Code 的模型不分供应商档")
        manager.setModel(command.modelId.ifEmpty { null }, asDefault = command.asDefault)
        // settings.json 的 `model` 是文件层的事，manager 只管进程（VM.setModel 的第二步）
        if (command.asDefault) configStore?.saveDefaultModel(command.modelId.ifEmpty { null })
        return CommandResult.Accepted
    }

    private fun setEffort(command: SessionCommand.SetEffort): CommandResult {
        val id = command.effortId?.ifEmpty { null }
        if (id == ClaudeStateMapper.OPTION_ULTRACODE) {
            manager.applyEffort(null, ultracode = true)
            return CommandResult.Accepted
        }
        val known = fresh().settings.efforts.map { it.id }
        if (id != null && id !in known) return CommandResult.Rejected("当前模型不支持这个强度：$id")
        manager.applyEffort(id, ultracode = false)
        return CommandResult.Accepted
    }

    private fun setEngineOption(command: SessionCommand.SetEngineOption, current: ClaudeCodeManager.SessionState): CommandResult =
        when (command.key) {
            ClaudeStateMapper.OPTION_CACHE_TTL -> {
                if (command.value !in ClaudeCodeManager.PROMPT_CACHE_TTLS) {
                    CommandResult.Rejected("缓存 TTL 只能是 ${ClaudeCodeManager.PROMPT_CACHE_TTLS}")
                } else {
                    manager.setPromptCacheTtl(command.value)
                    CommandResult.Accepted
                }
            }
            ClaudeStateMapper.OPTION_ULTRACODE -> {
                // ultracode 与 effort 互斥：开 = effort 置空，关 = 回到当前请求的强度
                manager.applyEffort(if (command.value == "true") null else current.options.effort, command.value == "true")
                CommandResult.Accepted
            }
            else -> CommandResult.Unsupported
        }

    // ---------------------------------------------------------------- 子 agent / 任务

    private fun stopSubagent(command: SessionCommand.StopSubagent): CommandResult {
        val thread = fresh().threads.firstOrNull { it.threadId == command.threadId }
            ?: return CommandResult.Stale("没有这个子 agent：${command.threadId}")
        if (!thread.running) return CommandResult.Stale("这个子 agent 已经结束")
        if (!thread.messageable) return CommandResult.Rejected("拿不到这个子 agent 的 id，没法停它")
        manager.stopSubagent(command.threadId)
        return CommandResult.Accepted
    }

    /** 停后台 shell：CLI 的发 stop_task，托管的（含 bypass 下的孪生）停进程表，各走各的路（与 VM.stopBackgroundShell 同） */
    private suspend fun stopTask(command: SessionCommand.StopTask, current: ClaudeCodeManager.SessionState): CommandResult {
        val shell = ClaudeStateMapper.shellsOf(current, externals.value.services).firstOrNull { it.key == command.taskId }
            ?: return CommandResult.Stale("没有这个后台任务：${command.taskId}")
        if (!shell.isRunning) return CommandResult.Stale("这个后台任务已经结束")
        shell.hostedServiceId?.let(stopHostedService)
        val taskId = shell.cliTaskId ?: return CommandResult.Accepted
        return manager.stopTask(taskId)?.let { CommandResult.Rejected(it) } ?: CommandResult.Accepted
    }

    private suspend fun refresh(command: SessionCommand.Refresh): CommandResult {
        command.targets.forEach { target ->
            when (target) {
                RefreshTarget.Models -> manager.refreshModels()
                RefreshTarget.Usage -> manager.refreshUsage()
                RefreshTarget.Plan -> manager.refreshPlan()
                RefreshTarget.AppliedSettings -> manager.refreshAppliedSettings()
            }
        }
        return CommandResult.Accepted
    }

    // ---------------------------------------------------------------- 查询与释放

    override suspend fun searchFiles(query: String, limit: Int): List<String> = manager.searchFiles(query, limit)

    override suspend fun hasCheckpoint(toolUseId: String): Boolean = toolUseId in manager.checkpointed(listOf(toolUseId))

    override suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> = manager.checkpointed(toolUseIds)

    override suspend fun revertFile(name: String, toolUseIds: List<String>): Set<String> = manager.revertFile(name, toolUseIds)

    override suspend fun readTaskOutput(taskId: String, maxChars: Int): String? =
        readTaskTail(taskId)?.text?.takeLast(maxChars)

    override suspend fun readTaskTail(taskId: String): TaskOutputTail? {
        val state = manager.state.value
        val shell = ClaudeStateMapper.shellsOf(state, externals.value.services)
            .firstOrNull { it.key == taskId } ?: return null
        return readShellOutput(shell, state.sessionId)?.let { TaskOutputTail(it.text, it.clipped, OUTPUT_TAIL_LINES) }
    }

    /**
     * 用户结束 = 只停进程、会话留在界面上显示「已结束」（顶栏停止键现行为，攥着的消息退回输入框）；
     * 其余原因都是整个摘掉：释放 manager（shutdown + 取消它的协程作用域）。
     */
    override suspend fun close(reason: CloseReason) {
        if (reason == CloseReason.UserEnded) {
            manager.stopSession()
        } else {
            manager.dispose()
            scope.cancel()
        }
    }

    /**
     * 只撤掉本包装自己订阅的那几条流，**不动 manager**。旧注册表摘掉一个会话（它自己 dispose 了 manager）、
     * 引擎把这个包装从表里丢掉时调：不然每开过一个会话就永远留着三条收集协程。
     */
    internal fun release() {
        scope.cancel()
    }
}

/** 等 `--resume` 接回进程的上限。冷启动 proot + node 实测几秒到十几秒，机器忙时更慢，取宽的一头 */
private const val RELAUNCH_WAIT_MS = 60_000L

/** 去掉逐字增量，见 [ClaudeCodeSession.state] */
private fun withoutLiveText(s: ClaudeCodeManager.SessionState): ClaudeCodeManager.SessionState =
    if (s.streamingText.isEmpty() && s.streamingThinking.isEmpty()) s else s.copy(streamingText = "", streamingThinking = "")
