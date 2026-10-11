package dev.min.agent.otel

import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 周期导出：用协程测试的虚拟时钟当「可控时钟」，不真等 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtelPeriodicTest {
    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun FakeSessionLog.turn(n: Int, at: Long) {
        add(at, TurnStarted(TurnId("t$n"), TurnTrigger.User))
        add(at + 1, TurnEnded(TurnId("t$n"), TurnOutcome.Completed, Usage(), durationMs = 1))
    }

    @Test
    fun `会话进行中按间隔导出：每过一个间隔把新事件发出去，没有新事件就不发`() = runTest {
        val log = FakeSessionLog("p")
        val cfg = config("OTEL_LOGS_EXPORT_INTERVAL" to "1000")
        val transport = ScriptedTransport(OK, OK, OK, OK)
        val tail = OtelTail(OtelSessionSource { _ -> listOf(log) }, cfg, OtelExporter(cfg, transport), clock = { currentTime })
        val job = launch { OtelPeriodic(tail, cfg, now = { currentTime }).run() }

        advanceTimeBy(999)
        assertTrue(transport.requests.isEmpty())

        log.turn(1, 10)
        advanceTimeBy(2) // 越过 1000ms 的第一个间隔
        assertEquals(1, transport.requests.size)

        // 再过一个间隔，没有新事件：不发
        advanceTimeBy(1000)
        assertEquals(1, transport.requests.size)

        log.turn(2, 20)
        advanceTimeBy(1000)
        assertEquals(2, transport.requests.size)
        job.cancel()
    }

    @Test
    fun `取消之后不再导出，缓冲里的留给收尾那次 pump`() = runTest {
        val log = FakeSessionLog("p")
        val cfg = config("OTEL_LOGS_EXPORT_INTERVAL" to "1000")
        val transport = ScriptedTransport(OK, OK)
        val tail = OtelTail(OtelSessionSource { _ -> listOf(log) }, cfg, OtelExporter(cfg, transport), clock = { currentTime })
        val job = launch { OtelPeriodic(tail, cfg, now = { currentTime }).run() }
        advanceTimeBy(500)
        job.cancel()
        log.turn(1, 10)
        advanceTimeBy(5000)
        assertTrue(transport.requests.isEmpty())
        // 进程退出时补一次：把没来得及导的都发出去
        tail.pump()
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `导出失败不停下周期循环，后面的间隔照常导出`() = runTest {
        val log = FakeSessionLog("p")
        val cfg = config("OTEL_LOGS_EXPORT_INTERVAL" to "1000")
        val exporter = OtelExporter(cfg, ScriptedTransport({ throw IOException("断网") }, OK))
        val tail = OtelTail(OtelSessionSource { _ -> listOf(log) }, cfg, exporter, clock = { currentTime })
        val job = launch { OtelPeriodic(tail, cfg, now = { currentTime }).run() }
        log.turn(1, 10)
        advanceTimeBy(1001)
        assertEquals(1, exporter.failures)
        log.turn(2, 20)
        advanceTimeBy(1000)
        assertEquals(1, exporter.failures) // 第二次成功
        job.cancel()
    }

    @Test
    fun `循环的节拍取已开启信号里最短的间隔，指标 60000 与日志 5000 并存时按 5000 走`() {
        val cfg = config("OTEL_METRICS_EXPORTER" to "otlp")
        assertEquals(5_000L, OtelPeriodic.tickMs(cfg))
        val fast = config("OTEL_LOGS_EXPORT_INTERVAL" to "200")
        assertEquals(OtelPeriodic.MIN_TICK_MS, OtelPeriodic.tickMs(fast))
    }
}
