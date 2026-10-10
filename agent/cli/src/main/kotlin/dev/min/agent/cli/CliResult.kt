package dev.min.agent.cli

import dev.min.agent.llm.ChargedCost
import dev.min.agent.llm.CostTotals
import dev.min.agent.llm.totalCost
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LIMIT_CAUSE_MAX_BUDGET_USD
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
internal data class CliResult(
    val session: String,
    val events: List<EventRecord>,
    /**
     * 本次运行**整棵树**的花费总账（子 agent、hook agent、团队成员的计费点都在，从运行账本折出——
     * resume 进来的旧花费不属于这次运行）。null = 折不出树级的（单测合成事件），退回只折根会话事件
     */
    val treeCost: CostTotals? = null,
    /** 这次运行设了 /goal 却没达成就结束了（[goalUnmetReason]）：不算成功，说明进 errors */
    val goalUnmet: String? = null,
) {
    private val ends = events.mapNotNull { it.event as? TurnEnded }
    val completed: Boolean get() = goalUnmet == null && ends.isNotEmpty() && ends.all { it.outcome == TurnOutcome.Completed }

    /** CC 对 result.subtype 的叫法：success / error_max_turns（--max-turns 到限）/ error_during_execution /
     *  error_max_structured_output_retries（--json-schema 重试用尽，内核用 Failed.cause 区分出来）/
     *  error_max_budget_usd（--max-budget-usd 到限，内核用 TurnEnded.limitCause 区分出来） */
    val subtype: String get() = when {
        completed -> "success"
        ends.any { (it.outcome as? TurnOutcome.Failed)?.cause == FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES } ->
            "error_max_structured_output_retries"
        ends.any { (it.outcome as? TurnOutcome.Interrupted)?.by == InterruptedBy.Limit && it.limitCause == LIMIT_CAUSE_MAX_BUDGET_USD } ->
            "error_max_budget_usd"
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
        ?: events.mapNotNull { it.event as? dev.min.agent.spi.WorkflowRunCompleted }.lastOrNull { it.status == "completed" }?.result?.toString()

    /** 校验通过的结构化输出（--json-schema）：只看本次命令之后的事件；失败 / 没开时没有这个字段 */
    private val structured = events.mapNotNull { it.event as? StructuredOutputDelivered }.lastOrNull()

    /**
     * 本次运行的花费：优先树级总账（[treeCost]，含子会话的计费点）；没有时退回根会话事件。
     * 计费点本身就是 model_charged——主请求、压缩、回顾、审查都在里面，首个请求之前的压缩也不会丢
     */
    val cost: CostTotals = treeCost ?: totalCost(
        events.mapNotNull { r ->
            (r.event as? dev.min.agent.spi.ModelCharged)?.let { ChargedCost(it.model, it.usage, it.amount, it.currency) }
        },
    )

    /** 失败时的说明列表（CC 的 error 臂带 errors:string[]）；到限与被中断也给一句人能读的原因 */
    val errors: List<String> = ends.mapNotNull { end ->
        when (val outcome = end.outcome) {
            TurnOutcome.Completed -> null
            is TurnOutcome.Failed -> outcome.message
            is TurnOutcome.Interrupted -> when (outcome.by) {
                InterruptedBy.Limit -> if (end.limitCause == LIMIT_CAUSE_MAX_BUDGET_USD) "到达 --max-budget-usd 的花费上限" else "到达 --max-turns 的回合上限"
                InterruptedBy.Hook -> "被 hook 停止"
                InterruptedBy.User -> "被用户中断"
                InterruptedBy.ProcessDeath -> "进程中断"
                InterruptedBy.Budget -> "到达定时任务的花费上限"
            }
        }
    } + listOfNotNull(goalUnmet)

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
        // 花费：全部 USD 计价才有 CC 的 total_cost_usd；跨币种（回退到 CNY 计价模型）时换 Min 自己的
        // total_cost 按币种分列——两种都给会让人以为换过算好了，只给真量得出的那个
        if (cost.byCurrency.isNotEmpty()) {
            when {
                cost.allUsd -> put("total_cost_usd", round6(cost.usd))
                else -> put("total_cost", JsonObject(cost.byCurrency.mapValues { JsonPrimitive(round6(it.value)) }))
            }
        }
        structured?.let { put("structured_output", it.value) }
        if (!completed) put("errors", JsonArray(errors.map(::JsonPrimitive)))
        if (denials.isNotEmpty()) put("permission_denials", JsonArray(denials))
    }

    /** 花费数值的位数：截到 1e-6，去掉浮点尾噪（CC 给到 6 位左右） */
    private fun round6(value: Double): Double = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
}

private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
    // 键名照 CC（04 :103）：cache 写入叫 cache_creation_input_tokens、读取叫 cache_read_input_tokens
    put("input_tokens", usage.inputTokens)
    put("output_tokens", usage.outputTokens)
    put("cache_creation_input_tokens", usage.cacheWriteTokens)
    put("cache_read_input_tokens", usage.cacheReadTokens)
}
