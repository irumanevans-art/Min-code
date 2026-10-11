package dev.min.code.core.engine.min

import dev.min.agent.kernel.changeAdvisor

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.GoalCommand
import dev.min.agent.kernel.GoalSetResult
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.clearGoal
import dev.min.agent.kernel.goalArgumentOf
import dev.min.agent.kernel.parseGoalCommand
import dev.min.agent.kernel.setGoal
import dev.min.agent.kernel.savedWorkflows
import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.ext.skills.SkillVars
import dev.min.agent.ext.skills.SlashCommands
import dev.min.agent.ext.skills.SlashResult
import dev.min.agent.spi.SkillKind
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
    private val editRestore: MinEditRestore? = null,
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
            if (mode == null) CommandResult.Rejected("不支持的权限模式：${command.modeId}")
            else { root.changeSettings(mode = mode); CommandResult.Accepted }
        }
        is SessionCommand.SetAutoReviewer -> setAutoReviewer(command)
        is SessionCommand.SetAdvisor -> setAdvisor(command)
        // 名字写进内核日志（session_renamed）：CLI 的按名续接与列表读同一个。规整后为空（全是空白 / 控制字符）内核拒绝
        is SessionCommand.Rename -> if (root.rename(command.title) != null) CommandResult.Accepted else CommandResult.Rejected("名字是空的")
        is SessionCommand.StopSubagent -> {
            val child = childRuntime(command.threadId)
            if (child == null) CommandResult.Stale("子 agent 已不在线") else { child.interrupt(); CommandResult.Accepted }
        }
        is SessionCommand.StopTask -> { effects.killTask(command.taskId); CommandResult.Accepted }
        // 模型表每次状态更新都按设置现算，没有要刷新的缓存
        is SessionCommand.Refresh -> CommandResult.Accepted
        // 能力位为假的几条（cwd / shell）在 supports 就拦了；引擎专有设置项 Min 没有
        is SessionCommand.Revert ->
            if (editRestore == null) CommandResult.Unsupported
            else if (command.toolUseId in editRestore.revert(listOf(command.toolUseId))) CommandResult.Accepted
            else CommandResult.Rejected("没能撤销这次修改")
        is SessionCommand.SetCwd, is SessionCommand.RunShell, is SessionCommand.SetEngineOption ->
            CommandResult.Unsupported
    }

    /**
     * `/goal`：设、看、清。回执经 [SessionSignal.Notice] 给界面，不是一条用户消息、不进对话；设不上（hooks 被禁用）是拒绝，原因照内核的说法。
     */
    private suspend fun goal(runtime: SessionRuntime, argument: String): CommandResult =
        when (val parsed = parseGoalCommand(argument)) {
            GoalCommand.Show -> notice(text.goalStatus(minGoalView(runtime.state.value.goal)))
            GoalCommand.Clear -> {
                val before = runtime.state.value.goal.condition
                if (before != null && runtime.clearGoal()) notice(text.goalCleared(before))
                else notice(text.goalStatus(null))
            }
            is GoalCommand.Set -> when (val result = runtime.setGoal(parsed.condition)) {
                is GoalSetResult.Refused -> CommandResult.Rejected(result.reason)
                is GoalSetResult.Set -> notice(text.goalSet(parsed.condition, result.startedTurn))
            }
        }

    private fun notice(message: String): CommandResult {
        effects.emit(SessionSignal.Notice(message, isError = false))
        return CommandResult.Accepted
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
        // `/goal` 是会话命令，不是 skill：在斜杠展开之前接住（子 agent 里的 /goal 当普通话，不设子会话的目标）
        if (command.target == SendTarget.Main) goalArgumentOf(command.input.text)?.let { return goal(runtime, it) }
        val expanded = expandSlash(runtime, command.input.text)
        if (expanded is SlashInput.Refused) return CommandResult.Rejected(expanded.reason)
        val input = if (expanded is SlashInput.Expanded) command.input.copy(text = expanded.body) else command.input
        val accepted = runtime.send(ClientMsgId(command.commandId), partsOf(input), delivery, scanMcpMentions = expanded is SlashInput.Plain)
        return if (accepted) CommandResult.Accepted else CommandResult.Duplicate
    }

    private sealed interface SlashInput {
        data object Plain : SlashInput
        data class Expanded(val body: String) : SlashInput
        data class Refused(val reason: String) : SlashInput
    }

    private suspend fun expandSlash(runtime: SessionRuntime, line: String): SlashInput {
        val typed = line.trim()
        if (!typed.startsWith("/")) return SlashInput.Plain
        val name = typed.removePrefix("/").takeWhile { !it.isWhitespace() }
        // 工作流由内核接管；用户插件命令用冻结清单，不能把安装后的磁盘变化偷渡进旧会话。
        if (runtime.savedWorkflows().any { it.name == name }) return SlashInput.Plain
        val entries = runtime.state.value.skills?.entries.orEmpty()
        val skills = mutableListOf<SkillDef>()
        val commands = mutableListOf<CommandDef>()
        val unreadable = mutableSetOf<String>()
        entries.forEach { entry ->
            val body = runtime.readSkillBody(entry.path)
            if (body == null) { unreadable += entry.name; return@forEach }
            val source = ConfigSource.entries.firstOrNull { it.label == entry.source }
                ?: if (entry.source?.startsWith("plugin:") == true) ConfigSource.Plugin else ConfigSource.User
            if (entry.kind == SkillKind.Command) commands += CommandDef(
                entry.name, entry.description, entry.path, body, source,
                arguments = entry.arguments, userInvocable = entry.userInvocable, context = entry.context,
            ) else skills += SkillDef(
                entry.name, entry.description, entry.path, body, source,
                arguments = entry.arguments, userInvocable = entry.userInvocable, context = entry.context,
            )
        }
        if (name in unreadable) return SlashInput.Refused("/$name 的文件现在读不了。")
        val snapshot = runtime.state.value
        val result = SlashCommands.expand(typed, skills, commands, SkillVars(
            runtime.sessionId.value, snapshot.settings.cwd.value, snapshot.settings.effort?.name?.lowercase(),
        ))
        return when (result) {
            SlashResult.NotACommand -> SlashInput.Plain
            is SlashResult.Unknown -> SlashInput.Refused("没有名为 /${result.name} 的 skill 或命令。")
            is SlashResult.NotInvocable -> SlashInput.Refused("/${result.name} 设置了 user-invocable: false，只有模型能调用它。")
            is SlashResult.InvalidArguments -> SlashInput.Refused(result.message)
            is SlashResult.McpPrompt -> SlashInput.Refused("此入口尚不支持 MCP prompt。")
            is SlashResult.Expanded -> {
                val args = typed.drop(1 + name.length).trim()
                runtime.promptExpansionHooks("slash_command", name, args, entries.firstOrNull { it.name == name }?.source ?: "user", typed)
                    ?.let { return SlashInput.Refused(it) }
                // 展开的文件内容不是人的引用：不扫描 MCP 提及；组件 hooks 只在调用确实获准后登记。
                result.names.forEach { called -> entries.firstOrNull { it.name == called }?.let { entry ->
                    entry.hooks?.let { runtime.registerComponentHooks(entry.kind.name.lowercase(), called, entry.source ?: "user", it) }
                } }
                SlashInput.Expanded(result.text)
            }
        }
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

    /**
     * 审查模型：null = 默认；指定的要解析得出来（供应商档在、有 key），内核 [SessionRuntime.changeAutoModeReviewer] 会再核一次并抛出原因。
     * 单独指定别家供应商时，数据流向（消息与工具入参）由界面在选之前说清，这里不再拦
     */
    private suspend fun setAutoReviewer(command: SessionCommand.SetAutoReviewer): CommandResult {
        if (state().readOnly) return CommandResult.Rejected(text.providerRemoved())
        val modelId = command.modelId
        if (modelId == null) {
            root.changeAutoModeReviewer(null)
            return CommandResult.Accepted
        }
        val ref = ModelRef(command.profileId ?: root.state.value.settings.model.providerId, modelId)
        if (lookup.providerLabel(ref.providerId) == null) return CommandResult.Rejected(text.providerRemoved())
        root.changeAutoModeReviewer(dev.min.agent.spi.AutoModeReviewer(ref))
        return CommandResult.Accepted
    }

    private suspend fun setAdvisor(command: SessionCommand.SetAdvisor): CommandResult {
        if (state().readOnly) return CommandResult.Rejected(text.providerRemoved())
        val ref = command.modelId?.let { ModelRef(command.profileId ?: root.state.value.settings.model.providerId, it) }
        if (ref != null && lookup.providerLabel(ref.providerId) == null) return CommandResult.Rejected(text.providerRemoved())
        root.changeAdvisor(ref)
        return CommandResult.Accepted
    }

    private suspend fun setModel(command: SessionCommand.SetModel): CommandResult {
        if (state().readOnly) return CommandResult.Rejected(text.providerRemoved())
        val current = root.state.value.settings.model
        val ref = ModelRef(command.profileId ?: current.providerId, command.modelId)
        // 允许跨供应商（设计 10.2 拍板 4）：发送时投影本来就处理跨模型历史，代价是缓存从头开始，界面切换时已经提示过
        if (lookup.providerLabel(ref.providerId) == null) return CommandResult.Rejected(text.providerRemoved())
        // PreModelSwitch hook 可以拦下切换：模型没换就不能报成功，也不能把没生效的模型记成默认。理由（含谁拒的）原样交给界面
        val refusal = root.changeSettings(model = ref)
        if (refusal != null) return modelSwitchResult(refusal)
        if (command.asDefault) effects.rememberDefaultModel(ref.providerId, ref.modelId)
        return CommandResult.Accepted
    }
}

/** PreModelSwitch 的结论翻成命令结果：没被拒 = 成功；被拒 = 失败并带 hook 给的原因（界面显示它，不要说“已切换”） */
internal fun modelSwitchResult(refusal: String?): CommandResult = if (refusal == null) CommandResult.Accepted else CommandResult.Rejected(refusal)
