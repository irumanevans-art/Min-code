package dev.min.agent.tools

import dev.min.agent.spi.PushSink
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import dev.min.agent.testkit.parsed
import dev.min.agent.testkit.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationToolTest {
    private class Recording(var accept: Boolean = true) : PushSink {
        val sent = mutableListOf<Pair<String?, String>>()
        override suspend fun push(title: String?, message: String): Boolean {
            sent += title to message
            return accept
        }
    }

    private fun ctx(sink: PushSink) = object : ToolContext by TestToolContext() {
        override val push: PushSink = sink
    }

    @Test
    fun `message is required and non-blank, title is optional`() {
        assertEquals(listOf("message"), (PushNotificationTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content })
        PushNotificationTool.reject()
        PushNotificationTool.reject("message" to "   ")
        PushNotificationTool.reject("message" to "x", "title" to 1)
        PushNotificationTool.parsed("message" to "x")
    }

    @Test
    fun `a delivered notification reaches the host with its title and message`() = runBlocking {
        val sink = Recording()
        val result = PushNotificationTool.call(ctx(sink), "message" to "构建好了", "title" to "CI")
        assertEquals(listOf<Pair<String?, String>>("CI" to "构建好了"), sink.sent)
        assertFalse(result.isError)
        assertTrue(result.text, result.text.contains("已发出"))
    }

    @Test
    fun `no title given reaches the host as null`() = runBlocking {
        val sink = Recording()
        PushNotificationTool.call(ctx(sink), "message" to "好了")
        assertNull(sink.sent.single().first)
    }

    @Test
    fun `a host that cannot notify says so as an error, so the model does not assume the user was told`() = runBlocking {
        val sink = Recording(accept = false)
        val result = PushNotificationTool.call(ctx(sink), "message" to "好了")
        assertTrue(result.isError)
        assertTrue(result.text, result.text.contains("没有发出"))
    }

    @Test
    fun `the default host cannot notify`() = runBlocking {
        val result = PushNotificationTool.call(TestToolContext(), "message" to "好了")
        assertTrue(result.isError)
    }
}
