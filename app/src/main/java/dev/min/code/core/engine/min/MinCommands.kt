package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputOrigin
import dev.min.agent.kernel.PendingInput
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.CommandDeduper
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.SendTarget
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionSignal
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 命令执行完要碰到的、不属于内核的几件事。窄接口：命令单测里换成记录调用的假实现 */
interface MinCommandEffects {
    fun emit(signal: SessionSignal)
    fun rename(title: String)
    fun rememberDefaultModel(profileId: String, modelId: String)
    suspend fun killTask(taskId: String)

    /** 「总是允许」写进某一层 settings 文件。[cwd] 是会话的工作目录（guest 路径）。结局以轻提示告诉用户（成功 / 没写成的原因） */
    suspend fun persistAllowRule(layer: RuleLayer, rule: String, cwd: String)
}

/**
 * Min 会话的命令入口（设计 5.4）。顺序固定：能力位 -> 幂等 -> 过期判定 -> 执行（见 `AgentSession.submit` 的约定）。
 *
 * 命令对内核运行时的调用都只在这里，所以「Steer 还是 Queue」「哪种情况返回 Stale」「拒绝并停止做了什么」
 * 这些语义有一处可查、一处可测。
 *
 * @param state 会话此刻的通用状态（过期判定、线程表、只读都看它）
 */
class MinCommandHandler(
    private val hub: SessionHub,
    private val root: SessionRuntime,
    private val state: () -> AgentSessionState,
    private val lookup: MinModelLookup,
    private val text: MinAgentText,
    private val effects: MinCommandEffects,
    private val scope: CoroutineScope,
    private val capabilities: EngineCapabilities = EngineCapabilities.MinAgent,
) {
    private val deduper = CommandDeduper()
    private val router = MinInteractionRouter(
        session = { id -> hub.get(id)?.asAnswerTarget() },
        interrupt = ::interruptFrom,
        persistRule = { layer, rule -> effects.persistAllowRule(layer, rule, root.state.value.created.cwd.value) },
    )

    suspend fun handle(command: SessionCommand): CommandResult {
        if (!capabilities.supports(command)) return CommandResult.Unsupported
        if (!deduper.firstSeen(command.commandId)) return CommandResult.Duplicate
        val result = try {
            execute(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Rejected(e.message ?: e.javaClass.simpleName)
        }
        // 失败的不记账：用户改完再点一次不该被当成重复
        if (result is CommandResult.Rejected) deduper.forget(command.commandId)
        return result
    }

    private suspend fun execute(command: SessionCommand): CommandResult = when (command) {
        is SessionCommand.Send -> send(command)
        is SessionCommand.Interrupt -> interrupt(command)
        is SessionCommand.Resume -> resume(command)
        is SessionCommand.WithdrawQueued -> withdraw(command)
        is SessionCommand.Answer -> router.answer(command.interactionId, command.answer)
        is SessionCommand.SetModel -> setModel(command)
        is SessionCommand.SetEffort -> when (val id = command.effortId) {
            // null = 回到「不控制」（模型自己的默认）
            null -> { root.changeSettings(clearEffort = true); CommandResult.Accepted }
            else -> effortOfId(id)?.let { root.changeSettings(effort = it); CommandResult.Accepted }
                ?: CommandResult.Rejected("没有这个思考强度：$id")
        }
        is SessionCommand.SetMode -> {
            val mode = modeOfId(command.modeId)
            // Auto 要审查模型，Min 还没有（设置面板里也不列）
            if (mode == null || mode == PermissionMode.Auto) CommandResult.Rejected("不支持的权限模式：${command.modeId}")
            else { root.changeSettings(mode = mode); CommandResult.Accepted }
        }
        is SessionCommand.Rename -> { effects.rename(command.title.trim()); CommandResult.Accepted }
        is SessionCommand.StopSubagent -> {
            val child = childRuntime(command.threadId)
            if (child == null) CommandResult.Stale("子 agent 已不在线") else { child.interrupt(); CommandResult.Accepted }
        }
        is SessionCommand.StopTask -> { effects.killTask(command.taskId); CommandResult.Accepted }
        // 模型表每次状态更新都按设置现算，没有要刷新的缓存
        is SessionCommand.Refresh -> CommandResult.Accepted
        // 能力位为假的几条（cwd / shell / 撤销）在 supports 就拦了；引擎专有设置项 Min 没有
        is SessionCommand.SetCwd, is SessionCommand.RunShell, is SessionCommand.Revert, is SessionCommand.SetEngineOption ->
            CommandResult.Unsupported
    }

    // ---- 发送 ----

    private suspend fun send(command: SessionCommand.Send): CommandResult {
        val snapshot = state()
        if (snapshot.readOnly) return CommandResult.Rejected(text.providerRemoved())
        if (command.input.isBlank) return CommandResult.Rejected("消息是空的")
        if (command.input.images.isNotEmpty() && !snapshot.settings.modelSupportsImages) {
            return CommandResult.Rejected("当前模型不支持图片")
        }
        val runtime = when (val target = command.target) {
            SendTarget.Main -> root
            is SendTarget.Subagent -> childRuntime(target.threadId) ?: return CommandResult.Stale("子 agent 已不在线")
        }
        // 回合在跑就插话（Steer：当前工具批结束后送达，对齐 CC 的「交棒进 stdin」）；空闲时 send 直接开回合，投递方式无所谓
        val delivery = if (runtime.state.value.turn != null) Delivery.Steer else Delivery.Queue
        val accepted = runtime.send(ClientMsgId(command.commandId), partsOf(command.input), delivery, scanMcpMentions = true)
        return if (accepted) CommandResult.Accepted else CommandResult.Duplicate
    }

    private fun partsOf(input: UserInput): List<Part> = buildList {
        if (input.text.isNotBlank()) add(TextPart(input.text))
        input.images.forEach { add(ImagePart(it.mediaType, ImageSource.Base64(it.base64))) }
    }

    private fun childRuntime(threadId: String): SessionRuntime? =
        state().threads.firstOrNull { it.threadId == threadId }?.childRef?.let { hub.get(SessionId(it.id)) }

    // ---- 停止 ----

    private suspend fun interrupt(command: SessionCommand.Interrupt): CommandResult {
        val now = root.state.value
        val turn = now.turn ?: return CommandResult.Stale("没有进行中的回合")
        if (command.expectedTurnId != null && command.expectedTurnId != turn.turnId.value) return CommandResult.Stale("回合已经换了")
        val queued = now.inbox.filter { it.origin == InputOrigin.User }
        when {
            // 显式的一档：打断，排队的紧接着跑。等回合收尾（收尾事件在回合自己的 finally 里写）再开新回合，
            // 不阻塞调用方——界面按下就该立刻有反应
            command.runQueuedAfter && queued.isNotEmpty() -> {
                root.interrupt()
                scope.launch { root.awaitIdle(); root.runInbox() }
            }
            // 默认（设计 10.2 拍板 2）：全停，排队的退回输入框。先撤回再打断：回合在安全点会把 Steer 输入提升进历史，
            // 先打断的话撤回可能晚了一步
            queued.isNotEmpty() -> {
                val returned = queued.filter { root.withdraw(it.clientMsgId) }
                root.interrupt()
                if (returned.isNotEmpty()) effects.emit(SessionSignal.Withdrawn(mergeInputs(returned)))
            }
            else -> root.interrupt()
        }
        return CommandResult.Accepted
    }

    /** 「拒绝并停止」：拒绝落盘之后再停。停哪几层见 [interruptFrom] */
    private suspend fun interruptFrom(session: SessionId) {
        var current: SessionId? = session
        while (current != null) {
            val runtime = hub.get(current) ?: return
            runtime.interrupt()
            val origin = runtime.state.value.origin ?: return
            val parent = hub.get(origin.parent)?.state?.value ?: return
            // 后台子 agent 与父会话的回合无关：停到它为止。前台的父会话正等着它，一起停
            if (parent.children[current]?.background != false) return
            current = origin.parent
        }
    }

    private suspend fun resume(command: SessionCommand.Resume): CommandResult {
        val snapshot = state()
        if (snapshot.resume?.offerId == command.offerId) {
            root.resume()
            return CommandResult.Accepted
        }
        val thread = snapshot.threads.firstOrNull { it.resume?.offerId == command.offerId }
        val child = thread?.let { childRuntime(it.threadId) } ?: return CommandResult.Stale("续跑提示已过期")
        child.resume()
        return CommandResult.Accepted
    }

    private suspend fun withdraw(command: SessionCommand.WithdrawQueued): CommandResult {
        val pending = root.state.value.inbox.firstOrNull { it.clientMsgId.value == command.clientId }
            ?: return CommandResult.Stale("这条已经送达，撤不回了")
        if (!root.withdraw(pending.clientMsgId)) return CommandResult.Stale("这条已经送达，撤不回了")
        effects.emit(SessionSignal.Withdrawn(mergeInputs(listOf(pending))))
        return CommandResult.Accepted
    }

    /** 撤回的几条合成一份输入：正文按行拼，图片原样带上（输入框接住后「再按发送」与原来发出去的一致） */
    private fun mergeInputs(inputs: List<PendingInput>): UserInput = UserInput(
        text = inputs.joinToString("\n") { it.parts.filterIsInstance<TextPart>().joinToString("\n") { p -> p.text } }.trim(),
        images = inputs.flatMap { it.parts }.filterIsInstance<ImagePart>().mapNotNull { part ->
            (part.source as? ImageSource.Base64)?.let { ImageInput(part.mediaType, it.data) }
        },
    )

    private suspend fun setModel(command: SessionCommand.SetModel): CommandResult {
        if (state().readOnly) return CommandResult.Rejected(text.providerRemoved())
        val current = root.state.value.settings.model
        val ref = ModelRef(command.profileId ?: current.providerId, command.modelId)
        // 允许跨供应商（设计 10.2 拍板 4）：发送时投影本来就处理跨模型历史，代价是缓存从头开始，界面切换时已经提示过
        if (lookup.providerLabel(ref.providerId) == null) return CommandResult.Rejected(text.providerRemoved())
        root.changeSettings(model = ref)
        if (command.asDefault) effects.rememberDefaultModel(ref.providerId, ref.modelId)
        return CommandResult.Accepted
    }
}
