package dev.min.agent.host

import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStatusException
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型请求与 WebFetch 共用的 HTTP 传输。用 HttpURLConnection：Android 与桌面 JVM 都有（Android 上底层就是 OkHttp），
 * 两个宿主共用这一份，不引第三方库。
 *
 * ## 超时的含义
 * - 连接：[connectTimeoutMs]。
 * - [send]：`request.timeoutMs` 作为读超时，即整个响应最长等多久。
 * - [stream]：读超时是**两次收到数据之间最长的静默**，取 `request.timeoutMs` 与 [maxStallMs] 的较小者。
 *   手机网络切换时常出现半死的连接：不断也不来数据。按静默判断断流，交给内核当作可重试的网络错误处理。
 * - [open]：原样端口只用 `request.timeoutMs`，协议调用者自行决定空闲和总时限；不沿用模型的停滞上限。
 *
 * ## 取消
 * JDK 的阻塞读与 disconnect 可能争用内部锁。模型流和原样流通过独立 IO 读取者交付有界队列，
 * 取消不等待阻塞线程；断开在独立 IO 协程中发起。物理清理可能等到新数据、EOF 或读超时。
 *
 * 请求头里有凭证：这里不记任何日志，异常信息里只有状态码与响应体（[HttpRequest.toString] 本身也不含头）。
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val maxStallMs: Long = 180_000,
) : HttpTransport {

    override suspend fun open(request: HttpRequest): HttpStreamingResponse = suspendCancellableCoroutine { continuation ->
        val connectionRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val io = CoroutineScope(Dispatchers.IO)
        // JDK 在等响应头时持有内部锁，disconnect 可能等同一把锁；不能让调用方的取消等待这个阻塞线程。
        continuation.invokeOnCancellation { io.launch { connectionRef.get()?.disconnect() } }
        io.launch {
            var connection: HttpURLConnection? = null
            var handedOff = false
            try {
                connection = open(request, request.timeoutMs, writeBody = false)
                connectionRef.set(connection)
                if (!continuation.isActive) return@launch
                // 固定长度上传会令 JDK 把 401 交给认证重试并丢掉 errorStream；原样端口要保留该正文。
                writeBody(connection, request.body, streaming = false)
                val status = connection.responseCode
                val response = rawResponse(connection, status, headersOf(connection))
                handedOff = true
                continuation.resume(response) { _, rejected, _ -> rejected.close() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                continuation.cancel(e)
                throw e
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(
                    if (e is java.net.SocketTimeoutException) java.net.SocketTimeoutException("原样 HTTP 等待响应头超时。")
                    else IOException("原样 HTTP 连接或响应头读取失败。"))
            } finally { if (!handedOff) connection?.disconnect() }
        }
    }

    private fun rawResponse(connection: HttpURLConnection, status: Int, headers: List<Pair<String, String>>): HttpStreamingResponse {
        val collected = AtomicBoolean()
        val closed = AtomicBoolean()
        val io = CoroutineScope(Dispatchers.IO)
        fun release() {
            // JDK 的流 close 会等待正在读的锁；取消发起断开，但不能让收集者等这个阻塞清理线程。
            if (closed.compareAndSet(false, true)) io.launch { connection.disconnect() }
        }
        val body = flow {
            check(collected.compareAndSet(false, true)) { "原样 HTTP 正文只能收集一次。" }
            check(!closed.get()) { "原样 HTTP 响应已关闭。" }
            val chunks = Channel<ByteArray>(1)
            val reader = io.launch {
                try {
                    val input = if (status >= 400) connection.errorStream else connection.inputStream
                    input?.use {
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = it.read(buffer)
                            if (n < 0) break
                            chunks.send(buffer.copyOf(n))
                        }
                    }
                    chunks.close()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    chunks.cancel(e)
                    throw e
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    chunks.close(if (e is java.net.SocketTimeoutException) java.net.SocketTimeoutException("原样 HTTP 正文读取超时。") else IOException("原样 HTTP 正文读取失败。"))
                } catch (e: Exception) {
                    chunks.close(IOException("原样 HTTP 正文读取失败。"))
                } finally { release() }
            }
            try { for (chunk in chunks) emit(chunk) }
            finally { reader.cancel(); chunks.cancel(); release() }
        }
        return HttpStreamingResponse(status, headers, body, ::release)
    }

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = open(request, readTimeoutMs = request.timeoutMs)
        try {
            coroutineScope {
                // WebFetch 的总截止时间必须能打断阻塞读，不能只依赖每次读的静默超时。
                val watchdog = launch {
                    try { awaitCancellation() } finally { connection.disconnect() }
                }
                try {
                    val status = connection.responseCode
                    val headers = headersOf(connection)
                    if (status !in 200..299) throw HttpStatusException(status, headers, readError(connection))
                    HttpResponse(status, headers, connection.inputStream.use(InputStream::readBytes))
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun stream(request: HttpRequest): Flow<SseEvent> = flow {
        val io = CoroutineScope(Dispatchers.IO)
        val connectionRef = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val closed = AtomicBoolean()
        val events = Channel<SseEvent>(1)
        fun release() {
            closed.set(true)
            connectionRef.getAndSet(null)?.let { connection -> io.launch { connection.disconnect() } }
        }
        // 不作为收集者的子协程：结构化等待无法打断 JDK 正在持锁的 read/close。
        val reader = io.launch {
            try {
                val connection = open(request, readTimeoutMs = minOf(request.timeoutMs, maxStallMs))
                connectionRef.set(connection)
                if (closed.get()) return@launch
                val status = connection.responseCode
                if (status !in 200..299) throw HttpStatusException(status, headersOf(connection), readError(connection))
                // 错误页里的行不是 SSE 字段，会被解析器全部忽略；必须在传输边界保住正文，让两种协议同样归 Other。
                val mediaType = connection.contentType?.substringBefore(';')?.trim()
                if (!mediaType.equals("text/event-stream", ignoreCase = true)) {
                    val preview = connection.inputStream.use(::readPreview)
                    throw HttpStatusException(status, headersOf(connection), preview)
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    SseReader(reader).forEach { events.send(it) }
                }
                events.close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                events.cancel(e)
                throw e
            } catch (e: Exception) {
                events.close(e)
            } finally { release() }
        }
        try { for (event in events) emit(event) }
        finally { reader.cancel(); events.cancel(); release() }
    }

    private fun open(request: HttpRequest, readTimeoutMs: Long, writeBody: Boolean = true): HttpURLConnection {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = minOf(connectTimeoutMs.toLong(), readTimeoutMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = readTimeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.instanceFollowRedirects = false // 重定向到别的主机会把凭证头带过去；供应商地址本不该重定向
        request.headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
        if (writeBody) writeBody(connection, request.body)
        return connection
    }

    private fun writeBody(connection: HttpURLConnection, body: ByteArray?, streaming: Boolean = true) {
        if (body != null) {
            connection.doOutput = true
            if (streaming) connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
    }

    private fun headersOf(connection: HttpURLConnection): List<Pair<String, String>> =
        connection.headerFields.orEmpty().flatMap { (name, values) ->
            if (name == null) emptyList() else values.map { name to it }
        }

    private fun readError(connection: HttpURLConnection): String =
        connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()

    private fun readPreview(input: InputStream): String {
        // UTF-8 每字符最多四字节；先限制读入量，不能为展示 500 字而吞下整页错误正文。
        val bytes = ByteArray(500 * 4)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            count += read
        }
        return String(bytes, 0, count, Charsets.UTF_8).take(500)
    }
}

/**
 * Server-Sent Events 解析（WHATWG 规范的子集）：`event:`、`data:`（多行拼接）、`id:`，冒号开头是注释，空行分派一个事件。
 * `retry:` 与未知字段忽略。流末尾没有空行收尾的最后一个事件也分派（有些中转这么发）。
 */
internal class SseReader(private val reader: BufferedReader) {
    suspend fun forEach(block: suspend (SseEvent) -> Unit) {
        var event: String? = null
        var id: String? = null
        val data = StringBuilder()
        var hasData = false

        suspend fun dispatch() {
            if (hasData) block(SseEvent(event, data.toString(), id))
            event = null
            data.setLength(0)
            hasData = false
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon < 0) line else line.substring(0, colon)
                    val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
                    when (field) {
                        "event" -> event = value
                        "data" -> {
                            if (hasData) data.append('\n')
                            data.append(value)
                            hasData = true
                        }
                        "id" -> id = value
                    }
                }
            }
        }
        dispatch()
    }
}
