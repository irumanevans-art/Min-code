package dev.min.agent.llm.openai

import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.testkit.FakeHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** OpenAI Chat：响应头 `x-request-id` 经 Started 交给内核（只给遥测）；超时单独标出，请求头里不带 trace 与客户端请求 id（非官方文档的线协议） */
class OpenAIRequestIdTest {
    @Test
    fun `started carries the x-request-id response header`() {
        val t = FakeHttpTransport(FakeHttpTransport.Reply.Sse(okStream.events, headers = listOf("X-Request-Id" to "req_oa_1")))
        val started = run(t).events.filterIsInstance<ModelStreamEvent.Started>().single()
        assertEquals("req_oa_1", started.responseId)
    }

    @Test
    fun `no header means no response id`() {
        val started = run(FakeHttpTransport(okStream)).events.filterIsInstance<ModelStreamEvent.Started>().single()
        assertNull(started.responseId)
    }

    @Test
    fun `an http error carries the request id`() {
        val t = FakeHttpTransport(FakeHttpTransport.Reply.Status(500, "{}", listOf("x-request-id" to "req_oa_err")))
        assertEquals("req_oa_err", run(t).modelError().responseId)
    }

    @Test
    fun `a read timeout is marked as a timeout`() {
        val t = FakeHttpTransport(FakeHttpTransport.Reply.NetworkError(SocketTimeoutException("Read timed out")))
        assertTrue(run(t).modelError().timedOut)
    }

    @Test
    fun `the chat wire never sends the client request id or traceparent`() {
        val t = FakeHttpTransport(okStream)
        val req = request().copy(
            clientRequestId = "creq_1",
            trace = dev.min.agent.spi.OutboundTrace("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", allowCustomAnthropicEndpoint = true),
        )
        run(t, req).error?.let { throw it }
        assertNull(t.header("x-client-request-id"))
        assertNull(t.header("traceparent"))
    }
}
