package dev.min.code.core.engine

import dev.min.code.core.session.PathScope
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.JsonObject

/** core/engine 各测试共用的小构造器，只放「造一个合法的东西」，不放断言 */
internal val CC = SessionRef(EngineKind.ClaudeCode, "cc-1")
internal val MIN = SessionRef(EngineKind.MinAgent, "s_1")

internal fun turn(
    produced: Boolean = true,
    id: String? = "t1",
    trigger: TurnTriggerView = TurnTriggerView.User,
) = TurnProgress(turnId = id, startedAt = 1_000L, produced = produced, trigger = trigger)

internal fun offer(
    id: String = "t0",
    reason: ResumeReason = ResumeReason.UserStopped,
    mode: ResumeMode = ResumeMode.Native,
    pending: Int = 0,
) = ResumeOffer(offerId = id, reason = reason, since = 500L, mode = mode, pendingInputs = pending)

internal fun thread(
    id: String,
    type: String = "Explore",
    parent: String? = null,
    depth: Int = 0,
    phase: AgentThread.Phase = AgentThread.Phase.Running,
    tracked: Boolean = true,
    messageable: Boolean = true,
    resume: ResumeOffer? = null,
    name: String? = null,
    background: Boolean = false,
) = AgentThread(
    threadId = id, parentThreadId = parent, depth = depth, agentType = type, name = name,
    description = "desc-$id", prompt = null, fork = false, background = background, phase = phase,
    activity = null, items = emptyList(), pendingInteractions = 0, usage = null, childRef = null,
    tracked = tracked, messageable = messageable, resume = resume,
)

internal fun approval(
    id: String,
    tool: String = "Bash",
    origin: InteractionOrigin = InteractionOrigin.Main,
) = PendingInteraction.Approval(
    id = id, origin = origin, toolUseId = "tu-$id", toolName = tool, input = JsonObject(emptyMap()),
    summary = "run $tool", paths = listOf(PathView("/workspace/a", PathScope.Workspace)),
    commands = emptyList(), domains = emptyList(), reason = null, blockedPath = null,
    options = listOf(
        ApprovalOption(ApprovalOption.ALLOW, "允许", ApprovalOption.Kind.Allow, acceptsNote = false, notificationSafe = true),
        ApprovalOption(ApprovalOption.ALWAYS_PREFIX + "0", "始终允许", ApprovalOption.Kind.AllowAlways, false, false),
        ApprovalOption(ApprovalOption.DENY, "拒绝", ApprovalOption.Kind.Deny, acceptsNote = true, notificationSafe = true),
        ApprovalOption(ApprovalOption.DENY_AND_STOP, "拒绝并停止", ApprovalOption.Kind.DenyAndStop, false, false),
    ),
)

internal fun question(id: String, origin: InteractionOrigin = InteractionOrigin.Main) = PendingInteraction.Question(
    id = id, origin = origin, toolUseId = null,
    questions = listOf(QuestionView("选哪个？", "选择", listOf(QuestionOptionView("A", null), QuestionOptionView("B", "更快")), false)),
)

internal fun planReview(id: String) = PendingInteraction.PlanReview(
    id = id, origin = InteractionOrigin.Main, toolUseId = null, plan = "# 计划", options = emptyList(),
)

internal fun summary(
    ref: SessionRef = CC,
    status: SessionStatus = SessionStatus.Running,
    busy: Boolean = false,
    keepAlive: Boolean = true,
    head: InteractionBrief? = null,
    error: String? = null,
    end: TurnEnd? = null,
    resume: ResumeBrief? = null,
) = LiveSessionSummary(
    ref = ref, title = null, status = status, keepAlive = keepAlive, busy = busy,
    headInteraction = head, interactionCount = if (head == null) 0 else 1,
    errorMessage = error, lastTurnEnd = end, resume = resume,
)
