package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** span 树的形状：纯函数（日志记录 → span），表驱动 */
class OtelSpanMapperTest {
    private val sid = "s1"
    private val model = ModelRef("anthropic", "claude-test")
    private val usage = Usage(inputTokens = 10, outputTokens = 5)
    private var seq = 0L

    private fun rec(atMs: Long, event: SessionEvent) = EventRecord(++seq, atMs, event)
    private fun turn(n: Int) = TurnId("t$n")

    // ---- 事件的小构造 ----
    private fun prompt(at: Long, text: String = "你好") =
        rec(at, InputReceived(ClientMsgId("c$at"), listOf(TextPart(text)), Delivery.Queue, InputOrigin.User))
    private fun turnStart(at: Long, n: Int = 1, trigger: TurnTrigger = TurnTrigger.User) = rec(at, TurnStarted(turn(n), trigger))
    private fun turnEnd(at: Long, n: Int = 1, outcome: TurnOutcome = TurnOutcome.Completed) =
        rec(at, TurnEnded(turn(n), outcome, usage, durationMs = 100))
    private fun reqStart(at: Long, r: String = "r1", n: Int = 1, attempt: Int = 1) =
        rec(at, ModelRequestStarted(turn(n), RequestId(r), attempt, model, "fp"))
    private fun charged(at: Long) = rec(at, ModelCharged(model, usage, amount = 0.01, currency = "USD"))
    private fun reply(at: Long, r: String = "r1", n: Int = 1, call: CallId? = null) = rec(at, AssistantMessageCompleted(
        turn(n), RequestId(r), MessageId("m$at"),
        listOfNotNull(TextPart("ok"), call?.let { ToolCallPart(it, "Bash", JsonObject(emptyMap())) }),
        usage, if (call != null) StopReason.ToolUse else StopReason.EndTurn,
    ))
    private fun planned(at: Long, call: String, tool: String = "Bash", input: JsonObject = JsonObject(mapOf("command" to JsonPrimitive("ls"))), n: Int = 1) =
        rec(at, ToolPlanned(turn(n), CallId(call), tool, input, ResumePolicy.Unknown))
    private fun asked(at: Long, approval: String, call: String, n: Int = 1) =
        rec(at, ApprovalRequested(turn(n), ApprovalId(approval), CallId(call), PermissionAsk(tool = "Bash", summary = "x")))
    private fun resolved(at: Long, approval: String, choice: ApprovalChoice = ApprovalChoice.Once) =
        rec(at, ApprovalResolved(ApprovalId(approval), choice, DecidedBy.User))
    private fun effect(at: Long, call: String, n: Int = 1) = rec(at, ToolEffectPending(turn(n), CallId(call)))
    private fun outcome(at: Long, call: String, error: Boolean = false, n: Int = 1) =
        rec(at, ToolOutcome(turn(n), CallId(call), ToolResult(listOf(TextPart(if (error) "boom" else "out")), isError = error)))

    private fun spansOf(
        events: List<EventRecord>,
        options: OtelOptions = OtelOptions(sessionId = sid),
        link: SpanLink? = null,
    ): List<OtelSpan> {
        val mapper = OtelSpanMapper(options, link)
        return events.flatMap { mapper.feed(it) }
    }

    /** 「名字 <- 父名字」；根是「名字 <- (root)」。同名多个时重复列出 */
    private fun shape(spans: List<OtelSpan>): List<String> {
        val byId = spans.associateBy { it.spanId }
        return spans.map { "${it.name} <- ${it.parentSpanId?.let { p -> byId[p]?.name ?: "(外部)" } ?: "(root)"}" }.sorted()
    }

    private class Scenario(val title: String, val events: () -> List<EventRecord>, val expected: List<String>)

    @Test
    fun `span 树的形状（表驱动）`() {
        val scenarios = listOf(
            Scenario(
                "只有一次模型请求",
                { listOf(prompt(900), turnStart(1000), reqStart(1010), charged(1500), reply(1510), turnEnd(1600)) },
                listOf("claude_code.interaction <- (root)", "claude_code.llm_request <- claude_code.interaction"),
            ),
            Scenario(
                "要审批的工具：tool 下挂 blocked_on_user 与 execution",
                {
                    listOf(
                        turnStart(1000), reqStart(1010), charged(1500), reply(1510, call = CallId("c1")),
                        planned(1520, "c1"), asked(1530, "a1", "c1"), resolved(2000, "a1"), effect(2010, "c1"), outcome(2600, "c1"), turnEnd(2700),
                    )
                },
                listOf(
                    "claude_code.interaction <- (root)",
                    "claude_code.llm_request <- claude_code.interaction",
                    "claude_code.tool <- claude_code.interaction",
                    "claude_code.tool.blocked_on_user <- claude_code.tool",
                    "claude_code.tool.execution <- claude_code.tool",
                ),
            ),
            Scenario(
                "免审批的工具：没有 blocked_on_user",
                { listOf(turnStart(1000), planned(1020, "c1", "Read"), effect(1030, "c1"), outcome(1100, "c1"), turnEnd(1200)) },
                listOf(
                    "claude_code.interaction <- (root)",
                    "claude_code.tool <- claude_code.interaction",
                    "claude_code.tool.execution <- claude_code.tool",
                ),
            ),
            Scenario(
                "被拒绝的工具：有 blocked_on_user，没有 execution",
                {
                    listOf(
                        turnStart(1000), planned(1020, "c1"), asked(1030, "a1", "c1"), resolved(1500, "a1", ApprovalChoice.Deny("no")),
                        outcome(1510, "c1", error = true), turnEnd(1600),
                    )
                },
                listOf(
                    "claude_code.interaction <- (root)",
                    "claude_code.tool <- claude_code.interaction",
                    "claude_code.tool.blocked_on_user <- claude_code.tool",
                ),
            ),
            Scenario(
                "请求失败：仍有 llm_request（状态为错误），回合照样收尾",
                {
                    listOf(
                        turnStart(1000), reqStart(1010),
                        rec(1200, ModelRequestFailed(turn(1), RequestId("r1"), 1, ModelFailureKind.Network, "断网")),
                        turnEnd(1300, outcome = TurnOutcome.Failed("断网", ModelFailureKind.Network)),
                    )
                },
                listOf("claude_code.interaction <- (root)", "claude_code.llm_request <- claude_code.interaction"),
            ),
            Scenario(
                "两次请求（工具回合）：两个 llm_request 都挂在同一个 interaction 下",
                {
                    listOf(
                        turnStart(1000), reqStart(1010, "r1"), charged(1100), reply(1110, "r1"),
                        reqStart(1200, "r2"), charged(1300), reply(1310, "r2"), turnEnd(1400),
                    )
                },
                listOf(
                    "claude_code.interaction <- (root)",
                    "claude_code.llm_request <- claude_code.interaction",
                    "claude_code.llm_request <- claude_code.interaction",
                ),
            ),
        )
        for (s in scenarios) {
            seq = 0 // 每个场景自己的 seq 从 1 开始
            assertEquals(s.title, s.expected.sorted(), shape(spansOf(s.events())))
        }
    }

    @Test
    fun `一个回合的所有 span 同属一条 trace，每个回合一条新 trace，interaction 带序号`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000, 1), reqStart(1010, "r1", 1), charged(1100), reply(1110, "r1", 1), turnEnd(1200, 1),
            turnStart(2000, 2), reqStart(2010, "r2", 2), charged(2100), reply(2110, "r2", 2), turnEnd(2200, 2),
        ))
        val interactions = spans.filter { it.name == "claude_code.interaction" }
        assertEquals(2, interactions.size)
        assertNotEquals(interactions[0].traceId, interactions[1].traceId)
        assertEquals(listOf(1L, 2L), interactions.map { (it.attributes["interaction.sequence"] as OtelValue.Whole).value })
        for (i in interactions) {
            val inTrace = spans.filter { it.traceId == i.traceId }
            assertEquals(2, inTrace.size)
        }
        spans.forEach {
            assertEquals(32, it.traceId.length)
            assertEquals(16, it.spanId.length)
            assertTrue(it.endMs >= it.startMs)
        }
    }

    @Test
    fun `id 是确定的：同一份日志重放得到同样的 trace 与 span id，换会话则不同`() {
        fun log() = listOf(turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(1200))
        seq = 0
        val a = spansOf(log())
        seq = 0
        val b = spansOf(log())
        assertEquals(a.map { it.traceId to it.spanId }, b.map { it.traceId to it.spanId })
        seq = 0
        val c = spansOf(log(), OtelOptions(sessionId = "other"))
        assertNotEquals(a.map { it.spanId }, c.map { it.spanId })
    }

    @Test
    fun `工具 span 的 id 与 SpanIds 公布的一致，子会话据此指回父会话的 Agent 工具 span`() {
        seq = 0
        val spans = spansOf(listOf(turnStart(1000), planned(1010, "c9", "Agent"), effect(1020, "c9"), outcome(1500, "c9"), turnEnd(1600)))
        val tool = spans.single { it.name == "claude_code.tool" }
        assertEquals(SpanIds.tool(sid, "c9"), tool.spanId)
        val interaction = spans.single { it.name == "claude_code.interaction" }
        assertEquals(SpanIds.trace(sid, "t1"), interaction.traceId)
    }

    @Test
    fun `属性：interaction、llm_request、tool、blocked_on_user、execution 各自的原文字段`() {
        seq = 0
        val spans = spansOf(listOf(
            prompt(900, "你好，世界"), turnStart(1000), reqStart(1010), charged(1500), reply(1510, call = CallId("c1")),
            planned(1520, "c1"), asked(1530, "a1", "c1"), resolved(2000, "a1"), effect(2010, "c1"), outcome(2600, "c1", error = true), turnEnd(2700),
        ))
        fun span(name: String) = spans.single { it.name == name }
        fun OtelSpan.text(k: String) = (attributes[k] as? OtelValue.Text)?.value
        fun OtelSpan.whole(k: String) = (attributes[k] as? OtelValue.Whole)?.value
        fun OtelSpan.flag(k: String) = (attributes[k] as? OtelValue.Flag)?.value

        val interaction = span("claude_code.interaction")
        assertEquals(5L, interaction.whole("user_prompt_length"))
        assertEquals("<REDACTED>", interaction.text("user_prompt"))
        assertEquals(100.0, (interaction.attributes["interaction.duration_ms"] as OtelValue.Real).value, 0.0)
        assertEquals("none", interaction.text("parent.source"))
        assertEquals(sid, interaction.text("session.id"))

        val llm = span("claude_code.llm_request")
        assertEquals("claude-test", llm.text("model"))
        assertEquals("claude-test", llm.text("gen_ai.request.model"))
        assertEquals("anthropic", llm.text("gen_ai.system"))
        assertEquals("interaction", llm.text("llm_request.context"))
        assertEquals(10L, llm.whole("input_tokens"))
        assertEquals(5L, llm.whole("output_tokens"))
        assertEquals("r1", llm.text("min.request_id"))
        assertEquals(1L, llm.whole("attempt"))
        assertEquals(true, llm.flag("success"))
        assertEquals(true, llm.flag("response.has_tool_call"))
        assertEquals("tool_use", llm.text("stop_reason"))
        assertNull(llm.error)

        val tool = span("claude_code.tool")
        assertEquals("Bash", tool.text("tool_name"))
        assertEquals("c1", tool.text("tool_use_id"))
        assertEquals("c1", tool.text("gen_ai.tool.call.id"))
        assertFalse(tool.attributes.containsKey("full_command"))

        val blocked = span("claude_code.tool.blocked_on_user")
        assertEquals("accept", blocked.text("decision"))
        assertEquals("user_temporary", blocked.text("source"))
        assertEquals(470L, blocked.endMs - blocked.startMs)

        val exec = span("claude_code.tool.execution")
        assertEquals(false, exec.flag("success"))
        assertTrue(exec.error != null) // 失败：状态为错误
        // error 是类别，错误正文（"boom"）受 OTEL_LOG_TOOL_DETAILS 门控
        assertEquals("ShellError", exec.text("error"))
        assertFalse(exec.attributes.values.any { it is OtelValue.Text && it.value.contains("boom") })
    }

    @Test
    fun `内容类属性受开关门控：提示词、完整命令、文件路径、错误正文`() {
        fun events(): List<EventRecord> {
            seq = 0
            return listOf(
                prompt(900, "秘密提示词"), turnStart(1000),
                planned(1010, "c1", "Bash", JsonObject(mapOf("command" to JsonPrimitive("rm -rf /tmp/x")))), effect(1020, "c1"),
                outcome(1100, "c1", error = true),
                planned(1110, "c2", "Read", JsonObject(mapOf("file_path" to JsonPrimitive("/w/a.txt")))), effect(1120, "c2"), outcome(1200, "c2"),
                turnEnd(1300),
            )
        }
        val closed = spansOf(events())
        assertTrue(closed.none { it.attributes.containsKey("full_command") || it.attributes.containsKey("file_path") })
        assertEquals("<REDACTED>", (closed.single { it.name == "claude_code.interaction" }.attributes["user_prompt"] as OtelValue.Text).value)

        val open = spansOf(events(), OtelOptions(sessionId = sid, logUserPrompts = true, logToolDetails = true))
        val tools = open.filter { it.name == "claude_code.tool" }
        assertEquals("rm -rf /tmp/x", (tools[0].attributes["full_command"] as OtelValue.Text).value)
        assertEquals("/w/a.txt", (tools[1].attributes["file_path"] as OtelValue.Text).value)
        assertEquals("秘密提示词", (open.single { it.name == "claude_code.interaction" }.attributes["user_prompt"] as OtelValue.Text).value)
        assertEquals("boom", (open.first { it.name == "claude_code.tool.execution" }.attributes["error"] as OtelValue.Text).value)
    }

    @Test
    fun `请求最终失败的 span 状态为错误，带错误说明与状态码；中途失败但重试成功的不留错误 span`() {
        seq = 0
        // 最终失败：一个错误 span
        val failedSpans = spansOf(listOf(
            turnStart(1000), reqStart(1010),
            rec(1200, ModelRequestFailed(turn(1), RequestId("r1"), 1, ModelFailureKind.Network, "断网", httpStatus = 503)),
            turnEnd(1300),
        )).filter { it.name == "claude_code.llm_request" }
        val failed = failedSpans.single()
        assertEquals("断网", failed.error)
        assertEquals(503L, (failed.attributes["status_code"] as OtelValue.Whole).value)
        assertEquals(false, (failed.attributes["success"] as OtelValue.Flag).value)
        // 重试成功：原文一个 llm_request 覆盖全部重试（attempt 是总次数），失败的那次只留成事件
        seq = 0
        val retried = spansOf(listOf(
            turnStart(1000), reqStart(1010),
            rec(1200, ModelRequestFailed(turn(1), RequestId("r1"), 1, ModelFailureKind.RateLimited, "太快了", httpStatus = 429, willRetry = true)),
            reqStart(1300, "r1", attempt = 2), charged(1400), reply(1410),
            turnEnd(1500),
        )).filter { it.name == "claude_code.llm_request" }.single()
        assertNull(retried.error)
        assertEquals(2L, (retried.attributes["attempt"] as OtelValue.Whole).value)
        assertEquals(1, retried.events.size)
    }

    @Test
    fun `回合被中断时，还开着的 span 在回合结束处收口并标为错误`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000), planned(1010, "c1"), asked(1020, "a1", "c1"),
            turnEnd(1500, outcome = TurnOutcome.Interrupted(InterruptedBy.User)),
        ))
        val tool = spans.single { it.name == "claude_code.tool" }
        assertEquals(1500L, tool.endMs)
        assertTrue(tool.error != null)
        val blocked = spans.single { it.name == "claude_code.tool.blocked_on_user" }
        assertEquals(1500L, blocked.endMs)
        val interaction = spans.single { it.name == "claude_code.interaction" }
        assertEquals("interrupted", (interaction.attributes["min.outcome"] as OtelValue.Text).value)
        // 收口顺序：子在前，interaction 最后
        assertEquals("claude_code.interaction", spans.last().name)
    }

    @Test
    fun `子会话：没有自己的 interaction，llm_request 与 tool 直接挂到父会话的工具 span 下，同一条 trace`() {
        seq = 0
        val link = SpanLink(traceId = "a".repeat(32), parentSpanId = "b".repeat(16))
        val lineage = OtelLineage(parent = "p1", root = "p1", parentCall = "c9", agentType = "Explore", depth = 1, teamMember = false)
        val spans = spansOf(
            listOf(
                turnStart(1000), reqStart(1010), charged(1100), reply(1110, call = CallId("k1")),
                planned(1120, "k1"), effect(1130, "k1"), outcome(1200, "k1"), turnEnd(1300),
            ),
            OtelOptions(sessionId = "child1", lineage = lineage),
            link,
        )
        assertTrue(spans.none { it.name == "claude_code.interaction" })
        assertTrue(spans.all { it.traceId == link.traceId })
        val llm = spans.single { it.name == "claude_code.llm_request" }
        assertEquals(link.parentSpanId, llm.parentSpanId)
        assertEquals("tool", (llm.attributes["llm_request.context"] as OtelValue.Text).value)
        val tool = spans.single { it.name == "claude_code.tool" }
        assertEquals(link.parentSpanId, tool.parentSpanId)
        // 子会话属性
        for (s in spans) {
            assertEquals("child1", (s.attributes["agent_id"] as OtelValue.Text).value)
            assertEquals("p1", (s.attributes["min.parent_session_id"] as OtelValue.Text).value)
            assertEquals("p1", (s.attributes["min.root_session_id"] as OtelValue.Text).value)
            assertEquals("Explore", (s.attributes["min.subagent_type"] as OtelValue.Text).value)
            assertEquals(1L, (s.attributes["min.session_depth"] as OtelValue.Whole).value)
            // 直接由主会话派生的：没有 parent_agent_id（原文）
            assertFalse(s.attributes.containsKey("parent_agent_id"))
        }
    }

    @Test
    fun `孙会话带 parent_agent_id，它的父也是子 agent`() {
        seq = 0
        val lineage = OtelLineage(parent = "child1", root = "root", parentCall = "c9", agentType = "general-purpose", depth = 2, teamMember = false)
        val spans = spansOf(
            listOf(turnStart(1000), reqStart(1010), charged(1100), reply(1110), turnEnd(1200)),
            OtelOptions(sessionId = "grand", lineage = lineage), SpanLink("a".repeat(32), "b".repeat(16)),
        )
        assertEquals("child1", (spans.single().attributes["parent_agent_id"] as OtelValue.Text).value)
    }

    @Test
    fun `openSinceSeq：回合进行中是最早还开着的 span 的起点，收尾后为 null`() {
        seq = 0
        val mapper = OtelSpanMapper(OtelOptions(sessionId = sid), null)
        assertNull(mapper.openSinceSeq)
        val p = prompt(900)
        mapper.feed(p)
        mapper.feed(turnStart(1000))
        // 提示词在回合之前：interaction 的起点算到提示词那一条，跨进程续导时才还看得到提示词
        assertEquals(p.seq, mapper.openSinceSeq)
        mapper.feed(reqStart(1010))
        assertEquals(p.seq, mapper.openSinceSeq)
        mapper.feed(charged(1100))
        mapper.feed(reply(1110))
        mapper.feed(turnEnd(1200))
        assertNull(mapper.openSinceSeq)
    }

    @Test
    fun `span 名字都登记在 OTEL_SPAN_NAMES 里，且照原文`() {
        seq = 0
        val spans = spansOf(listOf(
            turnStart(1000), reqStart(1010), charged(1100), reply(1110, call = CallId("c1")),
            planned(1120, "c1"), asked(1130, "a1", "c1"), resolved(1200, "a1"), effect(1210, "c1"), outcome(1300, "c1"), turnEnd(1400),
        ))
        assertTrue(spans.all { it.name in OTEL_SPAN_NAMES })
        assertEquals(
            setOf(
                "claude_code.interaction", "claude_code.llm_request", "claude_code.tool",
                "claude_code.tool.blocked_on_user", "claude_code.tool.execution", "claude_code.hook",
            ),
            OTEL_SPAN_NAMES,
        )
    }
}
