package dev.min.code.core.telemetry

import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Durability
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SseEvent
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
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
        assertEquals(1, transport.requests.size)
        assertEquals("https://collector.example.com/v1/logs", transport.requests[0].url)
        assertTrue(transport.requests[0].headers.contains("Authorization" to "Bearer $token"))

        val second = exporter.export(enabled, "Authorization=Bearer%20$token")
        assertEquals(0, second.records)
        assertEquals(1, transport.requests.size)
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
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.contains("\"kind\":\"log\"") })
    }

    @Test
    fun `端点不通时导出照常返回，失败计入报告，令牌不出现在报告与文件里`() = runBlocking {
        val (sessions, state) = dirs()
        seedSession(sessions)
        val transport = RecordingTransport(fail = true)
        val report = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport).export(enabled, "Authorization=$token")
        assertEquals(1, report.failures)
        assertFalse(report.toString().contains(token))
        assertFalse(state.toFile().walkTopDown().filter { it.isFile }.any { it.readText().contains(token) })
    }
}
