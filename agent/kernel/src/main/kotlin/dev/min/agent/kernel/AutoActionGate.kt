package dev.min.agent.kernel

import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** 生成和交回也走原来的审查、审批与熔断；用独立调用 id，不能改写正在执行的父工具阶段。 */
internal suspend fun SessionRuntime.reviewAutoAction(turnId: TurnId, name: String, input: JsonObject, summary: String, backgroundSpawn: Boolean = false): Verdict {
    val call = ToolCallPart(CallId(deps.ids.next("review")), name, input)
    val specifier = (input["agent_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val ask = PermissionAsk(name, summary, specifiers = listOfNotNull(specifier))
    val context = ToolExecutor(this, HookDriver(this)).policyContext()
    val policy = dev.min.agent.policy.PolicyEngine.evaluate(ask, false, context)
    if (policy is Verdict.Deny) return policy
    var verdict = AutoReviewer(this).review(turnId, call, ask, context, dev.min.agent.policy.PolicyEngine.askRuleHit(ask, context) != null)
    // 审查期间切模式后，旧 Auto 结论不能替新的 Plan / dontAsk 放行生成。
    if (state.value.settings.mode != context.mode) verdict = dev.min.agent.policy.PolicyEngine.evaluate(ask, false, ToolExecutor(this, HookDriver(this)).policyContext())
    if (verdict != Verdict.Ask) return verdict
    headlessClassifierError(call.callId, backgroundSpawn)?.let {
        // 已启动的后台子回合不能把不可继续的审查失败当成普通工具错误，让模型反复重试。
        if (state.value.origin != null) throw TurnAbort(it)
        return Verdict.Deny(DecidedBy.Timeout, it)
    }
    val id = ApprovalId(deps.ids.next("ap"))
    commit(listOf(ToolPlanned(turnId, call.callId, name, input, ResumePolicy.Unknown),
        ApprovalRequested(turnId, id, call.callId, ask, null)), Durability.Sync)
    val phase = state.first { it.phaseOf(call.callId) !is CallPhase.AwaitingApproval }.phaseOf(call.callId)
    val allowed = phase == CallPhase.Approved
    commit(listOf(ToolOutcome(turnId, call.callId, ToolResult(listOf(TextPart(if (allowed) "用户批准" else "用户拒绝")), isError = !allowed))), Durability.Sync)
    return if (allowed) Verdict.Allow(DecidedBy.User) else Verdict.Deny(DecidedBy.User, "用户拒绝$summary")
}

internal fun SessionRuntime.headlessClassifierError(callId: CallId, backgroundSpawn: Boolean = false): String? {
    val parent = state.value.origin?.parent?.let { hub?.get(it) }
    val background = parent?.state?.value?.children?.get(sessionId)?.background == true
    if (!(state.value.created.headless && backgroundSpawn || parent?.state?.value?.created?.headless == true && background)) return null
    val reviewed = state.value.lastAutoReview?.takeIf { it.callId == callId } ?: return null
    return when (reviewed.reason) {
        AutoReviewer.CLASSIFIER_SAFETY_REFUSED -> "Agent aborted: auto mode classifier request refused by the safety safeguard in headless mode"
        AutoReviewer.CLASSIFIER_CONTEXT_OVERFLOW -> "Agent aborted: auto mode classifier transcript exceeded context window in headless mode"
        else -> null
    }
}
