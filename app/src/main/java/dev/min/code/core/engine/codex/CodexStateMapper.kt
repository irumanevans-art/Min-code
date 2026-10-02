package dev.min.code.core.engine.codex

import dev.min.code.core.codex.CODEX_EFFORT_LEVELS
import dev.min.code.core.codex.CODEX_REASONING_SUMMARIES
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexEvent
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.codexPermissionWire
import dev.min.code.core.codex.permissionPreset
import dev.min.code.core.engine.AgentSessionState
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.ConnectionView
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.EngineOptionView
import dev.min.code.core.engine.InteractionOrigin
import dev.min.code.core.engine.LiveStream
import dev.min.code.core.engine.PathView
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SettingsView
import dev.min.code.core.engine.SlashCommandView
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.TurnProgress
import dev.min.code.core.engine.TurnReceipt
import dev.min.code.core.engine.UsageView
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.pathScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `CodexAppServerManager.State` -> [AgentSessionState]（设计 §3.4「Codex 来源」一列）。纯函数。
 *
 * Codex 比 CC 少很多东西：没有子 agent、任务条、计划、提问、花费、斜杠命令表（协议里没有斜杠命令这回事），
 * 对应字段一律空；能映射的都来自 `State` 的现有字段。几处要说明的：
 *
 * - **排队的追加消息**：manager 把它们放在 `State.queued: List<String>`（不进 items），页面画成输入框上方的 chip。
 *   通用状态按设计 §4.2 的终态把它们接在 items 末尾，画成 `UserText(queued = true)`，这样 `queuedCount` 与 CC 同口径。
 *   id 带着下标和正文哈希（[queuedItemId]）：队列是 FIFO、队头会被放出去，下标会移位，光有下标的 id 会让
 *   「撤回第 2 条」撤成别的东西；带上哈希，对不上就返回 Stale。
 * - **lastTurn.end**：manager 不记 turn/completed 的 status（只拿它决定要不要放出排队消息），所以 [CodexMapContext.lastTurnEnd]
 *   为 null 时回执写 [TurnEnd.Completed] 占位。B8 在 manager 补只读字段 `lastTurnEnd` 后再传进来。
 * - **用量**：`thread/tokenUsage/updated` 是线程累计值，页面现在不显示它；`contextTokens` 取 totalTokens（没有则输入 + 输出），
 *   `contextLimit` 取 contextWindow。没有逐回合输出量，turnOutputTokens 为 null。
 */
object CodexStateMapper {

    /** [SettingsView.extras] 的键，同时是 `SetEngineOption.key` */
    const val OPTION_SUMMARY = "summary"

    /** 权限档不在三档之内时的 `Choice.id` */
    const val MODE_CUSTOM = "custom"

    /** 选项 id：本会话允许。以 [ApprovalOption.ALWAYS_PREFIX] 开头，通知 / 界面按前缀认「始终允许」一类 */
    const val OPTION_ACCEPT_FOR_SESSION = ApprovalOption.ALWAYS_PREFIX + "acceptForSession"

    fun map(
        ref: SessionRef,
        state: CodexAppServerManager.State,
        context: CodexMapContext = CodexMapContext(),
    ): AgentSessionState {
        val queued = state.queued.mapIndexed { i, text -> ChatItem.UserText(queuedItemId(i, text), text, queued = true) }
        val approval = state.pendingApproval
        val mapped = AgentSessionState(
            ref = ref,
            status = state.status,
            title = context.title,
            cwd = state.options.cwd,
            items = state.items + queued,
            turn = if (state.busy) {
                TurnProgress(
                    turnId = state.turnId,
                    startedAt = state.turnStartedAt,
                    // Codex 不支持「一个字还没出就撤回」（withdrawInFlight = false）：填 true，停止键永远是打断
                    produced = true,
                    phase = state.activeFlags.firstOrNull(),
                )
            } else {
                null
            },
            lastTurn = state.lastTurnDurationMs?.let {
                TurnReceipt(turnId = state.turnId, durationMs = it, finishedAt = null, end = context.lastTurnEnd ?: TurnEnd.Completed)
            },
            resume = CodexResume.offer(state, context.lastTurnEnd, context.capabilities),
            interactions = listOfNotNull(approval?.let(::approvalCard)),
            settings = settingsOf(state.options),
            usage = UsageView(
                contextTokens = state.totalTokens ?: listOfNotNull(state.inputTokens, state.outputTokens).takeIf { it.isNotEmpty() }?.sum(),
                contextLimit = state.contextWindow,
                costUsd = null,
                costText = null,
                turnOutputTokens = null,
                cacheReadRatio = null,
            ),
            tasks = emptyList(),
            threads = emptyList(),
            slashCommands = SLASH_COMMANDS,
            plan = null,
            stopping = false,
            errorMessage = state.errorMessage,
            connection = context.connection,
        )
        return mapped
    }

    /** 逐字增量：manager 的 `streamingText / streamingThinking`。命令输出已经折进工具卡，没有单独的尾巴 */
    fun mapLive(state: CodexAppServerManager.State): LiveStream = LiveStream(
        turnId = state.turnId,
        text = state.streamingText,
        thinking = state.streamingThinking,
        toolDrafts = emptyList(),
        toolOutput = emptyMap(),
    )

    fun codexRef(threadId: String): SessionRef = SessionRef(EngineKind.Codex, threadId)

    /** 排队消息的稳定 id：`codex-queued-<下标>-<正文哈希>`，[parseQueuedItemId] 是它的逆 */
    fun queuedItemId(index: Int, text: String): String = "codex-queued-$index-${text.hashCode()}"

    /** 逆：(下标, 哈希)；不是这个形状返回 null */
    fun parseQueuedItemId(id: String): Pair<Int, Int>? {
        val m = QUEUED_ID.matchEntire(id) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: return null) to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    private val QUEUED_ID = Regex("""codex-queued-(\d+)-(-?\d+)""")

    // ---------------------------------------------------------------- 审批

    /**
     * 审批卡。选项按服务端给的 `availableDecisions` 来，空表示它没限制（与页面的 `allowsSession` 判据一致）：
     * accept = 允许；acceptForSession = 本会话允许；decline = 拒绝（不带说明）；cancel = 拒绝并停止。
     * 页面现在只画 decline / accept / acceptForSession，cancel 只在服务端明说可以时才出现。
     */
    private fun approvalCard(approval: CodexEvent.ApprovalRequest): PendingInteraction.Approval {
        val allowed = approval.availableDecisions
        fun ok(decision: CodexDecision, whenUnlisted: Boolean) =
            if (allowed.isEmpty()) whenUnlisted else decision.wire in allowed
        val options = buildList {
            if (ok(CodexDecision.ACCEPT, true)) {
                add(ApprovalOption(ApprovalOption.ALLOW, "允许", ApprovalOption.Kind.Allow, acceptsNote = false, notificationSafe = true))
            }
            if (ok(CodexDecision.ACCEPT_FOR_SESSION, true)) {
                add(ApprovalOption(OPTION_ACCEPT_FOR_SESSION, "本会话允许", ApprovalOption.Kind.AllowAlways, acceptsNote = false, notificationSafe = false))
            }
            if (ok(CodexDecision.DECLINE, true)) {
                add(ApprovalOption(ApprovalOption.DENY, "拒绝", ApprovalOption.Kind.Deny, acceptsNote = false, notificationSafe = true))
            }
            if (ok(CodexDecision.CANCEL, false)) {
                add(ApprovalOption(ApprovalOption.DENY_AND_STOP, "拒绝并停止", ApprovalOption.Kind.DenyAndStop, acceptsNote = false, notificationSafe = false))
            }
        }
        val toolName = when (approval.kind) {
            CodexEvent.ApprovalRequest.Kind.Command -> "Bash" // 与 CodexChatMapping 里命令卡的名字一致
            CodexEvent.ApprovalRequest.Kind.FileChange -> "Edit"
            CodexEvent.ApprovalRequest.Kind.Permissions -> "Permissions"
        }
        return PendingInteraction.Approval(
            id = approval.requestId,
            origin = InteractionOrigin.Main,
            toolUseId = approval.itemId,
            toolName = toolName,
            input = buildJsonObject {
                approval.command?.let { put("command", it) }
                approval.cwd?.let { put("cwd", it) }
                approval.grantRoot?.let { put("grantRoot", it) }
            },
            summary = approval.command?.takeIf { it.isNotBlank() } ?: approval.reason?.takeIf { it.isNotBlank() } ?: toolName,
            // 文件改动审批问的是「要不要把整个仓库根授权出去」：那个根就是它要碰的路径
            paths = listOfNotNull(approval.grantRoot?.takeIf { it.isNotBlank() }).map { PathView(it, pathScope(it)) },
            commands = listOfNotNull(approval.command?.takeIf { it.isNotBlank() }),
            domains = emptyList(),
            reason = approval.reason,
            blockedPath = approval.grantRoot,
            options = options,
        )
    }

    /** 选项 id -> Codex 决定；对不上返回 null */
    fun decisionOf(optionId: String): CodexDecision? = when (optionId) {
        ApprovalOption.ALLOW -> CodexDecision.ACCEPT
        OPTION_ACCEPT_FOR_SESSION -> CodexDecision.ACCEPT_FOR_SESSION
        ApprovalOption.DENY -> CodexDecision.DECLINE
        ApprovalOption.DENY_AND_STOP -> CodexDecision.CANCEL
        else -> null
    }

    // ---------------------------------------------------------------- 设置

    private fun settingsOf(options: CodexAppServerManager.Options): SettingsView {
        val presets = CodexPermissionPreset.entries.map { Choice(it.id, it.id, description = it.wireSummary) }
        val current = options.permissionPreset
        return SettingsView(
            // Codex 没接 model/list：没有目录可挑（默认 / 连接配置里的 / 手输），所以 models 为空
            model = options.model?.let { Choice(it, it) },
            models = emptyList(),
            modelsLoading = false,
            effort = options.effort?.let { Choice(it, it) },
            efforts = CODEX_EFFORT_LEVELS.map { Choice(it, it) },
            effortNeedsRestart = false, // 下一轮 turn/start 带上，不重启进程
            mode = current?.let { p -> presets.first { it.id == p.id } }
                ?: Choice(MODE_CUSTOM, codexPermissionWire(options.approvalPolicy, options.sandbox)),
            modes = presets,
            applying = false,
            extras = listOf(
                EngineOptionView(
                    key = OPTION_SUMMARY, label = "Reasoning summary", value = options.summary.orEmpty(),
                    options = CODEX_REASONING_SUMMARIES.map { Choice(it, it) },
                ),
            ),
            modelSupportsImages = false,
        )
    }

    /**
     * 本地斜杠命令（`ui/codex/CodexSlash.kt`）：协议里没有斜杠命令，这几条是 Min 自己有对应动作的，
     * 作用是拦下误发。表在 ui 里（core 不许引用），这里只抄名字；B6 并页时再决定由谁持有。
     */
    private val SLASH_COMMANDS = listOf("model", "effort", "permissions", "approvals", "cd", "new").map { SlashCommandView(it, null) }
}

/**
 * 映射要的、不在 `State` 里的外部信息。
 * @param title 会话标题：人手改名 > rollout 里第一句话（调用方按 `SessionMeta` / `CodexSessionSummary` 算好）
 * @param lastTurnEnd manager 的 `lastTurnEnd`（B8 才有），见 [CodexStateMapper] 头注释
 * @param capabilities 用哪一套能力位判 ▶：`turn/interrupt` 接上之前是 [EngineCapabilities.Codex]（没有 ▶），
 *   接上之后传 [EngineCapabilities.CodexWithTurnInterrupt]
 * @param connection 活跃 Codex 档与是否已过时
 */
data class CodexMapContext(
    val title: String? = null,
    val lastTurnEnd: TurnEnd? = null,
    val capabilities: EngineCapabilities = EngineCapabilities.Codex,
    val connection: ConnectionView? = null,
)

