package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentPort
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolResult
import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.args
import dev.min.agent.testkit.call
import dev.min.agent.testkit.reject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendMessage 的 `notify_when_idle`（CC cross-session-messaging「Get a notice when another session goes idle」）：
 * 布尔入参，可以附在一条消息上，也可以单独用（此时不需要 message）；走端口的 notifyWhenIdle，不走普通 message。
 */
class SendMessageNotifyTest {
    private class Recording : AgentPort {
        val messages = mutableListOf<Pair<String, String>>()
        val watches = mutableListOf<Pair<String, String?>>()
        override suspend fun delegate(request: DelegateRequest): DelegateResult = DelegateResult.Rejected("unused")
        override suspend fun message(to: String, text: String): ToolResult { messages += to to text; return ToolResult(listOf(TextPart("已送达"))) }
        override suspend fun stop(target: String): ToolResult = ToolResult(listOf(TextPart("unused")))
        override suspend fun notifyWhenIdle(to: String, text: String?): ToolResult { watches += to to text; return ToolResult(listOf(TextPart("已订阅"))) }
    }

    private fun ctx(port: AgentPort): ToolContext = object : ToolContext by TestToolContext() {
        override val agents: AgentPort = port
    }

    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    @Test fun `the schema offers a boolean notify_when_idle and only the target is always required`() {
        val props = SendMessageTool.inputSchema["properties"] as JsonObject
        assertEquals("boolean", ((props["notify_when_idle"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals(listOf("to"), (SendMessageTool.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content })
    }

    @Test fun `a message is still required unless the call only subscribes`() {
        assertTrue(SendMessageTool.reject("to" to "api").contains("message"))
        assertTrue(SendMessageTool.reject("to" to "api", "notify_when_idle" to false).contains("message"))
        assertTrue(SendMessageTool.parse(args("to" to "api", "notify_when_idle" to true)) is ToolInput.Valid)
        assertTrue(SendMessageTool.parse(args("to" to "api", "message" to "好了告诉我", "notify_when_idle" to true)) is ToolInput.Valid)
    }

    @Test fun `notify_when_idle must be a real boolean`() {
        for (bad in listOf<Any>("true", 1)) {
            assertTrue(SendMessageTool.parse(args("to" to "api", "message" to "x", "notify_when_idle" to bad)) is ToolInput.Invalid)
        }
    }

    @Test fun `a plain message goes through message and a subscription goes through notifyWhenIdle`() = runBlocking {
        val port = Recording()
        SendMessageTool.call(ctx(port), "to" to "api", "message" to "你好")
        assertEquals(listOf("api" to "你好"), port.messages)
        assertTrue(port.watches.isEmpty())

        SendMessageTool.call(ctx(port), "to" to "api", "notify_when_idle" to true)
        SendMessageTool.call(ctx(port), "to" to "web", "message" to "做完告诉我", "notify_when_idle" to true)
        assertEquals(listOf<Pair<String, String?>>("api" to null, "web" to "做完告诉我"), port.watches)
        assertEquals("订阅不经过普通的 message", 1, port.messages.size)
    }

    @Test fun `the description tells the model what the subscription does and does not do`() {
        val text = SendMessageTool.description(profile)
        assertTrue(text, text.contains("notify_when_idle"))
        assertTrue(text, text.contains("一次"))
        assertTrue(text, text.contains("12 小时"))
        assertTrue(text, text.contains("本机"))
    }

    @Test fun `the permission summary says a subscription is not a message`() {
        val ask = SendMessageTool.permissionAsk(SendMessageTool.parse(args("to" to "api", "notify_when_idle" to true)).let { (it as ToolInput.Valid).value }, ctx(Recording()))
        assertTrue(ask.summary, ask.summary.contains("空闲通知"))
    }
}
