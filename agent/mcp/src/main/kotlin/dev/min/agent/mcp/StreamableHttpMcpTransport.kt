package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

class StreamableHttpMcpTransport(
    private val endpoint: String,
    private val headers: suspend () -> List<Pair<String, String>>,
    private val http: HttpTransport,
    private val timeoutMs: Long = 300_000,
    private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES,
    private val enableGetStream: Boolean = true,
    private val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000),
    private val onDebug: (String) -> Unit = {},
) : McpTransport {
    private val inbox = HttpInbox()
    private val closed = AtomicBoolean()
    private val recovery = Mutex()
    @Volatile private var session: String? = null
    @Volatile private var protocolVersion: String? = null
    private var reinitialize: (suspend () -> Unit)? = null
    internal val negotiation = object : NegotiatedTransport {
        override var protocolVersion: String?
            get() = this@StreamableHttpMcpTransport.protocolVersion
            set(value) { this@StreamableHttpMcpTransport.protocolVersion = value }
        override var reinitialize: (suspend () -> Unit)?
            get() = this@StreamableHttpMcpTransport.reinitialize
            set(value) { this@StreamableHttpMcpTransport.reinitialize = value }
    }
    private var getJob: Job? = null
    init {
        validateEndpoint(endpoint)
        require(timeoutMs > 0 && maxMessageBytes > 0 && retryDelaysMs.size <= 3 && retryDelaysMs.all { it >= 0 }) { "MCP HTTP 超时、消息上限或重试参数无效。" }
    }
    override val incoming: Flow<JsonObject> = inbox.incoming { }

    override suspend fun send(message: JsonObject) {
        try { sendMessage(message) }
        catch (e: McpTransportClosed) { inbox.fail(e); throw e }
    }

    private suspend fun sendMessage(message: JsonObject) {
        inbox.ensureOpen()
        val body = message.toString().toByteArray(Charsets.UTF_8)
        if (body.size > maxMessageBytes) throw McpTransportClosed("MCP HTTP 待发送消息超过 $maxMessageBytes 字节上限。")
        val initial = message.text("method") == "initialize"
        var refreshed = false
        while (true) {
            val oldSession = session
            val response = openMcp(http, request("POST", body, oldSession), initial, retryDelaysMs, onDebug)
            response.use {
                if (it.status == 404 && oldSession != null && !refreshed) {
                    recover(oldSession); refreshed = true
                } else {
                    if (it.status !in 200..299) throw statusError(it.status, endpoint, "POST 请求")
                    if (initial) {
                        val next = it.header("Mcp-Session-Id")
                        if (next != null && (next.isEmpty() || next.any { c -> c.code !in 0x21..0x7e }))
                            throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} initialize 返回了不合法的会话标识。")
                        session = next
                    }
                    if (it.status != 202 && it.status != 204) consumePost(it, message["id"])
                    if (message.text("method") == "notifications/initialized") startGet()
                    return
                }
            }
        }
    }

    private suspend fun recover(expired: String) = recovery.withLock {
        if (session != expired) return@withLock
        session = null; protocolVersion = null
        val handshake = reinitialize ?: throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} 会话已过期，尚未配置重新握手。")
        onDebug("MCP HTTP 会话已过期，正在重新握手一次。")
        handshake()
    }

    private suspend fun request(method: String, body: ByteArray? = null, sessionId: String? = session, lastId: String? = null): HttpRequest {
        // 协议头由传输拥有；配置中的同名头不能覆盖协商结果或注入旧会话。
        val reserved = setOf("accept", "content-type", "mcp-session-id", "mcp-protocol-version", "last-event-id")
        val supplied = catchingNonCancellation { headers() }.getOrElse {
            throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} 获取请求头失败。")
        }
        val all = supplied.filterNot { it.first.lowercase() in reserved }.toMutableList()
        all += "Accept" to "application/json, text/event-stream"
        if (body != null) all += "Content-Type" to "application/json"
        sessionId?.let { all += "Mcp-Session-Id" to it }
        protocolVersion?.let { all += "MCP-Protocol-Version" to it }
        lastId?.takeIf { it.isNotEmpty() }?.let { all += "Last-Event-ID" to it }
        return HttpRequest(method, endpoint, all, body, timeoutMs)
    }

    private suspend fun consumePost(response: HttpStreamingResponse, requestId: JsonElement?) {
        when (response.mediaType()) {
            "application/json" -> decodeMessages(response.body.boundedBytes(maxMessageBytes).decodeToString(), onDebug) { inbox.emit(it) }
            "text/event-stream" -> {
                var cursor: String? = null
                var retryMs = 1_000L
                var matched = false
                suspend fun read(r: HttpStreamingResponse) {
                    r.body.sse(maxMessageBytes).takeWhile { event ->
                        cursor = event.id ?: cursor; event.retryMs?.let { retryMs = it }
                        event.data?.takeIf { it.isNotEmpty() }?.let { raw ->
                            decodeMessages(raw, onDebug) { message ->
                                inbox.emit(message)
                                if (requestId != null && message["id"] == requestId && ("result" in message || "error" in message)) matched = true
                            }
                        }
                        !matched
                    }.collect()
                }
                try { read(response) }
                catch (e: CancellationException) { throw e }
                catch (e: java.io.IOException) { if (cursor.isNullOrEmpty()) throw McpTransportClosed("MCP POST SSE 流中断且没有可续传的事件标识。") }
                if (requestId == null || matched) return
                repeat(5) {
                    if (cursor.isNullOrEmpty()) throw McpTransportClosed("MCP POST SSE 流结束，未收到对应响应且没有续传标识。")
                    delay(retryMs)
                    val resumed = try { openMcp(http, request("GET", lastId = cursor), false, retryDelaysMs, onDebug) }
                    catch (e: java.io.IOException) { onDebug("MCP POST SSE 续传连接失败，准备继续重连。"); return@repeat }
                    resumed.use {
                        if (it.status in 500..599) { onDebug("MCP POST SSE 续传暂时失败，准备继续重连。"); return@repeat }
                        if (it.status !in 200..299) throw statusError(it.status, endpoint, "POST SSE 续传")
                        if (it.mediaType() != "text/event-stream") throw McpTransportClosed("MCP POST SSE 续传没有返回 SSE。")
                        try { read(it) }
                        catch (e: CancellationException) { throw e }
                        catch (e: java.io.IOException) { onDebug("MCP POST SSE 续传中断，准备继续重连。") }
                    }
                    if (matched) return
                }
                throw McpTransportClosed("MCP POST SSE 连续续传五次仍未收到响应。")
            }
            else -> throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} POST 响应必须是 JSON 或 SSE。")
        }
    }

    private fun startGet() {
        if (!enableGetStream || getJob?.isActive == true || closed.get()) return
        val scope = inbox.scope ?: throw McpTransportClosed("MCP HTTP 输入尚未开始收集。")
        getJob = scope.launch {
            var cursor: String? = null
            var retryMs = 1_000L
            var failures = 0
            try {
                while (isActive) {
                    val oldSession = session
                    try {
                        val response = openMcp(http, request("GET", sessionId = oldSession, lastId = cursor), false, retryDelaysMs, onDebug)
                        response.use {
                            if (it.status == 405) return@launch
                            if (it.status == 404 && oldSession != null) { recover(oldSession); cursor = null }
                            else {
                                if (it.status !in 200..299) throw statusError(it.status, endpoint, "GET SSE")
                                if (it.mediaType() != "text/event-stream") throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} GET 流没有返回 SSE。")
                                it.body.sse(maxMessageBytes).collect { event ->
                                    cursor = event.id ?: cursor; event.retryMs?.let { retryMs = it }
                                    event.data?.takeIf { it.isNotEmpty() }?.let { raw -> decodeMessages(raw, onDebug) { inbox.emit(it) }; failures = 0 }
                                }
                            }
                        }
                    } catch (e: java.io.IOException) { onDebug("MCP GET SSE 流中断，准备续传。") }
                    catch (e: McpHttpException) { if (e.status !in 500..599) throw e }
                    if (++failures > 5) throw McpTransportClosed("MCP 服务器 ${safeUrl(endpoint)} GET SSE 连续重连五次失败。")
                    delay(retryMs)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { inbox.fail(e) }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox.close(); getJob?.cancel()
        withContext(NonCancellable) {
            if (session != null) withTimeoutOrNull(1_000) {
                catchingNonCancellation {
                    http.open(request("DELETE")).use {
                        if (it.status != 405 && it.status !in 200..299) onDebug("MCP HTTP 会话删除失败，连接已在本地关闭。")
                    }
                }
            }
        }
        session = null
    }
    override fun toString(): String = "StreamableHttpMcpTransport(${safeUrl(endpoint)})"
}
