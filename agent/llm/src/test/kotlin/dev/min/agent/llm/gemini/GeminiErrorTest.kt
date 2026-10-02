package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class GeminiErrorTest {
    private fun http(status: Int, body: String = "denied", headers: List<Pair<String, String>> = emptyList(), patterns: List<String> = emptyList()) =
        run(FakeHttpTransport(FakeHttpTransport.Reply.Status(status, body, headers)), provider = provider().copy(quirks = ProviderQuirks(overflowPatterns = patterns))).modelError()

    @Test fun `http auth wins over overflow`() {
        for (code in listOf(401, 403)) { val e = http(code, "prompt is too long"); assertEquals(ModelFailureKind.Auth, e.kind); assertFalse(e.retryable); assertTrue(e.beforeFirstByte) }
    }
    @Test fun `http ordinary 4xx include not found and payload too large`() {
        for (code in listOf(400, 402, 404, 409, 413, 422)) { val e = http(code); assertEquals(ModelFailureKind.BadRequest, e.kind); assertEquals(code, e.httpStatus); assertFalse(e.retryable) }
    }
    @Test fun `http 429 reads fractional retry after with case insensitive last header`() {
        val e = http(429, "prompt is too long", listOf("Retry-After" to "2", "retry-after" to " 1.5 "))
        assertEquals(ModelFailureKind.RateLimited, e.kind); assertEquals(1500L, e.retryAfterMs); assertTrue(e.retryable)
        assertEquals(0L, http(429, headers = listOf("retry-after" to "0")).retryAfterMs)
    }
    @Test fun `invalid retry after leaves backoff to kernel`() {
        for (raw in listOf("-1", "NaN", "Infinity", "bad", "Wed, 21 Oct 2026 07:28:00 GMT")) assertNull(http(429, headers = listOf("retry-after" to raw)).retryAfterMs)
        assertNull(http(429).retryAfterMs)
    }
    @Test fun `503 and 529 are overloaded while other 5xx remain server`() {
        for (code in listOf(503, 529)) { assertEquals(ModelFailureKind.Overloaded, http(code).kind); assertTrue(http(code).retryable) }
        for (code in listOf(500, 502, 504)) assertEquals(ModelFailureKind.Server, http(code, "input token count exceeds maximum").kind)
    }
    @Test fun `408 is network timeout even when body mentions overflow`() {
        val e = http(408, "prompt is too long"); assertEquals(ModelFailureKind.Network, e.kind); assertTrue(e.beforeFirstByte); assertTrue(e.retryable)
    }
    @Test fun `explicit input overflow wording is classified only on ordinary 4xx`() {
        for (body in listOf("The input token count (12345) exceeds the maximum number of tokens allowed (10000).", "input exceeds maximum token limit", "maximum context length is 100", "context window exceeded", "PROMPT IS TOO LONG")) {
            assertEquals(body, ModelFailureKind.ContextOverflow, http(400, body).kind)
            assertFalse(http(400, body).retryable)
        }
        for (body in listOf("RESOURCE_EXHAUSTED", "quota exceeded", "maxOutputTokens too large", "request too large")) assertEquals(ModelFailureKind.BadRequest, http(400, body).kind)
    }
    @Test fun `provider overflow regex augments builtin case insensitively`() {
        assertEquals(ModelFailureKind.BadRequest, http(400, "vendor 123 full").kind)
        assertEquals(ModelFailureKind.ContextOverflow, http(400, "vendor 123 full", patterns = listOf("VENDOR \\d+ FULL")).kind)
        assertThrows(IllegalArgumentException::class.java) { GeminiClient(provider().copy(quirks = ProviderQuirks(overflowPatterns = listOf("("))), FakeHttpTransport(), FakeSecrets()) }
    }
    @Test fun `unexpected status and transport non SSE 200 preserve preview`() {
        for (code in listOf(200, 302)) { val e = http(code, "<html>error</html>"); assertEquals(ModelFailureKind.Other, e.kind); assertTrue(e.beforeFirstByte); assertTrue(e.message!!.contains("<html>error</html>")) }
    }
    @Test fun `http preview is capped and does not copy secret headers`() {
        val e = http(400, "x".repeat(2000), listOf("authorization" to "Bearer SECRET", "set-cookie" to "COOKIE"))
        assertEquals("HTTP 400：" + "x".repeat(500), e.message)
        assertFalse(e.toString().contains("SECRET")); assertFalse(e.toString().contains("COOKIE"))
    }
    @Test fun `malformed frames classify other before any valid data`() {
        for (frame in listOf("<html>bad</html>", "broken {", "[]", "{}", "[DONE]")) {
            val e = parsed(frame).modelError(); assertEquals(ModelFailureKind.Other, e.kind); assertTrue(e.beforeFirstByte); assertTrue(e.message!!.contains(frame))
        }
    }
    @Test fun `midstream malformed frame is after first byte`() {
        val out = parsed(content("""[{"text":"half"}]"""), "<html>bad</html>")
        assertEquals(ModelFailureKind.Other, out.modelError().kind); assertFalse(out.modelError().beforeFirstByte)
        assertTrue(out.events.none { it is ModelStreamEvent.Completed })
    }
    @Test fun `stream codes and statuses map without guessing HTTP`() {
        val cases = listOf("PERMISSION_DENIED" to ModelFailureKind.Auth, "UNAUTHENTICATED" to ModelFailureKind.Auth,
            "RESOURCE_EXHAUSTED" to ModelFailureKind.RateLimited, "UNAVAILABLE" to ModelFailureKind.Overloaded,
            "INTERNAL" to ModelFailureKind.Server, "DEADLINE_EXCEEDED" to ModelFailureKind.Network,
            "INVALID_ARGUMENT" to ModelFailureKind.BadRequest, "NOT_FOUND" to ModelFailureKind.BadRequest, "FAILED_PRECONDITION" to ModelFailureKind.BadRequest, "FUTURE" to ModelFailureKind.Other)
        cases.forEach { (status, kind) -> val out = parsed("""{"error":{"status":"$status","message":"bad"}}"""); assertEquals(status, kind, out.modelError().kind); assertTrue(out.modelError().beforeFirstByte); assertTrue(out.events.isEmpty()) }
    }
    @Test fun `stream numeric codes preserve http status and precedence`() {
        for ((code, kind) in listOf(400 to ModelFailureKind.BadRequest, 403 to ModelFailureKind.Auth, 404 to ModelFailureKind.BadRequest, 408 to ModelFailureKind.Network, 429 to ModelFailureKind.RateLimited, 500 to ModelFailureKind.Server, 503 to ModelFailureKind.Overloaded)) {
            val e = parsed("""{"error":{"code":$code,"status":"INVALID_ARGUMENT","message":"bad"}}""").modelError()
            assertEquals(kind, e.kind); assertEquals(code, e.httpStatus)
        }
    }
    @Test fun `stream context overflow requires invalid argument or 4xx`() {
        assertEquals(ModelFailureKind.ContextOverflow, parsed("""{"error":{"status":"INVALID_ARGUMENT","message":"input token count exceeds maximum"}}""").modelError().kind)
        assertEquals(ModelFailureKind.Server, parsed("""{"error":{"code":500,"message":"prompt is too long"}}""").modelError().kind)
    }
    @Test fun `error after finish is observed instead of prematurely completing`() {
        val out = parsed(stopFrame, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")
        assertEquals(ModelFailureKind.RateLimited, out.modelError().kind); assertFalse(out.modelError().beforeFirstByte)
        assertEquals(listOf(ModelStreamEvent.Started("r1")), out.events)
    }
    @Test fun `event error without wrapper is classified`() {
        val out = run(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(SseEvent("error", """{"status":"UNAVAILABLE"}""")))))
        assertEquals(ModelFailureKind.Overloaded, out.modelError().kind)
    }
    @Test fun `connection and midstream failures preserve network classification`() {
        val first = run(FakeHttpTransport(FakeHttpTransport.Reply.NetworkError(IOException("dns")))).modelError()
        assertEquals(ModelFailureKind.Network, first.kind); assertTrue(first.beforeFirstByte)
        val later = run(FakeHttpTransport(stream(content("""[{"text":"half"}]"""), failure = IOException("reset")))).modelError()
        assertEquals(ModelFailureKind.Network, later.kind); assertFalse(later.beforeFirstByte)
    }
    @Test fun `blank event proves first byte without producing UI events`() {
        val out = run(FakeHttpTransport(FakeHttpTransport.Reply.Sse(listOf(SseEvent(null, "")), IOException("reset"))))
        assertFalse(out.modelError().beforeFirstByte); assertTrue(out.events.isEmpty())
    }
    @Test fun `missing secret is auth without request`() {
        val t = FakeHttpTransport(); val e = run(t, secrets = Secrets { null }).modelError()
        assertEquals(ModelFailureKind.Auth, e.kind); assertTrue(e.beforeFirstByte); assertTrue(t.requests.isEmpty())
    }
    @Test fun `secrets cancellation propagates unchanged`() {
        val stop = CancellationException("stop"); val t = FakeHttpTransport()
        assertSame(stop, run(t, secrets = Secrets { throw stop }).error); assertTrue(t.requests.isEmpty())
    }
    @Test fun `transport cancellation propagates unchanged`() {
        val stop = CancellationException("stop")
        val t = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = flow { throw stop }
        }
        assertSame(stop, run(t).error)
    }
    @Test fun `consumer cancellation and IO exceptions are never reclassified`() {
        val stop = CancellationException("consumer"); val io = IOException("render")
        for (error in listOf(stop, io)) {
            val caught = assertThrows(error.javaClass) { runBlocking {
                GeminiClient(provider(), FakeHttpTransport(stream(stopFrame)), FakeSecrets()).stream(request()).collect { throw error }
            } }
            assertSame(error, caught)
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `cancel pending stream closes upstream and never emits completed`() = runTest {
        var closed = false
        val events = mutableListOf<ModelStreamEvent>()
        val t = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
            override fun stream(request: HttpRequest): Flow<SseEvent> = flow {
                try { emit(SseEvent(null, content("""[{"text":"half"}]"""))); awaitCancellation() } finally { closed = true }
            }
        }
        val job = launch { GeminiClient(provider(), t, FakeSecrets()).stream(request()).toList(events) }
        runCurrent(); job.cancel(); job.join()
        assertTrue(closed); assertTrue(events.any { it is ModelStreamEvent.TextDelta }); assertTrue(events.none { it is ModelStreamEvent.Completed })
    }
}
