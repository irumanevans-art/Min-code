package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ASK_USER_QUESTION_TOOL
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.FORK_SUBAGENT_TYPE
import dev.min.code.core.claudecode.PermissionSuggestion
import dev.min.code.core.claudecode.parseAskUserQuestions
import dev.min.code.core.engine.AgentLabel
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.InteractionOrigin
import dev.min.code.core.engine.PathView
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.QuestionOptionView
import dev.min.code.core.engine.QuestionView
import dev.min.code.core.engine.withQueueDepth
import dev.min.code.core.session.escapedPaths
import dev.min.code.core.session.pathScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/*
 * Claude Code 的待批请求（`can_use_tool`）-> 通用的 [PendingInteraction]，以及答复的反向翻译（设计 §3.6 表格）。
 *
 * 三种卡共用 CLI 的同一个 `can_use_tool` 帧，按工具名分：AskUserQuestion = 提问，ExitPlanMode = 计划审批，
 * 其余 = 审批。纯函数，不碰 manager：答复只算出「该调 manager 哪个方法、传什么」（[ClaudeAnswerCall]），
 * 真的调用在 `ClaudeCodeSession`，这样翻译规则不起进程也能测。
 */

/** ExitPlanMode 的工具名（CLI 内置） */
internal const val EXIT_PLAN_MODE_TOOL = "ExitPlanMode"

/** 拒绝并说明时用户没写说明的兜底句，递给模型。与通知里的「拒绝」同口径（带话 = 本轮继续，不打断） */
internal const val DEFAULT_DENY_NOTE = "用户拒绝了这次调用"

/**
 * 把 manager 的待批队列翻成通用交互队列（队头 = 界面上挂着的那张）。
 *
 * @param tasks 任务表：认出发起请求的子 agent（`agentId` = 任务 id）是谁、后台还是前台
 */
internal fun claudeInteractions(
    queue: List<ClaudeCodeEvent.PermissionRequest>,
    tasks: List<ClaudeCodeManager.TaskInfo>,
    threads: List<AgentThread>,
): List<PendingInteraction> = queue.map { request ->
    val origin = InteractionOrigin(path = originPathOf(request, tasks, threads))
    when (request.toolName) {
        ASK_USER_QUESTION_TOOL -> PendingInteraction.Question(
            id = request.requestId,
            origin = origin,
            toolUseId = request.toolUseId,
            questions = parseAskUserQuestions(request.input).map { q ->
                QuestionView(
                    question = q.question,
                    header = q.header,
                    options = q.options.map { QuestionOptionView(it.label, it.description, it.preview) },
                    multiSelect = q.multiSelect,
                )
            },
        )

        EXIT_PLAN_MODE_TOOL -> PendingInteraction.PlanReview(
            id = request.requestId,
            origin = origin,
            toolUseId = request.toolUseId,
            plan = (request.input["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            // 计划没有「始终允许」：那条建议多半是切权限模式，不该在批准计划时顺手放宽
            options = listOf(allowOption(), denyWithNoteOption(), denyAndStopOption()),
            extraOptions = suggestionOptions(request),
        )

        else -> PendingInteraction.Approval(
            id = request.requestId,
            origin = origin,
            toolUseId = request.toolUseId,
            toolName = request.toolName,
            input = request.input,
            summary = request.description?.takeIf { it.isNotBlank() } ?: request.toolName,
            description = request.description,
            paths = escapedPaths(request.input).map { PathView(it, pathScope(it)) },
            commands = commandsOf(request),
            domains = domainsOf(request),
            reason = request.decisionReason,
            blockedPath = request.blockedPath,
            options = buildList {
                add(allowOption())
                addAll(suggestionOptions(request))
                add(denyAndStopOption())
                add(denyWithNoteOption())
            },
        )
    }
}.withQueueDepth()

private fun suggestionOptions(request: ClaudeCodeEvent.PermissionRequest): List<ApprovalOption> =
    request.suggestions.mapIndexed { i, s ->
        ApprovalOption(
            id = ApprovalOption.ALWAYS_PREFIX + i, label = s.label,
            kind = ApprovalOption.Kind.AllowAlways, acceptsNote = false, notificationSafe = false,
        )
    }

private fun allowOption() = ApprovalOption(
    id = ApprovalOption.ALLOW, label = "允许", kind = ApprovalOption.Kind.Allow,
    acceptsNote = false, notificationSafe = true,
)

/** 拒绝并说明：带话、本轮继续。通知上的「拒绝」就是这一种（现行为，见设计 §1.4） */
private fun denyWithNoteOption() = ApprovalOption(
    id = ApprovalOption.DENY, label = "拒绝并说明", kind = ApprovalOption.Kind.Deny,
    acceptsNote = true, notificationSafe = true,
)

/** 拒绝并停止：不带说明、会打断本轮。界面里的「拒绝」现行为 */
private fun denyAndStopOption() = ApprovalOption(
    id = ApprovalOption.DENY_AND_STOP, label = "拒绝并停止", kind = ApprovalOption.Kind.DenyAndStop,
    acceptsNote = false, notificationSafe = false,
)

private fun commandsOf(request: ClaudeCodeEvent.PermissionRequest): List<String> {
    if (request.toolName != "Bash") return emptyList()
    return listOfNotNull((request.input["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() })
}

/** WebFetch 的 url 取主机名；别的工具没有显式的域名入参，不去猜 */
private fun domainsOf(request: ClaudeCodeEvent.PermissionRequest): List<String> {
    if (request.toolName != "WebFetch") return emptyList()
    val url = (request.input["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
    return listOfNotNull(runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() })
}

/**
 * 请求来自哪个子 agent：名牌里的说明与 `PermissionOrigin` 一致（任务说明 > 类型 > id 前 8 位），
 * 这样「Explore · 查找入口（后台）请求」那一行换成通用类型后一个字不变。
 * CC 的请求只带 agentId（= 任务 id），层级看不出来，所以路径只有一层。
 */
private fun originPathOf(
    request: ClaudeCodeEvent.PermissionRequest,
    tasks: List<ClaudeCodeManager.TaskInfo>,
    threads: List<AgentThread>,
): List<AgentLabel> {
    val agentId = request.agentId ?: return emptyList()
    val task = tasks.firstOrNull { it.id == agentId }
    // 线程的 threadId 是发起它的 Agent 调用的 toolUseId；任务表里查不到时退回 agentId（task_started 还没到）
    val threadId = task?.toolUseId ?: threads.firstOrNull { it.threadId == agentId }?.threadId ?: agentId
    return listOf(
        AgentLabel(
            threadId = threadId,
            agentType = task?.subagentType ?: "agent",
            name = null,
            description = task?.description?.takeIf { it.isNotBlank() } ?: task?.subagentType ?: agentId.take(8),
            background = task?.backgrounded == true,
            fork = task?.subagentType == FORK_SUBAGENT_TYPE,
        ),
    )
}

/** 这张卡的来源是不是分叉子 agent：卡上说「分身」而不是「子 agent」 */
internal fun AgentLabel.isFork(): Boolean = agentType == FORK_SUBAGENT_TYPE

// ---------------------------------------------------------------------------
// 答复
// ---------------------------------------------------------------------------

/** 一次答复翻成 manager 的哪个调用。[Rejected] 带的是给界面看的原因 */
internal sealed interface ClaudeAnswerCall {
    /** `answerPermission(allow, denyMessage, suggestion, expectedRequestId)` */
    data class Permission(
        val allow: Boolean,
        val denyMessage: String?,
        val suggestion: PermissionSuggestion?,
        val requestId: String,
    ) : ClaudeAnswerCall

    /** `answerQuestions(answers)`；空 map = 跳过 */
    data class Questions(val answers: Map<String, String>) : ClaudeAnswerCall

    data class Rejected(val message: String) : ClaudeAnswerCall
}

/**
 * 通用答复 -> manager 调用。[card] 是按 id 找到的那张卡（找不到由调用方先返回 Stale）。
 *
 * 提问卡只认 [InteractionAnswer.Answer] / [InteractionAnswer.Decline]，审批与计划只认 [InteractionAnswer.Choose]，
 * 对不上的类型返回 Rejected：界面不该发出来，返回值只为兜底。
 *
 * 提问卡的 manager 方法 `answerQuestions` 永远应答「队头」，所以只有队头那张才能答；
 * 非队头返回 Rejected（调用方已用 Stale 挡掉「不在队里」的，队里非队头的这里挡）。
 */
internal fun claudeAnswerCall(
    card: PendingInteraction,
    answer: InteractionAnswer,
    request: ClaudeCodeEvent.PermissionRequest,
    isHead: Boolean,
): ClaudeAnswerCall = when (card) {
    is PendingInteraction.Question -> when {
        !isHead -> ClaudeAnswerCall.Rejected("提问卡只能答队头那一张")
        answer is InteractionAnswer.Answer ->
            ClaudeAnswerCall.Questions(answer.answers.associate { it.question to it.joined })
        // 跳过 = 允许工具带着空答案跑完，不能回 deny（模型会当成用户拒绝了提问）
        answer is InteractionAnswer.Decline -> ClaudeAnswerCall.Questions(emptyMap())
        else -> ClaudeAnswerCall.Rejected("提问卡要用 Answer / Decline 答复")
    }

    is PendingInteraction.Approval, is PendingInteraction.PlanReview -> {
        val options = when (card) {
            is PendingInteraction.Approval -> card.options
            is PendingInteraction.PlanReview -> card.options + card.extraOptions
            else -> emptyList()
        }
        if (answer !is InteractionAnswer.Choose) {
            ClaudeAnswerCall.Rejected("审批卡要用 Choose 答复")
        } else {
            val option = options.firstOrNull { it.id == answer.optionId }
            when (option?.kind) {
                null -> ClaudeAnswerCall.Rejected("没有这个选项：${answer.optionId}")
                ApprovalOption.Kind.Allow -> ClaudeAnswerCall.Permission(true, null, null, request.requestId)
                ApprovalOption.Kind.AllowAlways -> {
                    val index = option.id.removePrefix(ApprovalOption.ALWAYS_PREFIX).toIntOrNull()
                    ClaudeAnswerCall.Permission(true, null, index?.let(request.suggestions::getOrNull), request.requestId)
                }
                // 带话的拒绝：说明递给模型、本轮继续
                ApprovalOption.Kind.Deny -> ClaudeAnswerCall.Permission(
                    false, answer.note?.takeIf { it.isNotBlank() } ?: DEFAULT_DENY_NOTE, null, request.requestId,
                )
                // 不带话的拒绝：manager 会 interrupt（界面里「拒绝」的现行为）
                ApprovalOption.Kind.DenyAndStop -> ClaudeAnswerCall.Permission(false, null, null, request.requestId)
            }
        }
    }
}

/** 取出 [JsonObject] 里的字符串字段（私有小工具，免得到处写 JsonPrimitive 判断） */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
