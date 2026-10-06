package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
internal fun result(id: JsonElement, value: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
internal fun initializeResult(version: String = "2025-11-25") = buildJsonObject {
    put("protocolVersion", version); putJsonObject("serverInfo") { put("name", "test"); put("version", "1") }
    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }; put("instructions", "帮助")
}
internal class FakeTransport : McpTransport {
    val received = Channel<JsonObject>(64)
    val sent = mutableListOf<JsonObject>()
    var closed = false
    var respond: suspend (JsonObject) -> Unit = { m ->
        if (m.text("method") == "initialize") received.send(result(m.getValue("id"), initializeResult()))
        else if (m.text("method") == "ping") received.send(result(m.getValue("id"), emptyObject))
    }
    override val incoming = received.receiveAsFlow()
    override suspend fun send(message: JsonObject) { sent += message; respond(message) }
    override suspend fun close() { closed = true; received.close() }
}
@OptIn(ExperimentalCoroutinesApi::class)
class McpClientTest {
    @Test fun `roots changed sends protocol notification`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); c.rootsChanged()
        assertEquals("notifications/roots/list_changed", t.sent.last().text("method"))
        assertFalse(t.sent.last().containsKey("id")); c.close()
    }
    private fun TestScope.client(t: FakeTransport, options: McpClientOptions = McpClientOptions("1")) = McpClient(t, backgroundScope, options)

    @Test fun `handshake sends correct version info and empty capabilities then initialized`() = runTest {
        val t = FakeTransport(); val c = client(t)
        val info = c.connect()
        assertEquals("2025-11-25", info.protocolVersion); assertEquals("帮助", info.instructions)
        assertEquals(emptyObject, t.sent[0].obj("params")!!.obj("capabilities"))
        assertEquals("Min", t.sent[0].obj("params")!!.obj("clientInfo")!!.text("name"))
        assertEquals("notifications/initialized", t.sent[1].text("method"))
        assertTrue(c.state.value is McpConnectionState.Ready)
        assertEquals(info, c.connect()); assertEquals(2, t.sent.size); c.close()
    }
    @Test fun `negotiation accepts every supported older version and rejects unsupported version`() = runTest {
        for (v in listOf("2025-06-18", "2025-03-26", "2024-11-05", "2099-01-01")) {
            val t = FakeTransport(); t.respond = { m -> if (m.text("method") == "initialize") t.received.send(result(m.getValue("id"), initializeResult(v))) }
            val c = client(t)
            if (v.startsWith("2099")) {
                val e = try { c.connect(); error("expected") } catch (e: McpProtocolException) { e }
                assertTrue(e.message!!.contains("不支持")); assertTrue(t.closed)
            } else assertEquals(v, c.connect().protocolVersion)
            c.close()
        }
    }
    @Test fun `response routing uses monotonic ids and supports out of order concurrent calls`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.respond = {}
        val a = async { c.callTool("a", emptyObject, idleTimeoutMs = 1_000) }
        val b = async { c.callTool("b", emptyObject, idleTimeoutMs = 1_000) }; runCurrent()
        val calls = t.sent.filter { it.text("method") == "tools/call" }
        assertEquals(listOf(2L, 3L), calls.map { it["id"]!!.jsonPrimitive.long })
        for (m in calls.reversed()) t.received.send(result(m.getValue("id"), json("""{"content":[{"type":"text","text":"${m.obj("params")!!.text("name")}"}]}""")))
        assertEquals(McpContent.Text("a"), a.await().content.single()); assertEquals(McpContent.Text("b"), b.await().content.single()); c.close()
    }
    @Test fun `rpc errors preserve code data without leaking remote message and unknown responses are ignored`() = runTest {
        val logs = mutableListOf<String>(); val t = FakeTransport(); val c = client(t, McpClientOptions("1", onDebug = logs::add)); c.connect()
        t.respond = { m -> if (m.text("method") == "tools/call") {
            t.received.send(json("""{"jsonrpc":"2.0","id":999,"result":{}}"""))
            t.received.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", m.getValue("id")); put("error", json("""{"code":-32001,"message":"Authorization: secret","data":{"retry":false}}""")) })
        } }
        val e = try { c.callTool("x", emptyObject, idleTimeoutMs = 1_000); error("expected") } catch (e: McpRpcException) { e }
        assertEquals(-32001, e.code); assertEquals(json("""{"retry":false}"""), e.data); assertFalse(e.toString().contains("secret")); assertTrue(logs.any { "未知" in it }); c.close()
    }
    @Test fun `server requests reach handler failures become internal errors and ping bypasses handler`() = runTest {
        val t = FakeTransport(); val seen = mutableListOf<String>()
        val c = client(t, McpClientOptions("1", requestHandler = { method, _ -> seen += method; if (method == "broken") error("secret") else JsonPrimitive("done") }))
        c.connect()
        for (m in listOf("custom", "broken", "ping")) t.received.send(json("""{"jsonrpc":"2.0","id":"$m","method":"$m"}"""))
        runCurrent(); assertEquals(listOf("custom", "broken"), seen)
        val replies = t.sent.filter { it["id"] is JsonPrimitive && it["id"]!!.jsonPrimitive.isString }
        assertEquals(JsonPrimitive("done"), replies[0]["result"])
        assertEquals(-32603, replies[1].obj("error")!!["code"]!!.jsonPrimitive.int)
        assertEquals(emptyObject, replies[2]["result"]); assertFalse(replies.toString().contains("secret")); c.close()
    }
    @Test fun `default handler returns method not found`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.received.send(json("""{"jsonrpc":"2.0","id":"s","method":"roots/list"}""")); runCurrent()
        assertEquals(-32601, t.sent.last().obj("error")!!["code"]!!.jsonPrimitive.int); c.close()
    }
    @Test fun `tools list follows cursor parses schema annotations and stops repeated cursor`() = runTest {
        val logs = mutableListOf<String>(); val t = FakeTransport(); val c = client(t, McpClientOptions("1", onDebug = logs::add)); c.connect()
        t.respond = { m -> if (m.text("method") == "tools/list") t.received.send(result(m.getValue("id"), json("""{"tools":[{"name":"x","title":"X","description":"D","inputSchema":{"type":"object"},"outputSchema":{},"annotations":{"readOnlyHint":true,"openWorldHint":false},"_meta":{"a":1}}],"nextCursor":"same"}"""))) }
        val tools = c.listTools(); assertEquals(2, tools.size); assertEquals(true, tools[0].annotations!!.readOnlyHint)
        assertEquals(false, tools[0].annotations!!.openWorldHint); assertEquals("same", t.sent.last().obj("params")!!.text("cursor")); assertTrue(logs.any { "重复" in it }); c.close()
    }
    @Test fun `tool pagination also stops at page cap and completes normal pages`() = runTest {
        for (cap in listOf(2, 100)) {
            val logs = mutableListOf<String>(); val t = FakeTransport(); val c = client(t, McpClientOptions("1", maxToolPages = cap, onDebug = logs::add)); c.connect(); var pages = 0
            t.respond = { m -> if (m.text("method") == "tools/list") t.received.send(result(m.getValue("id"), buildJsonObject { put("tools", JsonArray(emptyList())); if (++pages < 3) put("nextCursor", "p$pages") })) }
            assertTrue(c.listTools().isEmpty()); assertEquals(if (cap == 2) 2 else 3, pages); assertEquals(cap == 2, logs.any { "上限" in it }); c.close()
        }
    }
    @Test fun `tool results preserve every content kind structured output errors and unknown extensions`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect()
        t.respond = { m -> if (m.text("method") == "tools/call") t.received.send(result(m.getValue("id"), json("""{"content":[{"type":"text","text":"t"},{"type":"image","data":"a","mimeType":"image/png"},{"type":"audio","data":"b","mimeType":"audio/wav"},{"type":"resource_link","uri":"file:///a","name":"a","title":"A"},{"type":"resource","resource":{"uri":"file:///b","mimeType":"text/plain","text":"B","blob":"c"}},{"type":"future","x":1}],"structuredContent":{"n":1},"isError":true}"""))) }
        val r = c.callTool("x", emptyObject, idleTimeoutMs = 1_000)
        assertTrue(r.isError); assertEquals(json("""{"n":1}"""), r.structuredContent)
        assertEquals(listOf(McpContent.Text::class, McpContent.Image::class, McpContent.Audio::class, McpContent.ResourceLink::class, McpContent.EmbeddedResource::class, McpContent.Unknown::class), r.content.map { it::class }); c.close()
    }
    @Test fun `progress routes only its token resets idle clock and completes long operation`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.respond = {}; val seen = mutableListOf<McpProgress>(); val done = CompletableDeferred<Unit>()
        val call = async { c.callTool("x", emptyObject, { seen.add(it); if (seen.size == 3) done.complete(Unit) }, 100, 1_000) }; runCurrent()
        val id = t.sent.last().getValue("id"); assertEquals(id, t.sent.last().obj("params")!!.obj("_meta")!!["progressToken"])
        repeat(3) { advanceTimeBy(80); t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", id); put("progress", it); put("total", 3) })); runCurrent() }
        t.received.send(result(id, json("""{"content":[]}"""))); call.await(); done.await(); assertEquals(3, seen.size); c.close()
    }
    @Test fun `hard timeout is not extended by progress and sends cancellation`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.respond = {}
        val call = async { try { c.callTool("x", emptyObject, {}, 100, 250); null } catch (e: McpTimeoutException) { e } }; runCurrent()
        val id = t.sent.last().getValue("id")
        repeat(3) { advanceTimeBy(80); t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", id); put("progress", it) })); runCurrent() }
        advanceTimeBy(11); runCurrent(); assertEquals(McpTimeoutException.Kind.Hard, call.await()!!.kind)
        assertEquals(id, t.sent.last().obj("params")!!["requestId"]); assertEquals("notifications/cancelled", t.sent.last().text("method")); c.close()
    }
    @Test fun `idle timeout and caller cancellation each notify server with original request id`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.respond = {}
        val idle = async { try { c.callTool("x", emptyObject, idleTimeoutMs = 100); null } catch (e: McpTimeoutException) { e } }; runCurrent()
        assertFalse(t.sent.last().obj("params")!!.containsKey("_meta")); advanceTimeBy(101); runCurrent()
        assertEquals(McpTimeoutException.Kind.Idle, idle.await()!!.kind)
        val cancel = async { c.callTool("y", emptyObject, idleTimeoutMs = 1000) }; runCurrent(); val id = t.sent.last()["id"]; cancel.cancelAndJoin()
        assertEquals(id, t.sent.last().obj("params")!!["requestId"]); assertTrue(cancel.isCancelled); c.close()
    }
    @Test fun `list changed notification and active ping work`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect()
        val event = async(start = CoroutineStart.UNDISPATCHED) { c.notifications.first() }
        t.received.send(rpcNotification("notifications/tools/list_changed")); assertEquals(McpNotification.ToolsListChanged, event.await()); c.ping(); assertEquals("ping", t.sent.last().text("method")); c.close()
    }
    @Test fun `disconnect fails all pending calls and preserves process diagnostics`() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); t.respond = {}
        val calls = List(2) { async { try { c.callTool("x", emptyObject, idleTimeoutMs = 1_000); null } catch (e: McpTransportClosed) { e } } }; runCurrent()
        t.received.close(McpTransportClosed("连接已断开", 7, listOf("err"))); runCurrent()
        calls.forEach { assertEquals(7, it.await()!!.exitCode) }; assertEquals(listOf("err"), (c.state.value as McpConnectionState.Closed).stderrTail); c.close()
    }
    @Test fun `scope cancellation closes transport and callbacks do not swallow cancellation`() = runTest {
        val t = FakeTransport(); val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)); val c = McpClient(t, scope, McpClientOptions("1"))
        c.connect(); scope.cancel(); runCurrent(); assertTrue(t.closed); assertTrue(c.state.value is McpConnectionState.Closed)
    }
}
