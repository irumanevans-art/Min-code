package dev.min.agent.otel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.SessionLog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 子会话纳入导出、span 跨会话挂靠、续导游标、按信号的导出间隔 */
class OtelTailTreeTest {
    private val model = ModelRef("anthropic", "claude-test")
    private val usage = Usage(inputTokens = 10, outputTokens = 5)

    private fun config(vararg extra: Pair<String, String>): OtelConfig {
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
        )
        val merged = base + extra.toMap()
        return OtelConfig.fromEnv { merged[it] }
    }

    private fun sourceOf(vararg logs: SessionLog) = OtelSessionSource { _ -> logs.toList() }

    // ---- 日志的小构造 ----

    private fun FakeSessionLog.created(at: Long) = add(at, SessionCreated(GuestPath("/w"), model, PermissionMode.Default))
    private fun FakeSessionLog.origin(parent: String, root: String, call: String, depth: Int, type: String = "Explore") = add(
        1, SubagentOrigin(SessionId(parent), SessionId(root), CallId(call), type, fork = false, depth = depth, cacheKey = "k"),
    )

    /** 一个回合：一次模型请求，可选地带一次工具调用（调用在回合里完成） */
    private fun FakeSessionLog.turn(n: Int, t0: Long, tool: String? = null, toolName: String = "Agent", onTool: () -> Unit = {}) {
        val tid = TurnId("t$n")
        add(t0, TurnStarted(tid, TurnTrigger.User))
        add(t0 + 10, ModelRequestStarted(tid, RequestId("r$n"), 1, model, "fp"))
        add(t0 + 50, ModelCharged(model, usage, 0.01, "USD"))
        val parts = listOfNotNull(TextPart("ok"), tool?.let { ToolCallPart(CallId(it), toolName, JsonObject(emptyMap())) })
        add(t0 + 60, AssistantMessageCompleted(tid, RequestId("r$n"), MessageId("m$n"), parts, usage, if (tool != null) StopReason.ToolUse else StopReason.EndTurn))
        if (tool != null) {
            add(t0 + 70, ToolPlanned(tid, CallId(tool), toolName, JsonObject(emptyMap()), ResumePolicy.Unknown))
            add(t0 + 80, ToolEffectPending(tid, CallId(tool)))
            onTool()
            add(t0 + 900, ToolOutcome(tid, CallId(tool), ToolResult(listOf(TextPart("done")))))
        }
        add(t0 + 1000, TurnEnded(tid, TurnOutcome.Completed, usage, durationMs = 1000))
    }

    // ---- 请求体解析 ----

    private class Sent(val metrics: List<JsonObject>, val logs: List<JsonObject>, val spans: List<JsonObject>)

    private fun sent(transport: ScriptedTransport): Sent {
        val m = mutableListOf<JsonObject>()
        val l = mutableListOf<JsonObject>()
        val s = mutableListOf<JsonObject>()
        for (req in transport.requests) {
            val body = Json.parseToJsonElement(req.body!!.toString(Charsets.UTF_8)).jsonObject
            when {
                req.url.endsWith("/v1/metrics") -> m += body["resourceMetrics"]!!.jsonArray[0].jsonObject["scopeMetrics"]!!.jsonArray[0]
                    .jsonObject["metrics"]!!.jsonArray.map { it.jsonObject }
                req.url.endsWith("/v1/logs") -> l += body["resourceLogs"]!!.jsonArray[0].jsonObject["scopeLogs"]!!.jsonArray[0]
                    .jsonObject["logRecords"]!!.jsonArray.map { it.jsonObject }
                req.url.endsWith("/v1/traces") -> s += body["resourceSpans"]!!.jsonArray[0].jsonObject["scopeSpans"]!!.jsonArray[0]
                    .jsonObject["spans"]!!.jsonArray.map { it.jsonObject }
            }
        }
        return Sent(m, l, s)
    }

    private fun JsonObject.attr(key: String): String? {
        val kv = this["attributes"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["key"]!!.jsonPrimitive.content == key } ?: return null
        val v = kv["value"]!!.jsonObject
        return (v["stringValue"] ?: v["intValue"] ?: v["boolValue"] ?: v["doubleValue"])!!.jsonPrimitive.content
    }

    private fun JsonObject.name() = this["name"]!!.jsonPrimitive.content
    private fun JsonObject.sid() = this["spanId"]!!.jsonPrimitive.content
    private fun JsonObject.tid() = this["traceId"]!!.jsonPrimitive.content
    private fun JsonObject.parent() = this["parentSpanId"]?.jsonPrimitive?.content

    // ---- 子会话 ----

    @Test
    fun `子会话的日志与指标一并导出，带父会话关联属性`() = runBlocking {
        val parent = FakeSessionLog("p")
        parent.created(1000)
        parent.turn(1, 1100, tool = "c9")
        val child = FakeSessionLog("c1", parent = "p", createdAtMs = 1200)
        child.created(1200)
        child.origin("p", "p", "c9", depth = 1)
        child.turn(1, 1300)

        val transport = ScriptedTransport(OK, OK, OK)
        val cfg = config()
        OtelTail(sourceOf(parent, child), cfg, OtelExporter(cfg, transport)).pump()

        val out = sent(transport)
        val childLogs = out.logs.filter { it.attr("session.id") == "c1" }
        assertTrue(childLogs.isNotEmpty())
        for (log in childLogs) {
            assertEquals("p", log.attr("min.parent_session_id"))
            assertEquals("p", log.attr("min.root_session_id"))
            assertEquals("c1", log.attr("agent_id"))
            assertEquals("Explore", log.attr("min.subagent_type"))
        }
        // 父会话的日志不带这些属性
        assertTrue(out.logs.filter { it.attr("session.id") == "p" }.all { it.attr("min.parent_session_id") == null })
        val childTokens = out.metrics.filter { it.name() == "claude_code.token.usage" }
            .flatMap { it["sum"]!!.jsonObject["dataPoints"]!!.jsonArray.map { p -> p.jsonObject } }
            .filter { it.attr("session.id") == "c1" }
        assertTrue(childTokens.isNotEmpty())
        assertTrue(childTokens.all { it.attr("min.parent_session_id") == "p" })
    }

    @Test
    fun `子会话的 span 挂到父会话 Agent 工具的 span 下，同一条 trace，父子哪个先导出都一样`() = runBlocking {
        val parent = FakeSessionLog("p")
        parent.created(1000)
        parent.turn(1, 1100, tool = "c9")
        val child = FakeSessionLog("c1", parent = "p", createdAtMs = 1200)
        child.created(1200)
        child.origin("p", "p", "c9", depth = 1)
        child.turn(1, 1300)

        for (order in listOf(listOf(parent, child), listOf(child, parent))) {
            val transport = ScriptedTransport(OK, OK, OK)
            val cfg = config()
            OtelTail(sourceOf(*order.toTypedArray()), cfg, OtelExporter(cfg, transport)).pump()
            val spans = sent(transport).spans
            val agentTool = spans.single { it.name() == "claude_code.tool" && it.attr("session.id") == "p" }
            val childLlm = spans.single { it.name() == "claude_code.llm_request" && it.attr("session.id") == "c1" }
            assertEquals(agentTool.sid(), childLlm.parent())
            assertEquals(agentTool.tid(), childLlm.tid())
            assertEquals("p", childLlm.attr("min.parent_session_id"))
            assertEquals("c1", childLlm.attr("agent_id"))
            assertTrue(spans.none { it.name() == "claude_code.interaction" && it.attr("session.id") == "c1" })
        }
    }

    @Test
    fun `孙会话沿父链挂回根会话的 trace，并带 parent_agent_id`() = runBlocking {
        val root = FakeSessionLog("p")
        root.created(1000)
        root.turn(1, 1100, tool = "c9")
        val child = FakeSessionLog("c1", parent = "p", createdAtMs = 1200)
        child.created(1200)
        child.origin("p", "p", "c9", depth = 1)
        child.turn(1, 1300, tool = "k1")
        val grand = FakeSessionLog("g1", parent = "c1", createdAtMs = 1400)
        grand.created(1400)
        grand.origin("c1", "p", "k1", depth = 2, type = "general-purpose")
        grand.turn(1, 1500)

        val transport = ScriptedTransport(OK, OK, OK)
        val cfg = config()
        OtelTail(sourceOf(root, child, grand), cfg, OtelExporter(cfg, transport)).pump()
        val spans = sent(transport).spans
        val rootInteraction = spans.single { it.name() == "claude_code.interaction" }
        val childTool = spans.single { it.name() == "claude_code.tool" && it.attr("session.id") == "c1" }
        val grandLlm = spans.single { it.name() == "claude_code.llm_request" && it.attr("session.id") == "g1" }
        assertEquals(rootInteraction.tid(), grandLlm.tid())
        assertEquals(childTool.sid(), grandLlm.parent())
        assertEquals("c1", grandLlm.attr("parent_agent_id"))
        assertEquals("p", grandLlm.attr("min.root_session_id"))
    }

    @Test
    fun `找不到父会话那次调用时，子会话退回成自己的根（仍带来历属性），不抛`() = runBlocking {
        val orphan = FakeSessionLog("c1", parent = "p-missing", createdAtMs = 1200)
        orphan.created(1200)
        orphan.origin("p-missing", "p-missing", "c9", depth = 1)
        orphan.turn(1, 1300)
        val transport = ScriptedTransport(OK, OK, OK)
        val cfg = config()
        OtelTail(sourceOf(orphan), cfg, OtelExporter(cfg, transport)).pump()
        val spans = sent(transport).spans
        val interaction = spans.single { it.name() == "claude_code.interaction" }
        assertNull(interaction.parent())
        assertEquals("p-missing", interaction.attr("min.parent_session_id"))
    }

    @Test
    fun `本进程启动之前就存在的子会话从日志末尾起，不重放历史；之后新建的从头`() = runBlocking {
        val old = FakeSessionLog("old", parent = "p", createdAtMs = 1000)
        old.created(1000)
        old.origin("p", "p", "c0", depth = 1)
        old.turn(1, 1100)
        val parent = FakeSessionLog("p", createdAtMs = 900)
        val transport = ScriptedTransport(OK, OK, OK, OK, OK, OK)
        val cfg = config()
        val tail = OtelTail(
            sourceOf(parent, old), cfg, OtelExporter(cfg, transport),
            initialCursors = mapOf("p" to OtelCursor(1, 1)), replayFromMs = 5000,
        )
        assertEquals(0, tail.pump())
        assertTrue(transport.requests.isEmpty())
        // 老子会话继续有新事件：只导出新的
        val baseline = old.records.size
        old.turn(2, 6000)
        assertTrue(tail.pump() > 0)
        val seqs = sent(transport).logs.filter { it.attr("session.id") == "old" }.map { it.attr("event.sequence")!!.toLong() }
        assertTrue(seqs.isNotEmpty())
        assertTrue(seqs.all { it > baseline })
    }

    @Test
    fun `读不到会话列表只记失败，不抛`() = runBlocking {
        val cfg = config()
        val exporter = OtelExporter(cfg, ScriptedTransport())
        val tail = OtelTail(OtelSessionSource { _ -> throw IOException("磁盘坏了") }, cfg, exporter)
        assertEquals(0, tail.pump())
        assertEquals(1, exporter.failures)
    }

    @Test
    fun `之后才出现的会话在下次也会被纳入`() = runBlocking {
        val parent = FakeSessionLog("p")
        parent.created(1000)
        val extra = FakeSessionLog("c1", parent = "p", createdAtMs = 1100)
        extra.created(1100)
        extra.origin("p", "p", "c9", depth = 1)
        var showExtra = false
        val cfg = config()
        val transport = ScriptedTransport(OK, OK, OK, OK)
        val tail = OtelTail(OtelSessionSource { _ -> if (showExtra) listOf(parent, extra) else listOf(parent) }, cfg, OtelExporter(cfg, transport))
        tail.pump()
        val before = transport.requests.size
        showExtra = true
        assertTrue(tail.pump() > 0)
        assertTrue(transport.requests.size > before)
    }

    // ---- 续导游标 ----

    @Test
    fun `跨进程续导：回合还开着时游标停在 span 的起点，下个进程补出完整的 interaction，日志不重复`() = runBlocking {
        val log = FakeSessionLog("p")
        log.created(1000)
        val prompt = log.add(1100, InputReceived(ClientMsgId("c1"), listOf(TextPart("你好世界")), Delivery.Queue, InputOrigin.User))
        val tid = TurnId("t1")
        log.add(1200, TurnStarted(tid, TurnTrigger.User))
        log.add(1210, ModelRequestStarted(tid, RequestId("r1"), 1, model, "fp"))
        log.add(1300, ModelCharged(model, usage, 0.01, "USD"))
        log.add(1310, AssistantMessageCompleted(tid, RequestId("r1"), MessageId("m1"), listOf(TextPart("ok")), usage, StopReason.EndTurn))

        val cfg = config()
        val t1 = ScriptedTransport(OK, OK, OK)
        val first = OtelTail(sourceOf(log), cfg, OtelExporter(cfg, t1))
        first.pump()
        val cursors = first.cursors()
        val c = cursors.getValue("p")
        assertEquals(log.records.size + 1L, c.next)
        assertEquals(prompt.seq, c.spansFrom) // interaction 还开着：续导从提示词那一条起
        assertEquals(1, sent(t1).spans.size) // 只有结束了的 llm_request

        log.add(1500, TurnEnded(tid, TurnOutcome.Completed, usage, durationMs = 300))
        val t2 = ScriptedTransport(OK, OK, OK)
        val second = OtelTail(sourceOf(log), cfg, OtelExporter(cfg, t2), initialCursors = cursors)
        second.pump()
        val out = sent(t2)
        val interaction = out.spans.single { it.name() == "claude_code.interaction" }
        assertEquals("4", interaction.attr("user_prompt_length"))
        assertEquals("1200000000", interaction["startTimeUnixNano"]!!.jsonPrimitive.content)
        // 日志：上一轮已经导出的 user_prompt 等不再出现，只有新的 turn.ended
        assertEquals(listOf("min.turn.ended"), out.logs.map { it["body"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content })
        // 回合结束后游标回到末尾
        val after = second.cursors().getValue("p")
        assertEquals(after.next, after.spansFrom)
    }

    @Test
    fun `没开 beta 时不产出 span，游标的 span 起点就是读到的位置`() = runBlocking {
        val log = FakeSessionLog("p")
        log.created(1000)
        log.turn(1, 1100)
        val cfg = config("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "")
        val transport = ScriptedTransport(OK, OK)
        val tail = OtelTail(sourceOf(log), cfg, OtelExporter(cfg, transport))
        tail.pump()
        assertTrue(transport.requests.none { it.url.endsWith("/v1/traces") })
        val c = tail.cursors().getValue("p")
        assertEquals(c.next, c.spansFrom)
    }

    @Test
    fun `跨进程续导时 effort 不丢：续读不是从日志开头起，开头的 SessionCreated 里的 effort 要先折出来`() = runBlocking {
        val log = FakeSessionLog("p")
        log.add(1000, SessionCreated(GuestPath("/w"), model, PermissionMode.Default, effort = Effort.High))
        log.add(1050, SettingsChanged(effort = Effort.XHigh))
        val tid = TurnId("t1")
        log.add(1200, TurnStarted(tid, TurnTrigger.User))
        log.add(1210, ModelRequestStarted(tid, RequestId("r1"), 1, model, "fp"))
        val cfg = config()
        val first = OtelTail(sourceOf(log), cfg, OtelExporter(cfg, ScriptedTransport(OK, OK, OK)))
        first.pump()
        val cursors = first.cursors()
        // 请求还开着：下个进程从 TurnStarted 之后的记录重放，不再经过 SessionCreated / SettingsChanged
        log.add(1300, ModelCharged(model, usage, 0.01, "USD"))
        log.add(1310, AssistantMessageCompleted(tid, RequestId("r1"), MessageId("m1"), listOf(TextPart("ok")), usage, StopReason.EndTurn))
        log.add(1400, TurnEnded(tid, TurnOutcome.Completed, usage, durationMs = 200))
        val t2 = ScriptedTransport(OK, OK, OK)
        OtelTail(sourceOf(log), cfg, OtelExporter(cfg, t2), initialCursors = cursors).pump()
        val llm = sent(t2).spans.single { it.name() == "claude_code.llm_request" }
        assertEquals("xhigh", llm.attr("effort"))
    }

    @Test
    fun `入站 TRACEPARENT 一路到导出：interaction 挂在它下面，子会话的父链也用同一条 trace`() = runBlocking {
        val tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val base = mapOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
            "OTEL_TRACES_EXPORTER" to "otlp", "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com", "TRACEPARENT" to tp,
        )
        val cfg = OtelConfig.fromEnv({ base[it] }, acceptInbound = true)
        val parent = FakeSessionLog("p")
        parent.created(1000)
        parent.turn(1, 1100, tool = "c9")
        val child = FakeSessionLog("kid", parent = "p")
        child.created(1)
        child.origin("p", "p", "c9", 1)
        child.turn(1, 1200)
        val transport = ScriptedTransport(OK)
        OtelTail(sourceOf(parent, child), cfg, OtelExporter(cfg, transport)).pump()
        val spans = sent(transport).spans
        assertTrue(spans.isNotEmpty())
        // 同一个进程里的所有 span 都在入站的 trace 里（子会话经父链继承）
        assertTrue(spans.all { it["traceId"]!!.jsonPrimitive.content == "0af7651916cd43dd8448eb211c80319c" })
        val interaction = spans.single { it.name() == "claude_code.interaction" }
        assertEquals("b7ad6b7169203331", interaction["parentSpanId"]!!.jsonPrimitive.content)
        assertEquals("env", interaction.attr("parent.source"))
        // 子会话的 llm_request 挂在父会话的 Agent 工具 span 下
        val tool = spans.single { it.name() == "claude_code.tool" }
        val kidLlm = spans.single { it.name() == "claude_code.llm_request" && it.attr("agent_id") == "kid" }
        assertEquals(tool.sid(), kidLlm["parentSpanId"]!!.jsonPrimitive.content)
    }

    // ---- 导出间隔 ----

    @Test
    fun `各信号按各自的间隔导出：到点才发，没到点留在缓冲里`() = runBlocking {
        val log = FakeSessionLog("p")
        log.created(1000)
        log.turn(1, 1100)
        var now = 0L
        val cfg = config() // 指标 60000、日志 5000、traces 5000（原文默认值）
        val transport = ScriptedTransport(OK, OK, OK, OK)
        val tail = OtelTail(sourceOf(log), cfg, OtelExporter(cfg, transport), clock = { now })
        tail.collect()
        now = 4_999
        tail.flushDue(now)
        assertTrue(transport.requests.isEmpty())

        now = 5_000
        tail.flushDue(now)
        val urls = transport.requests.map { it.url.substringAfterLast('/') }
        assertEquals(listOf("logs", "traces"), urls)

        now = 59_999
        tail.flushDue(now)
        assertEquals(2, transport.requests.size)
        now = 60_000
        tail.flushDue(now)
        assertEquals("metrics", transport.requests.last().url.substringAfterLast('/'))
        assertEquals(3, transport.requests.size)

        // 缓冲已清空：再到点也不重发
        now = 200_000
        tail.flushDue(now)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `pump 是读完立刻全部导出（进程退出、手动导出用）`() = runBlocking {
        val log = FakeSessionLog("p")
        log.created(1000)
        log.turn(1, 1100)
        val cfg = config()
        val transport = ScriptedTransport(OK, OK, OK)
        OtelTail(sourceOf(log), cfg, OtelExporter(cfg, transport), clock = { 0 }).pump()
        assertEquals(setOf("metrics", "logs", "traces"), transport.requests.map { it.url.substringAfterLast('/') }.toSet())
    }
}
