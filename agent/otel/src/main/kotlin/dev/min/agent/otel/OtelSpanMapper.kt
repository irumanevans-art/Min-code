package dev.min.agent.otel

import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/*
 * span 名字照 CC monitoring-usage 的 Traces（beta）一节，原样沿用 claude_code.* 前缀：
 *   claude_code.interaction            一个用户提示词（Min：一个回合）一条 trace 的根
 *   ├─ claude_code.llm_request         一次模型请求（重试是新的一次）
 *   └─ claude_code.tool                一次工具调用
 *      ├─ claude_code.tool.blocked_on_user   等审批的那段
 *      └─ claude_code.tool.execution         真正执行的那段
 * 没有 claude_code.hook（原文要求「详细 beta 追踪 + 组织白名单」，Min 没有对应物）。
 * 会话本身没有 span：CC 也没有（每个提示词一条 trace），会话信息以 session.id 属性挂在每个 span 上。
 */
object SpanNames {
    const val INTERACTION = "claude_code.interaction"
    const val LLM_REQUEST = "claude_code.llm_request"
    const val TOOL = "claude_code.tool"
    const val BLOCKED_ON_USER = "claude_code.tool.blocked_on_user"
    const val EXECUTION = "claude_code.tool.execution"
}

/** 本期会产出的 span 名字（数据即规格：测试核对「产出的名字都登记过」，文档据此对照） */
val OTEL_SPAN_NAMES: Set<String> = setOf(
    SpanNames.INTERACTION, SpanNames.LLM_REQUEST, SpanNames.TOOL, SpanNames.BLOCKED_ON_USER, SpanNames.EXECUTION,
)

/**
 * trace / span 的 id：由会话 id 与日志里的 turnId / callId / requestId 哈希得来，不随机。
 *
 * 为什么确定：（1）子会话要把自己的 span 挂到父会话的 Agent 工具 span 下，而父子会话的映射器互不相识、
 * 也可能在不同进程里导出，只有「两边各自算出同一个 id」才对得上；（2）重放同一段日志得到同样的 id，
 * 跨进程续导时重复发送的 span 对后端是同一个 span，不会变成两份。
 */
object SpanIds {
    fun trace(session: String, turn: String): String = hex(32, "trace", session, turn)
    fun interaction(session: String, turn: String): String = hex(16, "interaction", session, turn)
    fun llm(session: String, requestId: String, attempt: Int): String = hex(16, "llm", session, requestId, attempt.toString())
    fun tool(session: String, call: String): String = hex(16, "tool", session, call)
    fun blocked(session: String, call: String): String = hex(16, "blocked", session, call)
    fun execution(session: String, call: String): String = hex(16, "execution", session, call)

    private fun hex(length: Int, vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(length)
    }
}

/** 子会话的 span 挂到哪：父会话那次工具调用所在的 trace 与它的 tool span */
data class SpanLink(val traceId: String, val parentSpanId: String)

/**
 * 会话日志 → span。和 [OtelMapper] 一样是顺序折叠：状态只来自日志，没有另记一份账。
 * 只在 span 结束时产出（开着的 span 留在状态里，等到结束事件或回合结束）。
 *
 * 根会话：每个回合一个 interaction（一条新 trace）。子会话（[link] 非空）没有自己的 interaction：
 * 与 CC 一致，子 agent 的 llm_request / tool 直接挂在父会话那次 Agent 工具的 span 下，同一条 trace。
 */
class OtelSpanMapper(private val options: OtelOptions, private val link: SpanLink?) {
    private val sid = options.sessionId
    private val isChild = link != null
    private val common: Map<String, OtelValue> = buildMap {
        put("session.id", OtelValue.Text(sid))
        options.lineage?.let { putAll(it.attributes(sid)) }
    }

    private class Turn(
        val id: String,
        val traceId: String,
        /** llm_request 与 tool 的父：根会话是 interaction，子会话是父会话的 tool span */
        val childParent: String,
        /** 看到了 TurnStarted 才能产出 interaction（中途接上的回合不知道起点） */
        val interaction: Interaction?,
    )

    private class Interaction(val startMs: Long, val startSeq: Long, val sequence: Long, val prompt: String?)

    private class OpenLlm(
        val turn: String, val requestId: String, val attempt: Int, val model: dev.min.agent.protocol.ModelRef,
        val startMs: Long, val startSeq: Long,
    ) {
        var usage: dev.min.agent.protocol.Usage? = null
    }

    private class OpenTool(
        val turn: String, val callId: String, val name: String, val input: JsonObject, val startMs: Long, val startSeq: Long,
    ) {
        var approvalId: String? = null
        var blockedStartMs: Long = 0
        var blockedSeq: Long = 0
        var blockedOpen = false
        var resolvedAtMs: Long? = null
        var rejected = false
        var execStartMs: Long? = null
        var execSeq: Long = 0
    }

    private val turns = HashMap<String, Turn>()
    private val llms = HashMap<String, OpenLlm>()
    private val tools = HashMap<String, OpenTool>()
    private val approvalCall = HashMap<String, String>()
    private var currentLlm: OpenLlm? = null
    private var pendingPrompt: Pair<String, Long>? = null
    private var interactionCount = 0L

    /**
     * 最早一个还开着的 span 的起点 seq；没有开着的为 null。宿主据此决定「下次从哪条记录续映射 span」：
     * 跨进程时从这里重放，才能看到那个 span 的起点。
     */
    val openSinceSeq: Long?
        get() {
            val seqs = ArrayList<Long>()
            pendingPrompt?.let { seqs += it.second }
            for (t in turns.values) t.interaction?.let { seqs += it.startSeq }
            for (l in llms.values) seqs += l.startSeq
            for (t in tools.values) {
                seqs += t.startSeq
                if (t.blockedOpen) seqs += t.blockedSeq
                if (t.execStartMs != null) seqs += t.execSeq
            }
            return seqs.minOrNull()
        }

    fun feed(record: EventRecord): List<OtelSpan> {
        val at = record.atMs
        return when (val e = record.event) {
            is InputReceived -> {
                if (!isChild && e.origin is InputOrigin.User) {
                    val body = e.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                    pendingPrompt = body to record.seq
                }
                emptyList()
            }
            is TurnStarted -> {
                val prompt = pendingPrompt?.takeIf { e.trigger == TurnTrigger.User }
                pendingPrompt = null
                val interaction = if (isChild) null else Interaction(
                    startMs = at,
                    startSeq = minOf(record.seq, prompt?.second ?: record.seq),
                    sequence = ++interactionCount,
                    prompt = prompt?.first,
                )
                turns[e.turnId.value] = newTurn(e.turnId.value, interaction)
                emptyList()
            }
            is ModelRequestStarted -> {
                val open = OpenLlm(e.turnId.value, e.requestId.value, e.attempt, e.model, at, record.seq)
                llms[e.requestId.value] = open
                currentLlm = open
                emptyList()
            }
            is ModelCharged -> {
                // 辅助请求（分类器、复盘……）的结算没有对应的 llm_request：只认最近一次还没结算的
                val open = currentLlm
                if (open != null && open.usage == null && llms[open.requestId] === open) open.usage = e.usage
                emptyList()
            }
            is AssistantMessageCompleted -> {
                val open = llms.remove(e.requestId.value) ?: return emptyList()
                if (currentLlm === open) currentLlm = null
                listOf(closeLlm(open, at, e))
            }
            is ModelRequestFailed -> {
                val open = llms.remove(e.requestId.value) ?: return emptyList()
                if (currentLlm === open) currentLlm = null
                listOf(failLlm(open, at, e))
            }
            is ToolPlanned -> {
                tools[e.callId.value] = OpenTool(e.turnId.value, e.callId.value, e.tool, e.input, at, record.seq)
                emptyList()
            }
            is ApprovalRequested -> {
                val tool = tools[e.callId.value] ?: return emptyList()
                tool.approvalId = e.approvalId.value
                tool.blockedStartMs = at
                tool.blockedSeq = record.seq
                tool.blockedOpen = true
                approvalCall[e.approvalId.value] = e.callId.value
                emptyList()
            }
            is ApprovalResolved -> {
                val call = approvalCall.remove(e.approvalId.value) ?: return emptyList()
                val tool = tools[call] ?: return emptyList()
                if (!tool.blockedOpen) return emptyList()
                tool.blockedOpen = false
                tool.resolvedAtMs = at
                val accepted = e.choice !is ApprovalChoice.Deny
                tool.rejected = !accepted
                listOf(blockedSpan(tool, at, accepted, approvalSource(e.decidedBy, e.choice)))
            }
            is ToolEffectPending -> {
                val tool = tools[e.callId.value] ?: return emptyList()
                tool.execStartMs = at
                tool.execSeq = record.seq
                emptyList()
            }
            is ToolOutcome -> {
                val tool = tools.remove(e.callId.value) ?: return emptyList()
                val out = ArrayList<OtelSpan>()
                if (!tool.rejected) out += executionSpan(tool, at, e)
                out += toolSpan(tool, at, error = null)
                out
            }
            is TurnEnded -> endTurn(e, at)
            else -> emptyList()
        }
    }

    // ---- 回合 ----

    private fun newTurn(id: String, interaction: Interaction?): Turn {
        val trace = link?.traceId ?: SpanIds.trace(sid, id)
        val parent = link?.parentSpanId ?: SpanIds.interaction(sid, id)
        return Turn(id, trace, parent, interaction)
    }

    /** 事件指向的回合：中途接上时没见过 TurnStarted，按确定的 id 补一个（没有 interaction，只为让子 span 挂对地方） */
    private fun turnOf(id: String): Turn = turns.getOrPut(id) { newTurn(id, null) }

    private fun endTurn(e: TurnEnded, at: Long): List<OtelSpan> {
        val id = e.turnId.value
        val out = ArrayList<OtelSpan>()
        // 回合收尾时还开着的 span：被中断或崩溃留下的。收口在回合结束处，标为错误
        for (open in llms.values.filter { it.turn == id }) {
            llms.remove(open.requestId)
            out += if (open.usage != null) closeLlm(open, at, null) else failLlm(open, at, null)
        }
        currentLlm = null
        for (tool in tools.values.filter { it.turn == id }) {
            tools.remove(tool.callId)
            if (tool.blockedOpen) {
                tool.blockedOpen = false
                out += blockedSpan(tool, at, accepted = false, source = "interrupted")
            }
            if (!tool.rejected && tool.execStartMs != null) out += executionSpan(tool, at, null)
            out += toolSpan(tool, at, error = "interrupted")
        }
        val turn = turns.remove(id)
        val interaction = turn?.interaction
        if (turn != null && interaction != null) out += interactionSpan(turn, interaction, e, at)
        return out
    }

    private fun interactionSpan(turn: Turn, i: Interaction, e: TurnEnded, at: Long): OtelSpan {
        val attrs = LinkedHashMap<String, OtelValue>(common)
        i.prompt?.let {
            attrs["user_prompt"] = OtelValue.Text(options.content(it, options.logUserPrompts))
            attrs["user_prompt_length"] = OtelValue.Whole(it.length.toLong())
        }
        attrs["interaction.sequence"] = OtelValue.Whole(i.sequence)
        attrs["parent.source"] = OtelValue.Text("none")
        attrs["interaction.duration_ms"] = OtelValue.Real(e.durationMs.toDouble())
        attrs["min.outcome"] = OtelValue.Text(when (e.outcome) {
            is TurnOutcome.Completed -> "completed"
            is TurnOutcome.Failed -> "failed"
            is TurnOutcome.Interrupted -> "interrupted"
        })
        return OtelSpan(
            SpanNames.INTERACTION, turn.traceId, SpanIds.interaction(sid, turn.id), null,
            startMs = i.startMs, endMs = at, attributes = attrs,
        )
    }

    // ---- 模型请求 ----

    private fun llmBase(open: OpenLlm, endMs: Long): LinkedHashMap<String, OtelValue> {
        val attrs = LinkedHashMap<String, OtelValue>(common)
        attrs["model"] = OtelValue.Text(open.model.modelId)
        attrs["gen_ai.system"] = OtelValue.Text(open.model.providerId)
        attrs["gen_ai.request.model"] = OtelValue.Text(open.model.modelId)
        attrs["llm_request.context"] = OtelValue.Text(if (isChild) "tool" else "interaction")
        attrs["duration_ms"] = OtelValue.Real((endMs - open.startMs).toDouble())
        attrs["request_id"] = OtelValue.Text(open.requestId)
        attrs["attempt"] = OtelValue.Whole(open.attempt.toLong())
        return attrs
    }

    private fun closeLlm(open: OpenLlm, at: Long, done: AssistantMessageCompleted?): OtelSpan {
        val attrs = llmBase(open, at)
        val u = open.usage ?: done?.usage
        if (u != null) {
            attrs["input_tokens"] = OtelValue.Whole(u.inputTokens)
            attrs["output_tokens"] = OtelValue.Whole(u.outputTokens)
            attrs["cache_read_tokens"] = OtelValue.Whole(u.cacheReadTokens)
            attrs["cache_creation_tokens"] = OtelValue.Whole(u.cacheWriteTokens)
        }
        attrs["success"] = OtelValue.Flag(done != null)
        if (done != null) {
            attrs["response.has_tool_call"] = OtelValue.Flag(done.parts.any { it is ToolCallPart })
            attrs["stop_reason"] = OtelValue.Text(stopReason(done.stop))
        }
        val turn = turnOf(open.turn)
        return OtelSpan(
            SpanNames.LLM_REQUEST, turn.traceId, SpanIds.llm(sid, open.requestId, open.attempt), turn.childParent,
            open.startMs, at, attrs, error = if (done == null) "interrupted" else null,
        )
    }

    private fun failLlm(open: OpenLlm, at: Long, failed: ModelRequestFailed?): OtelSpan {
        val attrs = llmBase(open, at)
        attrs["success"] = OtelValue.Flag(false)
        failed?.httpStatus?.let { attrs["status_code"] = OtelValue.Whole(it.toLong()) }
        failed?.let { attrs["min.failure_kind"] = OtelValue.Text(it.kind.name.lowercase()) }
        failed?.let { attrs["min.will_retry"] = OtelValue.Flag(it.willRetry) }
        val message = failed?.message ?: "interrupted"
        attrs["error"] = OtelValue.Text(message)
        val turn = turnOf(open.turn)
        return OtelSpan(
            SpanNames.LLM_REQUEST, turn.traceId, SpanIds.llm(sid, open.requestId, open.attempt), turn.childParent,
            open.startMs, at, attrs, error = message,
        )
    }

    private fun stopReason(s: StopReason): String = when (s) {
        StopReason.EndTurn -> "end_turn"
        StopReason.ToolUse -> "tool_use"
        StopReason.MaxTokens -> "max_tokens"
        StopReason.StopSequence -> "stop_sequence"
        StopReason.Refusal -> "refusal"
        is StopReason.Other -> s.raw
    }

    // ---- 工具 ----

    private fun toolSpan(tool: OpenTool, at: Long, error: String?): OtelSpan {
        val attrs = LinkedHashMap<String, OtelValue>(common)
        attrs["tool_name"] = OtelValue.Text(tool.name)
        attrs["duration_ms"] = OtelValue.Real((at - tool.startMs).toDouble())
        attrs["tool_use_id"] = OtelValue.Text(tool.callId)
        attrs["gen_ai.tool.call.id"] = OtelValue.Text(tool.callId)
        if (options.logToolDetails) {
            // 原文：这些属性受 OTEL_LOG_TOOL_DETAILS 门控（防 PII）
            val input = tool.input
            if (tool.name == "Bash") stringField(input, "command")?.let { attrs["full_command"] = OtelValue.Text(options.content(it, true)) }
            if (tool.name in FILE_TOOLS) (stringField(input, "file_path") ?: stringField(input, "notebook_path"))
                ?.let { attrs["file_path"] = OtelValue.Text(it) }
            if (tool.name == "Agent" || tool.name == "Task") stringField(input, "subagent_type")?.let { attrs["subagent_type"] = OtelValue.Text(it) }
        }
        val turn = turnOf(tool.turn)
        return OtelSpan(
            SpanNames.TOOL, turn.traceId, SpanIds.tool(sid, tool.callId), turn.childParent,
            tool.startMs, at, attrs, error = error,
        )
    }

    private fun blockedSpan(tool: OpenTool, at: Long, accepted: Boolean, source: String): OtelSpan {
        val attrs = LinkedHashMap<String, OtelValue>(common)
        attrs["duration_ms"] = OtelValue.Real((at - tool.blockedStartMs).toDouble())
        attrs["decision"] = OtelValue.Text(if (accepted) "accept" else "reject")
        attrs["source"] = OtelValue.Text(source)
        val turn = turnOf(tool.turn)
        return OtelSpan(
            SpanNames.BLOCKED_ON_USER, turn.traceId, SpanIds.blocked(sid, tool.callId), SpanIds.tool(sid, tool.callId),
            tool.blockedStartMs, at, attrs,
        )
    }

    private fun executionSpan(tool: OpenTool, at: Long, outcome: ToolOutcome?): OtelSpan {
        // 起点：副作用开始前那条记录；没有（免审批、无副作用的工具）就取审批放行的时刻，再没有取计划时刻
        val start = tool.execStartMs ?: tool.resolvedAtMs ?: tool.startMs
        val failed = outcome == null || outcome.result.isError
        val attrs = LinkedHashMap<String, OtelValue>(common)
        attrs["duration_ms"] = OtelValue.Real((at - start).toDouble())
        attrs["tool_use_id"] = OtelValue.Text(tool.callId)
        attrs["gen_ai.tool.call.id"] = OtelValue.Text(tool.callId)
        attrs["success"] = OtelValue.Flag(!failed)
        var error: String? = null
        if (failed) {
            error = if (outcome == null) "interrupted" else "tool_error"
            if (outcome != null && options.logToolDetails) {
                val body = outcome.result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                attrs["error"] = OtelValue.Text(options.content(body, true))
            }
        }
        val turn = turnOf(tool.turn)
        return OtelSpan(
            SpanNames.EXECUTION, turn.traceId, SpanIds.execution(sid, tool.callId), SpanIds.tool(sid, tool.callId),
            start, at, attrs, error = error,
        )
    }

    private fun stringField(input: JsonObject, key: String): String? = input[key]?.let {
        try {
            it.jsonPrimitive.contentOrNull
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val FILE_TOOLS = setOf("Read", "Edit", "Write", "NotebookEdit")
    }
}
