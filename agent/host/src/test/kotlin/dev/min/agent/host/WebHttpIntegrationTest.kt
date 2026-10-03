package dev.min.agent.host

import com.sun.net.httpserver.HttpServer
import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class WebHttpIntegrationTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = this@WebHttpIntegrationTest.executor; start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    @After fun stop() { server.stop(0); executor.shutdownNow() }
    private val transport = object : HttpTransport {
        private val http = UrlConnectionTransport()
        override suspend fun send(request: HttpRequest): HttpResponse = http.send(HttpRequest(request.method,
            base + URI(request.url).rawPath + (URI(request.url).rawQuery?.let { "?$it" } ?: ""), request.headers, request.body, request.timeoutMs))
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
    }
    private val converter = PageConverter { text, _ -> PageContent(null, text) }

    @Test fun `real transport honors redirects headers cache and deadline without external network`() = runBlocking {
        val pageCalls = AtomicInteger()
        var accept: String? = null
        var ua: String? = null
        server.createContext("/start") { ex ->
            ex.responseHeaders.add("Location", "/page")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        server.createContext("/page") { ex ->
            pageCalls.incrementAndGet()
            accept = ex.requestHeaders.getFirst("Accept"); ua = ex.requestHeaders.getFirst("User-Agent")
            val body = "# full page".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/markdown")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        val port = CachedWebPort(converter, transport)
        val page = port.fetch("https://example.com/start") as WebResponse.Page
        assertEquals("https://example.com/page", page.url)
        assertEquals("# full page", page.content.markdown)
        assertEquals(page, port.fetch("https://example.com/start"))
        assertEquals(1, pageCalls.get())
        assertTrue(accept!!.startsWith("text/markdown")); assertTrue(ua!!.startsWith("Min-Agent"))
    }

    @Test fun `streaming slow response cannot extend total deadline`() = runBlocking {
        server.createContext("/slow") { ex ->
            ex.responseHeaders.add("Content-Type", "text/plain")
            ex.sendResponseHeaders(200, 0)
            try { ex.responseBody.use { body -> repeat(20) { body.write('x'.code); body.flush(); Thread.sleep(40) } } }
            catch (_: Exception) { ex.close() }
        }
        val port = CachedWebPort(converter, transport, deadlineMs = 200)
        val start = System.nanoTime()
        try { port.fetch("https://example.com/slow"); fail("deadline should fail") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("截止"))
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test fun `real search transport sends tavily credential only in header`() = runBlocking {
        var key: String? = null
        var body: String? = null
        server.createContext("/search") { ex ->
            key = ex.requestHeaders.getFirst("Authorization")
            body = ex.requestBody.readBytes().decodeToString()
            val bytes = """{"results":[{"title":"Test","url":"https://example.com","content":"Snippet"}]}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        val backend = TavilySearchBackend(Secrets { Secret("test-only-key") }, UrlConnectionTransport(), "$base/search")
        assertEquals(1, backend.search("test", emptyList(), emptyList(), 10).size)
        assertEquals("Bearer test-only-key", key)
        assertFalse(body!!.contains("test-only-key"))
    }
}
