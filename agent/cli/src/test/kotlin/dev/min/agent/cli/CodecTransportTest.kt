package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

/** 组合层验证真实传输与两种编解码器的接缝，避免假传输已经带正文而真实 HTTP 却丢正文的假阳性。 */
class CodecTransportTest {
    private fun nonSse(wire: Wire) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "<html>relay upstream error</html>"
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("content-type", "text/html")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val provider = ProviderDescriptor("test", "http://127.0.0.1:${server.address.port}", wire, AuthScheme.Bearer)
            val secrets = Secrets { Secret("dummy-private-header") }
            val transport = UrlConnectionTransport()
            val client = when (wire) {
                Wire.AnthropicMessages -> AnthropicMessagesClient(provider, transport, secrets)
                Wire.OpenAIChat -> OpenAIChatClient(provider, transport, secrets)
                else -> error("unused")
            }
            val profile = ModelProfile(ModelRef("test", "model"), family = ModelFamily.Other, contextWindow = 10000, maxOutputTokens = 1000)
            val request = ModelRequest(profile, listOf("system"), emptyList(), emptyList())
            val error = assertThrows(ModelException::class.java) { runBlocking { client.stream(request).toList() } }
            assertEquals(ModelFailureKind.Other, error.kind)
            assertEquals(200, error.httpStatus)
            assertTrue(error.beforeFirstByte)
            assertFalse(error.retryable)
            assertTrue(error.message.orEmpty().contains(body))
            assertFalse(error.message.orEmpty().contains("dummy-private-header"))
        } finally { server.stop(0) }
    }

    @Test fun `Anthropic classifies a real 200 error page as Other with its body`() = nonSse(Wire.AnthropicMessages)
    @Test fun `OpenAI Chat classifies a real 200 error page as Other with its body`() = nonSse(Wire.OpenAIChat)
}
