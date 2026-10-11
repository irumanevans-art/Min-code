package dev.min.agent.otel

/*
 * 待导出的遥测条目。与传输无关：OTLP JSON、本机 JSONL 都从这里编码，映射（OtelMapper）只产出这些。
 */

/** 属性值。OTLP 里对应 stringValue / intValue / doubleValue / boolValue，类型不混用 */
sealed interface OtelValue {
    data class Text(val value: String) : OtelValue
    data class Whole(val value: Long) : OtelValue
    data class Real(val value: Double) : OtelValue
    data class Flag(val value: Boolean) : OtelValue
    /** 字符串数组（OTLP arrayValue）：gen_ai.response.finish_reasons 这类原文是数组的属性 */
    data class Texts(val value: List<String>) : OtelValue
}

sealed interface OtelItem {
    val timeMs: Long
    val attributes: Map<String, OtelValue>
}

/** 一个指标数据点（CC 的指标都是计数器：每个数据点是一次增量） */
data class OtelMetric(
    val name: String,
    val unit: String,
    val value: Double,
    override val attributes: Map<String, OtelValue>,
    override val timeMs: Long,
) : OtelItem

/** 一条日志事件（CC 的 event.name 那一类） */
data class OtelLog(
    val name: String,
    override val attributes: Map<String, OtelValue>,
    override val timeMs: Long,
    /** 关联的 trace / span（原文：-p 与 SDK 会话带入站 TRACEPARENT 时才有，见 [OtelMapper]） */
    val traceId: String? = null,
    val spanId: String? = null,
) : OtelItem

/** span 上的一个事件（OTLP span events）：gen_ai.request.attempt、tool.output。时间来自会话日志的 atMs */
data class OtelSpanEvent(
    val name: String,
    val timeMs: Long,
    val attributes: Map<String, OtelValue>,
)

/**
 * 一个已经结束的 span（OTLP traces）。span 只在结束时导出：开始时间与结束时间都来自会话日志里的 atMs。
 * [error] 非空即状态为 ERROR（原文：llm_request、tool.execution、hook 失败时），为空则 UNSET。
 * 作为 [OtelItem] 时 [timeMs] 取结束时间，导出批次按它排序。
 * [traceState]：入站 TRACESTATE（W3C），只挂在以入站 TRACEPARENT 为父的根 span 上。
 */
data class OtelSpan(
    val name: String,
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val startMs: Long,
    val endMs: Long,
    override val attributes: Map<String, OtelValue>,
    val error: String? = null,
    val events: List<OtelSpanEvent> = emptyList(),
    val traceState: String? = null,
) : OtelItem {
    override val timeMs: Long get() = endMs
}

/**
 * 子会话（子 agent、团队成员）的来历，从子会话日志的 SubagentOrigin 与头部来：
 * 带到子会话的每条遥测上，后端据此把它们归到父会话名下。
 */
data class OtelLineage(
    val parent: String,
    val root: String,
    /** 父会话里发起委派的那次工具调用 id（Agent / TeamCreate 等） */
    val parentCall: String,
    val agentType: String,
    /** 根会话 = 0，它的子 agent = 1…… */
    val depth: Int,
    val teamMember: Boolean,
)

/**
 * 映射的开关，来自环境变量（[OtelConfig]）。默认值照 CC：内容类一律不记，指标里带 session.id。
 * 这里只描述「记不记」，不做任何 IO。
 */
data class OtelOptions(
    val sessionId: String,
    /** OTEL_LOG_USER_PROMPTS：用户提示词正文。默认不记，只记长度 */
    val logUserPrompts: Boolean = false,
    /** OTEL_LOG_ASSISTANT_RESPONSES：助手回复正文。未设时跟随 [logUserPrompts]（配置层处理） */
    val logAssistantResponses: Boolean = false,
    /** OTEL_LOG_TOOL_DETAILS：工具入参、错误正文。默认不记，防 PII */
    val logToolDetails: Boolean = false,
    /** OTEL_METRICS_INCLUDE_SESSION_ID：指标是否带 session.id（默认带） */
    val includeSessionId: Boolean = true,
    /** CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH：内容类属性的截断长度（UTF-16 单位），默认 61440 */
    val contentMaxLength: Int = 61440,
    /** 子会话的来历；根会话为 null */
    val lineage: OtelLineage? = null,
    /** OTEL_LOG_TOOL_CONTENT：`tool.output` span 事件里的工具内容（Read 文本、Bash 输出……）。默认不记，且要求 traces 开着 */
    val logToolContent: Boolean = false,
    /** 详细 beta 追踪（ENABLE_BETA_TRACING_DETAILED + BETA_TRACING_ENDPOINT）：`claude_code.hook` span、`query_source` 等 */
    val detailedTracing: Boolean = false,
    /** 入站 TRACEPARENT / TRACESTATE（-p 与 SDK 会话）：根会话的 interaction span 挂在它下面，`parent.source=env` */
    val inbound: TraceParent? = null,
)

/** 内容类属性：不许记就写占位，记就按上限截断（截断标记计入上限，满足 OTLP 属性长度约定） */
internal fun OtelOptions.content(value: String, allowed: Boolean): String {
    if (!allowed) return OTEL_REDACTED
    val max = contentMaxLength
    if (value.length <= max) return value
    return value.take((max - OTEL_TRUNCATED.length).coerceAtLeast(0)) + OTEL_TRUNCATED
}

internal const val OTEL_REDACTED = "<REDACTED>"
internal const val OTEL_TRUNCATED = "...[truncated]"

/** 子会话属性（日志与 span 共用同一套名字）。根会话为空 */
internal fun OtelLineage.attributes(sessionId: String): Map<String, OtelValue> = buildMap {
    put("agent_id", OtelValue.Text(sessionId))
    // 原文：parent_agent_id 在主会话、以及主会话直接派生的 agent 上缺省
    if (depth >= 2) put("parent_agent_id", OtelValue.Text(parent))
    put("min.parent_session_id", OtelValue.Text(parent))
    put("min.root_session_id", OtelValue.Text(root))
    put("min.subagent_type", OtelValue.Text(agentType))
    put("min.session_depth", OtelValue.Whole(depth.toLong()))
    if (teamMember) put("min.team_member", OtelValue.Flag(true))
}

/**
 * 内容类属性（tool.output 的 content / output / diff……）：按上限截断；被截断时多写两个伴随属性
 * `<名字>_truncated`（true）与 `<名字>_original_length`（原长，UTF-16 单位）——原文口径，后端据此知道没拿到全文。
 * 没被截断时不写伴随属性。
 */
internal fun OtelOptions.putContent(into: MutableMap<String, OtelValue>, name: String, value: String) {
    into[name] = OtelValue.Text(content(value, true))
    if (value.length > contentMaxLength) {
        into["${name}_truncated"] = OtelValue.Flag(true)
        into["${name}_original_length"] = OtelValue.Whole(value.length.toLong())
    }
}
