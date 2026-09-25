package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话流里改工具卡的两个公共件，以及托管 Bash 的判定 —— 都是从 ClaudeCodeManager 里收拢出来的纯函数 */
class ClaudeCodeItemsTest {

    private fun call(toolUseId: String, editDiff: String? = null) = ChatItem.ToolCall(
        id = "item-$toolUseId",
        toolUseId = toolUseId,
        name = "Bash",
        input = JsonObject(emptyMap()),
        status = ChatItem.ToolCall.Status.Running,
        editDiff = editDiff,
    )

    @Test
    fun `mapToolCall only touches the card with that id`() {
        val note = ChatItem.Note("n", "旁白")
        val items = listOf(call("a"), note, call("b"))
        val out = items.mapToolCall("b") { it.copy(result = "done") }

        assertEquals(null, (out[0] as ChatItem.ToolCall).result)
        assertSame(note, out[1])
        assertEquals("done", (out[2] as ChatItem.ToolCall).result)
    }

    @Test
    fun `withResult sets status, truncates the result and keeps an earlier diff when this one has none`() {
        val card = call("a", editDiff = "--- earlier")
        val ok = card.withResult(ClaudeCodeEvent.ToolResult("a", "x".repeat(50), isError = false), maxResultChars = 10)
        assertEquals(ChatItem.ToolCall.Status.Done, ok.status)
        assertEquals(10, ok.result?.length)
        // 截了要记下原文多长，界面据此标「已截断」
        assertEquals(50L, ok.resultTotalChars)
        assertEquals("--- earlier", ok.editDiff)

        val failed = card.withResult(
            ClaudeCodeEvent.ToolResult("a", "boom", isError = true, editDiff = "+++ new"),
            maxResultChars = 10,
        )
        assertEquals(ChatItem.ToolCall.Status.Error, failed.status)
        assertTrue(failed.isError)
        assertEquals("+++ new", failed.editDiff)
        assertNull(failed.resultTotalChars)
    }

    private fun bash(command: String, background: Boolean? = null) = buildJsonObject {
        put("command", command)
        if (background != null) put("run_in_background", JsonPrimitive(background))
    }

    @Test
    fun `a dev server is hosted with its noise stripped and its port guessed`() {
        val plan = hostedBashPlan(bash("python3 -m http.server 8765 &"))
        assertEquals(HostedBashPlan("python3 -m http.server 8765", 8765), plan)
    }

    @Test
    fun `ordinary commands and empty ones are not hosted`() {
        assertNull(hostedBashPlan(bash("git status")))
        assertNull(hostedBashPlan(bash("   ")))
        assertNull(hostedBashPlan(JsonObject(emptyMap())))
    }

    @Test
    fun `the background flag alone is enough to host`() {
        assertEquals("python app.py", hostedBashPlan(bash("python app.py", background = true))?.command)
        assertNull(hostedBashPlan(bash("python app.py", background = false)))
    }
}
