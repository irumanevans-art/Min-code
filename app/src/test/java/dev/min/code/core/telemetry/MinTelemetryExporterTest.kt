package dev.min.code.core.telemetry

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SseEvent
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** 记下请求、按脚本回复（或抛错）的假传输 */
private class RecordingTransport(private val fail: Boolean = false) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        if (fail) throw IOException("unreachable")
        return HttpResponse(200, emptyList(), ByteArray(0))
    }

    override fun stream(request: HttpRequest): Flow<SseEvent> = error("遥测不用流式")
    override suspend fun open(request: HttpRequest): HttpStreamingResponse = error("遥测不用原样流")
}

class MinTelemetryExporterTest {
    private val sid = SessionId("sess1")
    private val turn = TurnId("t1")
    private val token = "TOKEN-NOT-IN-FILES"

    private fun dirs(): Pair<Path, Path> {
        val root = Files.createTempDirectory("min-telemetry")
        return root.resolve("sessions") to root.resolve("agent")
    }

    /** 造一条真的会话日志（经 FileSessionStore，和 App 用的是同一个存储） */
    private fun seedSession(sessionsDir: Path) = runBlocking {
        val store = FileSessionStore(sessionsDir) { 1_000L }
        val log = store.create(LogHeader(formatVersion = 1, sessionId = sid, createdAtMs = 0))
        log.append(listOf(
            TurnStarted(turn, TurnTrigger.User),
            TurnEnded(turn, TurnOutcome.Completed, Usage(inputTokens = 3), durationMs = 10),
        ), Durability.Sync)
    }

    private val enabled = TelemetrySettings(enabled = true, endpoint = "https://collector.example.com", headersConfigured = true)

    @Test
    fun `默认关时什么都不发，也不写本机文件`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        val transport = RecordingTransport()
        val report = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(
            TelemetrySettings(enabled = false, endpoint = "https://collector.example.com"), "Authorization=$token",
        )
        assertTrue(report.skipped)
        assertTrue(transport.requests.isEmpty())
        assertFalse(state.resolve("telemetry.jsonl").toFile().exists())
    }

    @Test
    fun `开了以后按端点发出，游标落盘，第二次只发新的`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        val transport = RecordingTransport()
        val exporter = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport)

        val first = exporter.export(enabled, "Authorization=Bearer%20$token")
        assertEquals(2, first.records)
        // 日志与 traces 各一次（回合收尾，interaction 这个 span 结束了）
        assertEquals(
            listOf("https://collector.example.com/v1/logs", "https://collector.example.com/v1/traces"),
            transport.requests.map { it.url },
        )
        assertTrue(transport.requests.all { it.headers.contains("Authorization" to "Bearer $token") })

        val second = exporter.export(enabled, "Authorization=Bearer%20$token")
        assertEquals(0, second.records)
        assertEquals(2, transport.requests.size)
        assertTrue(state.resolve("telemetry-cursors.json").toFile().exists())
    }

    @Test
    fun `没有端点时只写本机文件，每条一行`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        val transport = RecordingTransport()
        val report = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(
            TelemetrySettings(enabled = true, endpoint = ""), "",
        )
        assertEquals(2, report.records)
        assertTrue(transport.requests.isEmpty())
        val lines = state.resolve("telemetry.jsonl").toFile().readLines()
        assertEquals(3, lines.size)
        assertEquals(2, lines.count { it.contains("\"kind\":\"log\"") })
        assertEquals(1, lines.count { it.contains("\"kind\":\"span\"") })
    }

    @Test
    fun `端点不通时导出照常返回，失败计入报告，令牌不出现在报告与文件里`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        val transport = RecordingTransport(fail = true)
        val report = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(enabled, "Authorization=$token")
        assertEquals(2, report.failures) // 日志与 traces 两次都失败，各记一次
        assertFalse(report.toString().contains(token))
        assertFalse(state.toFile().walkTopDown().filter { it.isFile }.any { it.readText().contains(token) })
    }

    private fun row(line: String) = Json.parseToJsonElement(line).jsonObject
    // 本机文件里属性值是 OTLP 风格的带类型对象（{"stringValue": ...}）
    private fun JsonObject.attr(key: String) = this["attributes"]!!.jsonObject[key]?.jsonObject?.values?.first()?.jsonPrimitive?.content

    @Test
    fun `子会话一并导出，带父会话关联属性，span 挂到父会话的 Agent 工具下`() = runBlocking {
        val (sessions, state) = dirs()
        val store = FileSessionStore(sessions) { 1_000L }
        val parent = store.create(LogHeader(formatVersion = 1, sessionId = SessionId("p"), createdAtMs = 0))
        parent.append(listOf(
            TurnStarted(TurnId("t1"), TurnTrigger.User),
            ToolPlanned(TurnId("t1"), CallId("c9"), "Agent", JsonObject(emptyMap()), ResumePolicy.Unknown),
            ToolOutcome(TurnId("t1"), CallId("c9"), ToolResult(listOf(TextPart("done")))),
            TurnEnded(TurnId("t1"), TurnOutcome.Completed, Usage(), durationMs = 10),
        ), Durability.Sync)
        val child = store.create(LogHeader(formatVersion = 1, sessionId = SessionId("c1"), createdAtMs = 5, parent = SessionId("p")))
        child.append(listOf(
            SubagentOrigin(SessionId("p"), SessionId("p"), CallId("c9"), "Explore", fork = false, depth = 1, cacheKey = "k"),
            TurnStarted(TurnId("tc"), TurnTrigger.User),
            ToolPlanned(TurnId("tc"), CallId("k1"), "Read", JsonObject(emptyMap()), ResumePolicy.Replay),
            ToolOutcome(TurnId("tc"), CallId("k1"), ToolResult(listOf(TextPart("x")))),
            TurnEnded(TurnId("tc"), TurnOutcome.Completed, Usage(), durationMs = 5),
        ), Durability.Sync)

        MinTelemetryExporter(sessions.toFile(), state.toFile(), RecordingTransport()).export(TelemetrySettings(enabled = true), "")

        val rows = state.resolve("telemetry.jsonl").toFile().readLines().map { row(it) }
        val childRows = rows.filter { it.attr("session.id") == "c1" }
        assertTrue(childRows.isNotEmpty())
        assertTrue(childRows.all { it.attr("min.parent_session_id") == "p" })
        val tools = rows.filter { it["name"]?.jsonPrimitive?.content == "claude_code.tool" }
        val parentTool = tools.single { it.attr("session.id") == "p" }
        val childTool = tools.single { it.attr("session.id") == "c1" }
        assertEquals(parentTool["span_id"], childTool["parent_span_id"])
        assertEquals(parentTool["trace_id"], childTool["trace_id"])
    }

    @Test
    fun `回合还开着时导出，下一次补出完整的 interaction span（游标跨进程续导）`() = runBlocking {
        val (sessions, state) = dirs()
        val store = FileSessionStore(sessions) { 1_000L }
        val log = store.create(LogHeader(formatVersion = 1, sessionId = sid, createdAtMs = 0))
        log.append(listOf(TurnStarted(turn, TurnTrigger.User)), Durability.Sync)
        val transport = RecordingTransport()
        MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(enabled, "")
        assertFalse(state.resolve("telemetry.jsonl").toFile().readText().contains("\"kind\":\"span\""))

        log.append(listOf(TurnEnded(turn, TurnOutcome.Completed, Usage(), durationMs = 10)), Durability.Sync)
        // 新的导出器对象 = 新进程视角：只靠落盘的游标
        MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(enabled, "")
        val spans = state.resolve("telemetry.jsonl").toFile().readLines().filter { it.contains("\"kind\":\"span\"") }
        assertEquals(1, spans.size)
        assertTrue(spans[0].contains("claude_code.interaction"))
    }

    @Test
    fun `旧格式的游标文件（只有数字）仍然读得懂`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        Files.createDirectories(state)
        // 旧版写的是 {"会话 id": 下一条 seq}：2 = 第一条（TurnStarted）已经导过
        Files.writeString(state.resolve("telemetry-cursors.json"), "{\"sess1\":2}")
        val report = MinTelemetryExporter(sessions.toFile(), state.toFile(), RecordingTransport()).export(
            TelemetrySettings(enabled = true), "",
        )
        assertEquals(1, report.records)
    }
}
