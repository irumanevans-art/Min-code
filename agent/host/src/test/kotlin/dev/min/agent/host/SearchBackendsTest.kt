package dev.min.agent.host

import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import dev.min.agent.testkit.FakeHttpTransport.Reply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

/** 样本按官方响应形状脱敏构造；不伪称真实抓包，也不访问外网。 */
class SearchBackendsTest {
    private fun sample(name: String) = javaClass.getResource("/web-search/$name.json")!!.readText()
    private fun backends(http: HttpTransport): List<SearchBackend> = listOf(
        BraveSearchBackend(FakeSecrets("test-only-key"), http), TavilySearchBackend(FakeSecrets("test-only-key"), http),
        SearXngSearchBackend("https://search.example.com", http),
    )

    @Test fun `brave request contains encoded query and token only in header`() = runBlocking {
        val http = FakeHttpTransport(Reply.Body(sample("brave")))
        val hits = BraveSearchBackend(FakeSecrets("test-only-key"), http).search("kotlin 中文&?", listOf("example.com", "docs.test.com"), emptyList(), 10)
        val req = http.requests.single()
        val decoded = URLDecoder.decode(req.url, "UTF-8")
        assertEquals("GET", req.method)
        assertTrue(decoded.contains("q=kotlin 中文&? (site:example.com OR site:docs.test.com)&count=10"))
        assertEquals("test-only-key", http.header("X-Subscription-Token"))
        assertNull(req.body)
        assertFalse(req.url.contains("test-only-key"))
        assertFalse(req.toString().contains("test-only-key"))
        assertEquals(listOf("Kotlin", "Docs"), hits.map { it.title })
    }

    @Test fun `tavily request uses bearer header native domains and no key in body`() = runBlocking {
        val http = FakeHttpTransport(Reply.Body(sample("tavily")))
        val hits = TavilySearchBackend(FakeSecrets("test-only-key"), http).search("kotlin", emptyList(), listOf("example.com"), 3)
        val body = Json.parseToJsonElement(http.lastBody).jsonObject
        assertEquals("POST", http.requests.single().method)
        assertEquals("Bearer test-only-key", http.header("Authorization"))
        assertEquals("kotlin", body["query"]!!.jsonPrimitive.content)
        assertEquals(3, body["max_results"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(listOf(JsonPrimitive("example.com"))), body["exclude_domains"])
        assertEquals(JsonPrimitive(false), body["include_answer"])
        assertFalse(http.lastBody.contains("test-only-key"))
        assertEquals(listOf("Wrong"), hits.map { it.title })
    }

    @Test fun `searxng request uses json scoped query and no credentials`() = runBlocking {
        val http = FakeHttpTransport(Reply.Body(sample("searxng")))
        val hits = SearXngSearchBackend("https://search.example.com/base/", http).search("a & b", emptyList(), listOf("badexample.com"), 1)
        val req = http.requests.single()
        assertTrue(req.url.startsWith("https://search.example.com/base/search?"))
        assertTrue(URLDecoder.decode(req.url, "UTF-8").contains("q=a & b -site:badexample.com&format=json"))
        assertEquals(listOf("Accept" to "application/json"), req.headers)
        assertEquals(listOf(SearchHit("Kotlin", "https://example.com/kotlin", "Kotlin & JVM")), hits)
    }

    @Test fun `all backends enforce allowed and blocked domains locally and honor limit`() = runBlocking {
        for ((i, name) in listOf("brave", "tavily", "searxng").withIndex()) {
            val http = FakeHttpTransport(Reply.Body(sample(name)), Reply.Body(sample(name)), Reply.Body(sample(name)))
            val backend = backends(http)[i]
            assertEquals(2, backend.search("x", listOf("example.com"), emptyList(), 10).size)
            assertEquals(listOf("Wrong"), backend.search("x", emptyList(), listOf("example.com"), 10).map { it.title })
            assertEquals(1, backend.search("x", emptyList(), emptyList(), 1).size)
        }
    }

    @Test fun `backend errors cannot become search hits or expose response secrets`() = runBlocking {
        for (i in 0..2) for (reply in listOf(Reply.Status(503, "echo test-only-key"), Reply.Body("<html>test-only-key</html>"), Reply.Body("{\"error\":\"test-only-key\"}"))) {
            val backend = backends(FakeHttpTransport(reply))[i]
            try { backend.search("x", emptyList(), emptyList(), 10); fail("must fail") } catch (e: IllegalStateException) {
                assertFalse(e.toString().contains("test-only-key"))
                assertNull(e.cause)
            }
        }
    }

    @Test fun `empty results and brave no web results are valid`() = runBlocking {
        val brave = BraveSearchBackend(FakeSecrets(), FakeHttpTransport(Reply.Body("{\"type\":\"search\"}")))
        assertTrue(brave.search("x", emptyList(), emptyList(), 10).isEmpty())
        val tavily = TavilySearchBackend(FakeSecrets(), FakeHttpTransport(Reply.Body("{\"results\":[]}")))
        assertTrue(tavily.search("x", emptyList(), emptyList(), 10).isEmpty())
    }

    @Test fun `searxng invalid base urls and missing credentials fail before sending`() = runBlocking {
        for (url in listOf("file:///tmp", "https://key:pass@example.com", "https://example.com?key=x"))
            assertThrows(IllegalArgumentException::class.java) { SearXngSearchBackend(url) }
        val http = FakeHttpTransport()
        val backend = BraveSearchBackend(Secrets { null }, http)
        try { backend.search("x", emptyList(), emptyList(), 1); fail("no key") } catch (_: IllegalStateException) { }
        assertTrue(http.requests.isEmpty())
    }
}
