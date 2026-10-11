package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * MCP channel（CC channels-reference）在协议层的口径：服务器握手时声明 `experimental["claude/channel"]`，
 * 之后推 `notifications/claude/channel`（content + meta）；声明了 `claude/channel/permission` 才能收发审批转发。
 * 这里只测帧的解析与能力闸，谁订阅、怎么进对话是内核的事（kernel 的 McpChannelsTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpChannelTest {
    private fun transport(experimental: String?): FakeTransport = FakeTransport().also { t ->
        t.respond = { m ->
            if (m.text("method") == "initialize") {
                val caps = if (experimental == null) """{"tools":{}}""" else """{"tools":{},"experimental":$experimental}"""
                t.received.send(result(m.getValue("id"), json("""{"protocolVersion":"2025-11-25","serverInfo":{"name":"hook","version":"1"},"capabilities":$caps}""")))
            }
        }
    }

    private fun TestScope.client(t: FakeTransport) = McpClient(t, backgroundScope, McpClientOptions("1"))

    private fun push(t: FakeTransport, params: String) = t.received.trySend(json("""{"jsonrpc":"2.0","method":"notifications/claude/channel","params":$params}"""))

    @Test fun `declared channel delivers content and string meta in order`() = runTest {
        val t = transport("""{"claude/channel":{}}"""); val c = client(t); c.connect()
        push(t, """{"content":"build failed","meta":{"severity":"high","run_id":"1234"}}"""); push(t, """{"content":"second"}""")
        runCurrent()
        val got = c.channelEvents.take(2).toList()
        assertEquals(McpChannelEvent.Message("build failed", mapOf("severity" to "high", "run_id" to "1234")), got[0])
        assertEquals(McpChannelEvent.Message("second", emptyMap()), got[1]); c.close()
    }

    @Test fun `server without channel capability cannot push events`() = runTest {
        for (caps in listOf(null, """{}""", """{"claude/channel/permission":{}}""", """{"claude/channel":false}""")) {
            val t = transport(caps); val c = client(t); c.connect()
            push(t, """{"content":"sneaky"}"""); runCurrent()
            assertNull(caps, withTimeoutOrNull(50) { c.channelEvents.first() }); c.close()
        }
    }

    @Test fun `meta keys must be identifiers and values strings`() = runTest {
        val t = transport("""{"claude/channel":{}}"""); val c = client(t); c.connect()
        push(t, """{"content":"x","meta":{"ok_1":"v","bad-key":"v","with space":"v","n":1,"b":true,"nested":{"a":"b"},"":"v"}}""")
        runCurrent()
        assertEquals(mapOf("ok_1" to "v"), (c.channelEvents.first() as McpChannelEvent.Message).meta); c.close()
    }

    @Test fun `non string or missing content is dropped`() = runTest {
        val t = transport("""{"claude/channel":{}}"""); val c = client(t); c.connect()
        push(t, """{"meta":{"a":"b"}}"""); push(t, """{"content":5}"""); push(t, """{"content":{"x":1}}"""); push(t, """{"content":null}""")
        t.received.trySend(json("""{"jsonrpc":"2.0","method":"notifications/claude/channel"}"""))
        push(t, """{"content":"real"}""")
        runCurrent()
        assertEquals("real", (c.channelEvents.first() as McpChannelEvent.Message).content); c.close()
    }

    @Test fun `events pushed before anyone collects are kept`() = runTest {
        val t = transport("""{"claude/channel":{}}"""); val c = client(t); c.connect()
        repeat(5) { push(t, """{"content":"m$it"}""") }; runCurrent()
        assertEquals(List(5) { "m$it" }, c.channelEvents.take(5).toList().map { (it as McpChannelEvent.Message).content }); c.close()
    }

    @Test fun `permission verdict needs the permission capability`() = runTest {
        for ((caps, accepted) in listOf("""{"claude/channel":{}}""" to false, """{"claude/channel":{},"claude/channel/permission":false}""" to false, """{"claude/channel":{},"claude/channel/permission":{}}""" to true)) {
            val t = transport(caps); val c = client(t); c.connect()
            t.received.trySend(json("""{"jsonrpc":"2.0","method":"notifications/claude/channel/permission","params":{"request_id":"abcde","behavior":"allow"}}"""))
            runCurrent()
            val ev = withTimeoutOrNull(50) { c.channelEvents.first() }
            assertEquals(caps, if (accepted) McpChannelEvent.Verdict("abcde", true) else null, ev); c.close()
        }
    }

    @Test fun `verdict behavior must be allow or deny and id a string`() = runTest {
        val t = transport("""{"claude/channel":{},"claude/channel/permission":{}}"""); val c = client(t); c.connect()
        for (p in listOf("""{"request_id":"abcde","behavior":"maybe"}""", """{"request_id":5,"behavior":"allow"}""", """{"behavior":"allow"}""", """{"request_id":"abcde"}""", """{"request_id":"abcde","behavior":"ALLOW"}"""))
            t.received.trySend(json("""{"jsonrpc":"2.0","method":"notifications/claude/channel/permission","params":$p}"""))
        t.received.trySend(json("""{"jsonrpc":"2.0","method":"notifications/claude/channel/permission","params":{"request_id":"vwxyz","behavior":"deny"}}"""))
        runCurrent()
        assertEquals(McpChannelEvent.Verdict("vwxyz", false), c.channelEvents.first()); c.close()
    }

    @Test fun `permission request goes out as a notification with the four fields`() = runTest {
        val t = transport("""{"claude/channel":{},"claude/channel/permission":{}}"""); val c = client(t); c.connect()
        c.sendChannelPermissionRequest("abcde", "Bash", "Run shell command", """{"command":"ls"}""")
        val m = t.sent.last()
        assertEquals("notifications/claude/channel/permission_request", m.text("method")); assertFalse(m.containsKey("id"))
        assertEquals(json("""{"request_id":"abcde","tool_name":"Bash","description":"Run shell command","input_preview":"{\"command\":\"ls\"}"}"""), m.obj("params")); c.close()
    }

    @Test fun `permission request is refused locally without the capability`() = runTest {
        val t = transport("""{"claude/channel":{}}"""); val c = client(t); c.connect()
        val before = t.sent.size
        val e = try { c.sendChannelPermissionRequest("abcde", "Bash", "d", "{}"); null } catch (e: IllegalStateException) { e }
        assertNotNull(e); assertEquals(before, t.sent.size); c.close()
    }
}
