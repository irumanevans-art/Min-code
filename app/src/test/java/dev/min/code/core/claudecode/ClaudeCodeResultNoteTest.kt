package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCodeResultNoteTest {

    private fun result(text: String?, subtype: String = "error_during_execution") = ClaudeCodeEvent.Result(
        isError = true,
        subtype = subtype,
        durationMs = null,
        numTurns = null,
        totalCostUsd = null,
        sessionId = null,
        resultText = text,
        permissionDenials = emptyList(),
    )

    @Test
    fun `an ordinary failure is red and quotes the result text`() {
        val note = resultErrorNote("n", result("API Error: 529 overloaded"), false, false, emptyList())!!
        assertEquals("任务失败: API Error: 529 overloaded", note.text)
        assertEquals(true, note.isError)
    }

    @Test
    fun `without result text the subtype is shown`() {
        assertEquals(
            "任务失败: error_max_turns",
            resultErrorNote("n", result(null, "error_max_turns"), false, false, emptyList())!!.text,
        )
    }

    @Test
    fun `a failure already explained by the last reply is not repeated`() {
        val items = listOf(ChatItem.AssistantText("a", "No such model."), ChatItem.ProcessOutput("p", listOf("x")))
        assertEquals("任务失败（原因见上）", resultErrorNote("n", result("No such model."), false, false, items)!!.text)
    }

    @Test
    fun `an interrupt is a plain note and a withdraw leaves nothing`() {
        val note = resultErrorNote("n", result(null), interrupted = true, withdrew = false, items = emptyList())!!
        assertEquals("已中断", note.text)
        assertEquals(false, note.isError)
        assertNull(resultErrorNote("n", result(null), interrupted = true, withdrew = true, items = emptyList()))
    }
}
