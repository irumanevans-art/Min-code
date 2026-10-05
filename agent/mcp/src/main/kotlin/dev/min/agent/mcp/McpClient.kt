package dev.min.agent.mcp

import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong

class McpClient(
    private val transport: McpTransport,
    scope: CoroutineScope,
    private val options: McpClientOptions,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(scope.coroutineContext + job)
    private val generation: ProtocolGeneration = Legacy20251125
    private val ids = AtomicLong()
    private val handshake = Mutex()
    private class Pending(val method: String, val progress: (McpProgress) -> Unit) {
        val result = CompletableDeferred<JsonElement>()
        val activity = Channel<Unit>(Channel.CONFLATED)
    }
    private val pending = mutableMapOf<Long, Pending>()
    private val mutableState = MutableStateFlow<McpConnectionState>(McpConnectionState.Connecting)
    val state: StateFlow<McpConnectionState> = mutableState.asStateFlow()
    private val events = MutableSharedFlow<McpNotification>(extraBufferCapacity = 64)
    val notifications: Flow<McpNotification> = events.asSharedFlow()
    private var server: McpServerInfo? = null
    private val displayName: String get() = safeLog(options.serverLabel)

    init {
        transport.negotiation()?.reinitialize = {
            handshake.withLock { establish() }
        }
        connectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                transport.incoming.collect { route(it) }
                terminate(McpTransportClosed("$displayName：服务器关闭了连接。"))
            } catch (e: CancellationException) {
                terminate(McpTransportClosed("$displayName：连接作用域已取消。"))
                throw e
            } catch (e: Exception) {
                terminate(if (e is McpTransportClosed) McpTransportClosed("$displayName：${e.reason}", e.exitCode, e.stderrTail, e.httpStatus)
                    else if (e is McpHttpException) McpTransportClosed(e.message!!, httpStatus = e.status)
                    else McpTransportClosed("$displayName：服务器连接读取失败。"))
            } finally {
                withContext(NonCancellable) { catchingNonCancellation { transport.close() } }
                job.cancel()
            }
        }
    }

    suspend fun connect(): McpServerInfo = handshake.withLock {
        ensureOpen()
        server ?: establish()
    }

    private suspend fun establish(): McpServerInfo = try { initialize() } catch (e: Exception) {
        val failure = if (e is McpTransportClosed) McpTransportClosed("$displayName：${e.reason}", e.exitCode, e.stderrTail, e.httpStatus) else e
        if (failure is McpTransportClosed) terminate(failure)
        else if (e is McpHttpException) terminate(McpTransportClosed(e.message!!, httpStatus = e.status))
        else if (e is McpTimeoutException) terminate(McpTransportClosed(e.message!!))
        withContext(NonCancellable) { close() }
        throw failure
    }

    private suspend fun initialize(): McpServerInfo {
        ensureOpen()
        mutableState.value = McpConnectionState.Connecting
        val info = generation.initialize(options) { method, params ->
            request(method, params, options.requestTimeoutMs)
        }
        transport.negotiation()?.protocolVersion = info.protocolVersion
        generation.initializedNotification()?.let { transport.send(it) }
        ensureOpen()
        server = info
        mutableState.value = McpConnectionState.Ready(info)
        return info
    }

    suspend fun listTools(): List<McpToolDef> {
        ready()
        val tools = mutableListOf<McpToolDef>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(options.maxToolPages) {
            val params = cursor?.let { buildJsonObject { put("cursor", it) } }
            val result = request("tools/list", params, options.requestTimeoutMs) as? JsonObject
                ?: throw McpTransportClosed("$displayName：tools/list 响应不是对象。")
            val page = result["tools"] as? JsonArray ?: throw McpTransportClosed("$displayName：tools/list 响应缺少工具列表。")
            tools += page.map {
                try { parseTool(it as? JsonObject ?: throw McpTransportClosed("tools/list 的工具不是对象。")) }
                catch (e: McpTransportClosed) { throw McpTransportClosed("$displayName：${e.reason}") }
            }
            cursor = result.text("nextCursor") ?: return tools
            if (!seen.add(cursor)) { debug("MCP tools/list 游标重复，已停止翻页。"); return tools }
        }
        debug("MCP tools/list 已到分页上限，已停止翻页。")
        return tools
    }

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
        onProgress: (McpProgress) -> Unit = NO_PROGRESS,
        idleTimeoutMs: Long,
        hardTimeoutMs: Long? = null,
    ): McpCallResult {
        ready()
        val result = request("tools/call", buildJsonObject {
            put("name", name); put("arguments", arguments)
        }, idleTimeoutMs, hardTimeoutMs, onProgress) as? JsonObject
            ?: throw McpTransportClosed("$displayName：tools/call 响应不是对象。")
        val content = result["content"] as? JsonArray
            ?: throw McpTransportClosed("$displayName：tools/call 响应缺少内容列表。")
        return McpCallResult(content.map(::parseContent), result["structuredContent"], result.bool("isError") ?: false)
    }

    suspend fun ping() { ready(); request("ping", null, options.requestTimeoutMs) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun request(
        method: String, params: JsonObject?, idleMs: Long, hardMs: Long? = null,
        progress: (McpProgress) -> Unit = NO_PROGRESS,
    ): JsonElement = coroutineScope {
        require(idleMs > 0 && (hardMs == null || hardMs > 0)) { "MCP 调用超时必须大于零。" }
        val id = ids.incrementAndGet()
        val p = Pending(method, progress)
        synchronized(pending) { ensureOpen(); pending[id] = p }
        val actualParams = if (progress !== NO_PROGRESS) buildJsonObject {
            params?.forEach { (k, v) -> put(k, v) }
            put("_meta", buildJsonObject { params?.obj("_meta")?.forEach { (k, v) -> put(k, v) }; put("progressToken", id) })
        } else params
        // send 可能读一个长 POST SSE 响应；计时和接收不能排在它结束之后。
        val sender = launch {
            try { transport.send(generation.request(id, method, actualParams)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { p.result.completeExceptionally(e) }
        }
        val hard = hardMs?.let { async { delay(it) } }
        try {
            while (true) {
                val value = select<JsonElement?> {
                    p.result.onAwait { it }
                    p.activity.onReceive { null }
                    hard?.onAwait { throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：$method 超过调用总时限。") }
                    onTimeout(idleMs) { throw McpTimeoutException(McpTimeoutException.Kind.Idle, "$displayName：$method 等待响应或进度超时。") }
                }
                if (value != null) return@coroutineScope value
            }
            @Suppress("UNREACHABLE_CODE") error("不可到达")
        } catch (e: Exception) {
            if (e is CancellationException || e is McpTimeoutException) {
                // 调用方已经取消也要给服务器一次有界的清理机会，不把原取消包装成普通错误。
                withContext(NonCancellable) {
                    withTimeoutOrNull(1_000) {
                        catchingNonCancellation { transport.send(rpcNotification("notifications/cancelled", buildJsonObject {
                            put("requestId", id); put("reason", if (e is McpTimeoutException) "调用超时" else "调用方已取消")
                        })) }
                    }
                }
            }
            throw e
        } finally {
            synchronized(pending) { pending.remove(id) }
            hard?.cancel(); sender.cancel(); p.activity.close()
        }
    }

    private suspend fun route(message: JsonObject) {
        if (message.text("jsonrpc") != "2.0") { debug("MCP 忽略不合法的 JSON-RPC 版本。"); return }
        val method = message.text("method")
        val id = message["id"]
        if (method != null && id != null) {
            connectionScope.launch {
                val response = try {
                    // ping 不能排在用户交互 handler 后面等待。
                    val result = if (method == "ping") emptyObject else options.requestHandler(method, message.obj("params"))
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        putJsonObject("error") {
                            put("code", if (e is McpRpcException && e.code == -32601) -32601 else -32603)
                            put("message", if (e is McpRpcException && e.code == -32601) "不支持这个 MCP 请求方法。" else "MCP 客户端处理服务器请求失败。")
                        }
                    }
                }
                catchingNonCancellation { transport.send(response) }.onFailure { debug("MCP 服务器请求的回复发送失败。") }
            }
        } else if (method != null) {
            when (method) {
                "notifications/tools/list_changed" -> events.emit(McpNotification.ToolsListChanged)
                "notifications/progress" -> {
                    val params = message.obj("params") ?: return
                    val token = (params["progressToken"] as? JsonPrimitive)?.longOrNull ?: return
                    val p = synchronized(pending) { pending[token] } ?: return
                    if (p.progress === NO_PROGRESS) return
                    val progress = params.number("progress") ?: return
                    p.activity.trySend(Unit)
                    catchingNonCancellation { p.progress(McpProgress(progress, params.number("total"), params.text("message"))) }
                        .onFailure { debug("MCP 进度回调失败，已继续等待结果。") }
                }
            }
        } else if (id != null) {
            val key = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val p = synchronized(pending) { pending[key] }
            if (p == null) { debug("MCP 忽略未知请求 id 的响应。"); return }
            val error = message.obj("error")
            if (error != null) {
                // 不把不可信错误正文拼进异常；原始 data 可供上层按协议读取。
                p.result.completeExceptionally(McpRpcException((error["code"] as? JsonPrimitive)?.intOrNull ?: -32603,
                    "$displayName：${p.method} 返回 JSON-RPC 错误。", error["data"]))
            } else if (message.containsKey("result")) p.result.complete(message.getValue("result"))
            else debug("MCP 忽略没有 result 或 error 的响应。")
        } else debug("MCP 忽略无法路由的消息。")
    }

    private fun debug(message: String) { catchingNonCancellation { options.onDebug(message) } }
    private fun ready() { ensureOpen(); check(state.value is McpConnectionState.Ready) { "$displayName：服务器尚未完成握手。" } }
    private fun ensureOpen() {
        val s = state.value
        if (s is McpConnectionState.Closed) throw McpTransportClosed(s.reason, s.exitCode, s.stderrTail, s.httpStatus)
    }
    private fun terminate(reason: McpTransportClosed) {
        synchronized(pending) {
            if (state.value is McpConnectionState.Closed) return
            mutableState.value = McpConnectionState.Closed(reason.reason, reason.exitCode, reason.stderrTail, reason.httpStatus)
            pending.values.forEach { it.result.completeExceptionally(reason) }
        }
    }
    suspend fun close() {
        terminate(McpTransportClosed("$displayName：客户端已关闭连接。"))
        try { withContext(NonCancellable) { transport.close() } }
        finally { job.cancel() }
    }
    private companion object { val NO_PROGRESS: (McpProgress) -> Unit = {} }
}
