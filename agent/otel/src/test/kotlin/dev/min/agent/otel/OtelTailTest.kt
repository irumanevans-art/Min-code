package dev.min.agent.otel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.protocol.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 只读的假日志：记下读写次数，测试据此断言「导出端不写日志」 */
private class FakeLog(private val sessionId: String = "s1") : SessionLog {
    val records = mutableListOf<EventRecord>()
    var reads = 0
    var appends = 0

    override val header: LogHeader = LogHeader(formatVersion = 1, sessionId = SessionId(sessionId), createdAtMs = 0)

    override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
        appends++
        return emptyList()
    }

    override fun read(fromSeq: Long): Flow<EventRecord> {
        reads++
        return records.filter { it.seq >= fromSeq }.asFlow()
    }

    fun add(event: SessionEvent) {
        records += EventRecord(seq = records.size + 1L, atMs = 1_000L + records.size, event = event)
    }
}

class OtelTailTest {
    private val turn = TurnId("t1")

    private fun logsOn(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    /** 请求体里的日志名字，按发送顺序 */
    private fun names(transport: ScriptedTransport): List<List<String>> = transport.requests.map { req ->
        val body = Json.parseToJsonElement(req.body!!.toString(Charsets.UTF_8)).jsonObject
        body["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!.jsonArray[0].jsonObject["logRecords"]!!.jsonArray
            .map { it.jsonObject["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content }
    }

    @Test
    fun `只导出游标之后的记录，追加后再 pump 只导出新的，不重复`() = runBlocking {
        val log = FakeLog()
        log.add(InputReceived(ClientMsgId("c1"), listOf(TextPart("hi")), Delivery.Queue, InputOrigin.User)) // seq 1，游标之前
        log.add(TurnStarted(turn, TurnTrigger.User)) // seq 2
        log.add(TurnEnded(turn, TurnOutcome.Completed, Usage(), durationMs = 5)) // seq 3
        val transport = ScriptedTransport(OK, OK)
        val tail = OtelTail(log, logsOn(), OtelExporter(logsOn(), transport), fromSeq = 2)

        assertEquals(2, tail.pump())
        assertEquals(listOf(listOf("min.turn.started", "min.turn.ended")), names(transport))

        log.add(TurnStarted(turn, TurnTrigger.Resume)) // seq 4
        assertEquals(1, tail.pump())
        assertEquals(listOf("min.turn.started", "min.turn.ended"), names(transport)[0])
        assertEquals(listOf("min.turn.started"), names(transport)[1])
        assertEquals(0, log.appends)
    }

    @Test
    fun `遥测关闭时不读日志、不发请求`() = runBlocking {
        val log = FakeLog()
        log.add(TurnStarted(turn, TurnTrigger.User))
        val transport = ScriptedTransport()
        val off = OtelConfig.fromEnv { null }
        assertEquals(0, OtelTail(log, off, OtelExporter(off, transport)).pump())
        assertEquals(0, log.reads)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `导出失败不影响会话：pump 正常返回，日志不被写，失败计数可见`() = runBlocking {
        val log = FakeLog()
        log.add(TurnStarted(turn, TurnTrigger.User))
        val config = logsOn()
        val transport = ScriptedTransport({ throw IOException("unreachable") })
        val exporter = OtelExporter(config, transport)
        val tail = OtelTail(log, config, exporter)

        assertEquals(1, tail.pump())
        assertEquals(1, exporter.failures)
        // 游标照样前进：失败的那批不重发（至多一次导出，失败只计数）
        assertEquals(0, tail.pump())
        assertEquals(0, log.appends)
    }

    @Test
    fun `协程取消照常传上去`() {
        val log = FakeLog()
        log.add(TurnStarted(turn, TurnTrigger.User))
        val transport = ScriptedTransport({ throw CancellationException("stop") })
        val config = logsOn()
        try {
            runBlocking { OtelTail(log, config, OtelExporter(config, transport)).pump() }
            fail("取消被吞掉了")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
