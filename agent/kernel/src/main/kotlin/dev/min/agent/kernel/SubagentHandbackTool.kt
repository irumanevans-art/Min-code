package dev.min.agent.kernel

import dev.min.agent.policy.Verdict
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

internal const val HANDBACK_NOTE = "子 agent 已通过 SubagentHandback 交回报告。"

internal fun SessionState.handbackAvailable(): Boolean = settings.mode == PermissionMode.Auto &&
    origin?.let { !it.fork && !it.workflow && !it.teamMember } == true

internal class SubagentHandbackTool(private val rt: SessionRuntime) : Tool<String> {
    override val name = "SubagentHandback"
    override val traits = ToolTraits(readOnly = true)
    override val inputSchema = buildJsonObject {
        put("type", "object"); putJsonObject("properties") { putJsonObject("message") { put("type", "string") } }
        put("required", JsonArray(listOf(JsonPrimitive("message")))); put("additionalProperties", false)
    }
    override fun description(profile: HarnessProfile) = "Deliver your final report to the caller after safety review. If rejected, revise the report and call again. An approved handback ends this turn; call it alone."
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
        if (verdict is Verdict.Allow) {
            rt.commit(listOf(SubagentReportReady(turnId, input, if (verdict.by == DecidedBy.Classifier) "approved" else "manual", handback = true)), Durability.Sync)
            emit(ToolProgress.Done(ToolResult(listOf(TextPart(HANDBACK_NOTE)))))
        } else {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("报告审查未通过，原文没有交给父会话。请修改后重新调用 SubagentHandback。")), isError = true)))
        }
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
    if (state.value.origin == null || state.value.settings.mode != PermissionMode.Auto) return
    val body = state.value.structuredOutput?.takeIf { it.turnId == turnId }?.value?.toString()
        ?: state.value.history.filterIsInstance<HistoryEntry.Assistant>().lastOrNull()?.parts.orEmpty().filterIsInstance<TextPart>().joinToString("") { it.text }
    val verdict = reviewReport(turnId, body)
    val ready = if (verdict is Verdict.Allow) SubagentReportReady(turnId, body, if (verdict.by == DecidedBy.Classifier) "approved" else "manual")
        else SubagentReportReady(turnId, "[子 agent 报告未通过审查，正文未交付。]", "withheld")
    commit(listOf(ready), Durability.Sync)
}
