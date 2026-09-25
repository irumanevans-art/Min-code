package dev.min.code.core.session

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 工具结果的共用截断规则：留头、记总长；流式累积到顶之后不再复制 */
class ToolResultClipTest {

    private val card = ChatItem.ToolCall(
        id = "c1",
        toolUseId = "c1",
        name = "Bash",
        input = JsonObject(emptyMap()),
        status = ChatItem.ToolCall.Status.Running,
    )

    @Test
    fun `a small result is kept verbatim and not marked`() {
        val out = card.withClippedResult("hello", max = 10)
        assertEquals("hello", out.result)
        assertNull(out.resultTotalChars)
    }

    @Test
    fun `an oversized result keeps its head and records the full length`() {
        val out = card.withClippedResult("0123456789abcdef", max = 10)
        assertEquals("0123456789", out.result)
        assertEquals(16L, out.resultTotalChars)
    }

    @Test
    fun `a fresh result replaces an earlier truncation mark`() {
        val clipped = card.withClippedResult("x".repeat(20), max = 10)
        val again = clipped.withClippedResult("short", max = 10)
        assertEquals("short", again.result)
        assertNull(again.resultTotalChars)
    }

    @Test
    fun `small streamed output comes out verbatim`() {
        val buffer = ClippedOutput(max = 10)
        buffer.append("ab")
        buffer.append("cd")
        val out = buffer.applyTo(card)
        assertEquals("abcd", out.result)
        assertNull(out.resultTotalChars)
    }

    @Test
    fun `exactly at the cap is not a truncation`() {
        val buffer = ClippedOutput(max = 4)
        buffer.append("abcd")
        assertNull(buffer.applyTo(card).resultTotalChars)
    }

    @Test
    fun `a delta crossing the cap is split at the cap`() {
        val buffer = ClippedOutput(max = 10)
        buffer.append("0123456")
        buffer.append("789abc")
        val out = buffer.applyTo(card)
        assertEquals("0123456789", out.result)
        assertEquals(13L, out.resultTotalChars)
    }

    /** 到顶之后的增量只加计数：结果不再长，也不再复制出新字符串 */
    @Test
    fun `deltas after the cap only move the count`() {
        val buffer = ClippedOutput(max = 10)
        buffer.append("x".repeat(12))
        val before = buffer.applyTo(card).result

        repeat(1000) { buffer.append("yyyy") }
        val after = buffer.applyTo(card)

        assertEquals("x".repeat(10), after.result)
        assertSame(before, after.result)
        assertEquals(12L + 4000L, after.resultTotalChars)
    }
}
