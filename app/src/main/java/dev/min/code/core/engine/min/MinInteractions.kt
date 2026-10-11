package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.QuestionAnswer
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.InteractionIds
import dev.min.code.core.engine.InteractionOrigin
import dev.min.code.core.engine.PEER_MESSAGE_APPROVAL
import dev.min.code.core.engine.PathView
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.QuestionOptionView
import dev.min.code.core.engine.QuestionView
import dev.min.code.core.engine.UrlRequest
import dev.min.code.core.engine.originOf
import dev.min.code.core.engine.withQueueDepth
import dev.min.code.core.session.PathScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.min.agent.spi.PathScope as KernelPathScope

/** 计划审批的工具名：`ExitPlanModeTool` 的约定——审批的 summary 就是计划正文 */
const val EXIT_PLAN_MODE = "ExitPlanMode"
private const val AGENT_TOOL = "Agent"

/**
 * 整棵子 agent 树里等人的审批与提问 -> 通用交互队列（设计 5.5–5.7）。
 *
 * 数据来自 `hub.pending`（K11，Hub 知道所有在线运行时），调用方先按根会话过滤好再传进来。
 * 每张卡的 id 是 `"<sessionId>/<approvalId 或 questionId>"`（[InteractionIds]）：子会话上浮的交互不会和根会话的撞号，
 * 答复时 [MinInteractionRouter] 按前缀路由回对应的运行时。来源（`origin.path`）由子 agent 线程链算出，
 * 「Explore · 查找入口（后台）请求 · 后面还有 2 张」读的就是它；队列按发起时间排，队头先答。
 *
 * @param nodes 树里各会话的状态：审批卡要从调用里取入参（`HubPending` 只带 ask，不带 input）
 */
fun minInteractions(
    pending: List<HubPending>,
    root: SessionId,
    nodes: Map<SessionId, SessionState>,
    threads: List<AgentThread>,
    text: MinAgentText,
): List<PendingInteraction> = pending.sortedBy { it.atMs }.map { item ->
    val threadId = if (item.session == root) null else threads.firstOrNull { it.childRef?.id == item.session.value }?.threadId
    val origin = originOf(threads, threadId)
    val id: String
    val card = when (val what = item.what) {
        is HubPending.What.Network -> {
            val request = what.request
            id = InteractionIds.scoped(item.session.value, request.approvalId.value)
            PendingInteraction.Approval(
                id = id, origin = origin, toolUseId = request.callId.value, toolName = "Network",
                input = JsonObject(emptyMap()), summary = text.networkApprovalSummary(),
                description = "${text.networkApprovalSummary()}\n${request.domain}:${request.port}\n${request.commandSummary}",
                paths = emptyList(), commands = listOf(request.commandSummary), domains = listOf("${request.domain}:${request.port}"),
                reason = null, blockedPath = null,
                options = listOf(
                    ApprovalOption(ApprovalOption.ALLOW, text.networkAllowOnce(), ApprovalOption.Kind.Allow, false, true),
                    ApprovalOption("network_always", text.networkAllowAlways(), ApprovalOption.Kind.AllowAlways, false, false),
                    ApprovalOption(ApprovalOption.DENY, text.networkDeny(), ApprovalOption.Kind.Deny, true, true),
                ),
            )
        }
        is HubPending.What.Peer -> {
            id = InteractionIds.scoped(item.session.value, PEER_ID_PREFIX + what.messageId)
            peerHold(id, origin, what, text)
        }
        is HubPending.What.Approval -> {
            id = InteractionIds.scoped(item.session.value, what.approvalId.value)
            val input = nodes[item.session]?.turn?.calls?.get(what.callId)?.input ?: JsonObject(emptyMap())
            if (what.tool == EXIT_PLAN_MODE) planReview(id, origin, what, text) else approval(id, origin, what, input, text)
        }
        is HubPending.What.Question -> {
            id = InteractionIds.scoped(item.session.value, what.questionId.value)
            // 服务器名：url 模式的请求自带；表单模式的提问记在状态的 mcpQuestions 里（模型自己的 AskUserQuestion 没有）
            val server = what.mcpUrl?.server ?: nodes[item.session]?.mcpQuestions?.get(what.questionId)?.server
            PendingInteraction.Question(
                id = id, origin = origin, toolUseId = what.callId.value,
                questions = what.questions.map { q ->
                    QuestionView(q.question, q.header, q.options.map { QuestionOptionView(it.label, it.description) }, q.multiSelect)
                },
                server = server,
                url = what.mcpUrl?.let { UrlRequest(it.url, it.domain, it.message) },
            )
        }
    }
    card
}.withQueueDepth()

/** 交互 id 里待批准的跨会话消息的前缀：答复时路由据此改走「批准 / 拒收这封信」，而不是答复一次工具审批 */
internal const val PEER_ID_PREFIX = "peer:"

/**
 * 待批准的跨会话消息：一张只有「送达 / 拒收」的审批卡。正文就是对方写的原话（[PendingInteraction.Approval.description]）；
 * 两个按钮都能放进通知——批不批一封信不需要回 App 看规则。没有「总是允许」：要长期收某个会话的信，改的是收件口径，不是在卡上攒规则
 */
private fun peerHold(id: String, origin: InteractionOrigin, what: HubPending.What.Peer, text: MinAgentText) = PendingInteraction.Approval(
    id = id, origin = origin, toolUseId = null, toolName = PEER_MESSAGE_APPROVAL, input = JsonObject(emptyMap()),
    summary = text.peerHoldSummary(what.fromName ?: what.from.value), description = what.text,
    paths = emptyList(), commands = emptyList(), domains = emptyList(), reason = text.peerHoldReason(), blockedPath = null,
    options = listOf(
        ApprovalOption(ApprovalOption.ALLOW, text.peerDeliver(), ApprovalOption.Kind.Allow, acceptsNote = false, notificationSafe = true),
        ApprovalOption(ApprovalOption.DENY, text.peerRefuse(), ApprovalOption.Kind.Deny, acceptsNote = false, notificationSafe = true),
    ),
)

private fun approval(
    id: String, origin: InteractionOrigin, what: HubPending.What.Approval, input: JsonObject, text: MinAgentText,
): PendingInteraction.Approval {
    val ask = what.ask
    // 委派的卡片正文写「委派 Explore · 查找入口」；prompt 在 input 里，界面默认折叠
    val summary = if (what.tool == AGENT_TOOL) {
        text.delegation(ask.specifiers.firstOrNull() ?: "general-purpose", (input["description"] as? JsonPrimitive)?.content.orEmpty())
    } else if (what.tool == "Workflow") ask.summary.substringBefore("\n\n") else ask.summary
    return PendingInteraction.Approval(
        id = id, origin = origin, toolUseId = what.callId.value, toolName = what.tool, input = input, summary = summary,
        // 卡片上的路径行是「要动的东西跑出了工作区」（界面写「在 /workspace 之外」，设备上的写朱字）：
        // 内核的 ask.paths 是这次调用碰到的**所有**路径，工作区之内的不该进来，否则每张写文件的卡都会谎称自己在工作区之外
        paths = ask.paths.map { PathView(it.path.value, scopeOf(it.scope)) }.filter { it.scope != PathScope.Workspace },
        commands = ask.commands.map { it.raw },
        // Monitor 的 WebSocket 源：内核的 ask 里不带域名（WebFetch 的放行规则不能顺手放行长连接），卡上要让人看见连哪里
        domains = ask.domains + listOfNotNull(if (what.tool == "Monitor") monitorWsHost(input) else null),
        // 踩了安全底线的原因：解释「为什么连跳过权限也要问你」
        reason = what.floorReason,
        blockedPath = null,
        // 内核给了建议规则（`suggestedRule`，CC 语法）才有「总是允许」：本会话 / 本项目 / 所有项目三档，写哪一层由用户点哪个。
        // 排在标准三项后面；委派（Agent）与踩了安全底线的调用内核不给建议规则（floorReason 不为空时连跳过权限也要问）
        options = listOf(
            ApprovalOption(ApprovalOption.ALLOW, text.optionAllow(), ApprovalOption.Kind.Allow, acceptsNote = false, notificationSafe = true),
            ApprovalOption(ApprovalOption.DENY, text.optionDeny(), ApprovalOption.Kind.Deny, acceptsNote = true, notificationSafe = true),
            ApprovalOption(ApprovalOption.DENY_AND_STOP, text.optionDenyAndStop(), ApprovalOption.Kind.DenyAndStop, acceptsNote = false, notificationSafe = false),
        ) + if (what.tool == "Workflow" && what.suggestedRule != null) listOf(
            ApprovalOption(AlwaysOptionIds.of(RuleLayer.Session, requireNotNull(what.suggestedRule)), text.optionWorkflowRemember(), ApprovalOption.Kind.AllowAlways, acceptsNote = false, notificationSafe = false)
        ) else alwaysOptions(what.suggestedRule, text),
    )
}

/** 「总是允许」的三档按钮；没有建议规则就一个都没有。不进通知：要看清规则，必须回 App 里点 */
private fun alwaysOptions(rule: String?, text: MinAgentText): List<ApprovalOption> {
    if (rule.isNullOrBlank()) return emptyList()
    return RuleLayer.entries.map { layer ->
        ApprovalOption(AlwaysOptionIds.of(layer, rule), text.optionAlways(layer, rule), ApprovalOption.Kind.AllowAlways, acceptsNote = false, notificationSafe = false)
    }
}

private fun planReview(id: String, origin: InteractionOrigin, what: HubPending.What.Approval, text: MinAgentText) =
    PendingInteraction.PlanReview(
        id = id, origin = origin, toolUseId = what.callId.value, plan = what.ask.summary,
        // 批准 = 放行，退出计划模式由内核的 SessionEffect.ModeChange 落成设置变更；打回 = 拒绝并附意见，会话留在计划模式
        options = listOf(
            ApprovalOption(ApprovalOption.ALLOW, text.optionApprovePlan(), ApprovalOption.Kind.Allow, acceptsNote = false, notificationSafe = false),
            ApprovalOption(ApprovalOption.DENY, text.optionRevisePlan(), ApprovalOption.Kind.Deny, acceptsNote = true, notificationSafe = false),
        ),
    )

private fun scopeOf(scope: KernelPathScope): PathScope = when (scope) {
    KernelPathScope.Workspace -> PathScope.Workspace
    KernelPathScope.Rootfs -> PathScope.Rootfs
    KernelPathScope.Device -> PathScope.Device
}

/**
 * 答复一张交互卡：按 id 前缀找到会话、写回对应运行时。返回 false 的情况统一成 [CommandResult.Stale]：
 * 另一端（通知、另一个界面）先答了，或回合已经结束——内核的 `commitIf` 保证只落一次。
 *
 * [session] 按会话 id 取运行时（Hub 的 `get`），拿不到（已卸下）也是 Stale。
 * [interrupt] 给「拒绝并停止」用：拒绝落盘之后再中断（停哪几层由调用方定，见 MinCommandHandler）（与 CC 现行的界面「拒绝」一致）。
 */
class MinInteractionRouter(
    private val session: (SessionId) -> MinAnswerTarget?,
    private val interrupt: suspend (SessionId) -> Unit,
    /** 「总是允许」落到文件：(层, 规则)。内核那一步（本会话规则）已经在 [MinAnswerTarget.approve] 里完成，这里只管持久化 */
    private val persistRule: suspend (RuleLayer, String) -> Unit = { _, _ -> },
) {
    suspend fun answer(interactionId: String, answer: InteractionAnswer): CommandResult {
        val (sessionId, localId) = InteractionIds.split(interactionId) ?: return CommandResult.Rejected("交互 id 不合法：$interactionId")
        val target = session(SessionId(sessionId)) ?: return CommandResult.Stale("会话已不在线")
        if (localId.startsWith(PEER_ID_PREFIX)) {
            val approve = when ((answer as? InteractionAnswer.Choose)?.optionId) {
                ApprovalOption.ALLOW -> true
                ApprovalOption.DENY -> false
                else -> return CommandResult.Rejected("跨会话消息只能送达或拒收")
            }
            // 返回 false：已经处理过（另一端先点了）、已经过期，或收件会话关了——都是「这张卡已经过时」
            return if (target.resolvePeer(localId.removePrefix(PEER_ID_PREFIX), approve)) CommandResult.Accepted else CommandResult.Stale("已在别处处理或已过期")
        }
        val applied = when (answer) {
            is InteractionAnswer.Choose -> when (answer.optionId) {
                ApprovalOption.ALLOW -> target.approve(ApprovalId(localId), ApprovalChoice.Once)
                "network_always" -> target.approve(ApprovalId(localId), ApprovalChoice.Always("Network"))
                ApprovalOption.DENY -> target.approve(ApprovalId(localId), ApprovalChoice.Deny(answer.note?.takeIf { it.isNotBlank() }))
                ApprovalOption.DENY_AND_STOP -> target.approve(ApprovalId(localId), ApprovalChoice.Deny()).also { if (it) interrupt(SessionId(sessionId)) }
                else -> {
                    val always = AlwaysOptionIds.parse(answer.optionId)
                        ?: return CommandResult.Rejected("没有这个选项：${answer.optionId}")
                    val (layer, rule) = always
                    // 先让内核放行并记下本会话规则；放行成功了才写文件——另一端已经答过（Stale）就不该再改用户的配置
                    target.approve(ApprovalId(localId), ApprovalChoice.Always(rule)).also { applied -> if (applied && layer != RuleLayer.Session) persistRule(layer, rule) }
                }
            }
            is InteractionAnswer.Answer -> try {
                target.answer(QuestionId(localId), answer.answers.map { QuestionAnswer(it.selected, it.other) })
            } catch (e: IllegalArgumentException) {
                // 答案对不上问题是界面的 bug，不是用户的选择：什么都没写，原样告诉调用方
                return CommandResult.Rejected(e.message ?: "答案不合法")
            }
            InteractionAnswer.Decline -> target.decline(QuestionId(localId))
        }
        return if (applied) CommandResult.Accepted else CommandResult.Stale("已在别处处理")
    }
}

/**
 * 运行时里答复相关的那四个方法。`SessionRuntime` 是 final 类，直接拿它做单测要起整套内核，
 * 所以路由只依赖这个窄接口，组合根用 [asAnswerTarget] 把运行时包一层。
 */
interface MinAnswerTarget {
    suspend fun approve(id: ApprovalId, choice: ApprovalChoice): Boolean
    suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>): Boolean
    suspend fun decline(id: QuestionId): Boolean

    /** 批准 / 拒收一封待批准的跨会话消息（它挂在 Hub 的邮箱里，不在运行时的状态里）。默认没有：测试替身不用管 */
    suspend fun resolvePeer(messageId: String, approve: Boolean): Boolean = false
}

fun dev.min.agent.kernel.SessionRuntime.asAnswerTarget(hub: dev.min.agent.kernel.SessionHub): MinAnswerTarget = object : MinAnswerTarget {
    override suspend fun resolvePeer(messageId: String, approve: Boolean) = hub.resolvePeerHold(sessionId, messageId, approve)
    override suspend fun approve(id: ApprovalId, choice: ApprovalChoice) = answerApproval(id, choice)
    override suspend fun answer(id: QuestionId, answers: List<QuestionAnswer>) = answerQuestion(id, answers)
    override suspend fun decline(id: QuestionId) = declineQuestion(id)
}
