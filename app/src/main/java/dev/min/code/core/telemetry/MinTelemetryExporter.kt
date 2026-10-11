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

    suspend fun export(settings: TelemetrySettings, headers: String): TelemetryExportReport {
        // 默认关：关着就一个字节都不碰（不读日志、不发请求、不建文件）
        if (!settings.enabled) return TelemetryExportReport(true, 0, 0, 0, null, emptyList())
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
        val config = OtelConfig.fromEnv { env[it] }
        if (!config.active) return TelemetryExportReport(false, 0, 0, 0, null, config.problems)

        val exporter = OtelExporter(config, transport)
        val store = FileSessionStore(sessionsDir.toPath()) { now() }
        // 全部会话一次接进来：子 agent 的日志与父会话在同一个存储里，父子关系由日志头部的 parent 带出
        val tail = OtelTail(OtelStoreSessions.all(store), config, exporter, initialCursors = loadCursors())
        val records = tail.pump() // 出错只计入 exporter 的失败数，不抛（取消除外）
        saveCursors(tail.cursors())
        return TelemetryExportReport(false, tail.sessionCount, records, exporter.failures, exporter.lastFailure, config.problems)
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
        const val LOCAL_FILE_NAME = "telemetry.jsonl"
        const val CURSOR_FILE_NAME = "telemetry-cursors.json"
    }
}
