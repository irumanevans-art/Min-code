package dev.min.agent.otel

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ComponentHooksRegistered
import dev.min.agent.spi.ComponentHooksUpdated
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
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
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/*
 * span 名字照 CC monitoring-usage 的 Traces（beta）一节，原样沿用 claude_code.* 前缀：
 *   claude_code.interaction            一个用户提示词（Min：一个回合）一条 trace 的根
 *   ├─ claude_code.llm_request         一次模型请求（含它的全部重试；每次重试一个 gen_ai.request.attempt 事件）
 *   ├─ claude_code.hook                一批 hook（只在详细 beta 追踪下，见 OtelConfig.detailedTracing）
 *   └─ claude_code.tool                一次工具调用（可带 tool.output 事件，受 OTEL_LOG_TOOL_CONTENT 管）
 *      ├─ claude_code.tool.blocked_on_user   等审批的那段
 *      └─ claude_code.tool.execution         真正执行的那段
 * 会话本身没有 span：CC 也没有（每个提示词一条 trace），会话信息以 session.id 属性挂在每个 span 上。
 * 每个 span 都带 span.type（原文：与 span 名字对应）。
 */
object SpanNames {
    const val INTERACTION = "claude_code.interaction"
    const val LLM_REQUEST = "claude_code.llm_request"
    const val TOOL = "claude_code.tool"
    const val BLOCKED_ON_USER = "claude_code.tool.blocked_on_user"
    const val EXECUTION = "claude_code.tool.execution"
    const val HOOK = "claude_code.hook"
}

/** span 上的事件名（原文） */
object SpanEventNames {
    const val REQUEST_ATTEMPT = "gen_ai.request.attempt"
    const val TOOL_OUTPUT = "tool.output"
}

/** 本期会产出的 span 名字（数据即规格：测试核对「产出的名字都登记过」，文档据此对照） */
val OTEL_SPAN_NAMES: Set<String> = setOf(
    SpanNames.INTERACTION, SpanNames.LLM_REQUEST, SpanNames.TOOL, SpanNames.BLOCKED_ON_USER, SpanNames.EXECUTION, SpanNames.HOOK,
)

/**
 * trace / span 的 id：由会话 id 与日志里的 turnId / callId / requestId 哈希得来，不随机。
 *
 * 为什么确定：（1）子会话要把自己的 span 挂到父会话的 Agent 工具 span 下，而父子会话的映射器互不相识、
 * 也可能在不同进程里导出，只有「两边各自算出同一个 id」才对得上；（2）重放同一段日志得到同样的 id，
 * 跨进程续导时重复发送的 span 对后端是同一个 span，不会变成两份；（3）Bash 子进程的 TRACEPARENT 要在执行前就给出
 * 那次执行 span 的 id——span 是事后从日志折出来的，只有 id 能事先算出来才对得上。
 */
object SpanIds {
    fun trace(session: String, turn: String): String = hex(32, "trace", session, turn)
    fun interaction(session: String, turn: String): String = hex(16, "interaction", session, turn)

    /** [startSeq]：这个 llm_request 第一次尝试的 ModelRequestStarted 的 seq。同一个请求 id 在回退模型后会重新开始，所以不能只用 requestId */
    fun llm(session: String, requestId: String, startSeq: Long): String = hex(16, "llm", session, requestId, startSeq.toString())
    fun tool(session: String, call: String): String = hex(16, "tool", session, call)
    fun blocked(session: String, call: String): String = hex(16, "blocked", session, call)
    fun execution(session: String, call: String): String = hex(16, "execution", session, call)

    /** hook span：一条 hooks_ran 记录一个。记录带 runId（宿主给了追踪上下文时，hook 子进程的 TRACEPARENT 已经指向它）就用它，否则取记录的 seq */
    fun hook(session: String, seq: Long): String = hex(16, "hook", session, seq.toString())
    fun hookRun(session: String, runId: String): String = hex(16, "hookrun", session, runId)

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
 * 根会话：每个回合一个 interaction（一条新 trace；有入站 TRACEPARENT 时挂在它下面）。
 * 子会话（[link] 非空）没有自己的 interaction：与 CC 一致，子 agent 的 llm_request / tool 直接挂在父会话那次 Agent 工具的 span 下，同一条 trace。
 */
class OtelSpanMapper(
    private val options: OtelOptions,
    private val link: SpanLink?,
    /** 中途接上（跨进程续导）时，这个会话当时的 effort；从头读日志时由 SessionCreated / SettingsChanged 折出来 */
    initialEffort: Effort? = null,
) {
    private val sid = options.sessionId
    private val isChild = link != null
    private val common: Map<String, OtelValue> = buildMap {
        put("session.id", OtelValue.Text(sid))
        options.lineage?.let { putAll(it.attributes(sid)) }
    }

    /** 当前生效的 effort：只有 SessionCreated 与 SettingsChanged 会改它 */
    private var effort: Effort? = initialEffort

    private class Turn(
        val id: String,
        val traceId: String,
        /** llm_request 与 tool 的父：根会话是 interaction，子会话是父会话的 tool span */
        val childParent: String,
        /** 看到了 TurnStarted 才能产出 interaction（中途接上的回合不知道起点） */
        val interaction: Interaction?,
    )

    private class Interaction(val startMs: Long, val startSeq: Long, val sequence: Long, val prompt: String?)

    /** 一次模型请求（含它的全部重试）。span 在最终成功、最终失败或回合收口时才产出 */
    private class OpenLlm(
        val turn: String, val requestId: String, var attempt: Int, var model: dev.min.agent.protocol.ModelRef,
        val startMs: Long, val startSeq: Long, val effort: Effort?,
    ) {
        var usage: dev.min.agent.protocol.Usage? = null

        /** 上一次尝试失败、正在等重试（willRetry）；下一条 ModelRequestStarted 把它变成一个重试事件 */
        var pendingFailure: ModelRequestFailed? = null
        val events = ArrayList<OtelSpanEvent>()

        /** 最后一次尝试的 x-client-request-id（原文：final attempt 的）；没带（第三方端点）为 null */
        var clientRequestId: String? = null

        /** 详细追踪下 `new_context` 的内容：上一次请求之后新增的用户输入与工具结果（只在详细追踪与 OTEL_LOG_USER_PROMPTS 都开着时才攒） */
        var newContext: String? = null
        var newContextChars: Long = 0
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

    /** call id → (回合 id, 工具名)：hook span 的 hook_name 要带工具名，而 PostToolUse 的记录到时工具已经收尾 */
    private val toolNames = HashMap<String, Pair<String, String>>()
    private var currentLlm: OpenLlm? = null

    /**
     * 详细追踪的 `new_context`（llm_request）：自上一次请求之后新进来的用户输入与工具结果。只在详细追踪且 OTEL_LOG_USER_PROMPTS 开着时才攒——
     * 其余情形不留任何内容（也不占内存）。缓冲封顶在内容上限，真实长度另记，截断时带 `_original_length`
     */
    private val newContext = StringBuilder()
    private var newContextChars = 0L
    private val collectNewContext: Boolean get() = options.detailedTracing && options.logUserPrompts

    private fun noteNewContext(text: String) {
        if (!collectNewContext || text.isEmpty()) return
        newContextChars += text.length
        // 多留一个字符：长度超过上限，content() 才会按口径截断并加标记
        val room = options.contentMaxLength + 1 - newContext.length
        if (room > 0) newContext.append(text, 0, minOf(room, text.length))
    }
    private var pendingPrompt: Pair<String, Long>? = null
    private var interactionCount = 0L

    /** hooks 的原文快照（hook_definitions 用）：settings 各层 + skill / command / agent 登记的组件 hooks */
    private var hookSources: List<Pair<String, JsonObject>> = emptyList()
    private val componentHooks = LinkedHashMap<String, JsonObject>()

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
            is SessionCreated -> {
                effort = e.effort
                emptyList()
            }
            is SettingsChanged -> {
                if (e.clearEffort) effort = null
                e.effort?.let { effort = it }
                emptyList()
            }
            is HooksLoaded -> {
                hookSources = e.sources.map { it.source to it.hooks }
                emptyList()
            }
            is ComponentHooksRegistered -> {
                componentHooks["${e.kind}:${e.name}"] = e.hooks
                emptyList()
            }
            is ComponentHooksUpdated -> {
                if (e.hooks.isEmpty()) componentHooks.remove("${e.kind}:${e.name}") else componentHooks["${e.kind}:${e.name}"] = e.hooks
                emptyList()
            }
            is InputReceived -> {
                noteNewContext(e.parts.filterIsInstance<TextPart>().joinToString("") { it.text })
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
                startAttempt(e, at, record.seq)
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
                val open = llms[e.requestId.value] ?: return emptyList()
                if (e.willRetry) {
                    // 还会重试：这一次的失败只是过程，span 等到最终结果（成功或用尽）再产出
                    open.pendingFailure = e
                    emptyList()
                } else {
                    llms.remove(e.requestId.value)
                    if (currentLlm === open) currentLlm = null
                    listOf(failLlm(open, at, e))
                }
            }
            is ToolPlanned -> {
                tools[e.callId.value] = OpenTool(e.turnId.value, e.callId.value, e.tool, e.input, at, record.seq)
                toolNames[e.callId.value] = e.turnId.value to e.tool
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
                noteNewContext(e.result.content.filterIsInstance<TextPart>().joinToString("") { it.text })
                val tool = tools.remove(e.callId.value) ?: return emptyList()
                val out = ArrayList<OtelSpan>()
                if (!tool.rejected) out += executionSpan(tool, at, e)
                out += toolSpan(tool, at, error = null, outcome = e)
                out
            }
            is HooksRan -> if (options.detailedTracing) listOfNotNull(hookSpan(e, record)) else emptyList()
            is TurnEnded -> endTurn(e, at)
            else -> emptyList()
        }
    }

    /** span 的公共属性：session.id、子会话来历、span.type（原文：与名字对应） */
    private fun base(spanName: String): LinkedHashMap<String, OtelValue> {
        val attrs = LinkedHashMap<String, OtelValue>(common)
        attrs["span.type"] = OtelValue.Text(spanName.removePrefix("claude_code."))
        return attrs
    }

    // ---- 回合 ----

    private fun newTurn(id: String, interaction: Interaction?): Turn {
        // 子会话沿用父会话的 trace；根会话有入站 TRACEPARENT 时所有 interaction 都在它的 trace 里
        val trace = link?.traceId ?: traceIdFor(sid, id, options.inbound)
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
            if (!tool.rejected && tool.execStartMs != null) out += executionSpan(tool, at, null, toolName = tool.name)
            out += toolSpan(tool, at, error = "interrupted", outcome = null)
        }
        toolNames.entries.removeAll { it.value.first == id }
        val turn = turns.remove(id)
        val interaction = turn?.interaction
        if (turn != null && interaction != null) out += interactionSpan(turn, interaction, e, at)
        return out
    }

    private fun interactionSpan(turn: Turn, i: Interaction, e: TurnEnded, at: Long): OtelSpan {
        val attrs = base(SpanNames.INTERACTION)
        i.prompt?.let {
            attrs["user_prompt"] = OtelValue.Text(options.content(it, options.logUserPrompts))
            attrs["user_prompt_length"] = OtelValue.Whole(it.length.toLong())
        }
        // 详细 beta 追踪下 interaction 另有 new_context = 用户提示词（门控 OTEL_LOG_USER_PROMPTS，原文「Content attributes under detailed beta tracing」）
        if (options.detailedTracing && options.logUserPrompts) i.prompt?.let { options.putContent(attrs, "new_context", it) }
        attrs["interaction.sequence"] = OtelValue.Whole(i.sequence)
        // 原文：parent.source = env（挂在入站 TRACEPARENT 下）或 none（自己起了一条 trace）
        val inbound = options.inbound
        attrs["parent.source"] = OtelValue.Text(if (inbound != null) "env" else "none")
        attrs["interaction.duration_ms"] = OtelValue.Real(e.durationMs.toDouble())
        attrs["min.outcome"] = OtelValue.Text(when (e.outcome) {
            is TurnOutcome.Completed -> "completed"
            is TurnOutcome.Failed -> "failed"
            is TurnOutcome.Interrupted -> "interrupted"
        })
        return OtelSpan(
            SpanNames.INTERACTION, turn.traceId, SpanIds.interaction(sid, turn.id), inbound?.spanId,
            startMs = i.startMs, endMs = at, attributes = attrs, traceState = inbound?.traceState,
        )
    }

    // ---- 模型请求 ----

    private fun startAttempt(e: ModelRequestStarted, at: Long, seq: Long) {
        val existing = llms[e.requestId.value]
        val prior = existing?.pendingFailure
        if (existing != null && prior != null) {
            // 重试：同一个 llm_request，记一个 gen_ai.request.attempt 事件（原文：每次重试一个，带 attempt）
            existing.attempt = e.attempt
            existing.model = e.model
            existing.pendingFailure = null
            existing.clientRequestId = e.clientRequestId
            val attrs = LinkedHashMap<String, OtelValue>()
            attrs["attempt"] = OtelValue.Whole(e.attempt.toLong())
            // 原文：每次重试的事件带 client_request_id；这家供应商没带（日志里没有）就不写
            e.clientRequestId?.let { attrs["client_request_id"] = OtelValue.Text(it) }
            attrs["min.prior_failure_kind"] = OtelValue.Text(prior.kind.name.lowercase())
            prior.httpStatus?.let { attrs["min.prior_status_code"] = OtelValue.Whole(it.toLong()) }
            existing.events += OtelSpanEvent(SpanEventNames.REQUEST_ATTEMPT, at, attrs)
            currentLlm = existing
            return
        }
        // 全新的请求（含重试用尽、换到回退模型后用同一个请求 id 重新开始的那次）
        val open = OpenLlm(e.turnId.value, e.requestId.value, e.attempt, e.model, at, seq, effort)
        open.clientRequestId = e.clientRequestId
        if (collectNewContext) {
            open.newContext = newContext.toString()
            open.newContextChars = newContextChars
            newContext.setLength(0)
            newContextChars = 0
        }
        llms[e.requestId.value] = open
        currentLlm = open
    }

    private fun llmBase(open: OpenLlm, endMs: Long): LinkedHashMap<String, OtelValue> {
        val attrs = base(SpanNames.LLM_REQUEST)
        attrs["model"] = OtelValue.Text(open.model.modelId)
        attrs["gen_ai.system"] = OtelValue.Text(open.model.providerId)
        attrs["gen_ai.request.model"] = OtelValue.Text(open.model.modelId)
        attrs["llm_request.context"] = OtelValue.Text(if (isChild) "tool" else "interaction")
        // 原文：整段含重试的墙钟时间
        attrs["duration_ms"] = OtelValue.Real((endMs - open.startMs).toDouble())
        // 原文：request_id 是服务端给的请求 id（响应头），与 gen_ai.response.id 同值，拿到才写（见 closeLlm / failLlm）；Min 自己的请求 id 另放 min.request_id
        attrs["min.request_id"] = OtelValue.Text(open.requestId)
        open.clientRequestId?.let { attrs["client_request_id"] = OtelValue.Text(it) }
        attrs.putAll(options.workflowAttributes())
        // 原文：attempt 是总尝试次数
        attrs["attempt"] = OtelValue.Whole(open.attempt.toLong())
        // Min 没有快速模式，请求恒为普通速度
        attrs["speed"] = OtelValue.Text("normal")
        effortName(open.effort)?.let { attrs["effort"] = OtelValue.Text(it) }
        // 原文：query_source 受详细 beta 追踪门控；它的「有界形式」query_source_safe 总是有
        val source = options.lineage?.agentType ?: MAIN_THREAD
        if (options.detailedTracing) attrs["query_source"] = OtelValue.Text(source)
        attrs["query_source_safe"] = OtelValue.Text(
            if (options.lineage == null) MAIN_THREAD else if (source in BUILTIN_AGENTS) "agent.$source" else "agent.custom",
        )
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
        done?.responseId?.let {
            attrs["request_id"] = OtelValue.Text(it)
            attrs["gen_ai.response.id"] = OtelValue.Text(it)
        }
        if (done != null) {
            done.ttftMs?.let { attrs["ttft_ms"] = OtelValue.Whole(it) }
            done.firstContentMs?.let { attrs["first_content_ms"] = OtelValue.Whole(it) }
            attrs["response.has_tool_call"] = OtelValue.Flag(done.parts.any { it is ToolCallPart })
            val stop = stopReason(done.stop)
            attrs["stop_reason"] = OtelValue.Text(stop)
            attrs["gen_ai.response.finish_reasons"] = OtelValue.Texts(listOf(stop))
        }
        detailedLlmContent(attrs, open, done)
        val turn = turnOf(open.turn)
        return OtelSpan(
            SpanNames.LLM_REQUEST, turn.traceId, SpanIds.llm(sid, open.requestId, open.startSeq), turn.childParent,
            open.startMs, at, attrs, error = if (done == null) "interrupted" else null, events = open.events.toList(),
            links = linkOf(done?.traceResponse),
        )
    }

    /** [failed] 为 null：回合收口时还没有最终失败记录，用上一次尝试的失败（等重试时留下的） */
    private fun failLlm(open: OpenLlm, at: Long, failed: ModelRequestFailed?): OtelSpan {
        val last = failed ?: open.pendingFailure
        val attrs = llmBase(open, at)
        attrs["success"] = OtelValue.Flag(false)
        last?.httpStatus?.let { attrs["status_code"] = OtelValue.Whole(it.toLong()) }
        last?.let { attrs["min.failure_kind"] = OtelValue.Text(it.kind.name.lowercase()) }
        last?.let { attrs["min.will_retry"] = OtelValue.Flag(it.willRetry) }
        last?.let { attrs["error_class"] = OtelValue.Text(if (it.timedOut) "api_timeout" else errorClass(it.kind)) }
        last?.responseId?.let {
            attrs["request_id"] = OtelValue.Text(it)
            attrs["gen_ai.response.id"] = OtelValue.Text(it)
        }
        val message = last?.message ?: "interrupted"
        attrs["error"] = OtelValue.Text(message)
        detailedLlmContent(attrs, open, null)
        val turn = turnOf(open.turn)
        return OtelSpan(
            SpanNames.LLM_REQUEST, turn.traceId, SpanIds.llm(sid, open.requestId, open.startSeq), turn.childParent,
            open.startMs, at, attrs, error = message, events = open.events.toList(),
            links = linkOf(last?.traceResponse),
        )
    }

    /**
     * 响应头 `traceresponse` → span link（原文：API 一侧的 traceresponse 记成 span link）。
     * 值来自远端，必须是合规的 W3C 格式才用（[TraceParent.parse] 拒绝一切不合规的），否则不出 link，不抛。
     */
    private fun linkOf(raw: String?): List<OtelSpanLink> {
        val parsed = TraceParent.parse(raw) ?: return emptyList()
        return listOf(OtelSpanLink(parsed.traceId, parsed.spanId, parsed.traceState))
    }

    /**
     * 详细 beta 追踪下 llm_request 的内容属性（原文「Content attributes under detailed beta tracing」，门控：详细追踪 + OTEL_LOG_USER_PROMPTS）：
     * `new_context`（自上一次请求后新进来的用户输入与工具结果）、`response.model_output`（模型这次回的文本）。
     * 原文说它们不属稳定 schema，所以只在详细追踪下出，默认关。
     * `system_prompt_preview`、`user_system_prompt`、`system_reminders` 不做：系统提示与提醒的正文不在会话日志里（日志只留前缀指纹），
     * 为遥测把它们每次请求再记一份既膨胀日志又多存一份提示词。
     */
    private fun detailedLlmContent(attrs: LinkedHashMap<String, OtelValue>, open: OpenLlm, done: AssistantMessageCompleted?) {
        if (!options.detailedTracing || !options.logUserPrompts) return
        open.newContext?.takeIf { it.isNotEmpty() }?.let { text ->
            options.putContent(attrs, "new_context", text, open.newContextChars)
        }
        done?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }?.takeIf { it.isNotEmpty() }?.let {
            options.putContent(attrs, "response.model_output", it)
        }
    }

    private fun stopReason(s: StopReason): String = when (s) {
        StopReason.EndTurn -> "end_turn"
        StopReason.ToolUse -> "tool_use"
        StopReason.MaxTokens -> "max_tokens"
        StopReason.StopSequence -> "stop_sequence"
        StopReason.Refusal -> "refusal"
        is StopReason.Other -> s.raw
    }

    /** 原文：error_class 是 `api_timeout`、`server_overload` 这样的短标识。Min 日志里区分不出超时，网络类统一 network_error */
    private fun errorClass(kind: ModelFailureKind): String = when (kind) {
        ModelFailureKind.Network -> "network_error"
        ModelFailureKind.RateLimited -> "rate_limited"
        ModelFailureKind.Overloaded -> "server_overload"
        ModelFailureKind.ContextOverflow -> "context_overflow"
        ModelFailureKind.Auth -> "auth_error"
        ModelFailureKind.BadRequest -> "bad_request"
        ModelFailureKind.ModelUnavailable -> "model_unavailable"
        ModelFailureKind.Server -> "server_error"
        ModelFailureKind.Other -> "other"
    }

    /** 原文的取值是 low / medium / high / xhigh / max，没发 effort 时不写。Min 多一档 minimal，按它自己的名字写；off = 没发 */
    private fun effortName(e: Effort?): String? = when (e) {
        null, Effort.Off -> null
        Effort.Minimal -> "minimal"
        Effort.Low -> "low"
        Effort.Medium -> "medium"
        Effort.High -> "high"
        Effort.XHigh -> "xhigh"
        Effort.Max -> "max"
    }

    // ---- 工具 ----

    private fun toolSpan(tool: OpenTool, at: Long, error: String?, outcome: ToolOutcome?): OtelSpan {
        val attrs = base(SpanNames.TOOL)
        attrs["tool_name"] = OtelValue.Text(tool.name)
        attrs["tool_name_safe"] = OtelValue.Text(OtelToolNames.safe(tool.name))
        if (tool.name == "Bash") {
            // 命令类别与 argv0 是「安全形式」（固定名单，不含参数），默认就能导出；命令本身受 OTEL_LOG_TOOL_DETAILS 门控
            stringField(tool.input, "command")?.let {
                val c = OtelBash.classify(it)
                attrs["bash_command_class"] = OtelValue.Text(c.commandClass)
                attrs["bash_argv0"] = OtelValue.Text(c.argv0)
            }
        }
        attrs["duration_ms"] = OtelValue.Real((at - tool.startMs).toDouble())
        outcome?.let { attrs["result_tokens"] = OtelValue.Whole(approxTokens(it)) }
        attrs["tool_use_id"] = OtelValue.Text(tool.callId)
        attrs["gen_ai.tool.call.id"] = OtelValue.Text(tool.callId)
        attrs.putAll(options.workflowAttributes())
        detailedToolContent(attrs, tool, outcome)
        if (options.logToolDetails) {
            // 原文：这些属性受 OTEL_LOG_TOOL_DETAILS 门控（防 PII）
            val input = tool.input
            if (tool.name == "Bash") stringField(input, "command")?.let { attrs["full_command"] = OtelValue.Text(options.content(it, true)) }
            if (tool.name in FILE_TOOLS) (stringField(input, "file_path") ?: stringField(input, "notebook_path"))
                ?.let { attrs["file_path"] = OtelValue.Text(it) }
            if (tool.name == "Skill") stringField(input, "skill")?.let { attrs["skill_name"] = OtelValue.Text(it) }
            if (tool.name == "Agent" || tool.name == "Task") stringField(input, "subagent_type")?.let { attrs["subagent_type"] = OtelValue.Text(it) }
        }
        val turn = turnOf(tool.turn)
        val events = outcome?.let { toolOutputEvent(tool, at, it) }?.let { listOf(it) }.orEmpty()
        return OtelSpan(
            SpanNames.TOOL, turn.traceId, SpanIds.tool(sid, tool.callId), turn.childParent,
            tool.startMs, at, attrs, error = error, events = events,
        )
    }

    /**
     * 详细 beta 追踪下 tool span 的内容属性（原文「Content attributes under detailed beta tracing」）：
     * `new_context` = 工具调用的结果，不论哪个工具，门控 OTEL_LOG_TOOL_CONTENT；`tool_input` = 序列化的工具入参，门控 OTEL_LOG_TOOL_DETAILS。
     * 两个都要先有详细追踪（原文：不属稳定 schema，只在详细追踪下出）。被中断的调用没有结果，不写 `new_context`。
     */
    private fun detailedToolContent(attrs: LinkedHashMap<String, OtelValue>, tool: OpenTool, outcome: ToolOutcome?) {
        if (!options.detailedTracing) return
        if (options.logToolContent && outcome != null) {
            val text = outcome.result.content.joinToString("") {
                when (it) {
                    is TextPart -> it.text
                    is ImagePart -> "[image]"
                    is DocumentPart -> "[document]"
                    else -> ""
                }
            }
            if (text.isNotEmpty()) options.putContent(attrs, "new_context", text)
        }
        if (options.logToolDetails) options.putContent(attrs, "tool_input", tool.input.toString())
    }

    /**
     * tool.output 事件（原文）：受 OTEL_LOG_TOOL_CONTENT 管，没开不产出，更不带任何内容。
     * 哪些工具：Read 与 Bash；Edit 与 Write 还要 OTEL_LOG_TOOL_DETAILS；MCP 工具、WebFetch、WebSearch。
     * 不记：出错的调用、其他工具、Read 到图片 / PDF 的结果。
     * 内容按上限截断，被截断的属性带 `<名字>_truncated` 与 `<名字>_original_length`。
     */
    private fun toolOutputEvent(tool: OpenTool, at: Long, outcome: ToolOutcome): OtelSpanEvent? {
        if (!options.logToolContent) return null
        val result = outcome.result
        if (result.isError) return null
        val details = options.logToolDetails
        val attrs = LinkedHashMap<String, OtelValue>()
        val text = result.content.joinToString("") {
            when (it) {
                is TextPart -> it.text
                is ImagePart -> "[image]"
                is DocumentPart -> "[document]"
                else -> ""
            }
        }
        when {
            tool.name == "Read" -> {
                if (result.content.any { it !is TextPart }) return null
                options.putContent(attrs, "content", text)
                if (details) stringField(tool.input, "file_path")?.let { attrs["file_path"] = OtelValue.Text(options.content(it, true)) }
            }
            tool.name == "Bash" -> {
                options.putContent(attrs, "output", text)
                if (details) stringField(tool.input, "command")?.let { options.putContent(attrs, "bash_command", it) }
            }
            tool.name == "Edit" -> {
                if (!details) return null
                val diff = result.fileEdits.mapNotNull { it.diff }.joinToString("\n")
                if (diff.isEmpty()) return null
                options.putContent(attrs, "diff", diff)
                stringField(tool.input, "file_path")?.let { attrs["file_path"] = OtelValue.Text(options.content(it, true)) }
            }
            tool.name == "Write" -> {
                if (!details) return null
                val body = stringField(tool.input, "content") ?: return null
                options.putContent(attrs, "content", body)
                stringField(tool.input, "file_path")?.let { attrs["file_path"] = OtelValue.Text(options.content(it, true)) }
            }
            tool.name == "WebFetch" || tool.name == "WebSearch" || tool.name.startsWith("mcp__") ->
                options.putContent(attrs, "output", text)
            else -> return null
        }
        return OtelSpanEvent(SpanEventNames.TOOL_OUTPUT, at, attrs)
    }

    /** 原文：result_tokens 是工具结果的「大致」token 数。日志里没有分词器，按四个字符一个 token 向上取整估（只数文本） */
    private fun approxTokens(outcome: ToolOutcome): Long {
        val chars = outcome.result.content.filterIsInstance<TextPart>().sumOf { it.text.length.toLong() }
        return (chars + 3) / 4
    }

    private fun blockedSpan(tool: OpenTool, at: Long, accepted: Boolean, source: String): OtelSpan {
        val attrs = base(SpanNames.BLOCKED_ON_USER)
        attrs["duration_ms"] = OtelValue.Real((at - tool.blockedStartMs).toDouble())
        attrs["decision"] = OtelValue.Text(if (accepted) "accept" else "reject")
        attrs["source"] = OtelValue.Text(source)
        val turn = turnOf(tool.turn)
        return OtelSpan(
            SpanNames.BLOCKED_ON_USER, turn.traceId, SpanIds.blocked(sid, tool.callId), SpanIds.tool(sid, tool.callId),
            tool.blockedStartMs, at, attrs,
        )
    }

    private fun executionSpan(tool: OpenTool, at: Long, outcome: ToolOutcome?, toolName: String = tool.name): OtelSpan {
        // 起点：副作用开始前那条记录；没有（免审批、无副作用的工具）就取审批放行的时刻，再没有取计划时刻
        val start = tool.execStartMs ?: tool.resolvedAtMs ?: tool.startMs
        val failed = outcome == null || outcome.result.isError
        val attrs = base(SpanNames.EXECUTION)
        attrs["duration_ms"] = OtelValue.Real((at - start).toDouble())
        attrs["tool_use_id"] = OtelValue.Text(tool.callId)
        attrs["gen_ai.tool.call.id"] = OtelValue.Text(tool.callId)
        attrs["success"] = OtelValue.Flag(!failed)
        var error: String? = null
        if (failed) {
            // 原文：error 是错误类别（如 ShellError），完整错误正文只在 OTEL_LOG_TOOL_DETAILS 开着时才有；error_class 是它的标识形式。
            // Min 的工具结果没有结构化的错误类别：Bash 失败记 ShellError，其余记 ToolError，被中断记 interrupted
            val category = when {
                outcome == null -> "interrupted"
                // 工具 / 内核给出了类别（Error:ENOENT 一类，随 tool_outcome 落盘）就用它，否则按工具名猜
                outcome.result.errorKind != null -> outcome.result.errorKind!!
                toolName == "Bash" -> "ShellError"
                else -> "ToolError"
            }
            error = if (outcome == null) "interrupted" else "tool_error"
            if (outcome != null && options.logToolDetails) {
                val body = outcome.result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                attrs["error"] = OtelValue.Text(options.content(body, true))
            } else {
                attrs["error"] = OtelValue.Text(category)
            }
            // 原文：error_class 是类别的标识形式，字母数字下划线之外的字符换成 _
            attrs["error_class"] = OtelValue.Text(category.replace(Regex("[^A-Za-z0-9_]"), "_"))
        }
        val turn = turnOf(tool.turn)
        return OtelSpan(
            SpanNames.EXECUTION, turn.traceId, SpanIds.execution(sid, tool.callId), SpanIds.tool(sid, tool.callId),
            start, at, attrs, error = error,
        )
    }

    // ---- hook ----

    /**
     * claude_code.hook（原文：只在详细 beta 追踪下，挂在 interaction 下）。一条 hooks_ran 记录一个 span：
     * 记录落在这批 hook 跑完之后，起点 = 记录时刻 - durationMs。没有回合的 hook（SessionStart、SessionEnd 一类）
     * 没有 interaction 可挂，不产出。后台 hook 完成时的记录同样产出（`min.background`）。
     */
    private fun hookSpan(e: HooksRan, record: EventRecord): OtelSpan? {
        val turnId = e.turnId?.value ?: return null
        val turn = turns[turnId] ?: newTurn(turnId, null)
        val toolName = e.callId?.let { toolNames[it.value]?.second }
        val attrs = base(SpanNames.HOOK)
        attrs["hook_event"] = OtelValue.Text(e.event)
        attrs["hook_name"] = OtelValue.Text(if (toolName != null) "${e.event}:$toolName" else e.event)
        attrs["num_hooks"] = OtelValue.Whole(e.handlers.toLong())
        attrs["duration_ms"] = OtelValue.Real(e.durationMs.toDouble())
        e.succeeded?.let { attrs["num_success"] = OtelValue.Whole(it.toLong()) }
        e.blocking?.let { attrs["num_blocking"] = OtelValue.Whole(it.toLong()) }
        e.nonBlockingErrors?.let { attrs["num_non_blocking_error"] = OtelValue.Whole(it.toLong()) }
        // 原文：num_cancelled = 没跑完就被取消的 hook 数。只在内核整批被取消时记（cancelled）；其余批有结局计数就是 0
        (e.cancelled ?: if (e.succeeded != null) 0 else null)?.let { attrs["num_cancelled"] = OtelValue.Whole(it.toLong()) }
        if (e.background) attrs["min.background"] = OtelValue.Flag(true)
        if (options.logToolDetails) definitionsFor(e.event)?.let { attrs["hook_definitions"] = OtelValue.Text(options.content(it, true)) }
        // 原文：hook 失败时状态为 ERROR。「失败」= 有处理器出了非阻断错误（没跑起来、超时）；拦下是 hook 的正常结论
        val failed = (e.nonBlockingErrors ?: 0) > 0 || (e.nonBlockingErrors == null && e.cancelled == null && e.problems.isNotEmpty())
        val message = if (failed) e.problems.firstOrNull() ?: "hook error" else null
        return OtelSpan(
            SpanNames.HOOK, turn.traceId, e.runId?.let { SpanIds.hookRun(sid, it) } ?: SpanIds.hook(sid, record.seq), turn.childParent,
            (record.atMs - e.durationMs).coerceAtLeast(0), record.atMs, attrs, error = message,
        )
    }

    /** 这个事件在各层 settings 与组件（skill / command / agent）里配的 hooks 原文，合成一个 JSON 对象；一个都没有返回 null */
    private fun definitionsFor(event: String): String? {
        val found = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((source, hooks) in hookSources) hooks[event]?.let { found[source] = it }
        for ((key, hooks) in componentHooks) hooks[event]?.let { found[key] = it }
        return if (found.isEmpty()) null else JsonObject(found).toString()
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
        const val MAIN_THREAD = "repl_main_thread"

        /** Min 自带的子 agent 类型：它们的名字是固定的，可以原样进 query_source_safe；其余（用户自定义）一律 agent.custom */
        val BUILTIN_AGENTS = setOf("general-purpose", "Explore", "Plan")
    }
}
