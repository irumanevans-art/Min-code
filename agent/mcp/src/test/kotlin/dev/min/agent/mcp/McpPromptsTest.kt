package dev.min.agent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpPromptsTest {
    @Test fun `prompt listing paginates and get retains roles with content order`() = runTest {
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1")); c.connect()
        t.respond = { m -> when (m.text("method")) {
            "prompts/list" -> t.received.send(result(m.getValue("id"), json("""{"prompts":[{"name":"p","arguments":[{"name":"arg","required":true}]}],"nextCursor":"again"}""")))
            "prompts/get" -> {
                assertEquals("p", m.obj("params")!!.text("name")); assertEquals("value", m.obj("params")!!.obj("arguments")!!.text("arg"))
                t.received.send(result(m.getValue("id"), json("""{"messages":[{"role":"user","content":{"type":"text","text":"a"}},{"role":"assistant","content":{"type":"text","text":"b"}}]}""")))
            }
        } }
        assertEquals(2, c.listPrompts().size)
        val messages = c.getPrompt("p", json("""{"arg":"value"}"""))
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals(listOf(McpContent.Text("a"), McpContent.Text("b")), messages.map { it.content }); c.close()
    }
}
