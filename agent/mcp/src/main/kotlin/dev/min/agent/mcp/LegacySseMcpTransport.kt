package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

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
    private val inbox = HttpInbox()
    private val closed = AtomicBoolean()
    private var reader: Job? = null
    @Volatile private var protocolVersion: String? = null
    internal val negotiation = object : NegotiatedTransport {
        override var protocolVersion: String?
            get() = this@LegacySseMcpTransport.protocolVersion
            set(value) { this@LegacySseMcpTransport.protocolVersion = value }
        override var reinitialize: (suspend () -> Unit)? = null
    }
    init { require(timeoutMs > 0 && maxMessageBytes > 0 && retryDelaysMs.size <= 3 && retryDelaysMs.all { it >= 0 }) { "MCP 旧 SSE 超时、消息上限或重试参数无效。" } }

    override val incoming: Flow<JsonObject> = inbox.incoming { scope ->
        reader = scope.launch {
            try {
                val response = openMcp(http, request("GET", url), true, retryDelaysMs, onDebug)
                response.use {
                    if (it.status !in 200..299) throw statusError(it.status, url, "旧 SSE GET")
                    if (it.mediaType() != "text/event-stream") throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 旧 SSE GET 没有返回 SSE。")
                    it.body.sse(maxMessageBytes).collect { event ->
                        when (event.event) {
                            "endpoint" -> {
                                val address = catchingNonCancellation { origin.resolve(event.data.orEmpty()) }.getOrNull()
                                if (address == null || !sameOrigin(origin, address) || address.fragment != null)
                                    throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 给出了跨源或无效的旧 SSE POST 地址，已拒绝转发凭证。")
                                if (endpoint.isCompleted) throw McpTransportClosed("MCP 旧 SSE 服务器重复发送 endpoint，已断开连接。")
                                endpoint.complete(address)
                            }
                            "message" -> event.data?.let { raw -> decodeMessages(raw, onDebug) { inbox.emit(it) } }
                        }
                    }
                }
                throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 旧 SSE 流已断开。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { endpoint.completeExceptionally(e); inbox.fail(e) }
        }
    }

    private suspend fun request(method: String, address: String, body: ByteArray? = null): HttpRequest {
        val reserved = setOf("accept", "content-type", "mcp-protocol-version")
        val supplied = catchingNonCancellation { headers() }.getOrElse {
            throw McpTransportClosed("MCP 服务器 ${safeUrl(url)} 获取请求头失败。")
        }
        val all = supplied.filterNot { it.first.lowercase() in reserved }.toMutableList()
        all += "Accept" to "text/event-stream"
        if (body != null) all += "Content-Type" to "application/json"
        protocolVersion?.let { all += "MCP-Protocol-Version" to it }
        return HttpRequest(method, address, all, body, timeoutMs)
    }
    override suspend fun send(message: JsonObject) {
        inbox.ensureOpen()
        val address = endpoint.await()
        val body = message.toString().toByteArray(Charsets.UTF_8)
        if (body.size > maxMessageBytes) throw McpTransportClosed("MCP 旧 SSE 待发送消息超过 $maxMessageBytes 字节上限。")
        openMcp(http, request("POST", address.toString(), body), false, retryDelaysMs, onDebug).use {
            if (it.status !in 200..299) throw statusError(it.status, url, "旧 SSE POST")
        }
    }
    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            endpoint.completeExceptionally(McpTransportClosed("MCP 旧 SSE 连接已关闭。")); inbox.close(); reader?.cancel()
        }
    }
    override fun toString(): String = "LegacySseMcpTransport(${safeUrl(url)})"
}
