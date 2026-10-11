package dev.min.agent.otel

import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files


class OtelExporterTest {
    private val token = "TOKEN-DO-NOT-LEAK-42"

    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://collector.example.com",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Bearer%20$token",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun cost(value: Double, model: String = "m") = OtelMetric(
        "claude_code.cost.usage", "USD", value,
        mapOf("model" to OtelValue.Text(model), "session.id" to OtelValue.Text("s1")), 1_000L,
    )

    private fun logItem(name: String) = OtelLog(
        name, mapOf("session.id" to OtelValue.Text("s1"), "event.sequence" to OtelValue.Whole(7)), 2_000L,
    )

    private fun body(request: HttpRequest): JsonObject =
        Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `默认关时什么都不导出：不发请求，也不建文件`() = runBlocking {
        val dir = Files.createTempDirectory("otel-off")
        val file = dir.resolve("out.jsonl").toFile()
        val transport = ScriptedTransport()
        val config = OtelConfig.fromEnv { if (it == "MIN_TELEMETRY_FILE") file.path else null }
        OtelExporter(config, transport).export(listOf(cost(0.1), logItem("claude_code.user_prompt")))
        assertTrue(transport.requests.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `开了以后：指标发到 v1 metrics，日志发到 v1 logs，头部带上令牌，请求体是 OTLP JSON`() = runBlocking {
        val transport = ScriptedTransport(OK, OK)
        val exporter = OtelExporter(config(), transport)
        exporter.export(listOf(cost(0.25), logItem("claude_code.api_error")))

        assertEquals(2, transport.requests.size)
        val metricsReq = transport.requests[0]
        val logsReq = transport.requests[1]
        assertEquals("https://collector.example.com/v1/metrics", metricsReq.url)
        assertEquals("https://collector.example.com/v1/logs", logsReq.url)
        assertEquals("POST", metricsReq.method)
        assertTrue(metricsReq.headers.contains("Content-Type" to "application/json"))
        assertTrue(metricsReq.headers.contains("Authorization" to "Bearer $token"))

        val scope = body(metricsReq)["resourceMetrics"]!!.jsonArray[0].jsonObject["scopeMetrics"]!!.jsonArray[0].jsonObject
        val metric = scope["metrics"]!!.jsonArray[0].jsonObject
        assertEquals("claude_code.cost.usage", metric["name"]!!.jsonPrimitive.content)
        assertEquals("USD", metric["unit"]!!.jsonPrimitive.content)
        val sum = metric["sum"]!!.jsonObject
        assertEquals(1, sum["aggregationTemporality"]!!.jsonPrimitive.content.toInt())
        val point = sum["dataPoints"]!!.jsonArray[0].jsonObject
        assertEquals(0.25, point["asDouble"]!!.jsonPrimitive.content.toDouble(), 0.0)
        // 1000 毫秒 = 1e9 纳秒（OTLP 的时间戳是纳秒，字符串形式）
        assertEquals("1000000000", point["timeUnixNano"]!!.jsonPrimitive.content)

        val logScope = body(logsReq)["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!.jsonArray[0].jsonObject
        val record = logScope["logRecords"]!!.jsonArray[0].jsonObject
        assertEquals("claude_code.api_error", record["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
        val attrs = record["attributes"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertTrue("event.name" in attrs)
        assertTrue("event.sequence" in attrs)
    }

    @Test
    fun `同一批里相同属性的指标先求和，成为一个数据点`() = runBlocking {
        val transport = ScriptedTransport(OK)
        OtelExporter(config(), transport).export(listOf(cost(0.1), cost(0.2), cost(0.3, model = "other")))
        val scope = body(transport.requests[0])["resourceMetrics"]!!.jsonArray[0].jsonObject["scopeMetrics"]!!.jsonArray[0].jsonObject
        val sum = scope["metrics"]!!.jsonArray[0].jsonObject["sum"]!!.jsonObject
        val points: JsonArray = sum["dataPoints"]!!.jsonArray
        assertEquals(2, points.size)
        assertEquals(0.3, points[0].jsonObject["asDouble"]!!.jsonPrimitive.content.toDouble(), 1e-12)
        assertEquals(0.3, points[1].jsonObject["asDouble"]!!.jsonPrimitive.content.toDouble(), 1e-12)
    }

    @Test
    fun `传输抛出 IO 错误时导出返回正常，只记一次失败，不带令牌`() = runBlocking {
        val transport = ScriptedTransport({ throw IOException("connect failed to $token") })
        val exporter = OtelExporter(config("OTEL_LOGS_EXPORTER" to "none"), transport)
        exporter.export(listOf(cost(0.1)))
        assertEquals(1, exporter.failures)
        assertFalse(exporter.lastFailure.orEmpty().contains(token))
    }

    @Test
    fun `收到非 2xx 时记失败并带状态码，不带响应正文`() = runBlocking {
        val transport = ScriptedTransport({ HttpResponse(503, emptyList(), "server says $token".toByteArray()) })
        val exporter = OtelExporter(config("OTEL_LOGS_EXPORTER" to "none"), transport)
        exporter.export(listOf(cost(0.1)))
        assertEquals(1, exporter.failures)
        assertTrue(exporter.lastFailure!!.contains("503"))
        assertFalse(exporter.lastFailure!!.contains(token))
    }

    @Test
    fun `取消要照常传上去，不能被导出吞掉`() {
        val transport = ScriptedTransport({ throw CancellationException("cancelled") })
        val exporter = OtelExporter(config("OTEL_LOGS_EXPORTER" to "none"), transport)
        try {
            runBlocking { exporter.export(listOf(cost(0.1))) }
            fail("取消被吞掉了")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun `本机文件：每条遥测一行 JSON，令牌不写进文件`() = runBlocking {
        val dir = Files.createTempDirectory("otel-file")
        val file = dir.resolve("sub/out.jsonl").toFile()
        val config = OtelConfig.fromEnv {
            when (it) {
                "CLAUDE_CODE_ENABLE_TELEMETRY" -> "1"
                "MIN_TELEMETRY_FILE" -> file.path
                "OTEL_EXPORTER_OTLP_HEADERS" -> "Authorization=$token"
                else -> null
            }
        }
        val transport = ScriptedTransport()
        OtelExporter(config, transport).export(listOf(cost(0.5), logItem("claude_code.user_prompt")))
        assertTrue(transport.requests.isEmpty())
        val lines = file.readLines(Charsets.UTF_8)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"kind\":\"metric\""))
        assertTrue(lines[1].contains("\"kind\":\"log\""))
        assertFalse(file.readText().contains(token))
    }

    @Test
    fun `文件写不进去也不抛出，只记失败`() = runBlocking {
        val dir = Files.createTempDirectory("otel-bad")
        // 把普通文件当成目录用，写入必然失败
        val blocker = dir.resolve("blocker").toFile().apply { writeText("x") }
        val config = OtelConfig.fromEnv { if (it == "CLAUDE_CODE_ENABLE_TELEMETRY") "1" else if (it == "MIN_TELEMETRY_FILE") blocker.resolve("x.jsonl").path else null }
        val exporter = OtelExporter(config, ScriptedTransport())
        exporter.export(listOf(cost(0.1)))
        assertEquals(1, exporter.failures)
        assertTrue(File(blocker.path).isFile)
    }
}
