package dev.min.code.core.telemetry

import dev.min.agent.otel.OtelConfig
import dev.min.agent.otel.OtelCursor
import dev.min.agent.otel.OtelExporter
import dev.min.agent.otel.OtelStoreSessions
import dev.min.agent.otel.OtelTail
import dev.min.agent.spi.HttpTransport
import dev.min.agent.store.FileSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 手机上的遥测设置（开关与端点）。头部令牌不在这里：它由 SettingsStore 用 Keystore 加密保存，界面只知道「已设置」。
 */
data class TelemetrySettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val headersConfigured: Boolean = false,
)

/** 一次「立即导出」的结果，界面据此说明发了多少、失败没有 */
class TelemetryExportReport(
    val skipped: Boolean,
    val sessions: Int,
    val records: Int,
    val failures: Int,
    val lastFailure: String?,
    val problems: List<String>,
)

/**
 * 把会话日志里的新事件导出到 OTLP 端点，并写一份本机 JSONL（[LOCAL_FILE_NAME]，App 私有目录，可用于分享）。
 *
 * 为什么把设置翻译成 CC 的环境变量再交给 [OtelConfig.fromEnv]：桌面 CLI 与手机是同一套解析与默认值，
 * 不会两边各写一份而慢慢不一致。
 *
 * 为什么游标要落盘：「立即导出」按下一次就是一次新进程视角，不存游标会把整段历史反复发出去。
 */
class MinTelemetryExporter(
    private val sessionsDir: File,
    private val stateDir: File,
    private val transport: HttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cursorFile = File(stateDir, CURSOR_FILE_NAME)
    private val localFile = File(stateDir, LOCAL_FILE_NAME)

    /**
     * 开一个导出会话（配置 + 读游标的 tail + 导出器），调用方自己决定何时 [TelemetrySession.tick] / [TelemetrySession.finish]。
     * 关着、或没有任何目的地返回 null（默认关：不读日志、不发请求、不建文件）。
     *
     * [baseline]：自动导出用。从没导出过（没有游标文件）时，已有的老会话从日志末尾起，不把历史一股脑发出去——
     * 用户只是打开了开关，没有要求发旧会话；要发旧会话得自己按「立即导出」（[export]，从头读）。
     */
    fun open(settings: TelemetrySettings, headers: String, baseline: Boolean = false): TelemetrySession? {
        if (!settings.enabled) return null
        val config = configOf(settings, headers, localFile)
        if (!config.active) return null
        val exporter = OtelExporter(config, transport)
        val store = FileSessionStore(sessionsDir.toPath()) { now() }
        val replayFromMs = if (baseline && !cursorFile.isFile) now() else null
        // 全部会话一次接进来：子 agent 的日志与父会话在同一个存储里，父子关系由日志头部的 parent 带出
        val tail = OtelTail(OtelStoreSessions.all(store), config, exporter, initialCursors = loadCursors(), replayFromMs = replayFromMs)
        return TelemetrySession(config, tail, exporter) { saveCursors(it) }
    }

    /** 「立即导出」：读完新记录、全部发出去，游标落盘。从头读（没有游标的会话从头） */
    suspend fun export(settings: TelemetrySettings, headers: String): TelemetryExportReport {
        // 默认关：关着就一个字节都不碰（不读日志、不发请求、不建文件）
        if (!settings.enabled) return TelemetryExportReport(true, 0, 0, 0, null, emptyList())
        val session = open(settings, headers)
            ?: return TelemetryExportReport(false, 0, 0, 0, null, configOf(settings, headers, localFile).problems)
        return session.finish()
    }

    /** 本机 JSONL 的位置：设置页「分享」据此取文件 */
    val localFilePath: File get() = localFile

    /**
     * 游标文件：`{"会话 id": {"next": N, "spansFrom": M}}`。旧版只存数字（`{"会话 id": N}`），读到数字就当两个游标相同。
     */
    private fun loadCursors(): Map<String, OtelCursor> = try {
        if (!cursorFile.isFile) emptyMap() else {
            Json.parseToJsonElement(cursorFile.readText()).jsonObject.mapValues { (_, v) ->
                if (v is JsonObject) {
                    OtelCursor(v["next"]!!.jsonPrimitive.content.toLong(), v["spansFrom"]!!.jsonPrimitive.content.toLong())
                } else {
                    val n = v.jsonPrimitive.content.toLong()
                    OtelCursor(n, n)
                }
            }
        }
    } catch (e: Exception) {
        // 游标文件坏了就从头来：最多重发一遍，比漏发好，且不影响会话
        emptyMap()
    }

    private fun saveCursors(cursors: Map<String, OtelCursor>) {
        try {
            stateDir.mkdirs()
            val json = JsonObject(cursors.mapValues { (_, c) ->
                JsonObject(mapOf("next" to JsonPrimitive(c.next), "spansFrom" to JsonPrimitive(c.spansFrom)))
            })
            cursorFile.writeText(json.toString())
        } catch (e: java.io.IOException) {
            // 存不下游标只意味着下次可能重发，不值得让导出失败
        }
    }

    companion object {
        /**
         * 设置 → 配置。把手机上的设置翻译成 CC 的环境变量再交给 [OtelConfig.fromEnv]：桌面 CLI 与手机是同一套解析与默认值。
         * 导出与子进程的 TRACEPARENT（[TelemetryConfigSource]）都从这里取，口径不会分叉。
         */
        fun configOf(settings: TelemetrySettings, headers: String, localFile: File): OtelConfig {
            val endpoint = settings.endpoint.trim()
            val env = buildMap {
                put("CLAUDE_CODE_ENABLE_TELEMETRY", "1")
                put("MIN_TELEMETRY_FILE", localFile.path)
                // traces 是 beta，需要这个开关；App 里「遥测开」就是用户的同意，span 里的提示词与工具入参仍默认脱敏
                put("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA", "1")
                if (endpoint.isNotEmpty()) {
                    put("OTEL_METRICS_EXPORTER", "otlp")
                    put("OTEL_LOGS_EXPORTER", "otlp")
                    put("OTEL_TRACES_EXPORTER", "otlp")
                    put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")
                    put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint)
                    if (headers.isNotBlank()) put("OTEL_EXPORTER_OTLP_HEADERS", headers)
                }
            }
            return OtelConfig.fromEnv { env[it] }
        }

        const val LOCAL_FILE_NAME = "telemetry.jsonl"
        const val CURSOR_FILE_NAME = "telemetry-cursors.json"
    }
}

/**
 * 当前生效的遥测配置（App 设置随时可改，所以是个函数）。遥测关着返回 null。
 * 给内核的 `traceContext` 用：Bash 与 hook 子进程要不要带 TRACEPARENT，按「现在的设置」判断，与导出用同一个 [MinTelemetryExporter.configOf]。
 * 设置没变时复用上一次解析的结果：每次 Bash 调用都问一遍，不该每次都重新解析环境。
 */
class TelemetryConfigSource(
    private val settings: () -> TelemetrySettings?,
    private val localFile: File,
) {
    private var last: Pair<TelemetrySettings, OtelConfig>? = null

    @Synchronized
    fun current(): OtelConfig? {
        val s = settings()?.takeIf { it.enabled } ?: return null
        last?.let { (cached, config) -> if (cached == s) return config }
        // 头部令牌这里用不到（只判断要不要传播），不取
        return MinTelemetryExporter.configOf(s, "", localFile).also { last = s to it }
    }
}

/**
 * 一个正在用的导出会话。[tick]：按各信号的间隔导出（自动导出的节拍）；[finish]：读完新记录、立刻全部发出去（闲下来、手动导出）。
 * 两者都把游标落盘，进程被杀后下次从这里续，不重发已发的指标与日志。
 */
class TelemetrySession internal constructor(
    val config: OtelConfig,
    private val tail: OtelTail,
    private val exporter: OtelExporter,
    private val saveCursors: (Map<String, OtelCursor>) -> Unit,
) {
    /** 读新记录，并把已到间隔的信号发出去 */
    suspend fun tick(nowMs: Long) {
        tail.collect()
        tail.flushDue(nowMs)
        saveCursors(tail.cursors())
    }

    suspend fun finish(): TelemetryExportReport {
        val records = tail.pump()
        saveCursors(tail.cursors())
        return TelemetryExportReport(false, tail.sessionCount, records, exporter.failures, exporter.lastFailure, config.problems)
    }
}
