package dev.min.agent.cli

import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.StructuredOutputDelivered
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `-p` 一次运行的结果对象，字段照 CC 的 result 消息（04-sessions-sdk-automation.md :218–219 的全表里
 * Min 有数据的那部分）：`type`/`subtype`/`is_error`/`result`/`session_id`/`num_turns`/`usage`/`duration_ms`/
 * `structured_output`/`errors`/`permission_denials`。花费只有全部请求有 USD 定价时输出，未知不能冒充零。
 * 只看本次命令之后的事件（[events]）；旧回合的累计 token 不能证明本轮成功
 */
internal data class CliResult(val session: String, val events: List<EventRecord>) {
    private val ends = events.mapNotNull { it.event as? TurnEnded }
    val completed: Boolean get() = ends.isNotEmpty() && ends.all { it.outcome == TurnOutcome.Completed }

    /** CC 对 result.subtype 的叫法：success / error_max_turns（--max-turns 到限）/ error_during_execution /
     *  error_max_structured_output_retries（--json-schema 重试用尽，内核用 Failed.cause 区分出来） */
    val subtype: String get() = when {
        completed -> "success"
        ends.any { (it.outcome as? TurnOutcome.Failed)?.cause == FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES } ->
            "error_max_structured_output_retries"
        ends.any { (it.outcome as? TurnOutcome.Interrupted)?.by == InterruptedBy.Limit } -> "error_max_turns"
        else -> "error_during_execution"
    }

    /** 这次运行的模型请求次数（CC 的 turn = 一次往返） */
    val numTurns: Int get() = events.count { it.event is ModelRequestStarted }

    private val turnUsage: Usage get() = ends.fold(Usage()) { total, end -> total + end.usage }
    private val durationMs: Long get() = ends.sumOf { it.durationMs }

    /** 最终回复文本：本次运行最后一条带文字的助手消息（success 臂的 `result` 字段；text 模式直接打它） */
    val resultText: String? = events.mapNotNull { it.event as? AssistantMessageCompleted }
        .lastOrNull { msg -> msg.parts.any { it is dev.min.agent.protocol.TextPart } }
        ?.let { m -> m.parts.filterIsInstance<dev.min.agent.protocol.TextPart>().joinToString("") { it.text }.trim().ifEmpty { null } }

    /** 校验通过的结构化输出（--json-schema）：只看本次命令之后的事件；失败 / 没开时没有这个字段 */
    private val structured = events.mapNotNull { it.event as? StructuredOutputDelivered }.lastOrNull()

    /** 失败时的说明列表（CC 的 error 臂带 errors:string[]）；到限与被中断也给一句人能读的原因 */
    val errors: List<String> = ends.mapNotNull { end ->
        when (val outcome = end.outcome) {
            TurnOutcome.Completed -> null
            is TurnOutcome.Failed -> outcome.message
            is TurnOutcome.Interrupted -> when (outcome.by) {
                InterruptedBy.Limit -> "到达 --max-turns 的回合上限"
                InterruptedBy.Hook -> "被 hook 停止"
                InterruptedBy.User -> "被用户中断"
                InterruptedBy.ProcessDeath -> "进程中断"
                InterruptedBy.Budget -> "到达定时任务的花费上限"
            }
        }
    }

    /** 本次运行被拒的审批（CC 的 permission_denials）：工具名、调用 id、入参 */
    private val denials: List<JsonObject> by lazy {
        val asks = events.mapNotNull { it.event as? ApprovalRequested }.associateBy { it.approvalId }
        val inputs = events.mapNotNull { it.event as? ToolPlanned }.associateBy { it.callId }
        events.mapNotNull { record ->
            val resolved = record.event as? ApprovalResolved ?: return@mapNotNull null
            if (resolved.choice !is ApprovalChoice.Deny) return@mapNotNull null
            val asked = asks[resolved.approvalId] ?: return@mapNotNull null
            buildJsonObject {
                put("tool", asked.ask.tool)
                put("call_id", asked.callId.value)
                inputs[asked.callId]?.let { put("input", it.input) }
            }
        }
    }

    fun json(): JsonObject = buildJsonObject {
        put("type", "result")
        put("subtype", subtype)
        put("is_error", !completed)
        // success 臂才带 result（CC :218「success（唯一带 result 字段）」）
        if (completed) put("result", resultText ?: "")
        put("session_id", session)
        put("num_turns", numTurns)
        put("duration_ms", durationMs)
        put("usage", usageJson(turnUsage))
        val charges = events.mapNotNull { it.event as? dev.min.agent.spi.ModelCharged }
        if (charges.isNotEmpty() && charges.all { it.currency == "USD" && it.amount != null }) {
            put("total_cost_usd", charges.sumOf { it.amount!! })
        }
        structured?.let { put("structured_output", it.value) }
        if (!completed) put("errors", JsonArray(errors.map(::JsonPrimitive)))
        if (denials.isNotEmpty()) put("permission_denials", JsonArray(denials))
    }
}

private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
    // 键名照 CC（04 :103）：cache 写入叫 cache_creation_input_tokens、读取叫 cache_read_input_tokens
    put("input_tokens", usage.inputTokens)
    put("output_tokens", usage.outputTokens)
    put("cache_creation_input_tokens", usage.cacheWriteTokens)
    put("cache_read_input_tokens", usage.cacheReadTokens)
}
