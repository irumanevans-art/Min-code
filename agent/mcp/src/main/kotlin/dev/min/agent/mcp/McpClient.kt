package dev.min.agent.mcp

import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
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
        val progressQueue = Channel<McpProgress>(16, BufferOverflow.DROP_OLDEST)
    }
    private val pending = mutableMapOf<Long, Pending>()
    private val serverRequests = mutableMapOf<JsonElement, Job>()
    private val reversePending = AtomicLong()

    private fun wakeIdleTimers() { synchronized(pending) { pending.values.forEach { it.activity.trySend(Unit) } } }
    private val mutableState = MutableStateFlow<McpConnectionState>(McpConnectionState.Connecting)
    val state: StateFlow<McpConnectionState> = mutableState.asStateFlow()
    private val listVersion = MutableStateFlow(0L)
    /** 幂等失效信号：慢订阅者可合并中间版本，晚订阅者也能看到已经变过。 */
    val toolsListVersion: StateFlow<Long> = listVersion.asStateFlow()
    val notifications: Flow<McpNotification> = toolsListVersion.filter { it > 0 }.map { McpNotification.ToolsListChanged }
    private var server: McpServerInfo? = null
    private val displayName: String get() = safeLog(options.serverLabel)

    init {
        transport.negotiation?.reinitialize = {
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

    private suspend fun establish(): McpServerInfo {
        try {
            repeat(2) { attempt ->
                transport.negotiation?.beginHandshake()
                try {
                    // initialized 是通知，不能靠 pending 的计时器保护；整个握手尝试都必须有界。
                    return withTimeoutOrNull(options.requestTimeoutMs) { initialize() }
                        ?: throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：握手超过总时限。")
                } catch (e: McpHttpException) {
                    if (e.status != 404 || attempt == 1) throw e
                    debug("MCP 握手会话已过期，正在重新开始一次完整握手。")
                } finally { transport.negotiation?.endHandshake() }
            }
            throw McpProtocolException("$displayName：握手未完成。")
        } catch (e: CancellationException) {
            terminate(McpTransportClosed("$displayName：握手已取消。"))
            withContext(NonCancellable) { close() }
            throw e
        } catch (e: Exception) {
            val failure = mcpFailure(e, "$displayName：握手失败。")
            terminate(if (failure is McpTransportClosed) failure else McpTransportClosed(failure.message.orEmpty(), httpStatus = (failure as? McpHttpException)?.status))
            withContext(NonCancellable) { close() }
            throw failure
        }
    }

    private suspend fun initialize(): McpServerInfo {
        ensureOpen()
        mutableState.value = McpConnectionState.Connecting
        val info = generation.initialize(options) { method, params ->
            request(method, params, options.requestTimeoutMs)
        }
        transport.negotiation?.negotiated(info.protocolVersion)
        generation.initializedNotification()?.let { transport.send(it, options.requestTimeoutMs) }
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
                ?: throw McpProtocolException("$displayName：tools/list 响应不是对象。")
            val page = result["tools"] as? JsonArray ?: throw McpProtocolException("$displayName：tools/list 响应缺少工具列表。")
            for (raw in page) {
                try { tools += parseTool(raw as? JsonObject ?: throw McpProtocolException("tools/list 的工具不是对象。")) }
                catch (e: McpException) { debug("MCP tools/list 跳过缺少名称或输入 schema 的畸形工具。") }
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
    ): McpCallResult = coroutineScope {
        if (idleTimeoutMs <= 0 || (hardTimeoutMs != null && hardTimeoutMs <= 0)) throw McpProtocolException("MCP 调用超时必须大于零。")
        // 等恢复不能把工具调用的硬时限重新开始；同一个截止信号贯穿等待、发送与收响应。
        val deadline = hardTimeoutMs?.let { async { delay(it) } }
        try {
            ready(deadline)
            val result = request("tools/call", buildJsonObject {
                put("name", name); put("arguments", arguments)
            }, idleTimeoutMs, hardTimeoutMs, onProgress, deadline) as? JsonObject
                ?: throw McpProtocolException("$displayName：tools/call 响应不是对象。")
            val content = result["content"] as? JsonArray
                ?: throw McpProtocolException("$displayName：tools/call 响应缺少内容列表。")
            McpCallResult(content.map(::parseContent), result["structuredContent"], result.bool("isError") ?: false)
        } finally { deadline?.cancel() }
    }

    suspend fun ping() { ready(); request("ping", null, options.requestTimeoutMs) }

    suspend fun rootsChanged() {
        ready()
        transport.send(rpcNotification("notifications/roots/list_changed"), options.requestTimeoutMs)
    }

    suspend fun listResources(): List<JsonObject> = listPages("resources/list", "resources")

    suspend fun listResourceTemplates(): List<JsonObject> = listPages("resources/templates/list", "resourceTemplates")

    suspend fun listPrompts(): List<JsonObject> = listPages("prompts/list", "prompts")

    suspend fun getPrompt(name: String, arguments: JsonObject): List<McpPromptMessage> {
        ready()
        val result = request("prompts/get", buildJsonObject { put("name", name); put("arguments", arguments) }, options.requestTimeoutMs) as? JsonObject
            ?: throw McpProtocolException("$displayName：prompts/get 响应不是对象。")
        val messages = result["messages"] as? JsonArray ?: throw McpProtocolException("$displayName：prompts/get 缺少 messages。")
        return messages.map { raw ->
            val message = raw as? JsonObject ?: throw McpProtocolException("$displayName：prompt 消息不是对象。")
            val role = message.text("role")
            if (role != "user" && role != "assistant") throw McpProtocolException("$displayName：prompt 消息角色不合法。")
            McpPromptMessage(role, parseContent(message["content"] ?: throw McpProtocolException("$displayName：prompt 消息没有 content。")))
        }
    }

    suspend fun readResource(uri: String): McpCallResult {
        ready()
        val result = request("resources/read", buildJsonObject { put("uri", uri) }, options.requestTimeoutMs) as? JsonObject
            ?: throw McpProtocolException("$displayName：resources/read 响应不是对象。")
        val contents = result["contents"] as? JsonArray
            ?: throw McpProtocolException("$displayName：resources/read 缺少 contents。")
        return McpCallResult(contents.map { resource ->
            val embedded = parseContent(buildJsonObject { put("type", "resource"); put("resource", resource) })
            if (embedded is McpContent.EmbeddedResource && embedded.text == null && embedded.blob != null && embedded.mimeType?.startsWith("image/") == true)
                McpContent.Image(embedded.blob, embedded.mimeType)
            else embedded
        }, null, false)
    }

    private suspend fun listPages(method: String, field: String): List<JsonObject> {
        ready()
        val items = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(options.maxToolPages) {
            val result = request(method, cursor?.let { buildJsonObject { put("cursor", it) } }, options.requestTimeoutMs) as? JsonObject
                ?: throw McpProtocolException("$displayName：$method 响应不是对象。")
            val page = result[field] as? JsonArray ?: throw McpProtocolException("$displayName：$method 缺少 $field。")
            items += page.mapNotNull { it as? JsonObject }
            cursor = result.text("nextCursor") ?: return items
            if (!seen.add(cursor)) { debug("MCP $method 游标重复，已停止翻页。"); return items }
        }
        debug("MCP $method 已到分页上限，已停止翻页。")
        return items
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun request(
        method: String, params: JsonObject?, idleMs: Long, hardMs: Long? = null,
        progress: (McpProgress) -> Unit = NO_PROGRESS,
        deadline: Deferred<Unit>? = null,
    ): JsonElement = coroutineScope {
        if (idleMs <= 0 || (hardMs != null && hardMs <= 0)) throw McpProtocolException("MCP 调用超时必须大于零。")
        val id = ids.incrementAndGet()
        val p = Pending(method, progress)
        synchronized(pending) { ensureOpen(); pending[id] = p }
        val actualParams = if (progress !== NO_PROGRESS) buildJsonObject {
            params?.forEach { (k, v) -> put(k, v) }
            put("_meta", buildJsonObject { params?.obj("_meta")?.forEach { (k, v) -> put(k, v) }; put("progressToken", id) })
        } else params
        if (progress !== NO_PROGRESS) connectionScope.launch(Dispatchers.Default) {
            for (value in p.progressQueue) {
                catchingNonCancellation { progress(value) }.onFailure { debug("MCP 进度回调失败，已继续等待结果。") }
            }
        }
        // send 可能读一个长 POST SSE 响应；计时和接收不能排在它结束之后。
        val sender = launch {
            try { transport.send(generation.request(id, method, actualParams), hardMs ?: if (method == "initialize" || method == "tools/list" || method == "ping") options.requestTimeoutMs else null) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { p.result.completeExceptionally(mcpFailure(e, "$displayName：$method 发送失败。")) }
        }
        val hard = deadline ?: hardMs?.let { async { delay(it) } }
        try {
            while (true) {
                val value = select<JsonElement?> {
                    p.result.onAwait { it }
                    p.activity.onReceive { null }
                    hard?.onAwait { throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：$method 超过调用总时限。") }
                    // 服务器在等本客户端回答时无法继续处理请求；配置的总时限仍然有效。
                    if (reversePending.get() == 0L) onTimeout(idleMs) {
                        if (reversePending.get() == 0L) throw McpTimeoutException(McpTimeoutException.Kind.Idle, "$displayName：$method 等待响应或进度超时。")
                        null
                    }
                }
                if (value != null) return@coroutineScope value
            }
            @Suppress("UNREACHABLE_CODE") error("不可到达")
        } catch (e: Exception) {
            if (method != "initialize" && (e is CancellationException || e is McpTimeoutException)) {
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
            hard?.cancel(); sender.cancel(); p.activity.close(); p.progressQueue.close()
        }
    }

    private suspend fun route(message: JsonObject) {
        if (message.text("jsonrpc") != "2.0") { debug("MCP 忽略不合法的 JSON-RPC 版本。"); return }
        val method = message.text("method")
        val id = message["id"]
        if (method != null && id != null) {
            reversePending.incrementAndGet(); wakeIdleTimers()
            val handler = connectionScope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = try {
                    // ping 不能排在用户交互 handler 后面等待。
                    val result = if (method == "ping") emptyObject else options.requestHandler(method, message.obj("params"))
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
                } catch (e: CancellationException) {
                    if (method != "elicitation/create") throw e
                    withContext(NonCancellable) {
                        withTimeoutOrNull(1_000) { catchingNonCancellation { transport.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") { put("action", "cancel") }
                        }) } }
                    }
                    return@launch
                }
                catch (e: Exception) {
                    buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", id)
                        putJsonObject("error") {
                            put("code", if (e is McpRpcException) e.code else -32603)
                            put("message", if (e is McpRpcException) e.serverMessage ?: e.message.orEmpty() else "MCP 客户端处理服务器请求失败。")
                            if (e is McpRpcException) e.data?.let { put("data", it) }
                        }
                    }
                }
                catchingNonCancellation { transport.send(response) }.onFailure { debug("MCP 服务器请求的回复发送失败。") }
            } finally {
                synchronized(serverRequests) { serverRequests.remove(id) }
                reversePending.decrementAndGet(); wakeIdleTimers()
            }
            }
            synchronized(serverRequests) { serverRequests[id] = handler }
            handler.start()
        } else if (method != null) {
            when (method) {
                "notifications/cancelled" -> message.obj("params")?.get("requestId")?.let { requestId -> synchronized(serverRequests) { serverRequests[requestId] }?.cancel() }
                "notifications/tools/list_changed" -> listVersion.update { it + 1 }
                "notifications/progress" -> {
                    val params = message.obj("params") ?: return
                    val token = (params["progressToken"] as? JsonPrimitive)?.longOrNull ?: return
                    val p = synchronized(pending) { pending[token] } ?: return
                    if (p.progress === NO_PROGRESS) return
                    val progress = params.number("progress") ?: return
                    p.activity.trySend(Unit)
                    p.progressQueue.trySend(McpProgress(progress, params.number("total"), params.text("message")))
                }
            }
        } else if (id != null) {
            val key = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            val p = synchronized(pending) { pending[key] }
            if (p == null) { debug("MCP 忽略未知请求 id 的响应。"); return }
            val error = message.obj("error")
            if (error != null) {
                val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: -32603
                val serverMessage = error.text("message")
                p.result.completeExceptionally(McpRpcException(code,
                    "$displayName：${p.method} 返回 JSON-RPC 错误 $code${serverMessage?.let { ": $it" }.orEmpty()}", error["data"], serverMessage))
            } else if (message.containsKey("result")) p.result.complete(message.getValue("result"))
            else debug("MCP 忽略没有 result 或 error 的响应。")
        } else debug("MCP 忽略无法路由的消息。")
    }

    private fun debug(message: String) { catchingNonCancellation { options.onDebug(message) } }
    private suspend fun ready(deadline: Deferred<Unit>? = null) {
        ensureOpen()
        if (deadline?.isCompleted == true) throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：tools/call 超过调用总时限。")
        if (state.value is McpConnectionState.Connecting) {
            withTimeoutOrNull(options.requestTimeoutMs) {
                coroutineScope {
                    val restored = async { state.first { it !is McpConnectionState.Connecting } }
                    try {
                        select<McpConnectionState> {
                            restored.onAwait { it }
                            deadline?.onAwait { throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：tools/call 等待连接恢复时超过总时限。") }
                        }
                    } finally { restored.cancel() }
                }
            }
                ?: throw McpTimeoutException(McpTimeoutException.Kind.Hard, "$displayName：等待连接恢复超过总时限。")
        }
        ensureOpen()
    }
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
