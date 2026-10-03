package dev.min.agent.host

import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStatusException
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型请求与 WebFetch 共用的 HTTP 传输。用 HttpURLConnection：Android 与桌面 JVM 都有（Android 上底层就是 OkHttp），
 * 两个宿主共用这一份，不引第三方库。
 *
 * ## 超时的含义
 * - 连接：[connectTimeoutMs]。
 * - [send]：`request.timeoutMs` 作为读超时，即整个响应最长等多久。
 * - [stream]：读超时是**两次收到数据之间最长的静默**，取 `request.timeoutMs` 与 [maxStallMs] 的较小者。
 *   手机网络切换时常出现半死的连接：不断也不来数据。按静默判断断流，交给内核当作可重试的网络错误处理。
 *
 * ## 取消
 * 读流是阻塞调用，协程取消打断不了它。读取时另起一个看门的子协程：一旦取消就断开连接，阻塞的读随即抛错退出——
 * 否则用户按了中断，连接还挂着，照样在消耗流量和 token。
 *
 * 请求头里有凭证：这里不记任何日志，异常信息里只有状态码与响应体（[HttpRequest.toString] 本身也不含头）。
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val maxStallMs: Long = 180_000,
) : HttpTransport {

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
        val connection = open(request, readTimeoutMs = minOf(request.timeoutMs, maxStallMs))
        try {
            coroutineScope {
                val watchdog = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) throw HttpStatusException(status, headersOf(connection), readError(connection))
                    // 错误页里的行不是 SSE 字段，会被解析器全部忽略；必须在传输边界保住正文，让两种协议同样归 Other。
                    val mediaType = connection.contentType?.substringBefore(';')?.trim()
                    if (!mediaType.equals("text/event-stream", ignoreCase = true)) {
                        val preview = connection.inputStream.use(::readPreview)
                        throw HttpStatusException(status, headersOf(connection), preview)
                    }
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        SseReader(reader).forEach { emit(it) }
                    }
                } catch (e: IOException) {
                    // 看门协程断开连接后，阻塞的读会抛 IOException。这时候真正发生的是「取消」，要按取消抛出：
                    // 否则编解码器会把它当成可重试的网络错误，内核就会在用户按了中断之后记一次失败再退避重试
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                watchdog.cancel()
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(request: HttpRequest, readTimeoutMs: Long): HttpURLConnection {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = minOf(connectTimeoutMs.toLong(), readTimeoutMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = readTimeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        connection.instanceFollowRedirects = false // 重定向到别的主机会把凭证头带过去；供应商地址本不该重定向
        request.headers.forEach { (name, value) -> connection.addRequestProperty(name, value) }
        val body = request.body
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return connection
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
