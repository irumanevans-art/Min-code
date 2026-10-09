package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** `/cost`：计费点从树上的 model_charged 折出，无价格如实说「算不出来」，各模型用量分列 */
class CostCommandTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    @After fun stop() { scope.cancel(); server.stop(0) }

    @Test fun `cost report folds model_charged from the log and says when pricing is unknown`() = runBlocking {
        withTimeout(15_000) {
            val modelCalls = CopyOnWriteArrayList<Int>()
            server.createContext("/v1/chat/completions") { ex ->
                val call = modelCalls.size + 1
                modelCalls += call
                val chunk = """{"id":"m$call","choices":[{"index":0,"delta":{"role":"assistant","content":"answer $call"},"finish_reason":null}]}"""
                val end = """{"id":"m$call","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
                val body = "data: $chunk\n\ndata: $end\n\ndata: [DONE]\n\n".toByteArray()
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            val cwd = tmp.newFolder("work").toPath()
            val home = tmp.newFolder("home").toPath()
            val claudeHome = tmp.newFolder("claude-home").toPath()
            val args = parseArgs(arrayOf("--provider", "local-test", "--model", "scripted", "--base-url", "$base/v1", "--wire", "openai_chat",
                "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER"), cwd)
            val app = DesktopApp(args, scope, home, claudeHome)
            val rt = app.openSession()
            rt.send(ClientMsgId("u1"), listOf(TextPart("问"))); rt.awaitIdle()
            rt.send(ClientMsgId("u2"), listOf(TextPart("再问"))); rt.awaitIdle()
            org.junit.Assert.assertEquals(2, modelCalls.size)

            val report = costReport(app, app.hub(), rt)
            // 目录外的自定义模型没有价格：如实说算不出来（不是 0），但各模型用量照常分列
            assertTrue(report, report.contains("本会话的花费算不出来：2 次请求的模型在目录里没有价格"))
            assertTrue(report, report.contains("local-test/scripted：请求 2 次，输入 20、输出 10"))
        }
    }
}
