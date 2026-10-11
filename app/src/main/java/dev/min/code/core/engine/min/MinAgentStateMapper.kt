package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.llm.ModelProfile
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.AutoReviewView
import dev.min.code.core.engine.BackgroundTask
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.FallbackNotice
import dev.min.code.core.engine.ConnectionView
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.RetryNotice
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SettingsView
import dev.min.code.core.engine.TurnProgress
import dev.min.code.core.engine.TurnReceipt
import dev.min.code.core.engine.UsageView
import dev.min.code.core.engine.withPendingCounts
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.JsonPrimitive

/**
 * 映射器与设置面板要的「模型相关的只读查询」。抽成窄接口，是为了映射器的单测不必构造设置存储与 HTTP 客户端。
 */
interface MinModelLookup {
    /** 这个模型的能力；供应商档不存在（被删了）返回 null。目录里没有的模型给保守值 */
    fun profileOf(ref: ModelRef): ModelProfile?

    /** 供应商档的名字；档被删了返回 null——会话据此变只读，状态栏写「供应商已删除」 */
    fun providerLabel(providerId: String): String?

    /** 设置面板的模型表：当前档的在前，其它能用的档的跟在后面（设计 10.2 拍板 4：允许跨供应商换模型） */
    fun choices(current: ModelRef): List<Choice>

    /** 这个供应商档的目录给的小模型（auto 审查的默认模型）；目录里没有 / 档不存在返回 null */
    fun fastModelOf(providerId: String): ModelRef? = null
}

/**
 * 映射器的输入：一个根会话此刻的全部事实。纯数据，映射器本身不碰 Hub / 设置 / 磁盘，所以能在 JVM 上直接单测。
 *
 * @param nodes 根会话与它在线的全部后代（含根）的状态
 * @param pending `hub.pending` 里属于这棵树的那些（调用方按 root 过滤）
 * @param toolStatus 最近一次工具状态行（来自 [MinLiveStream.toolStatus]）
 * @param titleOverride 人手改过的标题；null = 取第一条用户消息
 * @param stopping 正在关闭
 * @param errorMessage 会话打不开 / 运行时出事的原因
 * @param maxRetryAttempts 请求最多试几次（`RetryPolicy.maxAttempts`），退避重试的提示用
 */
data class MinMapperInput(
    val ref: SessionRef,
    val root: SessionId,
    val nodes: Map<SessionId, SessionState>,
    val pending: List<HubPending> = emptyList(),
    val toolStatus: String? = null,
    val titleOverride: String? = null,
    val tasks: List<BackgroundTask> = emptyList(),
    val stopping: Boolean = false,
    val errorMessage: String? = null,
    val maxRetryAttempts: Int = 5,
    val workflowCommands: List<dev.min.code.core.engine.SlashCommandView> = emptyList(),
    /** 内核报的团队成员状态（`hub.teamMembers(root)`；排队与搁浅要看内存里的队伍，不能只从状态推）。没有团队为空 */
    val teamMembers: List<dev.min.agent.kernel.TeamMemberStatus> = emptyList(),
    /** 模型 CronList 看到的同一份定时任务视图（`hub.cronBoard(root)`），与全局开关 */
    val cronBoard: dev.min.agent.spi.CronBoard? = null,
    val cronsEnabled: Boolean = true,
)

/**
 * 内核状态 -> 通用 [AgentSessionState]（设计 3.4 的 Min 列 + 5.2）。生成中的那一块不在这里（在 [MinLiveStream]）。
 *
 * 子 agent 的转录先投影好，再各挂两处：线程自己的 `items`，和发起它的那张 Agent 工具卡的 `subItems`，共用一份列表。
 */
class MinAgentStateMapper(private val text: MinAgentText, private val lookup: MinModelLookup) {
    fun map(input: MinMapperInput): AgentSessionState {
        val root = checkNotNull(input.nodes[input.root]) { "根会话 ${input.root.value} 不在节点表里" }
        val model = root.settings.model
        val providerLabel = lookup.providerLabel(model.providerId)
        val removed = providerLabel == null
        val itemMemo = HashMap<SessionId, List<ChatItem>>()
        fun itemsOf(id: SessionId): List<ChatItem> = itemMemo.getOrPut(id) {
            val state = input.nodes[id] ?: return@getOrPut emptyList()
            projectMinItems(
                state, text,
                subItems = { call -> state.children.values.firstOrNull { it.callId == call }?.child?.let(::itemsOf) },
                childOf = { state.children[it] },
            )
        }
        val items = itemsOf(input.root)
        val threads = minThreads(input.root, input.nodes, ::itemsOf, lookup, canRun = { s -> lookup.providerLabel(s.settings.model.providerId) != null })
        val interactions = minInteractions(input.pending, input.root, input.nodes, threads, text)
        val profile = lookup.profileOf(model)
        val cost = treeCostOf(input.root, input.nodes)
        return AgentSessionState(
            ref = input.ref,
            status = when {
                input.errorMessage != null -> SessionStatus.Failed
                root.turn != null -> SessionStatus.Running
                else -> SessionStatus.Idle
            },
            // 名字的来源：内核日志里的 session_renamed > 迁移前 App 自己存的标题（打开时会迁进内核）> 第一条用户消息
            title = root.name?.takeIf { it.isNotBlank() } ?: input.titleOverride?.takeIf { it.isNotBlank() } ?: titleOf(items),
            cwd = root.settings.cwd.value,
            worktree = root.worktree?.let { dev.min.code.core.engine.WorktreeView(it.name, it.path.value, it.branch, it.originalCwd.value, it.pinned) },
            items = items,
            turn = turnOf(root, input),
            lastTurn = root.lastTurn?.let {
                TurnReceipt(it.turnId.value, it.durationMs.takeIf { d -> d > 0 }, it.lastActivityAtMs.takeIf { t -> t > 0 }, it.end())
            },
            resume = minResumeOffer(root, canRun = !removed, stopping = input.stopping),
            interactions = interactions,
            settings = settingsOf(root, providerLabel),
            usage = usageViewOf(root, profile?.contextWindow).copy(costLabel = cost.totalLabel(), subagentCostLabel = cost.subagentLabel()),
            tasks = input.tasks,
            threads = threads.withPendingCounts(interactions),
            slashCommands = text.slashCommands() + input.workflowCommands,
            team = minTeamView(input.root, input.nodes, input.teamMembers, threads),
            schedule = minScheduleView(input.cronBoard, input.cronsEnabled),
            goal = minGoalView(root.goal),
            plan = planOf(root),
            stopping = input.stopping,
            errorMessage = input.errorMessage,
            connection = ConnectionView(model.providerId, providerLabel, stale = false, removed = removed),
            // Min 没有进程：空闲的会话 status 是 Idle，但它是打开着的（见 AgentSessionState.open）
            open = true,
        )
    }

    private fun turnOf(root: SessionState, input: MinMapperInput): TurnProgress? {
        val turn = root.turn ?: return null
        val attempt = turn.openRequest?.attempt ?: 1
        return TurnProgress(
            turnId = turn.turnId.value,
            startedAt = turn.startedAtMs,
            // Min 没有「撤回一个字都没出的回合」（输入在回合开始时就提升进历史），所以永远是打断
            produced = true,
            phase = when (turn.trigger) {
                dev.min.agent.spi.TurnTrigger.Notification -> text.subagentWoke()
                dev.min.agent.spi.TurnTrigger.Hook -> text.hookWoke()
                dev.min.agent.spi.TurnTrigger.Cron -> text.cronWoke()
                else -> null
            },
            detail = input.toolStatus,
            // attempt > 1 就是上一次请求失败后在退避重试
            retry = if (attempt > 1) RetryNotice(null, attempt, input.maxRetryAttempts) else null,
            outputTokensSettled = turn.usage.outputTokens.toInt(),
            outputTokensCurrent = 0,
            trigger = triggerViewOf(turn.trigger),
            fallback = turn.fallback?.let { FallbackNotice(it.model.modelId, it.primary.modelId, failureKindOf(it.reason)) },
        )
    }

    private fun settingsOf(root: SessionState, providerLabel: String?): SettingsView {
        val model = root.settings.model
        val profile = lookup.profileOf(model)
        val models = lookup.choices(model)
        // 档位表头上加一项「跟随默认」（id 为空 = 清掉强度设置，让模型用自己的默认）；模型没有强度档就整张表为空，面板据此隐藏这一段
        val efforts = profile?.effortPatches?.keys.orEmpty().sortedBy { it.ordinal }.map { Choice(effortId(it), text.effort(it)) }
            .let { levels -> if (levels.isEmpty()) levels else listOf(Choice("", "")) + levels }
        val modes = minModeChoices(text)
        return SettingsView(
            model = models.firstOrNull { it.id == model.modelId && it.profileId == model.providerId }
                ?: Choice(model.modelId, model.modelId, profileId = model.providerId),
            models = models,
            modelsLoading = false,
            effort = root.settings.effort?.let { e -> efforts.firstOrNull { it.id == effortId(e) } ?: Choice(effortId(e), text.effort(e)) },
            efforts = efforts,
            effortNeedsRestart = false,
            mode = modes.firstOrNull { it.id == modeId(root.settings.mode) } ?: Choice(modeId(root.settings.mode), text.mode(root.settings.mode)),
            modes = modes,
            autoReview = AutoReviewView(
                reviewer = root.autoReviewSettings.reviewer?.let { r -> models.firstOrNull { it.id == r.model.modelId && it.profileId == r.model.providerId } ?: Choice(r.model.modelId, r.model.modelId, profileId = r.model.providerId) },
                defaultModel = dev.min.agent.kernel.defaultAutoReviewer(model, lookup::fastModelOf).modelId,
            ),
            advisor = dev.min.code.core.engine.AdvisorView(root.advisor.model?.let { ref ->
                models.firstOrNull { it.id == ref.modelId && it.profileId == ref.providerId } ?: Choice(ref.modelId, ref.modelId, profileId = ref.providerId)
            }),
            applying = false,
            extras = emptyList(),
            // 档被删了拿不到能力：按「不吃图」处理，反正会话只读，发不了
            modelSupportsImages = profile?.supportsImages ?: false.also { providerLabel },
        )
    }

    /** 最近一次交给用户批准的计划正文（ExitPlanMode 的 plan 入参），界面的计划卡与回看用 */
    private fun planOf(root: SessionState): String? = root.history.asReversed()
        .flatMap { entry -> (entry as? dev.min.agent.kernel.HistoryEntry.Assistant)?.parts.orEmpty().asReversed() }
        .firstNotNullOfOrNull { part ->
            (part as? ToolCallPart)?.takeIf { it.name == EXIT_PLAN_MODE }?.let { (it.input["plan"] as? JsonPrimitive)?.content }
        }

    private fun titleOf(items: List<ChatItem>): String? =
        items.firstOrNull { it is ChatItem.UserText && !it.queued }?.let { (it as ChatItem.UserText).text }
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(TITLE_CHARS)

    companion object {
        /** 列表里标题的长度：设计 8.2，取第一条用户消息的前 80 字 */
        const val TITLE_CHARS = 80
    }
}

/** 设置面板与开始面板共用的权限模式表 */
fun minModeChoices(text: MinAgentText): List<Choice> = listOf(
    PermissionMode.Default, PermissionMode.AcceptEdits, PermissionMode.Auto, PermissionMode.DontAsk, PermissionMode.BypassPermissions, PermissionMode.Plan,
).map { Choice(modeId(it), text.mode(it), warning = text.modeWarning(it)) }

/** 权限模式在命令里的 id：沿用协议里的名字（与 Claude Code 同名同义） */
fun modeId(mode: PermissionMode): String = when (mode) {
    PermissionMode.Default -> "default"
    PermissionMode.AcceptEdits -> "acceptEdits"
    PermissionMode.Plan -> "plan"
    PermissionMode.Auto -> "auto"
    PermissionMode.DontAsk -> "dontAsk"
    PermissionMode.BypassPermissions -> "bypassPermissions"
}

fun modeOfId(id: String): PermissionMode? = PermissionMode.entries.firstOrNull { modeId(it) == id }

fun effortId(effort: Effort): String = when (effort) {
    Effort.Off -> "off"
    Effort.Minimal -> "minimal"
    Effort.Low -> "low"
    Effort.Medium -> "medium"
    Effort.High -> "high"
    Effort.XHigh -> "xhigh"
    Effort.Max -> "max"
}

fun effortOfId(id: String): Effort? = Effort.entries.firstOrNull { effortId(it) == id }

/**
 * 用量：上下文 = 最后一次请求的输入 + 缓存读写（K10，不是累计）；缓存命中率 = 累计缓存读 / 累计总输入。
 * 花费不填：目录没有价格表（M4）。子 agent 的用量在各自线程上，这里只算本会话。
 */
fun usageViewOf(state: SessionState, contextWindow: Int?): UsageView {
    val last = state.lastRequestUsage
    val total: Usage = state.usage + (state.turn?.usage ?: Usage())
    val inputs = total.inputTokens + total.cacheReadTokens + total.cacheWriteTokens
    return UsageView(
        contextTokens = last?.let { (it.inputTokens + it.cacheReadTokens + it.cacheWriteTokens).toInt() },
        contextLimit = contextWindow,
        costUsd = null,
        costText = null,
        turnOutputTokens = (state.turn?.usage ?: state.lastTurn?.usage)?.outputTokens?.toInt(),
        cacheReadRatio = if (inputs > 0) total.cacheReadTokens.toDouble() / inputs else null,
    )
}
