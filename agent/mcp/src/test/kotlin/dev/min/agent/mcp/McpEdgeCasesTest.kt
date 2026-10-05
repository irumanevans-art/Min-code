package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpEdgeCasesTest {
    @Test fun `progress callback failure is contained but cancellation closes connection`() = runTest {
        for (cancel in listOf(false, true)) {
            val t = FakeTransport(); val logs = mutableListOf<String>(); val c = McpClient(t, backgroundScope, McpClientOptions("1", onDebug = logs::add))
            c.connect(); t.respond = { m -> if (m.text("method") == "tools/call") {
                t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", m.getValue("id")); put("progress", 1) }))
                t.received.send(result(m.getValue("id"), json("""{"content":[]}""")))
            } }
            val e = try { c.callTool("x", emptyObject, { if (cancel) throw CancellationException("stop") else error("secret") }, 1_000); null } catch (e: McpTransportClosed) { e }
            if (cancel) { assertNotNull(e); assertTrue(c.state.value is McpConnectionState.Closed) }
            else { assertNull(e); assertTrue(logs.any { "回调失败" in it }); assertTrue(c.state.value is McpConnectionState.Ready) }
            c.close()
        }
    }
    @Test fun `idle timeout starts before blocked send and cancellation cleanup is bounded`() = runTest {
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1")); c.connect()
        var sendCancelled = false
        t.respond = { m -> if (m.text("method") == "tools/call") try { awaitCancellation() } finally { sendCancelled = true } else if (m.text("method") == "notifications/cancelled") awaitCancellation() }
        val e = try { c.callTool("x", emptyObject, idleTimeoutMs = 100); null } catch (e: McpTimeoutException) { e }
        assertEquals(McpTimeoutException.Kind.Idle, e!!.kind); assertTrue(sendCancelled); assertEquals(1_100, testScheduler.currentTime); c.close()
    }
    @Test fun `unknown progress tokens and progress for calls without callback cannot extend idle timeout`() = runTest {
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1")); c.connect(); t.respond = {}
        val call = async { try { c.callTool("x", emptyObject, idleTimeoutMs = 100); null } catch (e: McpTimeoutException) { e } }; runCurrent()
        val id = t.sent.last().getValue("id")
        advanceTimeBy(80); t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", id); put("progress", 1) })); runCurrent()
        advanceTimeBy(21); runCurrent(); assertNotNull(call.await()); c.close()
    }
    @Test fun `invalid jsonrpc version and mismatched id type do not resolve request`() = runTest {
        val logs = mutableListOf<String>(); val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1", onDebug = logs::add)); c.connect()
        t.respond = { m -> if (m.text("method") == "ping") {
            t.received.send(json("""{"jsonrpc":"1.0","id":2,"result":{}}""")); t.received.send(json("""{"jsonrpc":"2.0","id":"2","result":{}}"""))
            t.received.send(result(m.getValue("id"), emptyObject))
        } }
        c.ping(); assertTrue(logs.any { "版本" in it }); assertTrue(logs.any { "未知" in it }); c.close()
    }
    @Test fun `invalid tool metadata is a protocol failure instead of silently returning no tools`() = runTest {
        val t = FakeTransport(); val c = McpClient(t, backgroundScope, McpClientOptions("1")); c.connect()
        t.respond = { m -> if (m.text("method") == "tools/list") t.received.send(result(m.getValue("id"), json("""{"tools":[{"name":"bad"}]}"""))) }
        try { c.listTools(); fail("expected schema failure") } catch (e: McpTransportClosed) { assertTrue(e.reason.contains("schema")) }; c.close()
    }

    @Test fun `diagnostic string representations never dump raw headers data or URL credentials`() {
        val url = "https://user:pass@host/path?token=hidden"
        val raw = json("""{"Authorization":"secret"}""")
        val values = listOf(
            McpClientOptions("1", serverLabel = url), McpServerInfo("2025-11-25", json("""{"name":"server","websiteUrl":"$url"}"""), raw, url),
            McpProgress(1.0, message = url), McpToolDef("x", null, url, emptyObject, null, null, raw),
            McpCallResult(emptyList(), raw, false), McpContent.Text("Authorization: secret"), McpContent.Image("secret", "image/png"),
            McpContent.ResourceLink(url, "x", null, null, null, raw), McpContent.EmbeddedResource(url, null, "secret", "secret", raw), McpContent.Unknown(raw),
            McpTransportClosed(url, stderrTail = listOf("Authorization: secret")),
        )
        values.forEach { value -> for (secret in listOf("pass", "hidden", "secret")) assertFalse(value.toString(), value.toString().contains(secret)) }
    }
}
