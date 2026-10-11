package dev.min.agent.otel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** traces 的 OTLP 编码与导出，以及 console 导出器的输出形状 */
class OtelTracesExportTest {
    private val token = "TRACE-TOKEN-DO-NOT-LEAK"

    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://collector.example.com",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Bearer%20$token",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun span(error: String? = null, parent: String? = "b".repeat(16)) = OtelSpan(
        name = "claude_code.tool", traceId = "a".repeat(32), spanId = "c".repeat(16), parentSpanId = parent,
        startMs = 1_000, endMs = 1_500,
        attributes = mapOf("tool_name" to OtelValue.Text("Bash"), "duration_ms" to OtelValue.Real(500.0), "success" to OtelValue.Flag(true)),
        error = error,
    )

    private fun metric() = OtelMetric("claude_code.token.usage", "tokens", 5.0, mapOf("type" to OtelValue.Text("input")), 1_000L)
    private fun log() = OtelLog("claude_code.user_prompt", mapOf("session.id" to OtelValue.Text("s1")), 1_200L)

    private fun body(transport: ScriptedTransport, i: Int = 0): JsonObject =
        Json.parseToJsonElement(transport.requests[i].body!!.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `span 发到 v1 traces：十六进制 id、纳秒时间戳、kind、属性类型，头部带令牌`() = runBlocking {
        val transport = ScriptedTransport(OK)
        OtelExporter(config(), transport).export(listOf(span()))

        assertEquals(1, transport.requests.size)
        val req = transport.requests[0]
        assertEquals("https://collector.example.com/v1/traces", req.url)
        assertTrue(req.headers.contains("Authorization" to "Bearer $token"))
        val s = body(transport)["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        assertEquals("a".repeat(32), s["traceId"]!!.jsonPrimitive.content)
        assertEquals("c".repeat(16), s["spanId"]!!.jsonPrimitive.content)
        assertEquals("b".repeat(16), s["parentSpanId"]!!.jsonPrimitive.content)
        assertEquals("claude_code.tool", s["name"]!!.jsonPrimitive.content)
        assertEquals("1", s["kind"]!!.jsonPrimitive.content)
        assertEquals("1000000000", s["startTimeUnixNano"]!!.jsonPrimitive.content)
        assertEquals("1500000000", s["endTimeUnixNano"]!!.jsonPrimitive.content)
        assertFalse(s.containsKey("status"))
        val attrs = s["attributes"]!!.jsonArray.associate { it.jsonObject["key"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject }
        assertEquals("Bash", attrs["tool_name"]!!["stringValue"]!!.jsonPrimitive.content)
        assertEquals("500.0", attrs["duration_ms"]!!["doubleValue"]!!.jsonPrimitive.content)
        assertEquals("true", attrs["success"]!!["boolValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `根 span 没有 parentSpanId，失败的 span 状态为 ERROR（code 2）并带说明`() = runBlocking {
        val transport = ScriptedTransport(OK)
        OtelExporter(config(), transport).export(listOf(span(error = "boom", parent = null)))
        val s = body(transport)["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        assertFalse(s.containsKey("parentSpanId"))
        assertEquals("2", s["status"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("boom", s["status"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `traces 是 beta：没开 beta 开关时，递来的 span 也不发、不写文件`() = runBlocking {
        val dir = Files.createTempDirectory("otel-nobeta")
        val file = dir.resolve("out.jsonl").toFile()
        val transport = ScriptedTransport()
        val config = config("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "", "MIN_TELEMETRY_FILE" to file.path)
        OtelExporter(config, transport).export(listOf(span()))
        assertTrue(transport.requests.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `span 导出失败只计数，说明里没有令牌；本机文件里 span 一行一条`() = runBlocking {
        val dir = Files.createTempDirectory("otel-spans-file")
        val file = dir.resolve("out.jsonl").toFile()
        val transport = ScriptedTransport({ throw IOException("连不上 Bearer $token") })
        val exporter = OtelExporter(config("MIN_TELEMETRY_FILE" to file.path), transport)
        exporter.export(listOf(span(), span(error = "x")))
        assertEquals(1, exporter.failures)
        assertFalse(exporter.lastFailure!!.contains(token))
        val lines = file.readLines()
        assertEquals(2, lines.size)
        val first = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("span", first["kind"]!!.jsonPrimitive.content)
        assertEquals("claude_code.tool", first["name"]!!.jsonPrimitive.content)
        assertEquals("a".repeat(32), first["trace_id"]!!.jsonPrimitive.content)
        assertEquals("b".repeat(16), first["parent_span_id"]!!.jsonPrimitive.content)
        assertFalse(lines.joinToString().contains(token))
    }


    // ---- span 事件、数组属性、tracestate ----

    @Test
    fun `span 事件与 tracestate 与数组属性按 OTLP 形状发出，本机文件也带事件`() = runBlocking {
        val dir = Files.createTempDirectory("otel-events")
        val file = dir.resolve("out.jsonl").toFile()
        val transport = ScriptedTransport(OK)
        val withEvents = span().copy(
            attributes = mapOf("gen_ai.response.finish_reasons" to OtelValue.Texts(listOf("end_turn"))),
            events = listOf(OtelSpanEvent("gen_ai.request.attempt", 1_200, mapOf("attempt" to OtelValue.Whole(2)))),
            traceState = "vendor=x",
        )
        OtelExporter(config("MIN_TELEMETRY_FILE" to file.path), transport).export(listOf(withEvents))
        val s = body(transport)["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        assertEquals("vendor=x", s["traceState"]!!.jsonPrimitive.content)
        val event = s["events"]!!.jsonArray.single().jsonObject
        assertEquals("gen_ai.request.attempt", event["name"]!!.jsonPrimitive.content)
        assertEquals("1200000000", event["timeUnixNano"]!!.jsonPrimitive.content)
        assertEquals("2", event["attributes"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject["intValue"]!!.jsonPrimitive.content)
        val reasons = s["attributes"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject["arrayValue"]!!.jsonObject["values"]!!.jsonArray
        assertEquals("end_turn", reasons.single().jsonObject["stringValue"]!!.jsonPrimitive.content)

        val line = Json.parseToJsonElement(file.readLines().single()).jsonObject
        assertEquals("vendor=x", line["trace_state"]!!.jsonPrimitive.content)
        assertEquals("gen_ai.request.attempt", line["events"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有事件的 span 不带 events 与 traceState 键`() = runBlocking {
        val transport = ScriptedTransport(OK)
        OtelExporter(config(), transport).export(listOf(span()))
        val s = body(transport)["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0].jsonObject["spans"]!!.jsonArray[0].jsonObject
        assertFalse(s.containsKey("events"))
        assertFalse(s.containsKey("traceState"))
    }

    // ---- console 导出器 ----

    private fun consoleConfig(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
            "OTEL_METRICS_EXPORTER" to "console",
            "OTEL_LOGS_EXPORTER" to "console",
            "OTEL_TRACES_EXPORTER" to "console",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    @Test
    fun `console 导出器：每条一行 JSON，三种信号各自的形状，不发任何请求`() = runBlocking {
        val out = mutableListOf<String>()
        val transport = ScriptedTransport()
        OtelExporter(consoleConfig(), transport, console = { out += it }).export(listOf(metric(), log(), span()))
        assertTrue(transport.requests.isEmpty())
        assertEquals(3, out.size)
        val byKind = out.map { Json.parseToJsonElement(it).jsonObject }.associateBy { it["kind"]!!.jsonPrimitive.content }
        assertEquals(setOf("metric", "log", "span"), byKind.keys)
        assertEquals("claude_code.token.usage", byKind["metric"]!!["name"]!!.jsonPrimitive.content)
        assertEquals("5.0", byKind["metric"]!!["value"]!!.jsonPrimitive.content)
        assertEquals("claude_code.user_prompt", byKind["log"]!!["name"]!!.jsonPrimitive.content)
        assertEquals("claude_code.tool", byKind["span"]!!["name"]!!.jsonPrimitive.content)
        // 一行里不许有换行：逐行消费的脚本才读得对
        assertTrue(out.none { '\n' in it })
    }

    @Test
    fun `console 只对选了 console 的信号打印`() = runBlocking {
        val out = mutableListOf<String>()
        val config = consoleConfig("OTEL_METRICS_EXPORTER" to "none", "OTEL_TRACES_EXPORTER" to "none")
        OtelExporter(config, ScriptedTransport(), console = { out += it }).export(listOf(metric(), log(), span()))
        assertEquals(1, out.size)
        assertTrue(out[0].contains("\"kind\":\"log\""))
    }

    @Test
    fun `console 与 otlp 可以同时选：既打印也发`() = runBlocking {
        val out = mutableListOf<String>()
        val transport = ScriptedTransport(OK)
        val config = config("OTEL_TRACES_EXPORTER" to "console,otlp")
        OtelExporter(config, transport, console = { out += it }).export(listOf(span()))
        assertEquals(1, out.size)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `console 的输出口抛错也不影响导出方（只计数）`() = runBlocking {
        val exporter = OtelExporter(consoleConfig(), ScriptedTransport(), console = { throw IOException("管道断了") })
        exporter.export(listOf(log()))
        assertTrue(exporter.failures >= 1)
    }
}
