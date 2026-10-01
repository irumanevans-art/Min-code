package dev.min.agent.llm.openai

import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.testkit.FakeHttpTransport.Reply
import dev.min.agent.testkit.FakeSecrets
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Secrets
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class OpenAIChatErrorTest {
    private fun failure(status: Int, body: String = "denied", headers: List<Pair<String, String>> = emptyList(), patterns: List<String> = emptyList()) =
        run(FakeHttpTransport(Reply.Status(status, body, headers)), provider = provider(overflowPatterns = patterns)).modelError()

    @Test
    fun `connection failure is retryable network before first byte`() {
        val err = run(FakeHttpTransport(Reply.NetworkError(IOException("dns")))).modelError()
        assertEquals(ModelFailureKind.Network, err.kind)
        assertTrue(err.beforeFirstByte)
        assertTrue(err.retryable)
        assertNull(err.httpStatus)
    }

    @Test
    fun `401 and 403 always indicate auth`() {
        for (status in listOf(401, 403)) {
            val err = failure(status, "maximum context length")
            assertEquals(ModelFailureKind.Auth, err.kind)
            assertEquals(status, err.httpStatus)
            assertTrue(err.beforeFirstByte)
            assertFalse(err.retryable)
        }
    }

    @Test
    fun `408 is retryable request timeout`() {
        val err = failure(408)
        assertEquals(ModelFailureKind.Network, err.kind)
        assertTrue(err.beforeFirstByte)
        assertTrue(err.retryable)
    }

    @Test
    fun `429 reads case insensitive fractional retry after seconds`() {
        val err = failure(429, headers = listOf("Retry-After" to "2", "retry-after" to "1.5"))
        assertEquals(ModelFailureKind.RateLimited, err.kind)
        assertEquals(1500L, err.retryAfterMs)
        assertTrue(err.beforeFirstByte)
        assertTrue(err.retryable)
        assertEquals(30_000L, failure(429, headers = listOf("RETRY-AFTER" to " 30 ")).retryAfterMs)
        assertEquals(0L, failure(429, headers = listOf("retry-after" to "0")).retryAfterMs)
    }

    @Test
    fun `invalid and date retry after values leave backoff to kernel`() {
        for (raw in listOf("-1", "NaN", "Infinity", "bad", "Wed, 21 Oct 2026 07:28:00 GMT")) {
            assertNull(failure(429, headers = listOf("retry-after" to raw)).retryAfterMs)
        }
        assertNull(failure(429).retryAfterMs)
    }

    @Test
    fun `529 means overloaded while other 5xx are server failures`() {
        assertEquals(ModelFailureKind.Overloaded, failure(529).kind)
        for (status in listOf(500, 502, 503, 504)) {
            val err = failure(status, "maximum context length")
            assertEquals(ModelFailureKind.Server, err.kind)
            assertTrue(err.beforeFirstByte)
            assertTrue(err.retryable)
        }
    }

    @Test
    fun `413 is overflow even without a matching error body`() {
        val err = failure(413, "request too large")
        assertEquals(ModelFailureKind.ContextOverflow, err.kind)
        assertTrue(err.beforeFirstByte)
        assertFalse(err.retryable)
    }

    @Test
    fun `other 4xx including conflict are bad requests`() {
        for (status in listOf(400, 402, 404, 409, 422)) {
            val err = failure(status)
            assertEquals(ModelFailureKind.BadRequest, err.kind)
            assertTrue(err.beforeFirstByte)
            assertFalse(err.retryable)
        }
    }

    @Test
    fun `standard and inventory overflow signatures are built in`() {
        // inventory zhipu.md §9、qwen.md §9 有原文；DeepSeek/Kimi 没有，不能伪造逐家原文。
        for (body in listOf(
            """{"error":{"code":"context_length_exceeded","message":"bad prompt"}}""",
            """{"error":{"message":"This model's maximum context length is 128000 tokens"}}""",
            """{"error":{"code":"1261","message":"Prompt too long"}}""",
            """{"error":{"code":"InternalError.Algo.InvalidParameter","message":"Range of input length should be [1, 129024]"}}""",
            "CONTEXT_LENGTH_EXCEEDED", "Input token exceed the limit", "context window exceeded",
        )) {
            val err = failure(400, body)
            assertEquals(body, ModelFailureKind.ContextOverflow, err.kind)
            assertTrue(err.beforeFirstByte)
        }
    }

    @Test
    fun `provider regex augments builtins and is case insensitive`() {
        val body = "vendor input count 1234 above limit"
        assertEquals(ModelFailureKind.BadRequest, failure(400, body).kind)
        assertEquals(ModelFailureKind.ContextOverflow, failure(400, body, patterns = listOf("VENDOR INPUT COUNT \\d+ ABOVE LIMIT")).kind)
    }

    @Test
    fun `overflow matching does not hide auth rate limit or server failures`() {
        assertEquals(ModelFailureKind.Auth, failure(403, "prompt too long").kind)
        assertEquals(ModelFailureKind.RateLimited, failure(429, "prompt too long").kind)
        assertEquals(ModelFailureKind.Overloaded, failure(529, "prompt too long").kind)
        assertEquals(ModelFailureKind.Server, failure(500, "prompt too long").kind)
        assertEquals(ModelFailureKind.Network, failure(408, "prompt too long").kind)
    }

    @Test
    fun `status and capped body preview never copy headers`() {
        val err = failure(400, "x".repeat(1000), listOf("Authorization" to "Bearer sk-secret", "set-cookie" to "secret-cookie"))
        assertEquals("HTTP 400：" + "x".repeat(500), err.message)
        assertFalse(err.toString().contains("sk-secret"))
        assertFalse(err.toString().contains("secret-cookie"))
    }

    @Test
    fun `unexpected HTTP status is other`() {
        val err = failure(302, "moved")
        assertEquals(ModelFailureKind.Other, err.kind)
        assertEquals(302, err.httpStatus)
        assertTrue(err.beforeFirstByte)
    }

    @Test
    fun `200 HTML JSON completion or invalid JSON in stream is other with body preview`() {
        for (body in listOf("<html>proxy error</html>", "broken {", "[]", "{}",
            """{"id":"non-stream","choices":[{"message":{"role":"assistant","content":"hi"}}]}""")) {
            val err = run(FakeHttpTransport(stream("data: $body"))).modelError()
            assertEquals(ModelFailureKind.Other, err.kind)
            assertEquals(200, err.httpStatus)
            assertTrue(err.beforeFirstByte)
            assertTrue(err.message!!.contains(body))
        }
    }

    @Test
    fun `200 nonstream rejection reported by transport carries its body preview`() {
        val err = failure(200, "<html>upstream returned an error page</html>")
        assertEquals(ModelFailureKind.Other, err.kind)
        assertEquals(200, err.httpStatus)
        assertTrue(err.beforeFirstByte)
        assertEquals("HTTP 200：<html>upstream returned an error page</html>", err.message)
    }

    @Test
    fun `200 stream error before first chunk maps every documented failure kind`() {
        for ((type, kind) in listOf("authentication_error" to ModelFailureKind.Auth, "permission_error" to ModelFailureKind.Auth,
            "rate_limit_error" to ModelFailureKind.RateLimited, "overloaded_error" to ModelFailureKind.Overloaded,
            "api_error" to ModelFailureKind.Server, "invalid_request_error" to ModelFailureKind.BadRequest)) {
            val out = run(FakeHttpTransport(stream("""
                event: error
                data: {"error":{"type":"$type","message":"bad"}}
            """)))
            assertEquals(kind, out.modelError().kind)
            assertTrue(out.modelError().beforeFirstByte)
            assertTrue(out.events.isEmpty())
        }
    }

    @Test
    fun `stream errors after content are after first byte and never complete`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"id":"m","choices":[{"delta":{"content":"半句"}}]}

            data: {"error":{"type":"overloaded_error","message":"busy"}}
        """)))
        assertEquals(ModelFailureKind.Overloaded, out.modelError().kind)
        assertFalse(out.modelError().beforeFirstByte)
        assertTrue(out.modelError().retryable)
        assertEquals(listOf(ModelStreamEvent.Started("m"), ModelStreamEvent.TextDelta("半句")), out.events)
    }

    @Test
    fun `stream overflow inspects complete error body including code`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"error":{"type":"invalid_request_error","code":"context_length_exceeded","message":"too big"}}
        """)))
        assertEquals(ModelFailureKind.ContextOverflow, out.modelError().kind)
        assertTrue(out.modelError().beforeFirstByte)
    }

    @Test
    fun `malformed stream data after first event has before first byte false`() {
        val out = run(FakeHttpTransport(stream("data: {\"choices\":[{\"delta\":{}}]}\n\ndata: <html>bad</html>")))
        assertEquals(ModelFailureKind.Other, out.modelError().kind)
        assertFalse(out.modelError().beforeFirstByte)
    }

    @Test
    fun `missing secret is auth and does not send a request`() {
        val transport = FakeHttpTransport()
        val err = run(transport, secrets = Secrets { null }).modelError()
        assertEquals(ModelFailureKind.Auth, err.kind)
        assertTrue(err.beforeFirstByte)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `invalid overflow regex fails when client is constructed`() {
        try {
            OpenAIChatClient(provider(overflowPatterns = listOf("(")), FakeHttpTransport(), FakeSecrets())
            fail("应拒绝错误正则")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `cancellation from secrets remains cancellation`() {
        val cancellation = CancellationException("stop")
        val transport = FakeHttpTransport()
        assertSame(cancellation, run(transport, secrets = Secrets { throw cancellation }).error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `downstream cancellation and exceptions are not classified or swallowed`() {
        val transport = FakeHttpTransport(okStream)
        val cancellation = CancellationException("stop")
        try {
            runBlocking { OpenAIChatClient(provider(), transport, FakeSecrets()).stream(request()).collect { throw cancellation } }
            fail("应传播取消")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        val consumerError = IOException("display failed")
        try {
            runBlocking { OpenAIChatClient(provider(), FakeHttpTransport(okStream), FakeSecrets()).stream(request()).collect { throw consumerError } }
            fail("应传播消费者错误")
        } catch (e: IOException) {
            assertSame(consumerError, e)
        }
    }

    @Test
    fun `fake nonstream body exposes the transport contract limitation`() {
        val out = run(FakeHttpTransport(Reply.Body("<html>proxy error</html>")))
        // 替身直接抛无正文的 IllegalStateException；客户端不能凭空还原正文或把程序错误全归成 HTTP 错误。
        assertTrue(out.error is IllegalStateException)
        assertEquals("这个请求是流式的，脚本却给了非流式响应", out.error!!.message)
        assertTrue(out.events.isEmpty())
    }
}
