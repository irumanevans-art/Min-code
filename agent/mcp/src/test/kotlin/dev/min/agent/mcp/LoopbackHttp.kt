package dev.min.agent.mcp

import com.sun.net.httpserver.*
import dev.min.agent.spi.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.net.*
import java.util.concurrent.Executors

/** MCP 的测试端口只实现原样响应；真实宿主的同一契约在 host 单独验收。 */
internal class LoopbackHttp : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = this@LoopbackHttp.executor; start() }
    val url = "http://127.0.0.1:${server.address.port}"
    val http = object : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse = error("unused")
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("unused")
        override suspend fun open(request: HttpRequest): HttpStreamingResponse = withContext(Dispatchers.IO) {
            val c = URL(request.url).openConnection() as HttpURLConnection
            c.requestMethod = request.method; c.instanceFollowRedirects = false; c.readTimeout = 2_000; c.connectTimeout = 2_000
            request.headers.forEach { (k, v) -> c.addRequestProperty(k, v) }
            request.body?.let { c.doOutput = true; c.outputStream.use { out -> out.write(it) } }
            val status = c.responseCode
            val headers = c.headerFields.filterKeys { it != null }.flatMap { (k, v) -> v.map { k to it } }
            HttpStreamingResponse(status, headers, flow {
                try {
                    (if (status >= 400) c.errorStream else c.inputStream)?.use { input ->
                        val b = ByteArray(1024)
                        while (true) { val n = input.read(b); if (n < 0) break; emit(b.copyOf(n)) }
                    }
                } finally { c.disconnect() }
            }.flowOn(Dispatchers.IO)) { c.disconnect() }
        }
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}
internal fun HttpExchange.reply(status: Int, body: String = "", type: String = "application/json") {
    responseHeaders.add("Content-Type", type)
    if (body.isEmpty()) { sendResponseHeaders(status, -1); close() }
    else { val bytes = body.toByteArray(); sendResponseHeaders(status, bytes.size.toLong()); responseBody.use { it.write(bytes) } }
}
