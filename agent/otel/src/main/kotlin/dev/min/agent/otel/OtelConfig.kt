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
    /** Min 自己的本机导出（MIN_TELEMETRY_FILE）：每条遥测写一行 JSON 到这个文件；null = 不写 */
    val localFile: String?,
    val metricIntervalMs: Long,
    val logIntervalMs: Long,
    val resourceAttributes: List<Pair<String, String>>,
    val logUserPrompts: Boolean,
    val logAssistantResponses: Boolean,
    val logToolDetails: Boolean,
    val includeSessionId: Boolean,
    val contentMaxLength: Int,
    /** 配错了的地方（只写变量名与原因，不写值） */
    val problems: List<String>,
) {
    /** 真的会导出点什么：开关开着，且至少有一个目的地 */
    val active: Boolean get() = enabled && (metricsEndpoint != null || logsEndpoint != null || localFile != null)

    fun options(sessionId: String): OtelOptions = OtelOptions(
        sessionId = sessionId,
        logUserPrompts = logUserPrompts,
        logAssistantResponses = logAssistantResponses,
        logToolDetails = logToolDetails,
        includeSessionId = includeSessionId,
        contentMaxLength = contentMaxLength,
    )

    /** 头部的值是令牌：字符串形式里一律遮住 */
    override fun toString(): String = "OtelConfig(enabled=$enabled, active=$active, " +
        "metricsEndpoint=$metricsEndpoint, logsEndpoint=$logsEndpoint, localFile=$localFile, " +
        "headers=${masked(headers)}, metricsHeaders=${masked(metricsHeaders)}, logsHeaders=${masked(logsHeaders)}, " +
        "problems=$problems)"

    private fun masked(list: List<Pair<String, String>>) = list.joinToString(prefix = "[", postfix = "]") { "${it.first}=***" }

    companion object {
        const val DEFAULT_METRIC_INTERVAL_MS = 60_000L
        const val DEFAULT_LOG_INTERVAL_MS = 5_000L
        const val DEFAULT_CONTENT_MAX_LENGTH = 61_440

        /** 从环境读配置。[env] 注入是为了测试；宿主传 System::getenv 或设置存储的读取函数 */
        fun fromEnv(env: (String) -> String?): OtelConfig {
            val problems = mutableListOf<String>()
            fun flag(name: String) = env(name)?.trim() == "1"
            fun nonEmpty(name: String) = !env(name).isNullOrBlank()

            val enabled = flag("CLAUDE_CODE_ENABLE_TELEMETRY") &&
                !nonEmpty("DISABLE_TELEMETRY") && env("DO_NOT_TRACK")?.trim() != "1"

            val selectors = (env("OTEL_METRICS_EXPORTER").orEmpty()) to (env("OTEL_LOGS_EXPORTER").orEmpty())
            val metricsOtlp = wantsOtlp("OTEL_METRICS_EXPORTER", selectors.first, problems)
            val logsOtlp = wantsOtlp("OTEL_LOGS_EXPORTER", selectors.second, problems)

            val generic = env("OTEL_EXPORTER_OTLP_ENDPOINT")?.trim()?.takeIf { it.isNotEmpty() }
            val generalHeaders = parseHeaders("OTEL_EXPORTER_OTLP_HEADERS", env("OTEL_EXPORTER_OTLP_HEADERS"), problems)

            val metricsEndpoint = if (metricsOtlp) endpointFor(env, "METRICS", "/v1/metrics", generic, problems) else null
            val logsEndpoint = if (logsOtlp) endpointFor(env, "LOGS", "/v1/logs", generic, problems) else null

            val metricsHeaders = generalHeaders + parseHeaders(
                "OTEL_EXPORTER_OTLP_METRICS_HEADERS", env("OTEL_EXPORTER_OTLP_METRICS_HEADERS"), problems,
            )
            val logsHeaders = generalHeaders + parseHeaders(
                "OTEL_EXPORTER_OTLP_LOGS_HEADERS", env("OTEL_EXPORTER_OTLP_LOGS_HEADERS"), problems,
            )

            return OtelConfig(
                enabled = enabled,
                metricsEndpoint = metricsEndpoint?.takeIf { enabled },
                logsEndpoint = logsEndpoint?.takeIf { enabled },
                headers = generalHeaders,
                metricsHeaders = metricsHeaders,
                logsHeaders = logsHeaders,
                localFile = env("MIN_TELEMETRY_FILE")?.trim()?.takeIf { it.isNotEmpty() },
                metricIntervalMs = env("OTEL_METRIC_EXPORT_INTERVAL")?.toLongOrNull() ?: DEFAULT_METRIC_INTERVAL_MS,
                logIntervalMs = env("OTEL_LOGS_EXPORT_INTERVAL")?.toLongOrNull() ?: DEFAULT_LOG_INTERVAL_MS,
                resourceAttributes = parseHeaders("OTEL_RESOURCE_ATTRIBUTES", env("OTEL_RESOURCE_ATTRIBUTES"), problems),
                // 隐私开关默认全关（CC 原文）。助手回复未设时跟随用户提示词开关；设 0 可在提示词打开时仍脱敏回复
                logUserPrompts = flag("OTEL_LOG_USER_PROMPTS"),
                logAssistantResponses = env("OTEL_LOG_ASSISTANT_RESPONSES")?.trim()?.let { it == "1" } ?: flag("OTEL_LOG_USER_PROMPTS"),
                logToolDetails = flag("OTEL_LOG_TOOL_DETAILS"),
                includeSessionId = env("OTEL_METRICS_INCLUDE_SESSION_ID")?.trim() != "false",
                contentMaxLength = env("CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH")?.toIntOrNull()?.takeIf { it > 0 }
                    ?: DEFAULT_CONTENT_MAX_LENGTH,
                problems = problems,
            )
        }

        /** 选择器里有 otlp 才走 OTLP；none 与未设都不走；别的值（prometheus、console）说明不支持 */
        private fun wantsOtlp(name: String, value: String, problems: MutableList<String>): Boolean {
            val parts = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts) {
                if (p != "otlp" && p != "none") problems += "$name 的 $p 不支持，目前只有 otlp"
            }
            return "otlp" in parts
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
