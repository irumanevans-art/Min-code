package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.*
import org.junit.Test

class CachedWebPortTest {
    private val url = "https://example.com/start"
    private var now = 0L
    private val requests = mutableListOf<HttpRequest>()
    private var calls = 0
    private val converted = mutableListOf<Pair<String, String?>>()
    private val converter = PageConverter { html, base ->
        converted += html to base
        PageContent("title", "converted:$html")
    }
    private fun port(ttl: Long = 900_000, deadline: Long = 300_000, response: suspend (HttpRequest) -> HttpResponse) = CachedWebPort(
        converter, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse { requests += request; calls++; return response(request) }
            override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        }, ttl, deadline, Clock { now },
    )
    private fun response(type: String = "text/html", body: String = "<b>Hello</b>") = HttpResponse(200, listOf("Content-Type" to type), body.toByteArray())

    @Test fun `cache includes conversion expires at ttl and upgrades before lookup`() = runBlocking {
        val port = port { response() }
        val first = port.fetch(url.replace("https:", "http:"))
        now = 899_999
        assertEquals(first, port.fetch(url))
        assertEquals(1, calls)
        assertEquals(1, converted.size)
        now = 900_000
        port.fetch(url)
        assertEquals(2, calls)
        assertEquals(2, converted.size)
        assertEquals("https://example.com/start", requests.first().url)
        assertTrue(requests.first().headers.first { it.first == "Accept" }.second.startsWith("text/markdown"))
        assertTrue(requests.first().headers.first { it.first == "User-Agent" }.second.startsWith("Min-Agent"))
    }

    @Test fun `cache zero disables cache`() = runBlocking {
        val port = port(ttl = 0) { response() }
        port.fetch(url); port.fetch(url)
        assertEquals(2, calls)
    }

    @Test fun `same host redirects follow relative paths and upgrade downgrade`() = runBlocking {
        val port = port {
            if (calls == 1) throw HttpStatusException(302, listOf("location" to "/next"), "")
            if (calls == 2) return@port HttpResponse(307, listOf("Location" to "http://example.com/final"), ByteArray(0))
            response()
        }
        val page = port.fetch(url) as WebResponse.Page
        assertEquals("https://example.com/final", page.url)
        assertEquals(listOf(url, "https://example.com/next", "https://example.com/final"), requests.map { it.url })
        assertEquals("https://example.com/final", converted.single().second)
    }

    @Test fun `cross host redirect is not fetched or cached`() = runBlocking {
        val port = port { HttpResponse(301, listOf("LOCATION" to "https://other.com/page"), ByteArray(0)) }
        assertEquals(WebResponse.Redirect(url, "https://other.com/page"), port.fetch(url))
        assertEquals(1, calls)
        port.fetch(url)
        assertEquals(2, calls)
        assertTrue(converted.isEmpty())
    }

    @Test fun `invalid redirect local redirect loop and too many hops fail`() = runBlocking {
        for (target in listOf("http://localhost/path", "file:///tmp/x", "/start")) {
            val port = port { HttpResponse(302, listOf("Location" to target), ByteArray(0)) }
            try { port.fetch(url); fail("redirect should fail") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
        val many = port { HttpResponse(302, listOf("Location" to "/hop$calls"), ByteArray(0)) }
        try { many.fetch(url); fail("too many hops") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("超过")) }
    }

    @Test fun `plain text markdown and xhtml select correct conversion`() = runBlocking {
        for (type in listOf("text/plain", "text/markdown", "text/x-markdown")) {
            assertEquals("# Full & raw", (port { response(type, "# Full & raw") }.fetch(url) as WebResponse.Page).content.markdown)
        }
        assertTrue(converted.isEmpty())
        assertEquals("converted:<b>Hello</b>", (port { response("APPLICATION/XHTML+XML; charset=UTF-8") }.fetch(url) as WebResponse.Page).content.markdown)
    }

    @Test fun `unsupported type size and absent type skip conversion`() = runBlocking {
        for (type in listOf("application/pdf", "image/png", "")) {
            val port = port { response(type, "binary") }
            val page = port.fetch(url) as WebResponse.Unsupported
            assertEquals(6, page.sizeBytes.toInt())
            assertEquals(type.ifBlank { "application/octet-stream" }, page.mediaType)
        }
        assertTrue(converted.isEmpty())
    }

    @Test fun `deadline covers all redirects and failures are not cached`() = runBlocking {
        val port = port(deadline = 150) {
            delay(90)
            if (calls == 1) HttpResponse(302, listOf("Location" to "/next"), ByteArray(0)) else response()
        }
        try { port.fetch(url); fail("must time out across hops") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("截止")) }
        assertEquals(2, calls)
        assertTrue(port.fetch(url) is WebResponse.Page)
        assertEquals(3, calls)
    }

    @Test fun `zero deadline allows slow request and external cancellation propagates`() = runBlocking {
        val port = port(deadline = 0) { delay(200); response() }
        assertTrue(port.fetch(url) is WebResponse.Page)
        val forever = port { awaitCancellation() }
        val job = launch { forever.fetch(url) }
        yield(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun `http errors do not become content or enter cache`() = runBlocking {
        val port = port { throw HttpStatusException(500, emptyList(), "secret echo") }
        repeat(2) {
            try { port.fetch(url); fail("must fail") } catch (e: IllegalStateException) {
                assertFalse(e.message!!.contains("secret echo"))
            }
        }
        assertEquals(2, calls)
        assertTrue(converted.isEmpty())
    }
}
