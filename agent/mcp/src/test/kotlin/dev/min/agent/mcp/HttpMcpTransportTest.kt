package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class HttpMcpTransportTest {
    private suspend fun withClient(t: McpTransport, block: suspend (McpClient) -> Unit) = coroutineScope {
        val c = McpClient(t, this, McpClientOptions("1", requestTimeoutMs = 3_000))
        try { withTimeout(10_000) { block(c) } } finally { c.close() }
    }
    @Test fun `session header version json response get 405 and delete 405`() = runBlocking {
        LoopbackHttp().use { s ->
            val seen = Collections.synchronizedList(mutableListOf<Triple<String, String?, String?>>())
            s.server.createContext("/mcp") { e ->
                seen += Triple(e.requestMethod, e.requestHeaders.getFirst("Mcp-Session-Id"), e.requestHeaders.getFirst("MCP-Protocol-Version"))
                if (e.requestMethod != "POST") e.reply(405)
                else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> { e.responseHeaders.add("Mcp-Session-Id", "session1"); e.reply(200, result(m.getValue("id"), initializeResult("2025-06-18")).toString()) }
                        "notifications/initialized" -> e.reply(202)
                        "tools/list" -> e.reply(200, result(m.getValue("id"), json("""{"tools":[]}""")).toString())
                        else -> e.reply(200, result(m.getValue("id"), emptyObject).toString())
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { listOf("Authorization" to "secret", "MCP-Protocol-Version" to "wrong") }, s.http)) { c ->
                c.connect(); c.listTools(); c.ping(); delay(100)
            }
            assertNull(seen.first().second); assertNull(seen.first().third)
            seen.drop(1).forEach { assertEquals("session1", it.second); assertEquals("2025-06-18", it.third) }
            assertTrue(seen.any { it.first == "GET" }); assertTrue(seen.any { it.first == "DELETE" })
        }
    }
    @Test fun `404 expires session reinitializes once without old id and resends original request`() = runBlocking {
        LoopbackHttp().use { s ->
            val initialized = AtomicInteger(); val toolRequests = Collections.synchronizedList(mutableListOf<JsonObject>()); val initializeSessions = Collections.synchronizedList(mutableListOf<String?>())
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405)
                else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> { initializeSessions += e.requestHeaders.getFirst("Mcp-Session-Id"); e.responseHeaders.add("Mcp-Session-Id", "s${initialized.incrementAndGet()}"); e.reply(200, result(m.getValue("id"), initializeResult()).toString()) }
                        "notifications/initialized" -> e.reply(202)
                        "tools/call" -> { toolRequests += m; if (e.requestHeaders.getFirst("Mcp-Session-Id") == "s1") e.reply(404) else e.reply(200, result(m.getValue("id"), json("""{"content":[{"type":"text","text":"ok"}]}""")).toString()) }
                        else -> e.reply(202)
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c ->
                c.connect(); assertEquals(McpContent.Text("ok"), c.callTool("x", emptyObject, idleTimeoutMs = 3_000).content.single())
            }
            assertEquals(2, initialized.get()); assertEquals(listOf(null, null), initializeSessions); assertEquals(toolRequests[0], toolRequests[1])
        }
    }
    @Test fun `second 404 after recovery fails instead of looping`() = runBlocking {
        LoopbackHttp().use { s ->
            val count = AtomicInteger()
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> { e.responseHeaders.add("Mcp-Session-Id", "s${count.incrementAndGet()}"); e.reply(200, result(m.getValue("id"), initializeResult()).toString()) }
                        "notifications/initialized" -> e.reply(202)
                        else -> e.reply(404)
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c ->
                c.connect(); val e = try { c.ping(); null } catch (e: McpHttpException) { e }; assertEquals(404, e!!.status)
            }
            assertEquals(2, count.get())
        }
    }
    @Test fun `post SSE routes progress server ping and notification before matching result`() = runBlocking {
        LoopbackHttp().use { s ->
            val replies = Collections.synchronizedList(mutableListOf<JsonObject>())
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                        "tools/call" -> {
                            val token = m.obj("params")!!.obj("_meta")!!["progressToken"]
                            e.reply(200, "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":$token,\"progress\":1}}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":\"server\",\"method\":\"ping\"}\n\n" +
                                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n" +
                                "data: ${result(m.getValue("id"), json("""{"content":[]}"""))}\n\n", "text/event-stream")
                        }
                        else -> { replies += m; e.reply(202) }
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c ->
                c.connect(); val progress = CompletableDeferred<McpProgress>(); val notification = async(start = CoroutineStart.UNDISPATCHED) { c.notifications.first() }
                c.callTool("x", emptyObject, { progress.complete(it) }, 3_000); assertEquals(1.0, progress.await().progress, 0.0); notification.await()
                withTimeout(2_000) { while (replies.none { it.text("id") == "server" }) delay(10) }
                assertEquals(emptyObject, replies.first { it.text("id") == "server" }["result"])
            }
        }
    }
    @Test fun `post SSE resumes through GET with last event id and retry`() = runBlocking {
        LoopbackHttp().use { s ->
            var id: JsonElement = JsonPrimitive(0); var last: String? = null
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod == "GET") { last = e.requestHeaders.getFirst("Last-Event-ID"); e.reply(200, "id: done\ndata: ${result(id, json("""{"content":[]}"""))}\n\n", "text/event-stream") }
                else if (e.requestMethod != "POST") e.reply(405)
                else { val m = json(e.requestBody.readBytes().decodeToString()); when (m.text("method")) {
                    "initialize" -> e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                    "tools/call" -> { id = m.getValue("id"); e.reply(200, "id: cursor1\nretry: 1\n\n", "text/event-stream") }
                    else -> e.reply(202)
                } }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c -> c.connect(); c.callTool("x", emptyObject, idleTimeoutMs = 3_000) }
            assertEquals("cursor1", last)
        }
    }
    @Test fun `optional get SSE reconnect sends last event id`() = runBlocking {
        LoopbackHttp().use { s ->
            val seen = CompletableDeferred<String?>(); val gets = AtomicInteger()
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod == "GET") {
                    if (gets.incrementAndGet() == 1) e.reply(200, "id: g1\nretry: 1\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n", "text/event-stream")
                    else { seen.complete(e.requestHeaders.getFirst("Last-Event-ID")); e.reply(405) }
                } else if (e.requestMethod != "POST") e.reply(405)
                else { val m = json(e.requestBody.readBytes().decodeToString()); if (m.text("method") == "initialize") e.reply(200, result(m.getValue("id"), initializeResult()).toString()) else e.reply(202) }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http)) { c -> c.connect(); assertEquals("g1", withTimeout(3_000) { seen.await() }) }
        }
    }
    @Test fun `401 and 403 are needs auth do not retry and redact endpoint secrets`() = runBlocking {
        for (status in listOf(401, 403, 404)) LoopbackHttp().use { s ->
            val count = AtomicInteger(); s.server.createContext("/mcp") { e -> count.incrementAndGet(); e.reply(status, "secret header-value") }
            val t = StreamableHttpMcpTransport(s.url.replace("//", "//user:pass@") + "/mcp?key=secret", { listOf("Authorization" to "header-value") }, s.http, retryDelaysMs = listOf(1, 2, 4))
            withClient(t) { c -> val e = try { c.connect(); null } catch (e: McpHttpException) { e }; assertEquals(status, e!!.status); assertEquals(status != 404, e.needsAuth); assertFalse(e.toString().contains("secret")); assertFalse(e.toString().contains("pass")); assertFalse(e.toString().contains("header-value")) }
            assertEquals(if (status == 404) 2 else 1, count.get()); assertFalse(t.toString().contains("secret"))
        }
    }
    @Test fun `initial 5xx retries three times and network io errors also retry`() = runBlocking {
        LoopbackHttp().use { s ->
            val count = AtomicInteger()
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else { val m = json(e.requestBody.readBytes().decodeToString()); if (m.text("method") == "initialize") {
                    if (count.incrementAndGet() < 4) e.reply(503) else e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                } else e.reply(202) }
            }
            val ioAttempts = AtomicInteger()
            val http = object : HttpTransport by s.http {
                override suspend fun open(request: HttpRequest): HttpStreamingResponse {
                    if (ioAttempts.incrementAndGet() <= 2) throw java.net.ConnectException("http://user:pass@host?secret")
                    return s.http.open(request)
                }
            }
            // 分别验证 HTTP 状态重试和连接错误；每次 initialize 最多初次加三次。
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false, retryDelaysMs = listOf(1, 2, 4))) { it.connect() }
            assertEquals(4, count.get()); count.set(3)
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, http, enableGetStream = false, retryDelaysMs = listOf(1, 2, 4))) { it.connect() }
            assertTrue(ioAttempts.get() >= 3)
        }
    }
    @Test fun `legacy SSE resolves relative endpoint and receives messages on get stream`() = runBlocking {
        LoopbackHttp().use { s ->
            val output = CompletableDeferred<java.io.OutputStream>(); val closed = CompletableDeferred<Unit>(); val auth = Collections.synchronizedList(mutableListOf<String?>())
            s.server.createContext("/sse") { e ->
                e.responseHeaders.add("Content-Type", "text/event-stream"); e.sendResponseHeaders(200, 0)
                e.responseBody.write("event: endpoint\ndata: /post?session=one\n\n".toByteArray()); e.responseBody.flush(); output.complete(e.responseBody)
                runBlocking { closed.await() }; e.close()
            }
            s.server.createContext("/post") { e ->
                auth += e.requestHeaders.getFirst("Authorization"); val m = json(e.requestBody.readBytes().decodeToString())
                if ("id" in m) {
                    val r = result(m.getValue("id"), if (m.text("method") == "initialize") initializeResult("2024-11-05") else json("""{"content":[{"type":"text","text":"legacy"}]}"""))
                    runBlocking { output.await().apply { write("event: message\ndata: $r\n\n".toByteArray()); flush() } }
                }
                e.reply(202)
            }
            try { withClient(LegacySseMcpTransport(s.url + "/sse", { listOf("Authorization" to "token") }, s.http)) { c ->
                assertEquals("2024-11-05", c.connect().protocolVersion); assertEquals(McpContent.Text("legacy"), c.callTool("x", emptyObject, idleTimeoutMs = 3_000).content.single())
            } } finally { closed.complete(Unit) }
            assertTrue(auth.isNotEmpty()); assertTrue(auth.all { it == "token" })
        }
    }
    @Test fun `legacy rejects endpoint with different scheme host port or userinfo`() = runBlocking {
        for (endpoint in listOf("http://evil.invalid/post", "https://127.0.0.1/post", "http://127.0.0.1:1/post", "http://user:pass@127.0.0.1/post")) LoopbackHttp().use { s ->
            s.server.createContext("/sse") { e -> e.reply(200, "event: endpoint\ndata: $endpoint\n\n", "text/event-stream") }
            withClient(LegacySseMcpTransport(s.url + "/sse", { listOf("Authorization" to "secret") }, s.http)) { c ->
                val e = try { c.connect(); null } catch (e: McpTransportClosed) { e }; assertTrue(e!!.reason.contains("跨源")); assertFalse(e.toString().contains("secret")); assertFalse(e.toString().contains("pass"))
            }
        }
    }
    @Test fun `raw HTTP message limits and unsupported content type fail safely`() = runBlocking {
        for (body in listOf("x".repeat(201), "<html>secret</html>")) LoopbackHttp().use { s ->
            s.server.createContext("/mcp") { e -> e.reply(200, body, if (body.startsWith("<")) "text/html" else "application/json") }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, maxMessageBytes = 200, enableGetStream = false)) { c ->
                val e = try { c.connect(); null } catch (e: McpException) { e }; assertNotNull(e); assertFalse(e.toString().contains("secret"))
            }
        }
    }
}
