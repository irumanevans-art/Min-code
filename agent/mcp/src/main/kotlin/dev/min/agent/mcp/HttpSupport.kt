package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** 收消息和发 POST 必须独立，不然 SSE 上的服务器请求会与回复 POST 互相等待。 */
internal class HttpInbox {
    private val messages = Channel<JsonObject>(64)
    private val collected = AtomicBoolean()
    private val closed = AtomicBoolean()
    var scope: CoroutineScope? = null
        private set
    fun incoming(start: (CoroutineScope) -> Unit): Flow<JsonObject> = flow {
        check(collected.compareAndSet(false, true)) { "MCP HTTP 输入只能收集一次。" }
        coroutineScope {
            scope = this
            start(this)
            for (message in messages) emit(message)
        }
    }
    suspend fun emit(message: JsonObject) { messages.send(message) }
    fun fail(error: Exception) {
        val reason = when (error) {
            is McpTransportClosed -> error
            is McpHttpException -> McpTransportClosed(error.message!!, httpStatus = error.status)
            else -> McpTransportClosed("MCP HTTP 服务器连接读取失败。")
        }
        if (closed.compareAndSet(false, true)) messages.close(reason)
    }
    fun close() { fail(McpTransportClosed("MCP HTTP 连接已关闭。")) }
    fun ensureOpen() { if (closed.get()) throw McpTransportClosed("MCP HTTP 连接已关闭。") }
}

internal fun validateEndpoint(raw: String): URI {
    val uri = catchingNonCancellation { URI(raw) }.getOrNull()
    require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null && uri.fragment == null) {
        "MCP HTTP 服务器地址无效，必须使用完整的 HTTP 或 HTTPS 地址。"
    }
    return uri
}
internal fun sameOrigin(a: URI, b: URI): Boolean {
    fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
    return a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b) && b.userInfo == null
}
internal fun HttpStreamingResponse.header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
internal fun HttpStreamingResponse.mediaType(): String? = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
internal fun statusError(status: Int, url: String, step: String) = McpHttpException(
    status, status == 401 || status == 403,
    "MCP 服务器 ${safeUrl(url)} 在$step 返回 HTTP $status。" + if (status == 401 || status == 403) "需要认证（needs-auth），OAuth 登录尚未接入。" else "",
)
internal class McpHttpNetworkException(message: String) : java.io.IOException(message)

internal suspend fun openMcp(
    http: HttpTransport, request: HttpRequest, initial: Boolean,
    retryDelaysMs: List<Long>, debug: (String) -> Unit,
): HttpStreamingResponse {
    var attempt = 0
    while (true) {
        try {
            val response = http.open(request)
            if (initial && response.status in 500..599 && attempt < retryDelaysMs.size) {
                response.close(); debug("MCP 首次 HTTP 连接暂时失败，准备重试。"); delay(retryDelaysMs[attempt++]); continue
            }
            return response
        } catch (e: CancellationException) { throw e }
        catch (e: java.io.IOException) {
            if (!initial || attempt >= retryDelaysMs.size) throw McpHttpNetworkException("MCP 服务器 ${safeUrl(request.url)} HTTP 连接失败或超时。")
            debug("MCP 首次 HTTP 连接暂时失败，准备重试。"); delay(retryDelaysMs[attempt++])
        }
        catch (e: Exception) { throw McpTransportClosed("MCP 服务器 ${safeUrl(request.url)} 原样 HTTP 请求失败，请检查端口支持与请求头配置。") }
    }
}
