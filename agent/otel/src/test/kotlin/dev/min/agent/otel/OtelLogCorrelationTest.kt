package dev.min.agent.otel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原文：-p 与 SDK 会话带着入站 TRACEPARENT 时，事件日志记录带 trace_id 与 span_id（即使没开 traces 导出器）：
 * 在 interaction 里发出的记录带 interaction span 的 id，不在 interaction 里的带入站的 id。
 */
class OtelLogCorrelationTest {
    private val sid = "s1"
    private val inbound = TraceParent.parse("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")!!
    private var seq = 0L
    private fun rec(at: Long, e: dev.min.agent.spi.SessionEvent) = EventRecord(++seq, at, e)

    private fun events() = listOf(
        rec(900, InputReceived(ClientMsgId("c1"), listOf(TextPart("hi")), Delivery.Queue, InputOrigin.User)),
        rec(1000, TurnStarted(TurnId("t1"), TurnTrigger.User)),
        rec(1500, TurnEnded(TurnId("t1"), TurnOutcome.Completed, Usage(), durationMs = 500)),
        rec(2000, InputReceived(ClientMsgId("c2"), listOf(TextPart("again")), Delivery.Queue, InputOrigin.User)),
    )

    private fun logs(options: OtelOptions): List<OtelLog> {
        seq = 0
        val mapper = OtelMapper(options)
        return events().flatMap { mapper.feed(it) }.filterIsInstance<OtelLog>()
    }

    @Test
    fun `没有入站上下文时记录不带 trace_id 与 span_id`() {
        for (l in logs(OtelOptions(sessionId = sid))) {
            assertNull(l.traceId)
            assertNull(l.spanId)
        }
    }

    @Test
    fun `有入站上下文：回合里的记录带 interaction 的 id，回合外的带入站的 id`() {
        val out = logs(OtelOptions(sessionId = sid, inbound = inbound))
        val byName = out.groupBy { it.name }
        val interaction = SpanIds.interaction(sid, "t1")
        // 提示词在回合开始之前：不在 interaction 里，带入站的 id
        val prompts = byName.getValue(OtelNames.USER_PROMPT)
        assertEquals(inbound.spanId, prompts[0].spanId)
        assertEquals(inbound.traceId, prompts[0].traceId)
        // 回合里的 turn.started / turn.ended：interaction 的 span id、入站的 trace id
        for (name in listOf(OtelNames.MIN_TURN_STARTED, OtelNames.MIN_TURN_ENDED)) {
            val l = byName.getValue(name).single()
            assertEquals(name, inbound.traceId, l.traceId)
            assertEquals(name, interaction, l.spanId)
        }
        // 回合结束之后的下一条提示词：又回到入站的 id
        assertEquals(inbound.spanId, prompts[1].spanId)
    }

    @Test
    fun `子会话的记录不带（它们的 trace 要看父链，映射器不知道）`() {
        val lineage = OtelLineage("p", "p", "c9", "Explore", 1, false)
        for (l in logs(OtelOptions(sessionId = "kid", inbound = inbound, lineage = lineage))) {
            assertNull(l.traceId)
        }
    }

    @Test
    fun `OTLP 日志与本机文件都带这两个字段，没有时不写键`() {
        val out = logs(OtelOptions(sessionId = sid, inbound = inbound))
        val body = Json.parseToJsonElement(OtlpJson.logs(out, emptyList())).jsonObject
        val rec = body["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!.jsonArray[0].jsonObject["logRecords"]!!.jsonArray[0].jsonObject
        assertEquals(inbound.traceId, rec["traceId"]!!.jsonPrimitive.content)
        assertEquals(inbound.spanId, rec["spanId"]!!.jsonPrimitive.content)
        val line = Json.parseToJsonElement(OtlpJson.fileLine(out[0])).jsonObject
        assertEquals(inbound.traceId, line["trace_id"]!!.jsonPrimitive.content)
        val plain = logs(OtelOptions(sessionId = sid))
        val plainRec = Json.parseToJsonElement(OtlpJson.logs(plain, emptyList())).jsonObject["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!
            .jsonArray[0].jsonObject["logRecords"]!!.jsonArray[0].jsonObject
        assertTrue(!plainRec.containsKey("traceId") && !plainRec.containsKey("spanId"))
        assertTrue(!Json.parseToJsonElement(OtlpJson.fileLine(plain[0])).jsonObject.containsKey("trace_id"))
    }
}
