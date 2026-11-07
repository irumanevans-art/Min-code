package dev.min.agent.otel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AutoReviewDecision
import dev.min.agent.spi.AutoReviewed
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolPlanned
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `claude_code.safety_event`（CC 2.1.307，monitoring-usage「Safety event」）：一个检查拦下、要求审批或退回了工具调用时一条 */
class OtelSafetyEventTest {
    private val turn = TurnId("t1")
    private var seq = 0L
    private fun rec(event: SessionEvent): EventRecord = EventRecord(++seq, 1000 + seq, event)

    private fun run(vararg events: SessionEvent): List<OtelLog> {
        val mapper = OtelMapper(OtelOptions(sessionId = "s1"))
        return (listOf<SessionEvent>(SessionCreated(GuestPath("/w"), dev.min.agent.protocol.ModelRef("p", "m"), PermissionMode.Default)) + events)
            .flatMap { mapper.feed(rec(it)) }.filterIsInstance<OtelLog>().filter { it.name == OtelNames.SAFETY_EVENT }
    }

    private fun OtelLog.text(key: String): String? = (attributes[key] as? OtelValue.Text)?.value
    private fun plan(call: String, tool: String) = ToolPlanned(turn, CallId(call), tool, JsonObject(emptyMap()), ResumePolicy.Unknown)
    private fun asked(call: String, tool: String, floor: String? = null) =
        ApprovalRequested(turn, ApprovalId("a-$call"), CallId(call), PermissionAsk(tool, "x"), floorReason = floor)
    private fun resolved(call: String, choice: ApprovalChoice, by: DecidedBy) = ApprovalResolved(ApprovalId("a-$call"), choice, by)

    @Test
    fun `a safety floor prompt logs safety_check with the final decision and no path or command`() {
        val e = run(plan("c1", "Bash"), asked("c1", "Bash", floor = "要改受保护的路径 /w/.git/config"), resolved("c1", ApprovalChoice.Once, DecidedBy.User)).single()
        assertEquals("safety_check", e.text("category"))
        assertEquals("accept", e.text("decision"))
        assertEquals("user_temporary", e.text("decision_source"))
        assertEquals("min_safety_floor", e.text("circuit_breaker"))
        assertEquals("bash", e.text("tool_class"))
        assertEquals("Bash", e.text("tool_name"))
        assertEquals("builtin", e.text("tool_source"))
        assertEquals("default", e.text("permission_mode"))
        assertEquals("c1", e.text("tool_use_id"))
        assertFalse(e.attributes.values.toString(), e.attributes.values.any { it.toString().contains(".git") })
    }

    @Test
    fun `a prompt with no category logs only when someone declines it`() {
        assertTrue(run(plan("c1", "Edit"), asked("c1", "Edit"), resolved("c1", ApprovalChoice.Once, DecidedBy.User)).isEmpty())
        val e = run(plan("c2", "Edit"), asked("c2", "Edit"), resolved("c2", ApprovalChoice.Deny("no"), DecidedBy.User)).single()
        assertEquals("user_rejected", e.text("category")); assertEquals("reject", e.text("decision"))
        assertEquals("user_reject", e.text("decision_source")); assertEquals("file", e.text("tool_class"))
    }

    @Test
    fun `hook, mode and timeout refusals each name their check`() {
        val hook = run(plan("c1", "WebFetch"), asked("c1", "WebFetch"), resolved("c1", ApprovalChoice.Deny("no", fromHost = true), DecidedBy.Hook("PermissionRequest"))).single()
        assertEquals("hook", hook.text("category")); assertEquals("hook:PermissionRequest", hook.text("reason_code"))
        assertEquals("hook", hook.text("reason_type")); assertEquals("hook", hook.text("decision_source")); assertEquals("web", hook.text("tool_class"))
        val mode = run(plan("c2", "Write"), asked("c2", "Write"), resolved("c2", ApprovalChoice.Deny(), DecidedBy.Mode(PermissionMode.DontAsk))).single()
        assertEquals("permission_mode", mode.text("category")); assertEquals("mode:dontAsk", mode.text("reason_code")); assertEquals("mode", mode.text("reason_type"))
        val timeout = run(plan("c3", "Write"), asked("c3", "Write"), resolved("c3", ApprovalChoice.Deny(), DecidedBy.Timeout)).single()
        assertEquals("other", timeout.text("category")); assertEquals("min_timeout", timeout.text("decision_source"))
        val managed = run(plan("c4", "Bash"), asked("c4", "Bash"), resolved("c4", ApprovalChoice.Deny(), DecidedBy.Rule("Bash(rm *)", "managed"))).single()
        assertEquals("managed_policy", managed.text("category")); assertEquals("policySettings", managed.text("rule_source"))
        assertEquals("rule:policySettings", managed.text("reason_code")); assertEquals("config", managed.text("decision_source"))
    }

    @Test
    fun `hooks that refuse a call or send back a result or turn end`() {
        val pre = run(plan("c1", "Bash"), HooksRan("PreToolUse", 1, "deny", 5, turn, CallId("c1"))).single()
        assertEquals("hook", pre.text("category")); assertEquals("reject", pre.text("decision"))
        assertEquals("pre_tool_use", pre.text("hook_event")); assertEquals("hook:PreToolUse", pre.text("reason_code")); assertEquals("c1", pre.text("tool_use_id"))
        val post = run(plan("c2", "Read"), HooksRan("PostToolUse", 1, "block", 5, turn, CallId("c2"))).single()
        assertEquals("hook_review", post.text("category")); assertEquals("sent_back", post.text("decision")); assertEquals("post_tool_use", post.text("hook_event"))
        val stop = run(HooksRan("Stop", 1, "block", 5, turn)).single()
        assertEquals("hook_review", stop.text("category")); assertEquals("stop", stop.text("hook_event")); assertNull(stop.text("tool_use_id"))
        assertTrue("放行与中性不记", run(plan("c3", "Bash"), HooksRan("PreToolUse", 1, "allow", 5, turn, CallId("c3")), HooksRan("PostToolUse", 1, "neutral", 5, turn, CallId("c3"))).isEmpty())
    }

    @Test
    fun `an auto mode block on an MCP tool carries no server or tool name`() {
        val e = run(plan("c1", "mcp__github__delete_repo"), AutoReviewed(turn, CallId("c1"), null, AutoReviewDecision.Deny, "危险")).single()
        assertEquals("auto_mode_block", e.text("category")); assertEquals("reject", e.text("decision"))
        assertEquals("mcp_tool", e.text("tool_name")); assertEquals("mcp", e.text("tool_class")); assertEquals("mcp", e.text("tool_source"))
        assertFalse(e.attributes.values.toString(), e.attributes.values.any { it.toString().contains("github") })
        assertTrue(run(plan("c2", "Bash"), AutoReviewed(turn, CallId("c2"), null, AutoReviewDecision.Allow, "ok")).isEmpty())
    }
}
