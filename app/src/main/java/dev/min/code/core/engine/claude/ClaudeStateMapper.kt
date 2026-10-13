package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.BackgroundShell
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.claudecode.ClaudeCodeModelList
import dev.min.code.core.claudecode.ClaudeCodePermissionMode
import dev.min.code.core.claudecode.FORK_SUBAGENT_TYPE
import dev.min.code.core.claudecode.ReasoningLevel
import dev.min.code.core.claudecode.SUBAGENT_TOOLS
import dev.min.code.core.claudecode.SessionMeta
import dev.min.code.core.claudecode.SubagentThread
import dev.min.code.core.claudecode.backgroundShells
import dev.min.code.core.claudecode.subagentThreads
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.AppliedSettings
import dev.min.code.core.engine.BackgroundTask
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.ConnectionView
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.EngineOptionView
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.RetryNotice
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SettingsView
import dev.min.code.core.engine.SlashCommandView
import dev.min.code.core.engine.ThreadProgress
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.TurnProgress
import dev.min.code.core.engine.TurnReceipt
import dev.min.code.core.engine.UsageView
import dev.min.code.core.engine.withContinuations
import dev.min.code.core.engine.withPendingCounts
import dev.min.code.core.service.LocalService
import dev.min.code.core.session.ChatItem

/**
 * `ClaudeCodeManager.SessionState` -> [AgentSessionState]（设计 §3.4「Claude Code 来源」一列）。
 *
 * **纯函数**：状态进、通用状态出，不碰 manager、不起进程。黄金测试 `ClaudeStateMapperGoldenTest` 拿 B0 的 10 份
 * 夹具快照逐字段对拍，规则以「会话页现在读到什么」为准，不是以字段名为准。
 *
 * ## 几处不是一一对应的映射（都有黄金测试钉着）
 *
 * - **turn**：`busy` 为真才有 [TurnProgress]。`turnStartedAt` / `outputTokens*` 在回合结束后 manager 并不清零
 *   （快照里是残留值），但页面只在 busy 时读它们，所以通用状态里空闲 = turn 为 null，残留值不映射。
 * - **settings.model / effort**：`Choice.id` 是用户**请求**的值（空串 = 跟随默认，选中行按它判），`Choice.label` 是
 *   页面底栏芯片显示的**实际生效**值（`appliedModel` 优先）。两个信息各占一个字段，界面不用再碰 `applied*`。
 * - **extras**：缓存 TTL（`cache_ttl`）与 ultracode 是 CC 独有的设置项，发 `SetEngineOption`。
 * - **lastTurn.end**：取 manager 的只读 `lastTurnEnd`（B8，见 `ClaudeTurnEnd`）；[ClaudeMapContext.lastTurnEnd] 给了就以它为准。
 *   两边都没有时回执写 [TurnEnd.Completed]（「不知道」的占位，不是事实），▶ 此时不出（见 [ClaudeResume]）。
 */
object ClaudeStateMapper {

    /** 模型 / 强度 `Choice.id` 为空串 = 跟随默认（界面把它本地化成「默认」） */
    const val FOLLOW_DEFAULT = ""

    /** [SettingsView.extras] 的键，同时是 `SetEngineOption.key` */
    const val OPTION_CACHE_TTL = "cache_ttl"
    const val OPTION_ULTRACODE = "ultracode"

    fun map(
        ref: SessionRef,
        state: ClaudeCodeManager.SessionState,
        context: ClaudeMapContext = ClaudeMapContext(),
    ): AgentSessionState {
        val threads = mapThreads(state)
        return AgentSessionState(
            ref = ref,
            status = state.status,
            title = context.meta.title?.takeIf { it.isNotBlank() } ?: state.liveTitle,
            cwd = state.cwd,
            items = state.items.withContinuations(),
            turn = if (state.busy) turnOf(state) else null,
            lastTurn = lastTurnOf(state, context),
            resume = ClaudeResume.offer(state, endOf(state, context), state.lastTurnEnd?.atMs ?: state.lastTurnFinishedAt),
            interactions = claudeInteractions(state.permissionQueue, state.tasks, threads),
            settings = settingsOf(state),
            usage = UsageView(
                contextTokens = state.contextTokens,
                contextLimit = state.contextLimit,
                costUsd = null, // CC 只给一段预格式化文字，不是数字
                costText = state.costText,
                turnOutputTokens = state.turnOutputTokens,
                cacheReadRatio = null,
            ),
            tasks = mapShells(state, context.services),
            threads = threads,
            slashCommands = state.slashCommands.map { SlashCommandView(it.name, it.description) },
            plan = state.plan,
            stopping = state.stopping,
            errorMessage = state.errorMessage,
            connection = connectionOf(state, context),
            reportedModel = state.model,
            toolCount = state.toolCount,
        ).let { mapped ->
            // 线程名下待处理的交互数要等交互队列算好才填
            mapped.copy(threads = mapped.threads.withPendingCounts(mapped.interactions))
        }
    }

    /**
     * 逐字增量。manager 的 `streamingText / streamingThinking` 搬进 [LiveStream]。
     * 工具参数草稿 / 运行中工具输出的尾巴：manager 没有把它们暴露成状态（参数草稿在私有字段里、
     * 运行中的输出已经折进工具卡的 `result`），所以这两项是空的，B8 要画再加只读出口。
     */
    fun mapLive(state: ClaudeCodeManager.SessionState): LiveStream = LiveStream(
        turnId = null,
        text = state.streamingText,
        thinking = state.streamingThinking,
        toolDrafts = emptyList(),
        toolOutput = emptyMap(),
    )

    private fun turnOf(state: ClaudeCodeManager.SessionState) = TurnProgress(
        turnId = null,
        startedAt = state.turnStartedAt,
        produced = state.turnProduced,
        phase = state.statusPhase,
        detail = state.statusDetail,
        retry = state.retryNotice?.let { RetryNotice(text = it) },
        outputTokensSettled = state.outputTokensSettled,
        outputTokensCurrent = state.outputTokensCurrent,
    )

    /** 上一回合的结局：外部给的（测试 / 将来的别的来源）优先，否则取 manager 的只读记录；都没有 = 不知道 */
    private fun endOf(state: ClaudeCodeManager.SessionState, context: ClaudeMapContext): TurnEnd? =
        context.lastTurnEnd ?: state.lastTurnEnd?.toTurnEnd()

    private fun lastTurnOf(state: ClaudeCodeManager.SessionState, context: ClaudeMapContext): TurnReceipt? {
        val recorded = state.lastTurnEnd
        if (state.lastTurnDurationMs == null && state.lastTurnFinishedAt == null && recorded == null) return null
        return TurnReceipt(
            turnId = null,
            durationMs = state.lastTurnDurationMs,
            finishedAt = state.lastTurnFinishedAt ?: recorded?.atMs,
            // 「不知道」写 Completed 占位（见类注释）：manager 现在都会记，只有从没跑过一轮的会话才走到占位
            end = endOf(state, context) ?: TurnEnd.Completed,
        )
    }

    private fun connectionOf(state: ClaudeCodeManager.SessionState, context: ClaudeMapContext): ConnectionView? {
        val launched = state.launchedProfileId.ifBlank { return null }
        val current = context.currentProfileId
        return ConnectionView(
            profileId = launched,
            profileLabel = context.profileLabels[launched],
            // 启动之后用户切了供应商：env 在进程启动时就固化了，这个会话还在用旧的
            stale = current != null && current != launched,
        )
    }

    // ---------------------------------------------------------------- 设置

    private fun settingsOf(state: ClaudeCodeManager.SessionState): SettingsView {
        val options = state.options
        val chipModel = modelChipLabel(state)
        val chipEffort = effortChipLabel(state)
        val mode = ClaudeCodePermissionMode.entries.map { Choice(it.wire, it.label) }
        return SettingsView(
            model = if (options.model == null && chipModel.isBlank()) null else Choice(options.model ?: FOLLOW_DEFAULT, chipModel),
            models = modelChoices(state),
            // 中转站目录还没查过：页面上显示「正在查询」
            modelsLoading = !state.relayModelsChecked,
            catalogModels = state.availableModels.map {
                Choice(it.value, it.displayName, it.description, resolvedId = it.resolvedModel, supportedEfforts = it.supportedEffortLevels)
            },
            relayModels = state.relayModels.map { Choice(it.id, it.displayName ?: it.id, description = if (it.displayName != null) it.id else null) },
            modelsError = state.relayModelsError,
            // 目录里缺席的别名（Fable 这类）：CLI 只把当前选中的放进 list_models，不补这一格用户会陷进「选不到」
            aliasModels = ClaudeCodeModelList.HIDDEN_MODEL_ALIASES.map {
                Choice(it.value, it.displayName, it.description, recommended = it.value in ClaudeCodeModelList.PROMOTED_MODEL_IDS)
            },
            applied = AppliedSettings(state.appliedModel, state.appliedEffort, state.appliedUltracode),
            effort = Choice(options.effort ?: FOLLOW_DEFAULT, chipEffort),
            efforts = effortChoices(state),
            effortNeedsRestart = true, // CC 没有 set_effort，只能重启进程（EngineCapabilities.effortHotSwap = false）
            mode = mode.first { it.id == state.permissionMode.wire },
            modes = mode,
            applying = state.applyingSettings || state.applyingEffort,
            // 都没有（跟随默认且还没回读）就当支持
            effortSupported = (state.appliedModel ?: state.options.model ?: state.model)
                ?.let(ClaudeCodeModelCatalog::supportsEffort) ?: true,
            hotSwapping = state.applyingSettings,
            relaunching = state.applyingEffort,
            extras = listOf(
                EngineOptionView(
                    key = OPTION_CACHE_TTL, label = "Prompt cache TTL", value = options.promptCacheTtl,
                    options = ClaudeCodeManager.PROMPT_CACHE_TTLS.map { Choice(it, it) },
                ),
                EngineOptionView(
                    key = OPTION_ULTRACODE, label = "Ultracode", value = options.ultracode.toString(),
                    options = listOf(Choice("true", "on"), Choice("false", "off")),
                ),
            ),
        )
    }

    /**
     * 底栏的模型段（页面 `modelChipLabel` 的非 Composable 部分）：显式选过就显示它的名字，
     * 没选过显示 CLI 回读的实际模型。都没有给空串，界面本地化成「默认」。
     */
    private fun modelChipLabel(state: ClaudeCodeManager.SessionState): String {
        val selected = state.options.model
        if (selected != null) {
            return (state.availableModels + ClaudeCodeModelList.HIDDEN_MODEL_ALIASES)
                .firstOrNull { it.value == selected }?.displayName ?: selected
        }
        return state.appliedModel ?: state.model ?: ""
    }

    /** 底栏的强度段（页面 `effortChipLabel`）：ultracode 优先，其次回读值，最后用户请求的值 */
    private fun effortChipLabel(state: ClaudeCodeManager.SessionState): String {
        if (state.options.ultracode || state.appliedUltracode) return "ultracode"
        return state.appliedEffort ?: state.options.effort ?: ""
    }

    /**
     * 模型列表 = CLI 的目录 + 中转站真实供应的（`/v1/models`，id 已在目录里的不重复）。
     * 页面上还有「推荐」置顶、`[1m]` 后缀开关、隐藏别名分区，那些是 CC 专有的呈现，不进通用列表。
     */
    private fun modelChoices(state: ClaudeCodeManager.SessionState): List<Choice> {
        val catalog = state.availableModels.map { Choice(it.value, it.displayName, it.description) }
        val known = catalog.mapTo(HashSet()) { it.id }
        return catalog + state.relayModels.filter { it.id !in known }.map { Choice(it.id, it.displayName ?: it.id) }
    }

    /**
     * 强度档（页面 `effortLevelsFor`）：去掉 OFF；模型不支持强度时只剩「自动」；
     * 目录报了 supportedEffortLevels 就按它裁，免得出现「界面显示 max、CLI 实际钳到 high」。
     * 「自动」的 id 是 [FOLLOW_DEFAULT]（= 不传 flag）。
     */
    private fun effortChoices(state: ClaudeCodeManager.SessionState): List<Choice> {
        val auto = Choice(FOLLOW_DEFAULT, ReasoningLevel.AUTO.effort)
        val model = state.appliedModel ?: state.options.model ?: state.model
        if (!ClaudeCodeModelCatalog.supportsEffort(model)) return listOf(auto)
        val levels = ReasoningLevel.entries.filter { it != ReasoningLevel.OFF && it != ReasoningLevel.AUTO }
        val supported = state.availableModels.firstOrNull { it.value == state.options.model }
            ?.supportedEffortLevels?.takeIf { it.isNotEmpty() }
        return listOf(auto) + levels.filter { supported == null || it.effort in supported }.map { Choice(it.effort, it.effort) }
    }

    // ---------------------------------------------------------------- 子 agent / 后台任务

    /**
     * 子 agent 线程。`SubagentThread` 来自 `core/claudecode`（现成的纯函数，阶段规则在里面），
     * 这里补通用类型要的几项：父子关系（Agent 卡嵌在另一张 Agent 卡的 subItems 里）、分叉 / 后台标记。
     */
    internal fun mapThreads(state: ClaudeCodeManager.SessionState): List<AgentThread> {
        val parents = HashMap<String, String?>()
        val depths = HashMap<String, Int>()
        fun walk(list: List<ChatItem>, parent: String?, depth: Int) {
            for (item in list) {
                if (item !is ChatItem.ToolCall) continue
                val isAgent = item.name in SUBAGENT_TOOLS
                if (isAgent) {
                    parents[item.toolUseId] = parent
                    depths[item.toolUseId] = depth
                }
                if (item.subItems.isNotEmpty()) walk(item.subItems, if (isAgent) item.toolUseId else parent, if (isAgent) depth + 1 else depth)
            }
        }
        walk(state.items, null, 0)
        return subagentThreads(state.items, state.tasks).map { it.toAgentThread(state, parents[it.toolUseId], depths[it.toolUseId] ?: 0) }
    }

    private fun SubagentThread.toAgentThread(
        state: ClaudeCodeManager.SessionState,
        parent: String?,
        depth: Int,
    ): AgentThread {
        val task = state.tasks.firstOrNull { it.toolUseId == toolUseId }
        return AgentThread(
            threadId = toolUseId,
            parentThreadId = parent,
            depth = depth,
            agentType = agentType,
            name = null,
            description = description,
            prompt = prompt,
            fork = agentType == FORK_SUBAGENT_TYPE,
            background = task?.backgrounded == true,
            phase = when (phase) {
                SubagentThread.Phase.Running -> AgentThread.Phase.Running
                SubagentThread.Phase.Done -> AgentThread.Phase.Done
                SubagentThread.Phase.Failed -> AgentThread.Phase.Failed
                SubagentThread.Phase.Stopped -> AgentThread.Phase.Stopped
            },
            activity = activity,
            items = items,
            pendingInteractions = 0,
            usage = null, // 任务表的 totalTokens 是总量，不是上下文 / 输出量，对不上 UsageView 的任何一栏
            childRef = null,
            progress = task?.let {
                ThreadProgress(
                    description = it.description, summary = it.summary, detail = it.progress,
                    lastTool = it.lastToolName, toolUses = it.toolUses, tokens = it.totalTokens,
                )
            },
            tracked = tracked,
            // 拿到 agentId 才能递话（历史会话里拿不到就不能）
            messageable = agentId != null,
        )
    }

    /** 后台 shell 表（CLI 的后台任务 + 本会话托管的服务合成一张）-> [BackgroundTask] */
    internal fun mapShells(state: ClaudeCodeManager.SessionState, services: List<LocalService>): List<BackgroundTask> =
        shellsOf(state, services).map { shell ->
            BackgroundTask(
                id = shell.key,
                kind = if (shell.cliTaskId == null) BackgroundTask.Kind.Service else BackgroundTask.Kind.Shell,
                label = shell.description.ifBlank { shell.command },
                status = when (shell.state) {
                    BackgroundShell.State.Running -> BackgroundTask.Status.Running
                    BackgroundShell.State.Completed -> BackgroundTask.Status.Done
                    BackgroundShell.State.Failed -> BackgroundTask.Status.Failed
                    BackgroundShell.State.Stopped -> BackgroundTask.Status.Stopped
                },
                startedAt = shell.startedAt,
                endedAt = shell.endedAt,
                toolUseId = state.tasks.firstOrNull { it.id == shell.cliTaskId }?.toolUseId,
                pid = null,
                logRef = shell.outputFile,
                command = shell.command,
                exitCode = shell.exitCode,
                source = when {
                    shell.cliTaskId != null && shell.hostedServiceId != null -> BackgroundTask.Source.Both
                    shell.cliTaskId != null -> BackgroundTask.Source.Cli
                    else -> BackgroundTask.Source.Hosted
                },
                monitor = shell.monitor,
            )
        }

    internal fun shellsOf(state: ClaudeCodeManager.SessionState, services: List<LocalService>): List<BackgroundShell> =
        backgroundShells(state.tasks, state.items, services, state.sessionId)
}

/**
 * 映射要的、不在 `SessionState` 里的几样外部信息。都有默认值，测试只传用得到的。
 *
 * @param meta 人手改的标题（`SessionMetaStore`）：人手 > CLI 拟名 > 磁盘摘要
 * @param services 进程表（后台 shell 里托管的那一半）
 * @param currentProfileId 当前激活的供应商档，与会话启动时的比较得出「已过时」
 * @param lastTurnEnd manager 的 `lastTurnEnd`（B8 才有），见 [ClaudeStateMapper] 头注释
 */
data class ClaudeMapContext(
    val meta: SessionMeta = SessionMeta(),
    val services: List<LocalService> = emptyList(),
    val currentProfileId: String? = null,
    val profileLabels: Map<String, String> = emptyMap(),
    val lastTurnEnd: TurnEnd? = null,
)

/** 会话的 [SessionRef]：CC 的 session id 就是注册表 key，沿用裸 id（[SessionRef.storageKey]） */
fun claudeRef(sessionId: String): SessionRef = SessionRef(EngineKind.ClaudeCode, sessionId)
