package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐输出类的反解测试：一段照文档写法手写的 stdout JSON → [HookOutputCommon] → 从 hookSpecificOutput
 * 取对应事件形状 → 断言字段值。没有决策控制的事件不建输出类（见 [HookEvent] 注释）。
 */
class HookOutputsWireTest {
    private fun decode(text: String): HookOutputCommon = HookJson.decodeFromString(HookOutputCommon.serializer(), text)

    private inline fun <reified T : Any> specific(out: HookOutputCommon): T =
        HookJson.decodeFromJsonElement(out.hookSpecificOutput ?: error("脚本没写 hookSpecificOutput"))

    @Test
    fun `SessionStart specific output`() {
        val out = decode(
            """{"continue": true, "systemMessage": "welcome",
               "hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": "ctx",
                                      "initialUserMessage": "go", "sessionTitle": "Fix bug",
                                      "watchPaths": ["/a", "/b"], "reloadSkills": true}}""",
        )
        assertEquals(true, out.shouldContinue)
        assertEquals("welcome", out.systemMessage)
        val specific = specific<SessionStartSpecificOutput>(out)
        assertEquals("SessionStart", specific.hookEventName)
        assertEquals("ctx", specific.additionalContext)
        assertEquals("go", specific.initialUserMessage)
        assertEquals("Fix bug", specific.sessionTitle)
        assertEquals(listOf("/a", "/b"), specific.watchPaths)
        assertEquals(true, specific.reloadSkills)
    }

    @Test
    fun `SubagentStart specific output`() {
        val specific = specific<SubagentStartSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "SubagentStart", "additionalContext": "agent ctx"}}"""),
        )
        assertEquals("agent ctx", specific.additionalContext)
    }

    @Test
    fun `SubagentStop specific output with top level block`() {
        val out = decode(
            """{"decision": "block", "reason": "not done",
               "hookSpecificOutput": {"hookEventName": "SubagentStop", "additionalContext": "keep going"}}""",
        )
        assertEquals("block", out.decision)
        assertEquals("not done", out.reason)
        assertEquals("keep going", specific<SubagentStopSpecificOutput>(out).additionalContext)
    }

    @Test
    fun `Stop specific output`() {
        val specific = specific<StopSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "Stop", "additionalContext": "one more"}}"""),
        )
        assertEquals("one more", specific.additionalContext)
    }

    @Test
    fun `UserPromptSubmit specific output`() {
        val out = decode(
            """{"decision": "block", "reason": "off topic",
               "hookSpecificOutput": {"hookEventName": "UserPromptSubmit", "additionalContext": "note",
                                      "sessionTitle": "t", "suppressOriginalPrompt": true}}""",
        )
        assertEquals("block", out.decision)
        val specific = specific<UserPromptSubmitSpecificOutput>(out)
        assertEquals("note", specific.additionalContext)
        assertEquals("t", specific.sessionTitle)
        assertEquals(true, specific.suppressOriginalPrompt)
    }

    @Test
    fun `UserPromptExpansion specific output`() {
        val specific = specific<UserPromptExpansionSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "UserPromptExpansion", "additionalContext": "x"}}"""),
        )
        assertEquals("x", specific.additionalContext)
    }

    @Test
    fun `MessageDisplay specific output`() {
        val specific = specific<MessageDisplaySpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "MessageDisplay", "displayContent": "shown"}}"""),
        )
        assertEquals("shown", specific.displayContent)
    }

    @Test
    fun `PreToolUse specific output`() {
        // 旧格式顶层 decision: approve/block 已弃用（映射 allow/deny），这里只收新格式
        val specific = specific<PreToolUseSpecificOutput>(
            decode(
                """{"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                   "permissionDecisionReason": "danger", "updatedInput": {"command": "rm"},
                   "additionalContext": "c"}}""",
            ),
        )
        assertEquals("deny", specific.permissionDecision)
        assertEquals("danger", specific.permissionDecisionReason)
        assertEquals("rm", specific.updatedInput?.getValue("command")?.jsonPrimitive?.content)
        assertEquals("c", specific.additionalContext)
    }

    @Test
    fun `PermissionRequest specific output with nested decision`() {
        val specific = specific<PermissionRequestSpecificOutput>(
            decode(
                """{"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                   "decision": {"behavior": "allow", "updatedInput": {"command": "ls"},
                                "updatedPermissions": [{"type": "addRules", "destination": "session"}],
                                "message": "ok", "interrupt": false}}}""",
            ),
        )
        val decision = specific.decision!!
        assertEquals("allow", decision.behavior)
        assertEquals("ls", decision.updatedInput?.getValue("command")?.jsonPrimitive?.content)
        assertEquals(1, decision.updatedPermissions?.size)
        assertEquals("addRules", decision.updatedPermissions?.first()?.type)
        assertEquals("session", decision.updatedPermissions?.first()?.destination)
        assertEquals("ok", decision.message)
        assertEquals(false, decision.interrupt)
    }

    @Test
    fun `PostToolUse specific output`() {
        val specific = specific<PostToolUseSpecificOutput>(
            decode(
                """{"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": "a",
                   "classifierContext": "benign", "updatedToolOutput": {"stdout": "x"},
                   "updatedMCPToolOutput": {"result": 1}}}""",
            ),
        )
        assertEquals("a", specific.additionalContext)
        assertEquals("benign", specific.classifierContext)
        assertEquals("x", specific.updatedToolOutput?.getValue("stdout")?.jsonPrimitive?.content)
        assertTrue(specific.updatedMcpToolOutput!!.containsKey("result"))
    }

    @Test
    fun `PostToolUseFailure specific output`() {
        val specific = specific<PostToolUseFailureSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "PostToolUseFailure", "additionalContext": "a"}}"""),
        )
        assertEquals("a", specific.additionalContext)
    }

    @Test
    fun `PostToolBatch specific output`() {
        val specific = specific<PostToolBatchSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "PostToolBatch", "additionalContext": "a"}}"""),
        )
        assertEquals("a", specific.additionalContext)
    }

    @Test
    fun `PermissionDenied specific output`() {
        val specific = specific<PermissionDeniedSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "PermissionDenied", "retry": true}}"""),
        )
        assertEquals(true, specific.retry)
    }

    @Test
    fun `WorktreeCreate specific output`() {
        val specific = specific<WorktreeCreateSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "WorktreeCreate", "worktreePath": "/wt/x"}}"""),
        )
        assertEquals("/wt/x", specific.worktreePath)
    }

    @Test
    fun `PreModelSwitch specific output`() {
        val specific = specific<PreModelSwitchSpecificOutput>(
            decode(
                """{"hookSpecificOutput": {"hookEventName": "PreModelSwitch", "permissionDecision": "ask",
                   "permissionDecisionReason": "confirm"}}""",
            ),
        )
        assertEquals("ask", specific.permissionDecision)
        assertEquals("confirm", specific.permissionDecisionReason)
    }

    @Test
    fun `PostModelSwitch specific output`() {
        val specific = specific<PostModelSwitchSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "PostModelSwitch", "additionalContext": "switched"}}"""),
        )
        assertEquals("switched", specific.additionalContext)
    }

    @Test
    fun `Elicitation specific output`() {
        val specific = specific<ElicitationSpecificOutput>(
            decode(
                """{"hookSpecificOutput": {"hookEventName": "Elicitation", "action": "accept",
                   "content": {"q": "a"}}}""",
            ),
        )
        assertEquals("accept", specific.action)
        assertEquals("a", specific.content?.jsonObject?.getValue("q")?.jsonPrimitive?.content)
    }

    @Test
    fun `ElicitationResult specific output`() {
        val specific = specific<ElicitationResultSpecificOutput>(
            decode("""{"hookSpecificOutput": {"hookEventName": "ElicitationResult", "action": "decline"}}"""),
        )
        assertEquals("decline", specific.action)
        assertNull(specific.content)
    }
}
