package dev.min.agent.kernel

import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** 生成和交回也走原来的审查、审批与熔断；用独立调用 id，不能改写正在执行的父工具阶段。 */
internal suspend fun SessionRuntime.reviewAutoAction(turnId: TurnId, name: String, input: JsonObject, summary: String): Verdict {
    val call = ToolCallPart(CallId(deps.ids.next("review")), name, input)
    val ask = PermissionAsk(name, summary)
    val context = ToolExecutor(this, HookDriver(this)).policyContext()
    val verdict = AutoReviewer(this).review(turnId, call, ask, context, false)
    if (verdict != Verdict.Ask) return verdict
    val id = ApprovalId(deps.ids.next("ap"))
    commit(listOf(ToolPlanned(turnId, call.callId, name, input, ResumePolicy.Unknown),
        ApprovalRequested(turnId, id, call.callId, ask, null)), Durability.Sync)
    val phase = state.first { it.phaseOf(call.callId) !is CallPhase.AwaitingApproval }.phaseOf(call.callId)
    val allowed = phase == CallPhase.Approved
    commit(listOf(ToolOutcome(turnId, call.callId, ToolResult(listOf(TextPart(if (allowed) "用户批准" else "用户拒绝")), isError = !allowed))), Durability.Sync)
    return if (allowed) Verdict.Allow(DecidedBy.User) else Verdict.Deny(DecidedBy.User, "用户拒绝$summary")
}
