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
)
