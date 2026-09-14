package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出 token 的解析。
 *
 * 关键约定：`message_delta.usage.output_tokens` 是**这条消息**的累计值，不是整轮的。
 * 一轮里几次工具往返就有几条消息，累加必须在消息边界结转，直接相加会翻倍。
 */
class ClaudeCodeOutputTokensTest {

    @Test
    fun `从 message_delta 取输出 token`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"message_delta",
               |"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":488}}}""".trimMargin()
        )
        assertEquals(listOf(ClaudeCodeEvent.OutputTokens(488)), events)
    }

    /** 有的版本把 usage 塞进 delta 里 */
    @Test
    fun `usage 嵌在 delta 里也认`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"message_delta",
               |"delta":{"usage":{"output_tokens":12}}}}""".trimMargin()
        )
        assertEquals(listOf(ClaudeCodeEvent.OutputTokens(12)), events)
    }

    @Test
    fun `没有 usage 时不产生事件`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"message_delta","delta":{"stop_reason":"end_turn"}}}"""
        )
        assertTrue(events.isEmpty())
    }

    /** 子 agent 的 stream_event 整帧丢弃，它的 token 不该算进主轮的计数 */
    @Test
    fun `子 agent 的 message_delta 不计入`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","parent_tool_use_id":"toolu_1",
               |"event":{"type":"message_delta","usage":{"output_tokens":99}}}""".trimMargin()
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `畸形的 usage 不会炸掉解析`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","event":{"type":"message_delta","usage":"nope"}}"""
        )
        assertTrue(events.isEmpty())
    }
}
