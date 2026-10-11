package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
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
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelMapperTest {
    private val sid = SessionId("s1")
    private val turn = TurnId("t1")
    private val model = ModelRef("anthropic", "claude-test")
    private val req = RequestId("r1")

    private var seq = 0L
    private fun rec(atMs: Long, event: SessionEvent): EventRecord = EventRecord(++seq, atMs, event)

    private fun run(options: OtelOptions = OtelOptions(sessionId = sid.value), vararg events: EventRecord): List<OtelItem> {
        val mapper = OtelMapper(options)
        return events.flatMap { mapper.feed(it) }
    }

    private fun List<OtelItem>.named(name: String): List<OtelItem> = filter {
        (it is OtelMetric && it.name == name) || (it is OtelLog && it.name == name)
    }

    private fun text(item: OtelItem, key: String): String? = (item.attributes[key] as? OtelValue.Text)?.value
    private fun real(item: OtelItem, key: String): Double? = (item.attributes[key] as? OtelValue.Real)?.value
    private fun whole(item: OtelItem, key: String): Long? = (item.attributes[key] as? OtelValue.Whole)?.value

    private fun ask(tool: String) = PermissionAsk(tool = tool, summary = "test")

    @Test
    fun `会话创建计入 session count，start_type 为 fresh`() {
        val items = run(events = arrayOf(rec(1000, SessionCreated(GuestPath("/w"), model, PermissionMode.Default))))
        val count = items.named("claude_code.session.count").single() as OtelMetric
        assertEquals(1.0, count.value, 0.0)
        assertEquals("fresh", text(count, "start_type"))
        assertEquals("s1", text(count, "session.id"))
    }

    @Test
    fun `已结算的请求产出花费、四种 token 用量与 api_request，耗时取请求开始到结算`() {
        val usage = Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 300, cacheWriteTokens = 40)
        val items = run(events = arrayOf(
            rec(1000, ModelRequestStarted(turn, req, 1, model, "fp")),
            rec(1800, ModelCharged(model, usage, amount = 0.0123, currency = "USD")),
        ))
        val cost = items.named("claude_code.cost.usage").single() as OtelMetric
        assertEquals(0.0123, cost.value, 1e-12)
        assertEquals("USD", cost.unit)
        assertEquals("claude-test", text(cost, "model"))
        val tokens = items.named("claude_code.token.usage").filterIsInstance<OtelMetric>()
        assertEquals(listOf("input", "output", "cacheRead", "cacheCreation"), tokens.map { text(it, "type") })
        assertEquals(listOf(100.0, 20.0, 300.0, 40.0), tokens.map { it.value })
        val api = items.named("claude_code.api_request").single()
        assertEquals(0.0123, real(api, "cost_usd")!!, 1e-12)
        assertEquals(800.0, real(api, "duration_ms")!!, 0.0)
        assertEquals("r1", text(api, "request_id"))
        assertEquals(300L, whole(api, "cache_read_tokens"))
    }

    @Test
    fun `没有价格的请求不产出花费，api_request 也不写 cost_usd（未知不是 0）`() {
        val items = run(events = arrayOf(
            rec(1000, ModelRequestStarted(turn, req, 1, model, "fp")),
            rec(1100, ModelCharged(model, Usage(inputTokens = 5), amount = null, currency = null)),
        ))
        assertTrue(items.named("claude_code.cost.usage").isEmpty())
        val api = items.named("claude_code.api_request").single()
        assertFalse(api.attributes.containsKey("cost_usd"))
        assertFalse(api.attributes.containsKey("min.cost_currency"))
    }

    @Test
    fun `非 USD 计价不进 claude_code 花费指标，改用 min 的币种属性`() {
        val items = run(events = arrayOf(
            rec(1000, ModelRequestStarted(turn, req, 1, model, "fp")),
            rec(1100, ModelCharged(model, Usage(inputTokens = 5), amount = 0.5, currency = "CNY")),
        ))
        assertTrue(items.named("claude_code.cost.usage").isEmpty())
        val api = items.named("claude_code.api_request").single()
        assertEquals("CNY", text(api, "min.cost_currency"))
        assertFalse(api.attributes.containsKey("cost_usd"))
    }

    @Test
    fun `模型失败产出 api_error，带状态码与尝试次数`() {
        val items = run(events = arrayOf(
            rec(1000, ModelRequestStarted(turn, req, 2, model, "fp")),
            rec(1500, ModelRequestFailed(turn, req, 2, ModelFailureKind.RateLimited, "rate limited", httpStatus = 429, willRetry = true)),
        ))
        val err = items.named("claude_code.api_error").single()
        assertEquals("rate limited", text(err, "error"))
        assertEquals(429L, whole(err, "status_code"))
        assertEquals(2L, whole(err, "attempt"))
        assertEquals("claude-test", text(err, "model"))
    }

    @Test
    fun `用户提示默认脱敏只记长度，打开开关才带正文，并且挂上 prompt id`() {
        val prompt = rec(1000, InputReceived(ClientMsgId("c1"), listOf(TextPart("你好，世界")), Delivery.Queue, InputOrigin.User))
        val closed = run(events = arrayOf(prompt, rec(1100, ConversationEnded)))
        val up = closed.named("claude_code.user_prompt").single()
        assertEquals(5L, whole(up, "prompt_length"))
        assertEquals("<REDACTED>", text(up, "prompt"))
        assertEquals("c1", text(up, "prompt.id"))
        assertEquals("c1", text(closed.named("min.conversation.ended").single(), "prompt.id"))

        val open = run(OtelOptions(sessionId = "s1", logUserPrompts = true), prompt)
        assertEquals("你好，世界", text(open.named("claude_code.user_prompt").single(), "prompt"))
    }

    @Test
    fun `助手回复默认脱敏，只带长度`() {
        val items = run(events = arrayOf(rec(1000, AssistantMessageCompleted(turn, req, MessageId("m1"),
            listOf(TextPart("abc")), Usage(), StopReason.EndTurn))))
        val resp = items.named("claude_code.assistant_response").single()
        assertEquals("<REDACTED>", text(resp, "response"))
        assertEquals(3L, whole(resp, "response_length"))
    }

    @Test
    fun `工具调用：审批记 tool_decision，结果记 tool_result，耗时从实际执行开始算`() {
        val call = CallId("c9")
        val items = run(events = arrayOf(
            rec(1000, ToolPlanned(turn, call, "Bash", JsonObject(mapOf("command" to JsonPrimitive("ls"))), ResumePolicy.Unknown)),
            rec(1010, ApprovalRequested(turn, ApprovalId("a1"), call, ask("Bash"))),
            rec(2000, ApprovalResolved(ApprovalId("a1"), ApprovalChoice.Once, DecidedBy.User)),
            rec(2100, ToolEffectPending(turn, call)),
            rec(2600, ToolOutcome(turn, call, ToolResult(listOf(TextPart("file.txt"))))),
        ))
        val decision = items.named("claude_code.tool_decision").single()
        assertEquals("accept", text(decision, "decision"))
        assertEquals("user_temporary", text(decision, "source"))
        assertEquals("builtin", text(decision, "tool_source"))
        assertFalse(decision.attributes.containsKey("tool_parameters"))

        val result = items.named("claude_code.tool_result").single()
        assertEquals("Bash", text(result, "tool_name"))
        assertEquals(500.0, real(result, "duration_ms")!!, 0.0)
        assertEquals(true, (result.attributes["success"] as OtelValue.Flag).value)
        assertEquals("user_temporary", text(result, "decision_source"))
    }

    @Test
    fun `工具入参与错误正文只在打开 OTEL_LOG_TOOL_DETAILS 时出现`() {
        val input = JsonObject(mapOf("file_path" to JsonPrimitive("/a")))
        val closedCall = CallId("c2")
        val closed = run(events = arrayOf(
            rec(1000, ToolPlanned(turn, closedCall, "Read", input, ResumePolicy.Replay)),
            rec(1100, ToolOutcome(turn, closedCall, ToolResult(listOf(TextPart("boom")), isError = true))),
        )).named("claude_code.tool_result").single()
        assertFalse(closed.attributes.containsKey("tool_parameters"))
        assertFalse(closed.attributes.containsKey("error"))
        assertEquals(false, (closed.attributes["success"] as OtelValue.Flag).value)

        val openCall = CallId("c3")
        val open = run(OtelOptions(sessionId = "s1", logToolDetails = true), rec(1000, ToolPlanned(turn, openCall, "Read", input, ResumePolicy.Replay)),
            rec(1100, ToolOutcome(turn, openCall, ToolResult(listOf(TextPart("boom")), isError = true)))
        ).named("claude_code.tool_result").single()
        assertTrue(open.attributes.containsKey("tool_parameters"))
        assertEquals("boom", text(open, "error"))
    }

    @Test
    fun `被拒绝的审批不产出 tool_result，但 tool_decision 记 reject，代码编辑工具也记进 code_edit_tool`() {
        val call = CallId("c4")
        val items = run(events = arrayOf(
            rec(1000, ToolPlanned(turn, call, "Write", JsonObject(emptyMap()), ResumePolicy.Unknown)),
            rec(1010, ApprovalRequested(turn, ApprovalId("a4"), call, ask("Write"))),
            rec(1020, ApprovalResolved(ApprovalId("a4"), ApprovalChoice.Deny("no"), DecidedBy.User)),
            rec(1030, ToolOutcome(turn, call, ToolResult(listOf(TextPart("denied")), isError = true))),
        ))
        assertEquals("reject", text(items.named("claude_code.tool_decision").single(), "decision"))
        assertTrue(items.named("claude_code.tool_result").isEmpty())
        val edit = items.named("claude_code.code_edit_tool.decision").single()
        assertEquals("reject", text(edit, "decision"))
        assertEquals("user_reject", text(edit, "source"))
        assertEquals("Write", text(edit, "tool_name"))
    }

    @Test
    fun `文件改动的 diff 按加减行数记 lines_of_code`() {
        val call = CallId("c5")
        val edit = FileEdit(GuestPath("/w/a.kt"), diff = "@@ -1,2 +1,3 @@\n-old\n+new\n+more\n keep\n")
        val items = run(events = arrayOf(
            rec(1000, ToolPlanned(turn, call, "Edit", JsonObject(emptyMap()), ResumePolicy.Unknown)),
            rec(1100, ToolOutcome(turn, call, ToolResult(listOf(TextPart("ok")), fileEdits = listOf(edit)))),
        ))
        val lines = items.named("claude_code.lines_of_code.count").map { text(it, "type") to (it as OtelMetric).value }
        assertEquals(listOf("added" to 2.0, "removed" to 1.0), lines)
    }

    @Test
    fun `回合与会话收尾记 min 前缀的事件，权限模式变化记 permission_mode_changed`() {
        val items = run(events = arrayOf(
            rec(1000, TurnStarted(turn, TurnTrigger.User)),
            rec(1020, SettingsChanged(mode = PermissionMode.Auto)),
            rec(1500, TurnEnded(turn, TurnOutcome.Completed, Usage(inputTokens = 3), durationMs = 490)),
        ))
        assertEquals("user", text(items.named("min.turn.started").single(), "trigger"))
        val ended = items.named("min.turn.ended").single()
        assertEquals("completed", text(ended, "outcome"))
        assertEquals(490.0, real(ended, "duration_ms")!!, 0.0)
        val mode = items.named("claude_code.permission_mode_changed").single()
        assertEquals("auto", text(mode, "to_mode"))
    }

    @Test
    fun `流式片段等没有对标的记录不产出任何遥测`() {
        val items = run(events = arrayOf(
            rec(1000, AssistantSegment(turn, req, MessageId("m"), listOf(TextPart("half")))),
        ))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `产出的每个名字都登记在映射表里`() {
        val call = CallId("c6")
        val items = run(events = arrayOf(
            rec(1000, SessionCreated(GuestPath("/w"), model, PermissionMode.Default)),
            rec(1010, InputReceived(ClientMsgId("c"), listOf(TextPart("x")), Delivery.Queue, InputOrigin.User)),
            rec(1020, TurnStarted(turn, TurnTrigger.User)),
            rec(1030, ModelRequestStarted(turn, req, 1, model, "fp")),
            rec(1040, ModelCharged(model, Usage(inputTokens = 1), 0.1, "USD")),
            rec(1050, AssistantMessageCompleted(turn, req, MessageId("m"), listOf(TextPart("y")), Usage(), StopReason.EndTurn)),
            rec(1060, ToolPlanned(turn, call, "Edit", JsonObject(emptyMap()), ResumePolicy.Unknown)),
            rec(1070, ToolOutcome(turn, call, ToolResult(listOf(TextPart("z"))))),
            rec(1080, TurnEnded(turn, TurnOutcome.Completed, Usage(), durationMs = 80)),
        ))
        val names = items.map { if (it is OtelMetric) it.name else (it as OtelLog).name }.toSet()
        assertTrue(names.size >= 8)
        assertTrue("未登记的名字：$names", OTEL_NAME_TABLE.values.flatten().toSet().containsAll(names))
    }
}
