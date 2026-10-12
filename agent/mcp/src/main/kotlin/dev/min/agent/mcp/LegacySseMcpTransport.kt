package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** timeoutMs 用于 GET 流的静默检测；POST 无调用硬上限时允许静默 24 小时。 */
class LegacySseMcpTransport(
    private val url: String,
    private val headers: suspend () -> List<Pair<String, String>>,
    private val http: HttpTransport,
    private val timeoutMs: Long = 300_000,
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000),
    private val onDebug: (String) -> Unit = {},
) : McpTransport {
    private val origin = validateEndpoint(url)
    private val endpoint = CompletableDeferred<URI>()
    @Volatile private var postEndpoint: URI? = null
    private val inbox = HttpInbox()
    private val closed = AtomicBoolean()
    private var reader: Job? = null
    @Volatile private var protocolVersion: String? = null
    override val negotiation: McpNegotiation = object : McpNegotiation {
        override fun beginHandshake() { protocolVersion = null }
        override fun negotiated(version: String) { protocolVersion = version }
        override fun endHandshake() { }
        override var reinitialize: (suspend () -> Unit)? = null
    }
    init { require(timeoutMs > 0 && maxMessageBytes > 0 && retryDelaysMs.size <= 3 && retryDelaysMs.all { it >= 0 }) { "MCP 旧 SSE 超时、消息上限或重试参数无效。" } }

    override val incoming: Flow<JsonObject> = inbox.incoming { scope ->
        reader = scope.launch {
            var cursor: String? = null
            var retryMs = 1_000L
            var failures = 0
            var initial = true
            try {
                while (isActive) {
                    try {
                        val response = openMcp(http, request("GET", url, lastId = cursor), initial, retryDelaysMs, onDebug)
                        response.use {
                            if (it.status !in 200..299) throw statusError(it.status, url, "旧 SSE GET", it.headers)
                            if (it.mediaType() != "text/event-stream") throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 旧 SSE GET 没有返回 SSE。")
                            initial = false
                            var sawEndpoint = false
                            it.body.sse(maxMessageBytes).collect { event ->
                                cursor = event.id ?: cursor; event.retryMs?.let { retryMs = it }
                                when (event.event) {
                                    "endpoint" -> {
                                        val address = catchingNonCancellation { origin.resolve(event.data.orEmpty()) }.getOrNull()
                                        if (address == null || !sameOrigin(origin, address) || address.fragment != null)
                                            throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 给出了跨源或无效的旧 SSE POST 地址，已拒绝转发凭证。")
                                        if (sawEndpoint) throw McpTransportClosed("MCP 旧 SSE 服务器重复发送 endpoint，已断开连接。")
                                        sawEndpoint = true; postEndpoint = address; endpoint.complete(address)
                                    }
                                    null, "", "message" -> event.data?.let { raw -> decodeMessages(raw, onDebug) { inbox.emit(it) } }
                                }
                                failures = 0
                            }
                            failures = 0
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: java.net.SocketTimeoutException) { failures = 0; onDebug("MCP 旧 SSE 流空闲，准备续传。") }
                    catch (e: java.io.IOException) {
                        if (initial) throw McpTransportClosed("MCP 旧 SSE 首次连接重试已耗尽。")
                        if (++failures > 5) throw McpTransportClosed("MCP 旧 SSE 连续重连五次失败。")
                        onDebug("MCP 旧 SSE 流中断，准备续传。")
                    } catch (e: McpHttpException) {
                        if (initial || e.status !in 500..599 || ++failures > 5) throw e
                        onDebug("MCP 旧 SSE 连接暂时失败，准备续传。")
                    }
                    delay(retryMs.coerceAtLeast(1))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { endpoint.completeExceptionally(e); inbox.fail(e) }
        }
    }

    private suspend fun request(method: String, address: String, body: ByteArray? = null, lastId: String? = null, readTimeoutMs: Long = timeoutMs): HttpRequest {
        val reserved = setOf("accept", "content-type", "mcp-protocol-version", "last-event-id")
        val supplied = catchingNonCancellation { headers() }.getOrElse {
            throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 获取请求头失败。")
        }
        val all = supplied.filterNot { it.first.lowercase() in reserved }.toMutableList()
        all += "Accept" to "text/event-stream"
        if (body != null) all += "Content-Type" to "application/json"
        protocolVersion?.let { all += "MCP-Protocol-Version" to it }
        lastId?.takeIf { it.isNotEmpty() }?.let { all += "Last-Event-ID" to it }
        // 同 Streamable HTTP：调用方带了 trace 上下文就随这次 POST 发出
        if (body != null) kotlin.coroutines.coroutineContext[McpTraceContext]?.let { trace ->
            all.removeAll { it.first.equals("traceparent", ignoreCase = true) }
            all += "traceparent" to trace.traceparent
        }
        return HttpRequest(method, address, all, body, readTimeoutMs)
    }
    override suspend fun send(message: JsonObject) = send(message, null)
    override suspend fun send(message: JsonObject, timeoutMs: Long?) {
        try {
            inbox.ensureOpen()
            val address = postEndpoint ?: endpoint.await()
            val body = message.toString().toByteArray(Charsets.UTF_8)
            if (body.size > maxMessageBytes) throw McpTransportClosed("MCP 旧 SSE 待发送消息超过 $maxMessageBytes 字节上限。")
            openMcp(http, request("POST", address.toString(), body, readTimeoutMs = timeoutMs ?: 86_400_000), false, retryDelaysMs, onDebug).use {
                if (it.status !in 200..299) throw statusError(it.status, url, "旧 SSE POST", it.headers)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw mcpFailure(e, "MCP 旧 SSE POST 请求失败。") }
    }
    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            endpoint.completeExceptionally(McpTransportClosed("MCP 旧 SSE 连接已关闭。")); inbox.close(); reader?.cancel()
        }
    }
    override fun toString(): String = "LegacySseMcpTransport(${safeUrl(url)})"
}
