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
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SseEvent
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** 记下请求的假传输 */
private class CountingTransport : HttpTransport {
    val requests = CopyOnWriteArrayList<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return HttpResponse(200, emptyList(), ByteArray(0))
    }

    override fun stream(request: HttpRequest): Flow<SseEvent> = error("遥测不用流式")
    override suspend fun open(request: HttpRequest): HttpStreamingResponse = error("遥测不用原样流")
    fun bodies(suffix: String): List<String> = requests.filter { it.url.endsWith(suffix) }.map { it.body!!.toString(Charsets.UTF_8) }
}

/**
 * 自动导出的节奏：有会话在跑回合时按间隔导出，闲下来补发，闲着时什么都不做；不碰历史，不重复。
 * 节拍用毫秒级的小值、时间来源加了偏移（让「到间隔」立刻成立），不真等 5 秒。
 */
class AppTelemetryTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val enabled = TelemetrySettings(enabled = true, endpoint = "https://collector.example.com")
    private lateinit var sessions: Path
    private lateinit var state: Path
    private lateinit var store: FileSessionStore

    private fun setup() {
        val root = Files.createTempDirectory("min-app-telemetry")
        sessions = root.resolve("sessions")
        state = root.resolve("agent")
        store = FileSessionStore(sessions) { System.currentTimeMillis() }
    }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(10) }
        assertTrue(what, cond())
    }

    private suspend fun newSession(id: String): SessionLog =
        store.create(LogHeader(formatVersion = 1, sessionId = SessionId(id), createdAtMs = System.currentTimeMillis() - 60_000))

    private suspend fun SessionLog.turn(n: Int) = append(
        listOf(TurnStarted(TurnId("t$n"), TurnTrigger.User), TurnEnded(TurnId("t$n"), TurnOutcome.Completed, Usage(), durationMs = 10)),
        Durability.Sync,
    )

    private class Rig(
        val transport: CountingTransport,
        val busy: MutableStateFlow<Boolean>,
        val settings: MutableStateFlow<TelemetrySettings>,
        val telemetry: AppTelemetry,
    )

    private fun rig(initial: TelemetrySettings = enabled): Rig {
        val transport = CountingTransport()
        val busy = MutableStateFlow(false)
        val settings = MutableStateFlow(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val telemetry = AppTelemetry(
            scope = scope,
            exporter = MinTelemetryExporter(sessions.toFile(), state.toFile(), transport),
            settings = settings,
            headers = { "" },
            busy = busy,
            tickMs = 20,
            // 让每个信号的间隔都立刻到点：真时间加一个很大的偏移
            now = { System.currentTimeMillis() + 3_600_000 },
        )
        telemetry.start()
        return Rig(transport, busy, settings, telemetry)
    }

    @Test
    fun `没有会话在跑时什么都不做：不读日志、不发请求、不建文件`() = run {
        setup()
        val log = newSession("s1")
        log.turn(1)
        val r = rig()
        delay(300) // 远多于一个节拍
        assertTrue(r.transport.requests.isEmpty())
        assertFalse(state.resolve("telemetry.jsonl").toFile().exists())
        assertFalse(state.resolve("telemetry-cursors.json").toFile().exists())
    }

    @Test
    fun `会话在跑时按节拍导出新事件，不带已有的历史`() = run {
        setup()
        val log = newSession("s1")
        log.turn(1) // 打开开关之前的历史：自动导出不碰
        val r = rig()
        r.busy.value = true
        // 阶段开始后的新事件
        delay(100)
        log.turn(2)
        waitUntil("新回合的日志被导出") { r.transport.bodies("/v1/logs").any { it.contains("min.turn.ended") } }
        val allLogs = r.transport.bodies("/v1/logs").joinToString("\n")
        // 只有第 2 个回合：turn.ended 一条（历史里的第 1 个回合不在其中）
        assertEquals(1, Regex("\"body\":\\{\"stringValue\":\"min\\.turn\\.ended\"\\}").findAll(allLogs).count())
        assertTrue("游标落盘了", state.resolve("telemetry-cursors.json").toFile().exists())
    }

    @Test
    fun `会话闲下来时把没发的补发完，之后不再有节拍`() = run {
        setup()
        val log = newSession("s1")
        val r = rig()
        r.busy.value = true
        delay(100)
        log.turn(1)
        r.busy.value = false
        waitUntil("闲下来补发") { r.transport.bodies("/v1/logs").any { it.contains("min.turn.ended") } }
        val before = r.transport.requests.size
        delay(300)
        assertEquals("闲着时没有节拍、没有新请求", before, r.transport.requests.size)
    }

    @Test
    fun `关着遥测时即使会话在跑也什么都不发`() = run {
        setup()
        val log = newSession("s1")
        val r = rig(TelemetrySettings(enabled = false, endpoint = "https://collector.example.com"))
        r.busy.value = true
        delay(100)
        log.turn(1)
        delay(300)
        assertTrue(r.transport.requests.isEmpty())
        assertFalse(state.resolve("telemetry.jsonl").toFile().exists())
    }

    @Test
    fun `中途关掉遥测：阶段取消时补发一次，之后不再导出`() = run {
        setup()
        val log = newSession("s1")
        val r = rig()
        r.busy.value = true
        delay(100)
        log.turn(1)
        r.settings.value = enabled.copy(enabled = false)
        waitUntil("取消时补发") { r.transport.bodies("/v1/logs").any { it.contains("min.turn.ended") } }
        val before = r.transport.requests.size
        log.turn(2)
        delay(300)
        assertEquals(before, r.transport.requests.size)
    }

    @Test
    fun `手动立即导出与自动导出共用一份进度，同一条事件不会发两次`() = run {
        setup()
        val log = newSession("s1")
        val r = rig()
        r.busy.value = true
        delay(250) // 等阶段把会话开好（立即导出要用它的进度）
        log.turn(1)
        val report = r.telemetry.exportNow(enabled, "")
        assertFalse(report.skipped)
        r.busy.value = false
        delay(300)
        // turn.ended 只出现在一次日志请求里
        val ended = r.transport.bodies("/v1/logs").count { it.contains("min.turn.ended") }
        assertEquals(1, ended)
    }

    @Test
    fun `没有自动阶段时，立即导出仍是单次导出，从头读没有游标的会话`() = run {
        setup()
        val log = newSession("s1")
        log.turn(1)
        val r = rig()
        val report = r.telemetry.exportNow(enabled, "")
        assertEquals(2, report.records)
        assertTrue(r.transport.bodies("/v1/logs").any { it.contains("min.turn.ended") })
    }

    @Test
    fun `配置来源：关着是 null，开着是同一套解析，设置不变时复用`() {
        val file = Files.createTempFile("tp", ".jsonl").toFile()
        var current: TelemetrySettings? = null
        val source = TelemetryConfigSource({ current }, file)
        assertEquals(null, source.current())
        current = TelemetrySettings(enabled = false)
        assertEquals(null, source.current())
        current = enabled
        val a = source.current()!!
        assertTrue(a.tracesEnabled) // App 里遥测开 = 同意 traces
        assertTrue(a.propagateTraceparent)
        assertTrue(source.current() === a)
        current = enabled.copy(endpoint = "https://other.example.com")
        assertFalse(source.current() === a)
    }
}
