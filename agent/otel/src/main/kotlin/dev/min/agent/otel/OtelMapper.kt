package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.protocol.PermissionMode
import kotlinx.serialization.json.JsonObject

/**
 * 会话日志 → 遥测条目。按 seq 顺序喂入记录，逐条产出（一个折叠：状态只来自日志本身，重放结果一致）。
 *
 * 为什么是折叠而不是逐条独立：有些指标要成对才有意义（请求开始到结算算耗时、审批到工具结果算 tool_result 的来源），
 * 这些配对键都在日志里（requestId、callId、approvalId），不另记账。
 */
class OtelMapper(private val options: OtelOptions) {
    /** 最近一次模型请求的模型：错误与回复归属用，比 pending 活得久 */
    private var lastModel: ModelRef? = null
    /** 发出去、还没结算（ModelCharged）或失败的那一次请求，用来算耗时 */
    private var pending: PendingRequest? = null
    /** 当前用户输入的 id，CC 的 prompt.id：挂在它之后的所有日志上 */
    private var promptId: String? = null
    private var mode: PermissionMode? = null

    private val planned = HashMap<CallId, PlannedTool>()
    private val effectAt = HashMap<CallId, Long>()
    private val approvalCall = HashMap<ApprovalId, CallId>()
    private val approvalSource = HashMap<CallId, String>()
    private val rejected = HashSet<CallId>()

    private class PendingRequest(val requestId: RequestId, val model: ModelRef, val atMs: Long)
    private class PlannedTool(val name: String, val input: JsonObject, val atMs: Long)

    fun feed(record: EventRecord): List<OtelItem> {
        val at = record.atMs
        return when (val e = record.event) {
            is SessionCreated -> {
                mode = e.mode
                lastModel = e.model
                listOf(metric(OtelNames.SESSION_COUNT, "", 1.0, mapOf("start_type" to text("fresh")), at))
            }
            is InputReceived -> if (e.origin is InputOrigin.User) {
                val body = e.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                promptId = e.clientMsgId.value
                listOf(log(record, OtelNames.USER_PROMPT, mapOf(
                    "prompt_length" to OtelValue.Whole(body.length.toLong()),
                    "prompt" to text(content(body, options.logUserPrompts)),
                )))
            } else emptyList()
            is TurnStarted -> listOf(log(record, OtelNames.MIN_TURN_STARTED, mapOf(
                "trigger" to text(e.trigger.name.lowercase()),
            )))
            is TurnEnded -> listOf(log(record, OtelNames.MIN_TURN_ENDED, turnEndedAttrs(e)))
            is ConversationEnded -> listOf(log(record, OtelNames.MIN_CONVERSATION_ENDED, emptyMap()))
            is ModelRequestStarted -> {
                lastModel = e.model
                pending = PendingRequest(e.requestId, e.model, at)
                emptyList()
            }
            is ModelCharged -> modelCharged(record, e)
            is ModelRequestFailed -> {
                val p = pending?.takeIf { it.requestId == e.requestId }
                pending = null
                val model = p?.model ?: lastModel
                listOf(log(record, OtelNames.API_ERROR, buildMap {
                    model?.let { put("model", text(it.modelId)) }
                    put("error", text(e.message))
                    e.httpStatus?.let { put("status_code", OtelValue.Whole(it.toLong())) }
                    put("attempt", OtelValue.Whole(e.attempt.toLong()))
                    put("request_id", text(e.requestId.value))
                    put("min.failure_kind", text(e.kind.name))
                    p?.let { put("duration_ms", OtelValue.Real((at - it.atMs).toDouble())) }
                }))
            }
            is AssistantMessageCompleted -> {
                val body = e.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                listOf(log(record, OtelNames.ASSISTANT_RESPONSE, buildMap {
                    put("response_length", OtelValue.Whole(body.length.toLong()))
                    put("response", text(content(body, options.logAssistantResponses)))
                    lastModel?.let { put("model", text(it.modelId)) }
                    put("message.uuid", text(e.messageId.value))
                }))
            }
            is ToolPlanned -> {
                planned[e.callId] = PlannedTool(e.tool, e.input, at)
                emptyList()
            }
            is ApprovalRequested -> {
                approvalCall[e.approvalId] = e.callId
                emptyList()
            }
            is ApprovalResolved -> approvalResolved(record, e)
            is ToolEffectPending -> {
                effectAt[e.callId] = at
                emptyList()
            }
            is ToolOutcome -> toolOutcome(record, e)
            is SettingsChanged -> {
                val next = e.mode ?: return emptyList()
                val prev = mode
                mode = next
                listOf(log(record, OtelNames.PERMISSION_MODE_CHANGED, buildMap {
                    prev?.let { put("from_mode", text(it.wire())) }
                    put("to_mode", text(next.wire()))
                }))
            }
            // 其余事件（流式片段、子 agent、压缩……）本期不对标，见 OTEL_NAME_TABLE 之外的部分
            else -> emptyList()
        }
    }

    private fun modelCharged(record: EventRecord, e: ModelCharged): List<OtelItem> {
        val at = record.atMs
        val p = pending
        pending = null
        val usd = e.currency == "USD" && e.amount != null
        val out = ArrayList<OtelItem>()
        val modelAttr = mapOf("model" to text(e.model.modelId))
        if (usd) out += metric(OtelNames.COST_USAGE, "USD", e.amount!!, modelAttr, at)
        val u: Usage = e.usage
        for ((type, n) in listOf(
            "input" to u.inputTokens,
            "output" to u.outputTokens,
            "cacheRead" to u.cacheReadTokens,
            "cacheCreation" to u.cacheWriteTokens,
        )) {
            out += metric(OtelNames.TOKEN_USAGE, "tokens", n.toDouble(), modelAttr + ("type" to text(type)), at)
        }
        out += log(record, OtelNames.API_REQUEST, buildMap {
            put("model", text(e.model.modelId))
            put("min.provider", text(e.model.providerId))
            if (usd) put("cost_usd", OtelValue.Real(e.amount!!))
            // 没有价格时不写花费（未知不是 0）；非 USD 的币种单列，不当 USD 记
            if (e.amount != null && e.currency != null && e.currency != "USD") put("min.cost_currency", text(e.currency!!))
            p?.let { put("duration_ms", OtelValue.Real((at - it.atMs).toDouble())) }
            put("input_tokens", OtelValue.Whole(u.inputTokens))
            put("output_tokens", OtelValue.Whole(u.outputTokens))
            put("cache_read_tokens", OtelValue.Whole(u.cacheReadTokens))
            put("cache_creation_tokens", OtelValue.Whole(u.cacheWriteTokens))
            p?.let { put("request_id", text(it.requestId.value)) }
        })
        return out
    }

    private fun approvalResolved(record: EventRecord, e: ApprovalResolved): List<OtelItem> {
        val call = approvalCall.remove(e.approvalId) ?: return emptyList()
        val tool = planned[call]?.name ?: return emptyList()
        val accepted = e.choice !is ApprovalChoice.Deny
        val source = sourceOf(e.decidedBy, e.choice)
        approvalSource[call] = source
        if (!accepted) rejected += call
        val decision = if (accepted) "accept" else "reject"
        val out = ArrayList<OtelItem>()
        out += log(record, OtelNames.TOOL_DECISION, buildMap {
            put("tool_name", text(tool))
            put("tool_use_id", text(call.value))
            put("decision", text(decision))
            put("source", text(source))
            put("tool_source", text(toolSource(tool)))
            if (options.logToolDetails) put("tool_parameters", text(planned[call]!!.input.toString()))
        })
        if (tool in CODE_EDIT_TOOLS) {
            out += metric(OtelNames.CODE_EDIT_DECISION, "", 1.0, mapOf(
                "tool_name" to text(tool),
                "decision" to text(decision),
                "source" to text(source),
            ), record.atMs)
        }
        return out
    }

    private fun toolOutcome(record: EventRecord, e: ToolOutcome): List<OtelItem> {
        val call = e.callId
        val p = planned.remove(call)
        val started = effectAt.remove(call) ?: p?.atMs ?: record.atMs
        val source = approvalSource.remove(call) ?: "config"
        val wasRejected = rejected.remove(call)
        val resultText = e.result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        val out = ArrayList<OtelItem>()
        // 被拒绝的调用没有执行，CC 只为真执行的工具记 tool_result
        if (!wasRejected && p != null) {
            out += log(record, OtelNames.TOOL_RESULT, buildMap {
                put("tool_name", text(p.name))
                put("tool_use_id", text(call.value))
                put("success", OtelValue.Flag(!e.result.isError))
                put("duration_ms", OtelValue.Real((record.atMs - started).toDouble()))
                put("decision_type", text("accept"))
                put("decision_source", text(source))
                put("tool_input_size_bytes", OtelValue.Whole(p.input.toString().toByteArray().size.toLong()))
                put("tool_result_size_bytes", OtelValue.Whole(resultText.toByteArray().size.toLong()))
                if (options.logToolDetails) {
                    put("tool_parameters", text(p.input.toString()))
                    if (e.result.isError) put("error", text(content(resultText, true)))
                }
            })
        }
        for (edit in e.result.fileEdits) out += lineMetrics(edit, record.atMs)
        return out
    }

    private fun lineMetrics(edit: FileEdit, at: Long): List<OtelItem> {
        val diff = edit.diff ?: return emptyList()
        var added = 0L
        var removed = 0L
        for (line in diff.lineSequence()) {
            // hunk 正文里 +++ / --- 不会出现；防御性跳过文件头，免得多算
            if (line.startsWith("+") && !line.startsWith("+++")) added++
            else if (line.startsWith("-") && !line.startsWith("---")) removed++
        }
        return listOf(
            metric(OtelNames.LINES_OF_CODE, "", added.toDouble(), mapOf("type" to text("added")), at),
            metric(OtelNames.LINES_OF_CODE, "", removed.toDouble(), mapOf("type" to text("removed")), at),
        )
    }

    private fun turnEndedAttrs(e: TurnEnded): Map<String, OtelValue> = buildMap {
        put("outcome", text(
            when (e.outcome) {
                is TurnOutcome.Completed -> "completed"
                is TurnOutcome.Failed -> "failed"
                is TurnOutcome.Interrupted -> "interrupted"
            },
        ))
        when (val o = e.outcome) {
            is TurnOutcome.Failed -> {
                put("error", text(content(o.message, options.logToolDetails)))
                o.kind?.let { put("min.failure_kind", text(it.name)) }
            }
            is TurnOutcome.Interrupted -> put("interrupted_by", text(o.by.name.lowercase()))
            else -> Unit
        }
        put("duration_ms", OtelValue.Real(e.durationMs.toDouble()))
        put("input_tokens", OtelValue.Whole(e.usage.inputTokens))
        put("output_tokens", OtelValue.Whole(e.usage.outputTokens))
    }

    private fun sourceOf(by: DecidedBy, choice: ApprovalChoice): String = when (by) {
        is DecidedBy.Rule -> "config"
        is DecidedBy.Hook -> "hook"
        is DecidedBy.Mode -> "min_mode"
        is DecidedBy.Classifier -> "min_classifier"
        // 无人应答按拒绝处理：不是用户的动作，单列，免得被算成 user_abort
        is DecidedBy.Timeout -> "min_timeout"
        is DecidedBy.User -> when (choice) {
            is ApprovalChoice.Once -> "user_temporary"
            is ApprovalChoice.Always -> "user_permanent"
            is ApprovalChoice.Deny -> "user_reject"
        }
    }

    // ---- 属性与条目的小工具 ----

    private fun text(value: String): OtelValue = OtelValue.Text(value)

    /** 内容类属性：不许记就写占位，记就按上限截断（截断标记计入上限，满足 OTLP 属性长度约定） */
    private fun content(value: String, allowed: Boolean): String {
        if (!allowed) return REDACTED
        val max = options.contentMaxLength
        if (value.length <= max) return value
        return value.take((max - TRUNCATED.length).coerceAtLeast(0)) + TRUNCATED
    }

    private fun metric(name: String, unit: String, value: Double, attrs: Map<String, OtelValue>, at: Long): OtelMetric =
        OtelMetric(name, unit, value, withSession(attrs), at)

    private fun log(record: EventRecord, name: String, attrs: Map<String, OtelValue>): OtelLog {
        val all = LinkedHashMap<String, OtelValue>()
        all["session.id"] = text(options.sessionId)
        all["event.sequence"] = OtelValue.Whole(record.seq)
        promptId?.let { all["prompt.id"] = text(it) }
        all.putAll(attrs)
        return OtelLog(name, all, record.atMs)
    }

    private fun withSession(attrs: Map<String, OtelValue>): Map<String, OtelValue> {
        if (!options.includeSessionId) return attrs
        return LinkedHashMap<String, OtelValue>().apply {
            put("session.id", text(options.sessionId))
            putAll(attrs)
        }
    }

    private companion object {
        const val REDACTED = "<REDACTED>"
        const val TRUNCATED = "...[truncated]"
        val CODE_EDIT_TOOLS = setOf("Edit", "Write", "NotebookEdit")

        /** CC 的 mode 取值是驼峰小写开头（default / acceptEdits / bypassPermissions…），这里照这个写 */
        fun PermissionMode.wire(): String = name.replaceFirstChar { it.lowercase() }

        /** 工具名：MCP 工具是 mcp__server__tool，其余是内置 */
        fun toolSource(name: String): String = if (name.startsWith("mcp__")) "mcp" else "builtin"
    }
}
