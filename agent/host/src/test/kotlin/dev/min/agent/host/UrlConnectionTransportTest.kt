package dev.min.agent.host

import com.sun.net.httpserver.HttpServer
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpStatusException
import dev.min.agent.spi.SseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 用 JDK 自带的 HttpServer 起一个本机服务，测真实的连接、流与取消（只在桌面 JVM 跑） */
class UrlConnectionTransportTest {
    // 多线程：取消测试里慢响应的处理线程要和别的请求并存；线程池必须在 start 之前设
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val transport = UrlConnectionTransport(maxStallMs = 5_000)

    @After
    fun stop() = server.stop(0)

    private fun post(path: String, body: String = "{}") =
        HttpRequest("POST", "$base$path", listOf("x-api-key" to "sk-secret", "content-type" to "application/json"), body.toByteArray(), 10_000)

    @Test
    fun `streams SSE events in order and sends headers and body`() = runBlocking {
        var seenKey: String? = null
        var seenBody: String? = null
        server.createContext("/sse") { ex ->
            seenKey = ex.requestHeaders.getFirst("x-api-key")
            seenBody = ex.requestBody.readBytes().decodeToString()
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use {
                it.write("event: a\ndata: {\"n\":1}\n\n: comment\ndata: line1\ndata: line2\nid: 7\n\ndata: [DONE]\n\n".toByteArray())
            }
        }
        val events = transport.stream(post("/sse", "{\"q\":1}")).toList()
        assertEquals(
            // id 按 SSE 规范是「最后事件 id」，分派后不清空，所以延续到第三个事件
            listOf(SseEvent("a", "{\"n\":1}"), SseEvent(null, "line1\nline2", "7"), SseEvent(null, "[DONE]", "7")),
            events,
        )
        assertEquals("sk-secret", seenKey)
        assertEquals("{\"q\":1}", seenBody)
    }

    @Test
    fun `non-2xx responses raise HttpStatusException with the body, never the key`() = runBlocking {
        server.createContext("/limited") { ex ->
            ex.responseHeaders.add("retry-after", "7")
            val body = "{\"error\":{\"type\":\"rate_limit_error\"}}".toByteArray()
            ex.sendResponseHeaders(429, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        val e = assertThrows(HttpStatusException::class.java) { runBlocking { transport.stream(post("/limited")).toList() } }
        assertEquals(429, e.status)
        assertTrue(e.body.contains("rate_limit_error"))
        assertEquals("7", e.headers.first { it.first.equals("retry-after", ignoreCase = true) }.second)
        assertTrue("异常里不能有凭证", "sk-secret" !in (e.message ?: "") && "sk-secret" !in e.body)
    }

    @Test
    fun `cancelling a stalled stream disconnects promptly instead of waiting for the server`() = runBlocking {
        val clientGone = CountDownLatch(1)
        server.createContext("/slow") { ex ->
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            try {
                out.write("data: first\n\n".toByteArray())
                out.flush()
                // 之后一直不发数据，直到客户端断开（写入失败）
                repeat(200) {
                    Thread.sleep(50)
                    out.write(": keepalive\n".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                clientGone.countDown()
            }
        }
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            transport.stream(post("/slow")).collect { started.complete(Unit) }
        }
        withTimeout(5_000) { started.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("取消后服务端应很快发现连接断了", clientGone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a silent server trips the stall timeout as an IOException`() {
        val stalling = UrlConnectionTransport(maxStallMs = 300)
        server.createContext("/stall") { ex ->
            ex.sendResponseHeaders(200, 0)
            Thread.sleep(2_000)
            ex.close()
        }
        assertThrows(IOException::class.java) { runBlocking { stalling.stream(post("/stall")).first() } }
    }

    @Test
    fun `send returns the whole body`() = runBlocking {
        server.createContext("/plain") { ex ->
            val body = "hello".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        assertEquals("hello", transport.send(post("/plain")).body.decodeToString())
    }

    @Test
    fun `sse parsing handles missing final blank line and fields without a space`() = runBlocking {
        val events = mutableListOf<SseEvent>()
        SseReader(BufferedReader(StringReader("event:x\ndata:1\n\nretry: 10\ndata: tail"))).forEach { events += it }
        assertEquals(listOf(SseEvent("x", "1"), SseEvent(null, "tail")), events)
    }
}
