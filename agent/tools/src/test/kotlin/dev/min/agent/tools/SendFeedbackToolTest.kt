package dev.min.agent.tools

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.spi.FeedbackSink
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFeedbackToolTest {
    private class Recording(var accept: Boolean = true) : FeedbackSink {
        val queued = mutableListOf<FeedbackDraft>()
        override suspend fun queue(draft: FeedbackDraft): Boolean {
            queued += draft
            return accept
        }
    }

    private fun wrap(sink: FeedbackSink): ToolContext =
        object : ToolContext by TestToolContext() { override val feedback: FeedbackSink = sink }

    @Test
    fun `title and body are required, and both have length caps`() {
        assertEquals(listOf("title", "body"), (SendFeedbackTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        SendFeedbackTool.reject()
        SendFeedbackTool.reject("title" to "x")
        SendFeedbackTool.reject("title" to "", "body" to "x")
        SendFeedbackTool.reject("title" to "x".repeat(SendFeedbackTool.MAX_TITLE + 1), "body" to "x")
        SendFeedbackTool.reject("title" to "x", "body" to "y".repeat(SendFeedbackTool.MAX_BODY + 1))
        SendFeedbackTool.parsed("title" to "rg 超时", "body" to "工具连续失败三次")
    }

    @Test
    fun `the draft is only queued, the result says it was not sent, and nothing is sent`() = runBlocking {
        val sink = Recording()
        val result = SendFeedbackTool.call(wrap(sink), "title" to "rg 超时", "body" to "工具连续失败三次")
        assertFalse(result.isError)
        assertEquals(listOf(FeedbackDraft("rg 超时", "工具连续失败三次")), sink.queued)
        // 口径：草稿在本机等用户决定，模型不能以为已经发出去
        assertTrue(result.text, result.text.contains("未发送"))
    }

    @Test
    fun `no draft queue on this host is an error the model can see`() = runBlocking {
        val result = SendFeedbackTool.call(wrap(Recording(accept = false)), "title" to "t", "body" to "b")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("没有"))
    }

    @Test
    fun `the default port is unavailable, so a host that does not wire it fails loudly`() = runBlocking {
        val result = SendFeedbackTool.call(wrap(FeedbackSink.Unavailable), "title" to "t", "body" to "b")
        assertTrue(result.isError)
    }
}
