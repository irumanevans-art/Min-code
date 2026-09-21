package dev.min.code.core.relay

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 真 socket 往返。
 *
 * proot 不隔离网络命名空间，CLI 访问 `127.0.0.1:port` 就是打到 App 自己 ——
 * 这条路要是通不了，整个协议路由就是摆设。所以这里不 mock，起两个真端口对打。
 */
class LocalRelayServerTest {

    @Test
    fun loopback_round_trip_converts_anthropic_to_chat_and_back() {
        // 上游：一个最小的 Chat Completions 假服务
        val upstream = FakeChatUpstream()
        upstream.start()
        try {
            val profileId = "p1"
            val server = LocalRelayServer { kind, id ->
                assertEquals(LocalRelayServer.Kind.Claude, kind)
                assertEquals(profileId, id)
                LocalRelayServer.Upstream(
                    baseUrl = "http://127.0.0.1:${upstream.port}",
                    token = "sk-test",
                    toChatCompletions = true,
                    authHeader = LocalRelayServer.AuthStyle.Bearer,
                )
            }
            server.start()
            try {
                val url = URL(server.claudeBaseUrl(profileId) + "/v1/messages")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                val body = """
                    {"model":"claude-sonnet-4","max_tokens":64,"messages":[{"role":"user","content":"hi"}]}
                """.trimIndent()
                conn.outputStream.use { it.write(body.toByteArray()) }
                assertEquals(200, conn.responseCode)
                val response = conn.inputStream.bufferedReader().readText()
                assertTrue("should be anthropic message: $response", response.contains("\"type\":\"message\""))
                assertTrue(response.contains("hello from upstream"))
                assertEquals("sk-test", upstream.lastAuth.get()?.removePrefix("Bearer "))
                assertTrue(upstream.lastBody.get()!!.contains("\"messages\""))
            } finally {
                server.stop()
            }
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun wrong_token_is_404() {
        val server = LocalRelayServer { _, _ -> null }
        server.start()
        try {
            val url = URL("http://127.0.0.1:${server.port}/r/wrong/claude/p1/v1/messages")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3_000
                readTimeout = 3_000
            }
            conn.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(404, conn.responseCode)
        } finally {
            server.stop()
        }
    }

    /** 最小的 Chat Completions 上游：读请求、回一条固定响应 */
    private class FakeChatUpstream {
        private var server: ServerSocket? = null
        private val pool = Executors.newSingleThreadExecutor()
        val lastAuth = AtomicReference<String?>(null)
        val lastBody = AtomicReference<String?>(null)
        val port: Int get() = server!!.localPort

        fun start() {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server = ss
            pool.execute {
                while (!ss.isClosed) {
                    try {
                        val socket = ss.accept()
                        handle(socket)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        private fun handle(socket: Socket) {
            socket.use { s ->
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val first = input.readLine() ?: return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                lastAuth.set(headers["authorization"])
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val buf = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                lastBody.set(String(buf))
                val response = buildJsonObject {
                    put("id", "chatcmpl_test")
                    put("model", "gpt-x")
                    put("choices", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("finish_reason", "stop")
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", "hello from upstream")
                            })
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 1)
                        put("completion_tokens", 3)
                    })
                }.toString()
                val out = OutputStreamWriter(s.getOutputStream())
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n")
                out.write(response)
                out.flush()
            }
        }

        fun stop() {
            runCatching { server?.close() }
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
