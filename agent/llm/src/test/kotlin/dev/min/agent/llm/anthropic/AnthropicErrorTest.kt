package dev.min.agent.llm.anthropic

import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.testkit.FakeHttpTransport.Reply
import dev.min.agent.testkit.FakeSecrets
import dev.min.agent.llm.ModelException
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AnthropicErrorTest {
    private fun fail(
        status: Int,
        body: String = "{}",
        headers: List<Pair<String, String>> = emptyList(),
        overflowPatterns: List<String> = emptyList(),
    ): ModelException = run(
        FakeHttpTransport(Reply.Status(status, body, headers)),
        provider = provider(overflowPatterns = overflowPatterns),
    ).modelError()

    private fun anthropicError(type: String, message: String) =
        """{"type":"error","error":{"type":"$type","message":"$message"},"request_id":"req_1"}"""

    @Test
    fun `connect-stage IOException is a network failure before the first byte`() {
        val err = run(FakeHttpTransport(Reply.NetworkError(IOException("Unable to resolve host")))).modelError()
        assertEquals(ModelFailureKind.Network, err.kind)
        assertTrue(err.beforeFirstByte)
        assertTrue(err.retryable)
        assertTrue(err.message!!.contains("Unable to resolve host"))
    }

    @Test
    fun `404 for a missing model is unavailable while overflow and plain bad requests keep their kinds`() {
        val missing = fail(404, anthropicError("not_found_error", "model: claude-retired-1"))
        assertEquals(ModelFailureKind.ModelUnavailable, missing.kind)
        assertEquals(404, missing.httpStatus)
        assertFalse(missing.retryable)
        // 404 但与模型无关（别的资源不存在）：仍是 BadRequest
        assertEquals(ModelFailureKind.BadRequest, fail(404, anthropicError("not_found_error", "file not found")).kind)
        // 超长优先：同样是 4xx，不能被误归成模型不可用
        assertEquals(
            ModelFailureKind.ContextOverflow,
            fail(400, anthropicError("invalid_request_error", "prompt is too long: model limit 200000")).kind,
        )
    }

    @Test
    fun `401 and 403 are auth failures`() {
        for (status in listOf(401, 403)) {
            val err = fail(status, anthropicError("authentication_error", "invalid x-api-key"))
            assertEquals(ModelFailureKind.Auth, err.kind)
            assertEquals(status, err.httpStatus)
            assertTrue(err.beforeFirstByte)
            assertFalse(err.retryable)
        }
    }

    @Test
    fun `429 is rate limited and reads retry-after seconds`() {
        val err = fail(429, anthropicError("rate_limit_error", "slow down"), listOf("Retry-After" to "30"))
        assertEquals(ModelFailureKind.RateLimited, err.kind)
        assertEquals(30_000L, err.retryAfterMs)
        assertTrue(err.beforeFirstByte)
        assertTrue(err.retryable)
        assertEquals(1_500L, fail(429, headers = listOf("retry-after" to "1.5")).retryAfterMs)
        assertNull(fail(429).retryAfterMs)
        // HTTP-date 形式不解析，宁可让内核用自己的退避
        assertNull(fail(429, headers = listOf("retry-after" to "Wed, 21 Oct 2026 07:28:00 GMT")).retryAfterMs)
        assertNull(fail(429, headers = listOf("retry-after" to "-3")).retryAfterMs)
    }

    @Test
    fun `529 is overloaded and 5xx are server errors`() {
        val overloaded = fail(529, anthropicError("overloaded_error", "Overloaded"))
        assertEquals(ModelFailureKind.Overloaded, overloaded.kind)
        assertTrue(overloaded.beforeFirstByte)
        for (status in listOf(500, 502, 503, 504)) {
            val err = fail(status, "upstream broke")
            assertEquals(ModelFailureKind.Server, err.kind)
            assertTrue(err.beforeFirstByte)
            assertTrue(err.retryable)
        }
    }

    @Test
    fun `other 4xx are bad requests`() {
        for (status in listOf(400, 402, 404, 409, 413, 422)) {
            val err = fail(status, anthropicError("invalid_request_error", "messages.0.content: Input should be a valid list"))
            assertEquals("status $status", ModelFailureKind.BadRequest, err.kind)
            assertTrue(err.beforeFirstByte)
            assertFalse(err.retryable)
        }
    }

    @Test
    fun `built-in overflow signatures win over bad request, case-insensitively`() {
        val bodies = listOf(
            anthropicError("invalid_request_error", "prompt is too long: 213462 tokens > 200000 maximum"),
            anthropicError("invalid_request_error", "Prompt Is Too Long"),
            anthropicError("invalid_request_error", "The input exceeds the context window of this model"),
            anthropicError(
                "invalid_request_error",
                "input length and `max_tokens` exceed context limit: 188240 + 21333 > 200000, decrease input length or `max_tokens` and try again",
            ),
        )
        bodies.forEach { body ->
            val err = fail(400, body)
            assertEquals(body, ModelFailureKind.ContextOverflow, err.kind)
            assertFalse(err.retryable)
            assertTrue(err.beforeFirstByte)
        }
    }

    @Test
    fun `provider overflow patterns cover vendor wording from the inventory`() {
        // Qwen（inventory/providers/qwen.md）：400 InternalError.Algo.InvalidParameter
        val qwen = """{"error":{"code":"InvalidParameter","message":"Range of input length should be [1, 129024]"}}"""
        // GLM（inventory/providers/zhipu.md）：业务码 1261（400）Prompt too long
        val glm = """{"error":{"code":"1261","message":"Prompt too long"}}"""
        val patterns = listOf("range of input length should be", "prompt too long")
        assertEquals(ModelFailureKind.BadRequest, fail(400, qwen).kind)
        assertEquals(ModelFailureKind.BadRequest, fail(400, glm).kind)
        assertEquals(ModelFailureKind.ContextOverflow, fail(400, qwen, overflowPatterns = patterns).kind)
        assertEquals(ModelFailureKind.ContextOverflow, fail(400, glm, overflowPatterns = patterns).kind)
    }

    @Test
    fun `overflow patterns do not turn auth or server errors into overflow`() {
        val body = anthropicError("invalid_request_error", "prompt is too long")
        assertEquals(ModelFailureKind.Auth, fail(401, body).kind)
        assertEquals(ModelFailureKind.RateLimited, fail(429, body).kind)
        assertEquals(ModelFailureKind.Server, fail(500, body).kind)
    }

    @Test
    fun `message carries status and a capped body preview but never credentials`() {
        val long = "x".repeat(2_000)
        val err = fail(400, long, headers = listOf("x-api-key" to "sk-test", "set-cookie" to "secret-cookie"))
        val message = err.message!!
        assertTrue(message.contains("400"))
        assertTrue(message.contains("x".repeat(500)))
        assertFalse(message.contains("x".repeat(501)))
        assertFalse(message.contains("sk-test"))
        assertFalse(message.contains("secret-cookie"))
        assertEquals(400, err.httpStatus)
    }

    @Test
    fun `credentials never leak into the failure of a request`() {
        val transport = FakeHttpTransport(Reply.Status(401, "denied"))
        val err = run(transport).modelError()
        assertFalse(err.message!!.contains("sk-test"))
        assertFalse(transport.requests.single().toString().contains("sk-test"))
    }

    @Test
    fun `missing credentials fail as auth before any request is sent`() {
        val transport = FakeHttpTransport()
        val nobody = Secrets { null }
        val err = try {
            runBlocking { AnthropicMessagesClient(provider(), transport, nobody).stream(request()).toList() }
            fail("应抛 ModelException")
            return
        } catch (e: ModelException) {
            e
        }
        assertEquals(ModelFailureKind.Auth, err.kind)
        assertTrue(err.beforeFirstByte)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `an invalid overflow regex is rejected when the client is built`() {
        try {
            AnthropicMessagesClient(provider(overflowPatterns = listOf("(unclosed")), FakeHttpTransport(), FakeSecrets())
            fail("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 模型数据写错要在建客户端时暴露，而不是等到真出错时把原始错误盖住
        }
    }

    @Test
    fun `secret toString stays masked`() {
        assertEquals("Secret(***)", Secret("sk-test").toString())
    }
}
