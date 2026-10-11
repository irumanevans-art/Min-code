package dev.min.code.core.telemetry

import dev.min.agent.otel.OtelConfig
import dev.min.agent.otel.OtelExporter
import dev.min.agent.otel.OtelTail
import dev.min.agent.spi.HttpTransport
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CancellationException
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
            if (endpoint.isNotEmpty()) {
                put("OTEL_METRICS_EXPORTER", "otlp")
                put("OTEL_LOGS_EXPORTER", "otlp")
                put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/json")
                put("OTEL_EXPORTER_OTLP_ENDPOINT", endpoint)
                if (headers.isNotBlank()) put("OTEL_EXPORTER_OTLP_HEADERS", headers)
            }
        }
        val config = OtelConfig.fromEnv { env[it] }
        if (!config.active) return TelemetryExportReport(false, 0, 0, 0, null, config.problems)

        val exporter = OtelExporter(config, transport)
        val cursors = loadCursors().toMutableMap()
        var sessions = 0
        var records = 0
        try {
            val store = FileSessionStore(sessionsDir.toPath()) { now() }
            for (header in store.list()) {
                val id = header.sessionId
                val log = store.peek(id) ?: continue
                val tail = OtelTail(log, config, exporter, fromSeq = cursors[id.value] ?: 1L)
                records += tail.pump()
                cursors[id.value] = tail.nextSeq
                sessions++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读日志坏了也只是这次导出失败，不把错误抛给设置页
            exporter.noteFailure("读会话日志失败：${e.javaClass.simpleName}")
        }
        saveCursors(cursors)
        return TelemetryExportReport(false, sessions, records, exporter.failures, exporter.lastFailure, config.problems)
    }

    private fun loadCursors(): Map<String, Long> = try {
        if (!cursorFile.isFile) emptyMap() else {
            Json.parseToJsonElement(cursorFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
        }
    } catch (e: Exception) {
        // 游标文件坏了就从头来：最多重发一遍，比漏发好，且不影响会话
        emptyMap()
    }

    private fun saveCursors(cursors: Map<String, Long>) {
        try {
            stateDir.mkdirs()
            val json = JsonObject(cursors.mapValues { JsonPrimitive(it.value) })
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
