package dev.min.agent.kernel

import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

internal const val HANDBACK_NOTE = "子 agent 已通过 SubagentHandback 交回报告。"

// Auto 创建的子会话即使转去 Plan，也不能靠改模式把未经审查的结果交回父会话。
internal fun SessionState.autoReportReviewRequired(): Boolean = settings.mode == PermissionMode.Auto || created.mode == PermissionMode.Auto

internal fun SessionState.handbackAvailable(): Boolean = settings.mode == PermissionMode.Auto &&
    origin?.let { !it.fork && !it.workflow && !it.teamMember } == true

internal class SubagentHandbackTool(private val rt: SessionRuntime) : Tool<String> {
    override val name = "SubagentHandback"
    override val traits = ToolTraits(readOnly = true)
    override val inputSchema = buildJsonObject {
        put("type", "object"); putJsonObject("properties") { putJsonObject("message") { put("type", "string") } }
        put("required", JsonArray(listOf(JsonPrimitive("message")))); put("additionalProperties", false)
    }
    override fun description(profile: HarnessProfile) = "Deliver your final report to the caller after safety review. A flagged report is delivered with a security warning; an unavailable review adds a verification note. Handback ends this turn; call it alone."
    override fun parse(raw: JsonObject): ToolInput<String> {
        val report = (raw["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (raw.keys == setOf("message") && !report.isNullOrBlank()) ToolInput.Valid(report) else ToolInput.Invalid("message 必须是非空文字")
    }
    override fun permissionAsk(input: String, ctx: ToolContext) = PermissionAsk(name, "交回子 agent 报告")
    override fun run(input: String, ctx: ToolContext) = flow {
        val turnId = requireNotNull(rt.state.value.turn).turnId
        val calls = (rt.state.value.history.lastOrNull() as? HistoryEntry.Assistant)?.toolCalls.orEmpty()
        if (!rt.state.value.handbackAvailable() || calls.size != 1) {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("SubagentHandback 只能由普通 auto 子 agent 单独调用。")), isError = true)))
            return@flow
        }
        val verdict = rt.reviewReport(turnId, input)
        rt.commit(listOf(rt.reportReady(turnId, input, verdict, handback = true)), Durability.Sync)
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(HANDBACK_NOTE)))))
    }
}

internal suspend fun SessionRuntime.reviewReport(turnId: TurnId, body: String): Verdict {
    val input = buildJsonObject {
        put("report", body); put("report_origin", "subagent")
        put("work", JsonArray(state.value.history.filterIsInstance<HistoryEntry.Assistant>().flatMap { it.toolCalls }
            .filter { it.name != "SubagentHandback" }.map { call -> buildJsonObject { put("tool", call.name); put("input", call.input) } }))
    }
    return reviewAutoAction(turnId, "SubagentHandback", input, "交回子 agent 报告")
}

internal suspend fun SessionRuntime.prepareFallbackReport(turnId: TurnId) {
    if (state.value.origin == null || !state.value.autoReportReviewRequired()) return
    val body = state.value.structuredOutput?.takeIf { it.turnId == turnId }?.value?.toString()
        ?: state.value.history.filterIsInstance<HistoryEntry.Assistant>().lastOrNull()?.parts.orEmpty().filterIsInstance<TextPart>().joinToString("") { it.text }
    val verdict = reviewReport(turnId, body)
    commit(listOf(reportReady(turnId, body, verdict)), Durability.Sync)
}

internal const val REPORT_SECURITY_WARNING = "[安全警告：审查标记了子 agent 的工作或报告，或 API 安全检查拒绝了核验。请谨慎核实以下报告；父会话的后续动作仍须正常审查。]"
internal const val REPORT_VERIFY_NOTE = "[核验说明：未能审查子 agent 的工作和报告。采取行动前，请核实子 agent 的工作。]"

private fun SessionRuntime.reportReady(turnId: TurnId, body: String, verdict: Verdict, handback: Boolean = false): SubagentReportReady {
    val review = when {
        verdict is Verdict.Deny || verdict == Verdict.Ask && state.value.lastAutoReview?.reason == AutoReviewer.CLASSIFIER_SAFETY_REFUSED -> "warning"
        verdict is Verdict.Allow && verdict.by == DecidedBy.Classifier -> "approved"
        else -> "unavailable"
    }
    val prefix = when (review) { "warning" -> REPORT_SECURITY_WARNING + "\n\n"; "unavailable" -> REPORT_VERIFY_NOTE + "\n\n"; else -> "" }
    return SubagentReportReady(turnId, prefix + body, review, handback)
}
