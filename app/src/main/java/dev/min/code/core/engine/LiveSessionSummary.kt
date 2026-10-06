package dev.min.code.core.engine

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/**
 * 供 Supervisor / 前台服务 / 抽屉用的扁平摘要：一个在线会话此刻的最小可观察面。
 * 通知的边沿判定（`SupervisorDiff.kt`）比较的就是前后两帧 [LiveSessionSummary]。
 *
 * 为什么不直接用 [AgentSessionState]：Supervisor 要订阅所有在线会话，包括界面上没打开的
 * （Min：后台子 agent 跑完唤醒的父会话），那些会话没有人在收 state 的完整流；摘要只带通知与保活要用的几项，
 * 订阅它不会把整页 `items` 拖进来重算。
 *
 * @param keepAlive 要不要保活 App 进程。CC / Codex：进程活着；Min：本会话树有回合在跑，或有后台进程在跑。
 *   空闲的 Min 会话不占资源，不需要保活（内核跑在 App 进程里，前台服务保的是进程本身与网络长连接）
 * @param isActive 是不是界面当前显示的那个。引擎自己不知道，由 [AgentSessionRegistry] 合并时填
 * @param attached 界面打开过、还没关。同上由注册表填
 * @param lastTurnEnd 上一个回合怎么结束的。null = 不知道，边沿判定当作正常完成。
 *   设计草稿的摘要里没有它，但 §7.2 的「完成 / 未完成」要靠它区分，所以补上
 */
data class LiveSessionSummary(
    val ref: SessionRef,
    val title: String?,
    val status: SessionStatus,
    val keepAlive: Boolean,
    val busy: Boolean,
    val isActive: Boolean = false,
    val attached: Boolean = false,
    val headInteraction: InteractionBrief? = null,
    val interactionCount: Int = 0,
    /** 顶栏那句实时状态（detail 优先，退回 phase），进通知副标题 */
    val statusText: String? = null,
    val errorMessage: String? = null,
    /** 最后一条助手回复原文，完成通知的正文取自它 */
    val lastAssistantText: String? = null,
    val resume: ResumeBrief? = null,
    val lastTurnEnd: TurnEnd? = null,
    /** 会话的工作目录（终端页给每个在跑的会话开页签用）。B6(b) 补 */
    val cwd: String? = null,
)

/** 队头交互的最小描述：通知按 [id] 判断「是不是换了一张」，按 [kind] 决定带不带按钮 */
data class InteractionBrief(
    val id: String,
    val kind: InteractionKind,
    /** Approval 的工具名；Question / PlanReview 为 null */
    val toolName: String?,
    /** 来自哪个子 agent；主会话为 null。通知标题写「Explore（后台）请求批准：Bash」 */
    val agent: AgentLabel?,
)

fun PendingInteraction.brief(): InteractionBrief = InteractionBrief(
    id = id,
    kind = kind,
    toolName = (this as? PendingInteraction.Approval)?.toolName,
    agent = origin.agent,
)

/**
 * 从一个会话的完整状态投出摘要。三个适配器各自的 `live` 流都用它，不各写一份。
 * [keepAlive] 的含义因引擎而异（见 [LiveSessionSummary]），只能由适配器给；[isActive] / [attached] 留给注册表。
 */
fun AgentSessionState.toLiveSummary(keepAlive: Boolean): LiveSessionSummary = LiveSessionSummary(
    ref = ref,
    title = title,
    status = status,
    keepAlive = keepAlive,
    busy = busy,
    headInteraction = headInteraction?.brief(),
    interactionCount = interactions.size,
    statusText = turn?.detail ?: turn?.phase,
    errorMessage = errorMessage,
    lastAssistantText = items.filterIsInstance<ChatItem.AssistantText>().lastOrNull()?.text,
    resume = resume?.brief(),
    lastTurnEnd = lastTurn?.end,
    cwd = cwd.ifBlank { null },
)
