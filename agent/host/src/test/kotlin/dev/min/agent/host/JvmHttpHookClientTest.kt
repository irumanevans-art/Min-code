package dev.min.agent.host

import com.sun.net.httpserver.HttpServer
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** `type:http` hook 的宿主实现：用 JDK 自带的 HttpServer 测真实往返；配置类问题只能是 Failed，不能抛 */
class JvmHttpHookClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val client = JvmHttpHookClient()

    @After
    fun stop() = server.stop(0)

    private fun send(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 5_000) = runBlocking {
        withTimeout(30_000) { client.send(HttpHookRequest(url, headers, "{}", timeoutMs)) }
    }

    @Test
    fun `a normal exchange round-trips status and body, and sends the headers`() {
        var seen: String? = null
        server.createContext("/ok") { ex ->
            seen = ex.requestHeaders.getFirst("X-Token")
            val bytes = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val result = send("$base/ok", mapOf("X-Token" to "abc"))
        assertEquals(HttpHookResult.Responded(200, """{"ok":true}"""), result)
        assertEquals("abc", seen)
    }

    @Test
    fun `configuration mistakes come back as Failed instead of throwing`() {
        val cases = mapOf(
            "没有协议" to send("hooks.example.com/x"),
            "ftp" to send("ftp://hooks.example.com/x"),
            "file" to send("file:///etc/passwd"),
            "头值含换行" to send("$base/x", mapOf("X-A" to "a\nb: c")),
            "头名为空" to send("$base/x", mapOf("" to "a")),
            "头名含空格" to send("$base/x", mapOf("X A" to "a")),
            "连不上" to send("http://127.0.0.1:9/x"),
        )
        cases.forEach { (name, result) -> assertTrue("$name -> $result", result is HttpHookResult.Failed) }
    }

    @Test
    fun `failure messages do not echo header values or the query string`() {
        val bad = send("$base/x?token=URLSECRET", mapOf("Authorization" to "Bearer HEADERSECRET\nX: y")) as HttpHookResult.Failed
        assertTrue(bad.error, !bad.error.contains("HEADERSECRET") && !bad.error.contains("URLSECRET"))
        val noScheme = send("hooks.example.com/x?token=URLSECRET") as HttpHookResult.Failed
        assertTrue(noScheme.error, !noScheme.error.contains("URLSECRET"))
    }

    @Test
    fun `a response body over the cap is cut and flagged, one exactly at the cap is not`() {
        fun body(path: String, size: Int) = server.createContext(path) { ex ->
            val bytes = ByteArray(size) { 'a'.code.toByte() }
            ex.sendResponseHeaders(200, size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        body("/big", 3_000)
        body("/exact", 1_000)
        val small = JvmHttpHookClient(maxBodyBytes = 1_000)
        fun get(path: String) = runBlocking { withTimeout(30_000) { small.send(HttpHookRequest("$base$path", emptyMap(), "{}", 5_000)) } } as HttpHookResult.Responded
        val big = get("/big")
        assertEquals(1_000, big.body.length)
        assertTrue(big.truncated)
        val exact = get("/exact")
        assertEquals(1_000, exact.body.length)
        assertTrue(!exact.truncated)
    }

    @Test
    fun `an endless response is not read past the cap`() {
        val sent = java.util.concurrent.atomic.AtomicLong(0)
        server.createContext("/endless") { ex ->
            ex.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            try {
                ex.responseBody.use { out ->
                    while (sent.get() < 256L * 1024 * 1024) { out.write(chunk); sent.addAndGet(chunk.size.toLong()) }
                }
            } catch (_: java.io.IOException) {
                // 客户端读够了就断开：写端报错是预期的
            }
        }
        val small = JvmHttpHookClient(maxBodyBytes = 1_000)
        val result = runBlocking { withTimeout(30_000) { small.send(HttpHookRequest("$base/endless", emptyMap(), "{}", 20_000)) } } as HttpHookResult.Responded
        assertTrue(result.truncated)
        assertEquals(1_000, result.body.length)
        Thread.sleep(200)
        assertTrue("客户端不该把整条流读完（服务端已写 ${sent.get()} 字节）", sent.get() < 16L * 1024 * 1024)
    }

    @Test
    fun `a slow server is TimedOut`() {
        server.createContext("/slow") { ex ->
            Thread.sleep(1_500)
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        assertEquals(HttpHookResult.TimedOut, send("$base/slow", timeoutMs = 300))
    }
}
