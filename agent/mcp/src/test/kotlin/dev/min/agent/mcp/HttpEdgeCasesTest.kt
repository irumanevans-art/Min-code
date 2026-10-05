package dev.min.agent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpEdgeCasesTest {
    private suspend fun withClient(t: McpTransport, block: suspend CoroutineScope.(McpClient) -> Unit) = coroutineScope {
        val c = McpClient(t, this, McpClientOptions("1", requestTimeoutMs = 3_000))
        try { withTimeout(10_000) { block(c) } } finally { c.close() }
    }
    @Test fun `post SSE resume 404 reinitializes and resends once but second expiry fails`() = runBlocking {
        for (failAgain in listOf(false, true)) LoopbackHttp().use { s ->
            val initializations = AtomicInteger(); val gets = AtomicInteger()
            val initializeSessions = Collections.synchronizedList(mutableListOf<String?>())
            val calls = Collections.synchronizedList(mutableListOf<JsonObject>())
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod == "GET") { gets.incrementAndGet(); assertEquals("resume", e.requestHeaders.getFirst("Last-Event-ID")); e.reply(404) }
                else if (e.requestMethod != "POST") e.reply(405)
                else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> {
                            initializeSessions += e.requestHeaders.getFirst("Mcp-Session-Id")
                            e.responseHeaders.add("Mcp-Session-Id", "s${initializations.incrementAndGet()}")
                            e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                        }
                        "tools/call" -> {
                            calls += m
                            if (failAgain || e.requestHeaders.getFirst("Mcp-Session-Id") == "s1")
                                e.reply(200, "id: resume\nretry: 1\n\n", "text/event-stream")
                            else e.reply(200, result(m.getValue("id"), json("""{"content":[]}""")).toString())
                        }
                        else -> e.reply(202)
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c ->
                c.connect()
                val failure = try { c.callTool("x", emptyObject, idleTimeoutMs = 3_000); null } catch (e: McpHttpException) { e }
                if (failAgain) assertEquals(404, failure!!.status) else assertNull(failure)
            }
            assertEquals(2, initializations.get()); assertEquals(listOf(null, null), initializeSessions)
            assertEquals(2, calls.size); assertEquals(calls[0], calls[1]); assertEquals(if (failAgain) 2 else 1, gets.get())
        }
    }
    @Test fun `concurrent session 404s share one reinitialization`() = runBlocking {
        LoopbackHttp().use { s ->
            val initializations = AtomicInteger(); val oldPosts = CountDownLatch(2)
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> { e.responseHeaders.add("Mcp-Session-Id", "s${initializations.incrementAndGet()}"); e.reply(200, result(m.getValue("id"), initializeResult()).toString()) }
                        "notifications/initialized" -> e.reply(202)
                        else -> if (e.requestHeaders.getFirst("Mcp-Session-Id") == "s1") { oldPosts.countDown(); oldPosts.await(2, TimeUnit.SECONDS); e.reply(404) }
                            else e.reply(200, result(m.getValue("id"), emptyObject).toString())
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c -> c.connect(); awaitAll(async { c.ping() }, async { c.ping() }) }
            assertEquals(2, initializations.get())
        }
    }
    @Test fun `resumed get without new id preserves prior last event id`() = runBlocking {
        LoopbackHttp().use { s ->
            val gets = AtomicInteger(); val ids = Collections.synchronizedList(mutableListOf<String?>()); val done = CompletableDeferred<Unit>()
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod == "GET") {
                    ids += e.requestHeaders.getFirst("Last-Event-ID")
                    when (gets.incrementAndGet()) {
                        1 -> e.reply(200, "id: original\nretry: 1\n\n", "text/event-stream")
                        2 -> e.reply(200, "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n", "text/event-stream")
                        else -> { e.reply(405); done.complete(Unit) }
                    }
                } else if (e.requestMethod != "POST") e.reply(405)
                else { val m = json(e.requestBody.readBytes().decodeToString()); if (m.text("method") == "initialize") e.reply(200, result(m.getValue("id"), initializeResult()).toString()) else e.reply(202) }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http)) { c -> c.connect(); done.await() }
            assertEquals(listOf(null, "original", "original"), ids)
        }
    }
    @Test fun `exhausted initial retries stop after four requests while client errors get one attempt`() = runBlocking {
        for (status in listOf(500, 502, 429)) LoopbackHttp().use { s ->
            val count = AtomicInteger(); s.server.createContext("/mcp") { e -> count.incrementAndGet(); e.reply(status) }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, retryDelaysMs = listOf(1, 2, 4))) { c ->
                try { c.connect(); fail("expected") } catch (e: McpHttpException) { assertEquals(status, e.status) }
            }
            assertEquals(if (status == 429) 1 else 4, count.get())
        }
    }
    @Test fun `oversized server message closes HTTP connection and fails other pending calls`() = runBlocking {
        LoopbackHttp().use { s ->
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    if (m.text("method") == "initialize") e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                    else if (m.text("method") == "ping") e.reply(200, "x".repeat(1001)) else e.reply(202)
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, maxMessageBytes = 1000, enableGetStream = false)) { c ->
                c.connect(); try { c.ping(); fail("expected") } catch (_: McpTransportClosed) {}
                withTimeout(2_000) { while (c.state.value !is McpConnectionState.Closed) delay(5) }
            }
        }
    }

    @Test fun `header provider and HTTP port exceptions cannot expose header or endpoint secrets`() = runBlocking {
        LoopbackHttp().use { s ->
            val http = object : dev.min.agent.spi.HttpTransport by s.http {
                override suspend fun open(request: dev.min.agent.spi.HttpRequest): dev.min.agent.spi.HttpStreamingResponse =
                    throw IllegalArgumentException("bad header value secret http://user:pass@host/?token=hidden")
            }
            for (brokenProvider in listOf(false, true)) {
                val t = StreamableHttpMcpTransport(s.url + "/mcp?token=hidden", { if (brokenProvider) error("header secret") else emptyList() }, http)
                withClient(t) { c ->
                    val e = try { c.connect(); null } catch (e: McpTransportClosed) { e }
                    assertNotNull(e); for (secret in listOf("secret", "pass", "hidden")) assertFalse(e.toString().contains(secret))
                }
            }
        }
    }

    @Test fun `optional get authentication failure reaches typed closed state`() = runBlocking {
        LoopbackHttp().use { s ->
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod == "GET") e.reply(401)
                else if (e.requestMethod != "POST") e.reply(405)
                else { val m = json(e.requestBody.readBytes().decodeToString()); if (m.text("method") == "initialize") e.reply(200, result(m.getValue("id"), initializeResult()).toString()) else e.reply(202) }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http)) { c ->
                try { c.connect() } catch (e: McpTransportClosed) { assertTrue(e.needsAuth) }
                withTimeout(2_000) { while (c.state.value !is McpConnectionState.Closed) delay(5) }
                val state = c.state.value as McpConnectionState.Closed
                assertEquals(401, state.httpStatus); assertTrue(state.needsAuth)
            }
        }
    }

    @Test fun `failed expiry reinitialization closes client and preserves authentication classification`() = runBlocking {
        LoopbackHttp().use { s ->
            val initializes = AtomicInteger()
            s.server.createContext("/mcp") { e ->
                if (e.requestMethod != "POST") e.reply(405) else {
                    val m = json(e.requestBody.readBytes().decodeToString())
                    when (m.text("method")) {
                        "initialize" -> if (initializes.incrementAndGet() == 1) {
                            e.responseHeaders.add("Mcp-Session-Id", "old"); e.reply(200, result(m.getValue("id"), initializeResult()).toString())
                        } else e.reply(403)
                        "notifications/initialized" -> e.reply(202)
                        else -> e.reply(404)
                    }
                }
            }
            withClient(StreamableHttpMcpTransport(s.url + "/mcp", { emptyList() }, s.http, enableGetStream = false)) { c ->
                c.connect(); try { c.ping(); fail("expected") } catch (e: Exception) { assertTrue(e is McpTransportClosed || e is McpHttpException) }
                val state = c.state.value as McpConnectionState.Closed; assertEquals(403, state.httpStatus); assertTrue(state.needsAuth)
                try { c.connect(); fail("closed client must not return cached handshake") } catch (e: McpTransportClosed) { assertTrue(e.needsAuth) }
            }
            assertEquals(2, initializes.get())
        }
    }
}
