package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AnthropicQuirks
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ProviderQuirks
import dev.min.agent.llm.Wire
import dev.min.agent.llm.createModelClient
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.OutboundTrace
import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.testkit.FakeSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * OTel 第四段在线协议层的部分：`x-client-request-id` 与 `traceparent` 只发给 Anthropic 本家（第三方兼容中转不发），
 * 响应头里的 `request-id` / `traceresponse` 经 Started 与 ModelException 交给内核，超时单独标出来。
 */
class AnthropicTelemetryTest {
    private val official = provider(baseUrl = "https://api.anthropic.com")
    private val relay = provider(baseUrl = "https://relay.example.com")
    private val trace = OutboundTrace("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")

    private fun withIds(trace: OutboundTrace? = null) = request().copy(clientRequestId = "creq_1", trace = trace)

    // ---- 请求头 ----

    @Test
    fun `the official endpoint gets the client request id and the traceparent`() {
        val t = FakeHttpTransport(okStream)
        run(t, withIds(trace), official).error?.let { throw it }
        assertEquals("creq_1", t.header("x-client-request-id"))
        assertEquals(trace.traceparent, t.header("traceparent"))
    }

    @Test
    fun `a third party relay gets neither header even when the request carries them`() {
        val t = FakeHttpTransport(okStream)
        run(t, withIds(trace), relay).error?.let { throw it }
        assertNull("第三方中转不带 x-client-request-id", t.header("x-client-request-id"))
        assertNull("第三方中转不带 traceparent", t.header("traceparent"))
    }

    @Test
    fun `explicit consent lets a custom anthropic endpoint get the traceparent but still not the client request id`() {
        val t = FakeHttpTransport(okStream)
        run(t, withIds(trace.copy(allowCustomAnthropicEndpoint = true)), relay).error?.let { throw it }
        assertEquals(trace.traceparent, t.header("traceparent"))
        assertNull(t.header("x-client-request-id"))
    }

    @Test
    fun `a request without ids sends neither header`() {
        val t = FakeHttpTransport(okStream)
        run(t, request(), official).error?.let { throw it }
        assertNull(t.header("x-client-request-id"))
        assertNull(t.header("traceparent"))
    }

    @Test
    fun `only the official host counts as first party`() {
        fun client(url: String): ModelClient = createModelClient(provider(baseUrl = url), FakeHttpTransport(), FakeSecrets())
        assertTrue(client("https://api.anthropic.com").sendsClientRequestId)
        assertTrue(client("https://eu.api.anthropic.com/").sendsClientRequestId)
        // 前缀 / 后缀相像的域名不算（防止 evilanthropic.com 一类拿到 trace id）
        assertFalse(client("https://evilanthropic.com").sendsClientRequestId)
        assertFalse(client("https://anthropic.com.evil.example").sendsClientRequestId)
        assertFalse(client("https://api.example.com/anthropic").sendsClientRequestId)
        assertFalse(client("not a url").sendsClientRequestId)
        assertFalse(client("https://relay.example.com").propagatesTrace(false))
        assertTrue(client("https://relay.example.com").propagatesTrace(true))
        assertTrue(client("https://api.anthropic.com").propagatesTrace(false))
    }

    @Test
    fun `the other wires never send the headers`() {
        for (wire in listOf(Wire.OpenAIChat, Wire.OpenAIResponses, Wire.Gemini)) {
            val client = createModelClient(
                ProviderDescriptor("p", "https://api.anthropic.com", wire, AuthScheme.Bearer, quirks = ProviderQuirks(anthropic = AnthropicQuirks())),
                FakeHttpTransport(), FakeSecrets(),
            )
            assertFalse("$wire", client.sendsClientRequestId)
            assertFalse("$wire", client.propagatesTrace(true))
        }
    }

    // ---- 响应头 ----

    @Test
    fun `started carries the request id and traceresponse from the response headers`() {
        val tr = "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"
        val t = FakeHttpTransport(
            FakeHttpTransport.Reply.Sse(okStream.events, headers = listOf("Request-Id" to "req_011CT", "TraceResponse" to tr)),
        )
        val started = run(t, withIds(trace), official).events.filterIsInstance<ModelStreamEvent.Started>().single()
        assertEquals("msg_1", started.providerMessageId)
        assertEquals("req_011CT", started.responseId)
        assertEquals(tr, started.traceResponse)
    }

    @Test
    fun `junk response header values are dropped before they reach the log`() {
        val t = FakeHttpTransport(
            FakeHttpTransport.Reply.Sse(
                okStream.events,
                headers = listOf("request-id" to "req\u0001bad", "traceresponse" to "x".repeat(400)),
            ),
        )
        val started = run(t, request(), official).events.filterIsInstance<ModelStreamEvent.Started>().single()
        assertNull(started.responseId)
        assertNull(started.traceResponse)
    }

    @Test
    fun `an http error carries the request id from its headers`() {
        val t = FakeHttpTransport(
            FakeHttpTransport.Reply.Status(529, """{"type":"error","error":{"type":"overloaded_error","message":"x"}}""", listOf("request-id" to "req_err")),
        )
        val e = run(t, request(), official).modelError()
        assertEquals("req_err", e.responseId)
        assertFalse(e.timedOut)
    }

    @Test
    fun `a socket timeout is a network failure marked as timed out`() {
        val t = FakeHttpTransport(FakeHttpTransport.Reply.NetworkError(SocketTimeoutException("Read timed out")))
        val e = run(t, request(), official).modelError()
        assertTrue(e.timedOut)
        assertEquals(ModelFailureKind.Network, e.kind)
        assertTrue("重试语义不变", e.retryable)
    }

    @Test
    fun `a timeout in the middle of the stream keeps the response headers seen before it`() {
        val events = okStream.events.take(1)
        val t = FakeHttpTransport(
            FakeHttpTransport.Reply.Sse(events, failAfter = SocketTimeoutException("Read timed out"), headers = listOf("request-id" to "req_mid")),
        )
        val e = run(t, request(), official).modelError()
        assertTrue(e.timedOut)
        assertEquals("req_mid", e.responseId)
    }

    @Test
    fun `an ordinary io failure is not a timeout`() {
        val t = FakeHttpTransport(FakeHttpTransport.Reply.NetworkError(IOException("reset")))
        assertFalse(run(t, request(), official).modelError().timedOut)
    }
}
