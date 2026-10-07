package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpResourcesTest {
    @Test fun `resource collections paginate with cursor cap and repetition protection`() = runTest {
        for ((method, field) in listOf("resources/list" to "resources", "resources/templates/list" to "resourceTemplates")) {
            val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1", maxToolPages = 3)); c.connect()
            t.respond = { m -> if (m.text("method") == method) t.received.send(result(m.getValue("id"), json("""{"$field":[{"name":"n","uri":"r://1"}],"nextCursor":"same"}"""))) }
            val listed = if (method == "resources/list") c.listResources() else c.listResourceTemplates()
            assertEquals(2, listed.size)
            assertEquals(listOf(null, "same"), t.sent.filter { it.text("method") == method }.map { it.obj("params")?.text("cursor") })
            c.close()
        }
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1", maxToolPages = 2)); c.connect()
        var page = 0
        t.respond = { m -> if (m.text("method") == "resources/list") t.received.send(result(m.getValue("id"), json("""{"resources":[],"nextCursor":"${++page}"}"""))) }
        assertTrue(c.listResources().isEmpty()); assertEquals(2, page); c.close()
    }

    @Test fun `read resources sends URI and preserves text image and binary content`() = runTest {
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1")); c.connect()
        t.respond = { m -> if (m.text("method") == "resources/read") {
            assertEquals("ui://app", m.obj("params")!!.text("uri"))
            t.received.send(result(m.getValue("id"), json("""{"contents":[{"uri":"r://t","text":"正文"},{"uri":"r://i","mimeType":"image/png","blob":"aQ=="},{"uri":"r://b","mimeType":"application/octet-stream","blob":"Yg=="}]}""")))
        } }
        val r = c.readResource("ui://app")
        assertEquals("正文", (r.content[0] as McpContent.EmbeddedResource).text)
        assertEquals(McpContent.Image("aQ==", "image/png"), r.content[1])
        assertEquals("Yg==", (r.content[2] as McpContent.EmbeddedResource).blob)
        c.close()
    }
}
