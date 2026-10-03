package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ToolResult
import dev.min.agent.tools.WebSearchTool
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class DesktopWebIntegrationTest {
    private val ToolResult.text: String get() = content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    @After fun stop() { scope.cancel(); server.stop(0) }

    @Test fun `desktop search backend wiring and cap survive a new app resuming session`() = runBlocking {
        withTimeout(15_000) {
            val searchCalls = AtomicInteger()
            val modelCalls = AtomicInteger()
            val modelRequests = CopyOnWriteArrayList<JsonObject>()
            server.createContext("/search") { ex ->
                searchCalls.incrementAndGet()
                assertTrue(ex.requestURI.rawQuery.contains("format=json"))
                val body = """{"results":[{"title":"Local fixture","url":"https://example.com","content":"Snippet"}]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            server.createContext("/v1/chat/completions") { ex ->
                modelRequests += Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
                val call = modelCalls.incrementAndGet()
                val chunk = if (call % 2 == 1)
                    """{"id":"m$call","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"s$call","type":"function","function":{"name":"WebSearch","arguments":"{\"query\":\"kotlin\"}"}}]},"finish_reason":null}]}""" else
                    """{"id":"m$call","choices":[{"index":0,"delta":{"role":"assistant","content":"done"},"finish_reason":null}]}"""
                val reason = if (call % 2 == 1) "tool_calls" else "stop"
                val end = """{"id":"m$call","choices":[{"index":0,"delta":{},"finish_reason":"$reason"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                val body = "data: $chunk\n\ndata: $end\n\ndata: [DONE]\n\n".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            val cwd = tmp.newFolder("work").toPath()
            val home = tmp.newFolder("home").toPath()
            val claudeHome = tmp.newFolder("claude-home").toPath()
            val args = parseArgs(arrayOf("--provider", "local-test", "--model", "scripted", "--base-url", "$base/v1", "--wire", "openai_chat",
                "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
                "--search-backend", "searxng", "--search-url", base, "--max-web-searches", "1"), cwd)
            val rt = DesktopApp(args, scope, home, claudeHome).openSession()
            rt.send(ClientMsgId("u1"), listOf(TextPart("search"))); rt.awaitIdle()
            assertEquals(1, searchCalls.get())
            assertTrue(rt.state.value.toolResults.values.single().text.contains("Local fixture — https://example.com — Snippet"))
            assertTrue(modelRequests.first()["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"] == JsonPrimitive("WebSearch") })
            val reopened = DesktopApp(args.copy(resume = rt.sessionId.value), scope, home, claudeHome).openSession()
            reopened.send(ClientMsgId("u2"), listOf(TextPart("search again"))); reopened.awaitIdle()
            assertEquals(1, searchCalls.get())
            assertEquals(1, reopened.state.value.webSearches)
            assertTrue(reopened.state.value.toolResults.values.any { it.text == WebSearchTool.LIMIT_NOTICE && !it.isError })
            assertEquals(modelRequests.first()["tools"], modelRequests[2]["tools"])
        }
    }
}
