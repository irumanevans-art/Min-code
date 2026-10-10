package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.InputOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 桌面端到端：真 bash、真 LocalExec、真日志文件、真 WebSocket 客户端，经 [DesktopApp] 装配的内核。
 * 模型是本机脚本化的 OpenAI chat 服务：第一次请求调 Monitor，之后（通知唤醒会话时）回一句话。
 */
class DesktopMonitorIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val requests = CopyOnWriteArrayList<JsonObject>()

    @After fun stop() { scope.cancel(); server.stop(0) }

    private fun modelCalls(monitorInput: JsonObject) {
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { ex ->
            requests += Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
            val n = calls.incrementAndGet()
            val delta = if (n == 1) buildJsonObject {
                put("role", "assistant")
                put("tool_calls", JsonArray(listOf(buildJsonObject {
                    put("index", 0); put("id", "m1"); put("type", "function")
                    put("function", buildJsonObject { put("name", "Monitor"); put("arguments", monitorInput.toString()) })
                })))
            } else buildJsonObject { put("role", "assistant"); put("content", "noted") }
            val chunk = buildJsonObject {
                put("id", "m$n")
                put("choices", JsonArray(listOf(buildJsonObject { put("index", 0); put("delta", delta); put("finish_reason", JsonPrimitive(null as String?)) })))
            }
            val end = """{"id":"m$n","choices":[{"index":0,"delta":{},"finish_reason":"${if (n == 1) "tool_calls" else "stop"}"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
            val body = "data: $chunk\n\ndata: $end\n\ndata: [DONE]\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
    }

    private fun app(): DesktopApp {
        val cwd = tmp.newFolder("work").toPath()
        val args = parseArgs(arrayOf("--provider", "local-test", "--model", "scripted", "--base-url", "$base/v1", "--wire", "openai_chat",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--mode", "bypassPermissions"), cwd)
        return DesktopApp(args, scope, tmp.newFolder("home").toPath(), tmp.newFolder("claude-home").toPath())
    }

    private fun dev.min.agent.kernel.SessionRuntime.notices(): List<String> =
        state.value.history.filterIsInstance<HistoryEntry.User>().filter { it.origin is InputOrigin.Task }
            .map { e -> e.parts.filterIsInstance<TextPart>().joinToString("") { it.text } }

    private suspend fun eventually(what: String, cond: () -> Boolean) {
        withTimeout(60_000) { while (!cond()) delay(100) }.also { assertTrue(what, cond()) }
    }

    @Test fun `a command watch turns each output line into a notice, wakes the idle session and ends with the exit code`() = runBlocking {
        withTimeout(120_000) {
            modelCalls(buildJsonObject {
                put("description", "count to three")
                put("command", "for i in 1 2 3; do echo line-\$i; sleep 0.4; done")
            })
            val rt = app().openSession()
            rt.send(ClientMsgId("u1"), listOf(TextPart("watch it")))
            eventually("三行与结局都到了对话里") { rt.notices().any { "line-3" in it } && rt.notices().any { "命令已结束（退出码 0）" in it } }
            val all = rt.notices().joinToString("\n")
            assertTrue(all.contains("line-1") && all.contains("line-2"))
            assertTrue("Monitor 与 TaskOutput 在桌面工具表里", requests.first()["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.toString().trim('"') }.containsAll(listOf("Monitor", "TaskOutput")))
            val receipt = rt.state.value.toolResults.getValue(dev.min.agent.protocol.CallId("m1"))
            val text = receipt.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            assertTrue(text, !receipt.isError && "mon_" in text && ".log" in text)
            // 通知唤醒了会话：模型被再次请求，看得到通知文字
            eventually("模型收到了通知") { requests.size >= 2 }
            assertTrue(requests.drop(1).any { it.toString().contains("task-notification") })
        }
    }

    /** 一个只接一次连接的最小 WebSocket 服务器：握手后发两条文本再带关闭码关掉 */
    private fun startWsServer(): Int {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread {
            ss.use {
                val c = it.accept()
                val input = c.getInputStream().buffered()
                val head = ByteArrayOutputStream()
                while (true) { head.write(input.read()); val a = head.toByteArray(); if (a.size >= 4 && String(a, a.size - 4, 4) == "\r\n\r\n") break }
                val key = String(head.toByteArray()).lines().first { it.startsWith("Sec-WebSocket-Key", ignoreCase = true) }.substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val out = c.getOutputStream()
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                fun frame(op: Int, payload: ByteArray) { out.write(0x80 or op); out.write(payload.size); out.write(payload); out.flush() }
                Thread.sleep(300)
                frame(1, "tick-1".toByteArray()); Thread.sleep(300)
                frame(1, "tick-2".toByteArray()); Thread.sleep(300)
                frame(8, byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray())
                Thread.sleep(500)
            }
        }.apply { isDaemon = true; start() }
        return ss.localPort
    }

    @Test fun `a websocket watch reports each message, then the close code`() = runBlocking {
        withTimeout(120_000) {
            val port = startWsServer()
            modelCalls(buildJsonObject {
                put("description", "ticker")
                put("ws", buildJsonObject { put("url", "ws://127.0.0.1:$port/feed") })
            })
            val rt = app().openSession()
            rt.send(ClientMsgId("u1"), listOf(TextPart("watch the socket")))
            eventually("两条消息与关闭码") { rt.notices().any { "tick-2" in it } && rt.notices().any { "关闭码 1000" in it && "bye" in it } }
            assertTrue(rt.notices().joinToString("\n").contains("tick-1"))
            val receipt = rt.state.value.toolResults.getValue(dev.min.agent.protocol.CallId("m1"))
            assertTrue(receipt.content.filterIsInstance<TextPart>().joinToString("") { it.text }, !receipt.isError)
            assertEquals(1, rt.notices().count { "关闭码 1000" in it })
        }
    }
}
