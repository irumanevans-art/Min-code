package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.TurnStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** 通过真实 DesktopApp + 本地 SSE 模型验证组合根，新建与恢复都能调用子 agent 端口。 */
class DesktopSubagentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
    @After fun tearDown() { scopes.forEach { it.cancel() } }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun args(server: ModelServer, cwd: Path, resume: String? = null): CliArgs = parseArgs(
        arrayOf(
            "--provider", "desktop-test", "--model", "scripted", "--base-url", server.baseUrl,
            "--wire", "openai_chat", "--mode", "bypassPermissions", "--cwd", cwd.toString(),
            // 本地模型只需要存在的普通环境变量，不读取用户的供应商凭证。
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
        ), cwd,
    ).copy(resume = resume)

    private fun SessionState.reports() = history.filterIsInstance<HistoryEntry.User>()
        .filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report }.flatMap { it.parts } +
        inbox.filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report }.flatMap { it.parts }

    @Test fun `desktop creates through Hub and resumes with a working SendMessage port`() = run {
        ModelServer { last ->
            when {
                last.contains("CLI_FOREGROUND") -> toolResponse("delegate", "Agent", buildJsonObject {
                    put("description", "探索代码")
                    put("prompt", "CHILD_FOREGROUND")
                    put("subagent_type", "Explore")
                    put("run_in_background", false)
                    put("name", "helper")
                })
                last.contains("CHILD_FOREGROUND") -> textResponse("首次子报告")
                last.contains("CLI_RESUME") -> toolResponse("send", "SendMessage", buildJsonObject {
                    put("to", "helper")
                    put("message", "CHILD_RESUME")
                })
                last.contains("CHILD_RESUME") -> textResponse("续聊子报告")
                else -> textResponse("PARENT_DONE")
            }
        }.use { server ->
            val home = tmp.newFolder("home").toPath()
            val cwd = tmp.newFolder("work").toPath()
            val firstScope = scope()
            val app = DesktopApp(args(server, cwd), firstScope, home)
            val parent = app.openSession()
            parent.send(ClientMsgId("first"), listOf(TextPart("CLI_FOREGROUND")))
            val done = parent.state.first { it.turn == null && it.lastTurn != null }
            val result = done.toolResults.getValue(CallId("delegate"))
            assertFalse(result.isError)
            assertTrue(result.content.filterIsInstance<TextPart>().single().text.contains("首次子报告"))
            assertTrue(done.reports().isEmpty())
            val childId = done.children.keys.single()
            assertEquals("前台结果与交付标记一起落盘", 1, done.children.getValue(childId).reportedTurns.size)
            assertTrue(app.records(childId, 0).any { it.event is SubagentOrigin })
            assertEquals(parent.sessionId, (app.records(childId, 0).first { it.event is SubagentOrigin }.event as SubagentOrigin).parent)

            val toolNames = server.requests.first().getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            assertTrue(toolNames.containsAll(listOf("Agent", "SendMessage", "TaskStop")))
            firstScope.cancel()

            val resumedApp = DesktopApp(args(server, cwd, parent.sessionId.value), scope(), home)
            val resumed = resumedApp.openSession()
            assertEquals(parent.sessionId, resumed.sessionId)
            assertEquals(childId, resumed.state.value.children.keys.single())
            assertTrue("前台报告已交付，恢复时不能重复补发", resumed.state.value.reports().isEmpty())
            resumed.send(ClientMsgId("resume"), listOf(TextPart("CLI_RESUME")))
            val continued = withTimeoutOrNull(5_000) {
                resumed.state.first { it.turn == null && it.reports().filterIsInstance<TextPart>().any { part -> "续聊子报告" in part.text } }
            } ?: error("续聊未收到报告：${server.texts}; outcome=${resumed.state.value.lastTurn}; results=${resumed.state.value.toolResults}")
            assertFalse(continued.toolResults.getValue(CallId("send")).isError)
            assertEquals("续聊只新增一个子报告", 1, continued.reports().size)
            assertEquals(2, resumedApp.records(childId, 0).count { it.event is TurnStarted })
        }
    }

    @Test fun `desktop Hub watches a background child and automatically delivers its report`() = run {
        ModelServer { last ->
            when (last) {
                "CLI_BACKGROUND" -> toolResponse("delegate", "Agent", buildJsonObject {
                    put("description", "后台探索")
                    put("prompt", "CHILD_BACKGROUND")
                    put("subagent_type", "Explore")
                })
                "CHILD_BACKGROUND" -> textResponse("后台子报告")
                else -> textResponse("PARENT_DONE")
            }
        }.use { server ->
            val home = tmp.newFolder("home").toPath()
            val cwd = tmp.newFolder("work").toPath()
            val app = DesktopApp(args(server, cwd), scope(), home)
            val parent = app.openSession()
            parent.send(ClientMsgId("background"), listOf(TextPart("CLI_BACKGROUND")))
            val done = parent.state.first { it.turn == null && it.reports().isNotEmpty() }
            val childId = done.children.keys.single()
            val result = done.toolResults.getValue(CallId("delegate"))
            assertFalse(result.isError)
            val receipt = result.content.filterIsInstance<TextPart>().single().text
            assertTrue(receipt, receipt.startsWith("已在后台启动") && childId.value in receipt)
            assertTrue(done.children.getValue(childId).background)
            val reports = done.reports().filterIsInstance<TextPart>()
            assertEquals(1, reports.size)
            assertTrue(reports.single().text.contains("后台子报告"))
        }
    }

    private class ModelServer(private val route: (String) -> String) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = CopyOnWriteArrayList<JsonObject>()
        val texts = CopyOnWriteArrayList<String>()
        val baseUrl get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().decodeToString() }
                val request = Json.parseToJsonElement(body).jsonObject
                requests += request
                val messages = request.getValue("messages").jsonArray
                // 恢复后收件箱可同时提升用户输入与子报告，它们是相邻的 user 消息。
                val tail = if (messages.last().jsonObject["role"]?.jsonPrimitive?.content == "user") {
                    messages.reversed().takeWhile { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.reversed()
                } else listOf(messages.last())
                val last = tail.joinToString("\n") {
                    val content = it.jsonObject["content"]
                    (content as? JsonPrimitive)?.content ?: content.toString()
                }
                texts += last
                val response = ("data: " + route(last) + "\n\ndata: [DONE]\n\n").encodeToByteArray()
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }

    private companion object {
        fun textResponse(text: String) = response("stop", buildJsonObject { put("role", "assistant"); put("content", text) })
        fun toolResponse(id: String, name: String, input: JsonObject) = response("tool_calls", buildJsonObject {
            put("role", "assistant")
            put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("id", id); put("type", "function")
                put("function", buildJsonObject { put("name", name); put("arguments", input.toString()) })
            }) })
        })
        fun response(stop: String, delta: JsonObject) = buildJsonObject {
            put("id", "response")
            put("choices", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("delta", delta); put("finish_reason", stop)
            }) })
        }.toString()
    }
}
