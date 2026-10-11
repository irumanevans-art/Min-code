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
) : OtelItem

/**
 * 一个已经结束的 span（OTLP traces）。span 只在结束时导出：开始时间与结束时间都来自会话日志里的 atMs。
 * [error] 非空即状态为 ERROR（原文：llm_request、tool.execution、hook 失败时），为空则 UNSET。
 * 作为 [OtelItem] 时 [timeMs] 取结束时间，导出批次按它排序。
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
