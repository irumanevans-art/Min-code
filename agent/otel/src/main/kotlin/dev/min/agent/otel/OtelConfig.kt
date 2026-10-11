package dev.min.agent.otel

/*
 * 遥测配置：从环境变量读（变量名照 CC 的 OpenTelemetry 文档），不做任何 IO。
 *
 * 默认关。开关要同时满足：CLAUDE_CODE_ENABLE_TELEMETRY=1，且 DISABLE_TELEMETRY / DO_NOT_TRACK 没有设成开。
 * 有了开关还要有目的地（OTLP 端点或本机文件），否则 [active] 为假，什么都不导出。
 *
 * 为什么把 "problems" 单列：CC 的 exporter 错误默认只在 --debug 下可见，配错了会静默失败；
 * 这里把配错的原因收集起来交给宿主显示，但说明里只写变量名，绝不带头部的值（那里有令牌）。
 */
data class OtelConfig(
    val enabled: Boolean,
    /** OTLP 指标端点（已补好 /v1/metrics）；null = 指标不走 OTLP */
    val metricsEndpoint: String?,
    /** OTLP 日志端点（已补好 /v1/logs）；null = 日志不走 OTLP */
    val logsEndpoint: String?,
    /** 通用头部（OTEL_EXPORTER_OTLP_HEADERS） */
    val headers: List<Pair<String, String>>,
    /** 指标的全部头部：通用头部 + OTEL_EXPORTER_OTLP_METRICS_HEADERS，后者同名时追加在后 */
    val metricsHeaders: List<Pair<String, String>>,
    /** 日志的全部头部：通用头部 + OTEL_EXPORTER_OTLP_LOGS_HEADERS */
    val logsHeaders: List<Pair<String, String>>,
    /** OTLP traces 端点（已补好 /v1/traces）；null = traces 不走 OTLP。traces 是 beta，见 [tracesEnabled] */
    val tracesEndpoint: String?,
    /** traces 的全部头部：通用头部 + OTEL_EXPORTER_OTLP_TRACES_HEADERS */
    val tracesHeaders: List<Pair<String, String>>,
    /** console 导出器（OTEL_*_EXPORTER 含 console）：把条目逐行打印到宿主给的输出口，见 [OtelExporter] */
    val metricsConsole: Boolean,
    val logsConsole: Boolean,
    val tracesConsole: Boolean,
    /** CLAUDE_CODE_ENHANCED_TELEMETRY_BETA（或别名 ENABLE_ENHANCED_TELEMETRY_BETA）：span 追踪的 beta 开关，原文要求它才有 traces */
    val tracesBeta: Boolean,
    /** Min 自己的本机导出（MIN_TELEMETRY_FILE）：每条遥测写一行 JSON 到这个文件；null = 不写 */
    val localFile: String?,
    val metricIntervalMs: Long,
    val logIntervalMs: Long,
    val traceIntervalMs: Long,
    val resourceAttributes: List<Pair<String, String>>,
    val logUserPrompts: Boolean,
    val logAssistantResponses: Boolean,
    val logToolDetails: Boolean,
    val includeSessionId: Boolean,
    val contentMaxLength: Int,
    /** OTEL_LOG_TOOL_CONTENT：`tool.output` span 事件带工具内容（原文：默认关，要求 traces；截断口径同 [contentMaxLength]） */
    val logToolContent: Boolean = false,
    /** 详细 beta 追踪：ENABLE_BETA_TRACING_DETAILED 与 BETA_TRACING_ENDPOINT 成对设置（原文），见 [detailedTracing] */
    val betaTracingDetailed: Boolean = false,
    /** 入站 TRACEPARENT / TRACESTATE；只在宿主声明接受时才有值（原文：-p 与 SDK 会话读，交互会话忽略），见 [fromEnv] 的 acceptInbound */
    val inbound: TraceParent? = null,
    /**
     * 出站传播（Bash / hook 子进程的 TRACEPARENT）：原文口径——`ANTHROPIC_BASE_URL` 没设或指向 Anthropic API 时传播；
     * 指向自定义代理时默认不传（免得把 trace id 交给第三方），设 `CLAUDE_CODE_PROPAGATE_TRACEPARENT=1` 才传
     */
    val propagateTraceparent: Boolean = true,
    /** 配错了的地方（只写变量名与原因，不写值） */
    val problems: List<String>,
) {
    /** 真的会导出点什么：开关开着，且至少有一个目的地 */
    val active: Boolean get() = enabled && (
        metricsEndpoint != null || logsEndpoint != null || tracesEndpoint != null ||
            metricsConsole || logsConsole || tracesConsole || localFile != null
        )

    /**
     * 要不要产出 span：总开关开着、beta 开关开着，并且 span 有去处（OTLP traces 端点、console 或本机文件）。
     * 不满足时连映射都不做（见 OtelTail），默认关的口径不变。
     */
    val tracesEnabled: Boolean get() = enabled && tracesBeta &&
        (tracesEndpoint != null || tracesConsole || localFile != null)

    /**
     * 详细 beta 追踪生效：traces 在产出、且成对设了 ENABLE_BETA_TRACING_DETAILED 与 BETA_TRACING_ENDPOINT（原文：`CLAUDE_CODE_ENHANCED_TELEMETRY_BETA` 单独不会产出 hook span）。
     * Min 只把它当开关：**不向 BETA_TRACING_ENDPOINT 发送任何数据**（原文说这对变量还会改变日志与 traces 的去向，Min 不改道——
     * 遥测去哪只由 OTEL_EXPORTER_OTLP_* 说了算）。原文的组织白名单（交互 CLI 才要）Min 没有对应物。
     */
    val detailedTracing: Boolean get() = tracesEnabled && betaTracingDetailed

    fun options(sessionId: String): OtelOptions = OtelOptions(
        sessionId = sessionId,
        logUserPrompts = logUserPrompts,
        logAssistantResponses = logAssistantResponses,
        logToolDetails = logToolDetails,
        includeSessionId = includeSessionId,
        contentMaxLength = contentMaxLength,
        // 原文：OTEL_LOG_TOOL_CONTENT 要求 tracing 开着
        logToolContent = logToolContent && tracesEnabled,
        detailedTracing = detailedTracing,
        inbound = inbound,
    )

    /** 头部的值是令牌：字符串形式里一律遮住 */
    override fun toString(): String = "OtelConfig(enabled=$enabled, active=$active, " +
        "metricsEndpoint=$metricsEndpoint, logsEndpoint=$logsEndpoint, tracesEndpoint=$tracesEndpoint, localFile=$localFile, " +
        "headers=${masked(headers)}, metricsHeaders=${masked(metricsHeaders)}, logsHeaders=${masked(logsHeaders)}, " +
        "tracesHeaders=${masked(tracesHeaders)}, " +
        "problems=$problems)"

    private fun masked(list: List<Pair<String, String>>) = list.joinToString(prefix = "[", postfix = "]") { "${it.first}=***" }

    companion object {
        const val DEFAULT_METRIC_INTERVAL_MS = 60_000L
        const val DEFAULT_LOG_INTERVAL_MS = 5_000L
        const val DEFAULT_TRACE_INTERVAL_MS = 5_000L
        const val DEFAULT_CONTENT_MAX_LENGTH = 61_440

        /** 从环境读配置。[env] 注入是为了测试；宿主传 System::getenv 或设置存储的读取函数 */
        fun fromEnv(env: (String) -> String?): OtelConfig = fromEnv(env, acceptInbound = false)

        /** [acceptInbound]：宿主是 -p / SDK 这类非交互会话才为真，此时入站的 TRACEPARENT / TRACESTATE 才被读（原文：交互会话忽略） */
        fun fromEnv(env: (String) -> String?, acceptInbound: Boolean): OtelConfig {
            val problems = mutableListOf<String>()
            fun flag(name: String) = env(name)?.trim() == "1"
            fun nonEmpty(name: String) = !env(name).isNullOrBlank()

            val enabled = flag("CLAUDE_CODE_ENABLE_TELEMETRY") &&
                !nonEmpty("DISABLE_TELEMETRY") && env("DO_NOT_TRACK")?.trim() != "1"

            val metricsSel = selectExporters("OTEL_METRICS_EXPORTER", env("OTEL_METRICS_EXPORTER").orEmpty(), problems)
            val logsSel = selectExporters("OTEL_LOGS_EXPORTER", env("OTEL_LOGS_EXPORTER").orEmpty(), problems)
            val tracesSel = selectExporters("OTEL_TRACES_EXPORTER", env("OTEL_TRACES_EXPORTER").orEmpty(), problems)
            val tracesBeta = flag("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA") || flag("ENABLE_ENHANCED_TELEMETRY_BETA")
            if (enabled && !tracesBeta && (tracesSel.otlp || tracesSel.console)) {
                problems += "OTEL_TRACES_EXPORTER 已设置，但没有 CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1，traces 不会导出（beta）"
            }

            // 详细 beta 追踪：原文要求这一对变量同时设置；Min 只把它当开关，不向 BETA_TRACING_ENDPOINT 发数据（见 detailedTracing）
            val detailedFlag = flag("ENABLE_BETA_TRACING_DETAILED")
            val betaDetailed = detailedFlag && nonEmpty("BETA_TRACING_ENDPOINT")
            if (enabled && detailedFlag && !betaDetailed) {
                problems += "ENABLE_BETA_TRACING_DETAILED 需要同时设置 BETA_TRACING_ENDPOINT（成对），详细追踪没有开启"
            }
            if (enabled && betaDetailed) {
                problems += "BETA_TRACING_ENDPOINT 只作为详细追踪的开关：Min 不会向它发送数据，遥测只去 OTEL_EXPORTER_OTLP_* 配的地方"
            }

            val generic = env("OTEL_EXPORTER_OTLP_ENDPOINT")?.trim()?.takeIf { it.isNotEmpty() }
            val generalHeaders = parseHeaders("OTEL_EXPORTER_OTLP_HEADERS", env("OTEL_EXPORTER_OTLP_HEADERS"), problems)

            val metricsEndpoint = if (metricsSel.otlp) endpointFor(env, "METRICS", "/v1/metrics", generic, problems) else null
            val logsEndpoint = if (logsSel.otlp) endpointFor(env, "LOGS", "/v1/logs", generic, problems) else null
            val tracesEndpoint = if (tracesSel.otlp) endpointFor(env, "TRACES", "/v1/traces", generic, problems) else null

            val metricsHeaders = generalHeaders + parseHeaders(
                "OTEL_EXPORTER_OTLP_METRICS_HEADERS", env("OTEL_EXPORTER_OTLP_METRICS_HEADERS"), problems,
            )
            val logsHeaders = generalHeaders + parseHeaders(
                "OTEL_EXPORTER_OTLP_LOGS_HEADERS", env("OTEL_EXPORTER_OTLP_LOGS_HEADERS"), problems,
            )

            val tracesHeaders = generalHeaders + parseHeaders(
                "OTEL_EXPORTER_OTLP_TRACES_HEADERS", env("OTEL_EXPORTER_OTLP_TRACES_HEADERS"), problems,
            )

            return OtelConfig(
                enabled = enabled,
                metricsEndpoint = metricsEndpoint?.takeIf { enabled },
                logsEndpoint = logsEndpoint?.takeIf { enabled },
                headers = generalHeaders,
                metricsHeaders = metricsHeaders,
                logsHeaders = logsHeaders,
                tracesEndpoint = tracesEndpoint?.takeIf { enabled },
                tracesHeaders = tracesHeaders,
                metricsConsole = metricsSel.console && enabled,
                logsConsole = logsSel.console && enabled,
                tracesConsole = tracesSel.console && enabled,
                tracesBeta = tracesBeta,
                localFile = env("MIN_TELEMETRY_FILE")?.trim()?.takeIf { it.isNotEmpty() },
                // 间隔非正数 / 不是数字：回到原文的默认值（0 会让周期导出变成空转）
                metricIntervalMs = interval(env("OTEL_METRIC_EXPORT_INTERVAL"), DEFAULT_METRIC_INTERVAL_MS),
                logIntervalMs = interval(env("OTEL_LOGS_EXPORT_INTERVAL"), DEFAULT_LOG_INTERVAL_MS),
                traceIntervalMs = interval(env("OTEL_TRACES_EXPORT_INTERVAL"), DEFAULT_TRACE_INTERVAL_MS),
                resourceAttributes = parseHeaders("OTEL_RESOURCE_ATTRIBUTES", env("OTEL_RESOURCE_ATTRIBUTES"), problems),
                // 隐私开关默认全关（CC 原文）。助手回复未设时跟随用户提示词开关；设 0 可在提示词打开时仍脱敏回复
                logUserPrompts = flag("OTEL_LOG_USER_PROMPTS"),
                logAssistantResponses = env("OTEL_LOG_ASSISTANT_RESPONSES")?.trim()?.let { it == "1" } ?: flag("OTEL_LOG_USER_PROMPTS"),
                logToolDetails = flag("OTEL_LOG_TOOL_DETAILS"),
                includeSessionId = env("OTEL_METRICS_INCLUDE_SESSION_ID")?.trim() != "false",
                // 原文：SDK 的属性长度限制（OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT 及其 span / logrecord 变体）更小时，按更小的截断
                contentMaxLength = listOfNotNull(
                    env("CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_CONTENT_MAX_LENGTH,
                    attributeLimit(env, "OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT"),
                    attributeLimit(env, "OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT"),
                    attributeLimit(env, "OTEL_LOGRECORD_ATTRIBUTE_VALUE_LENGTH_LIMIT"),
                ).min(),
                logToolContent = flag("OTEL_LOG_TOOL_CONTENT"),
                betaTracingDetailed = betaDetailed,
                // 原文：Agent SDK 与 -p 会话读入站 TRACEPARENT / TRACESTATE，交互会话忽略——由宿主用 acceptInbound 声明
                propagateTraceparent = flag("CLAUDE_CODE_PROPAGATE_TRACEPARENT") || baseUrlIsAnthropicOrUnset(env("ANTHROPIC_BASE_URL")),
                inbound = if (acceptInbound) TraceParent.parse(env("TRACEPARENT"), env("TRACESTATE")) else null,
                problems = problems,
            )
        }

        /** ANTHROPIC_BASE_URL 没设、或主机是 anthropic.com（含子域）：不是自定义代理 */
        private fun baseUrlIsAnthropicOrUnset(raw: String?): Boolean {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return true
            val host = try {
                java.net.URI(value).host
            } catch (e: java.net.URISyntaxException) {
                null
            } ?: return false
            return host == "anthropic.com" || host.endsWith(".anthropic.com")
        }

        private fun attributeLimit(env: (String) -> String?, name: String): Int? = env(name)?.trim()?.toIntOrNull()?.takeIf { it > 0 }

        private fun interval(raw: String?, default: Long): Long = raw?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: default

        private class Selection(val otlp: Boolean, val console: Boolean)

        /** 选择器（逗号分隔）：otlp、console 支持；none 与未设都不走；别的值（prometheus……）说明不支持 */
        private fun selectExporters(name: String, value: String, problems: MutableList<String>): Selection {
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts) {
                if (p != "otlp" && p != "console" && p != "none") problems += "$name 的 $p 不支持，目前只有 otlp 与 console"
            }
            return Selection(otlp = "otlp" in parts, console = "console" in parts)
        }

        private fun endpointFor(
            env: (String) -> String?,
            signal: String,
            path: String,
            generic: String?,
            problems: MutableList<String>,
        ): String? {
            val protocol = (env("OTEL_EXPORTER_OTLP_${signal}_PROTOCOL") ?: env("OTEL_EXPORTER_OTLP_PROTOCOL"))?.trim()
            if (protocol.isNullOrEmpty()) {
                problems += "设置了 OTLP 导出，但没有 OTEL_EXPORTER_OTLP_PROTOCOL（只支持 http/json）"
                return null
            }
            if (protocol != "http/json") {
                problems += "OTLP 协议 $protocol 不支持，目前只有 http/json"
                return null
            }
            val explicit = env("OTEL_EXPORTER_OTLP_${signal}_ENDPOINT")?.trim()?.takeIf { it.isNotEmpty() }
            if (explicit != null) return explicit
            if (generic == null) {
                problems += "设置了 OTLP 导出，但没有任何端点（OTEL_EXPORTER_OTLP_ENDPOINT）"
                return null
            }
            // 通用端点只给了主机：各信号按 OTLP/HTTP 约定补路径
            return generic.trimEnd('/') + path
        }

        /** `k=v,k2=v2`，值做 percent 解码（OTEL 约定）。解码失败只报变量名，不报值 */
        private fun parseHeaders(name: String, raw: String?, problems: MutableList<String>): List<Pair<String, String>> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',').mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = "").trim()
                if (key.isEmpty()) return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                val decoded = try {
                    java.net.URLDecoder.decode(value, "UTF-8")
                } catch (e: IllegalArgumentException) {
                    problems += "$name 里有一项的值不是合法的 percent 编码，已忽略该项"
                    return@mapNotNull null
                }
                key to decoded
            }
        }
    }
}
