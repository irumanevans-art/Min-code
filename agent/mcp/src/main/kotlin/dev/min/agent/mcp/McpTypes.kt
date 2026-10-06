package dev.min.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

interface McpTransport {
    suspend fun send(message: JsonObject)
    /** POST 的读超时由调用硬上限决定；旧传输与装饰器可保留单参数实现。 */
    suspend fun send(message: JsonObject, timeoutMs: Long?) = send(message)
    /** 可选的会话协商能力，装饰器通过接口委托转发，不依赖具体传输类。 */
    val negotiation: McpNegotiation? get() = null
    /** 只允许一个收集者；断开以 McpTransportClosed 结束，连接持有者负责 close。 */
    val incoming: Flow<JsonObject>
    suspend fun close()
}

interface McpNegotiation {
    var reinitialize: (suspend () -> Unit)?
    /** 清空旧会话，并禁止握手内的 404 重入恢复回调。 */
    fun beginHandshake()
    fun negotiated(version: String)
    fun endHandshake()
}

sealed class McpException(message: String) : Exception(safeLog(message))

class McpTransportClosed(
    reason: String,
    val exitCode: Int? = null,
    stderrTail: List<String> = emptyList(),
    val httpStatus: Int? = null,
    val authChallenge: McpAuthChallenge? = null,
) : McpException(reason) {
    val reason: String = safeLog(reason)
    val stderrTail: List<String> = stderrTail.map(::safeLog)
    val needsAuth: Boolean get() = httpStatus == 401 || httpStatus == 403
}

class McpRpcException(
    val code: Int,
    message: String,
    val data: JsonElement? = null,
    /** 原始 error.message 是协议数据；异常显示仍去掉已知凭证形态。 */
    val serverMessage: String? = message,
) : McpException(message)
class McpTimeoutException(val kind: Kind, message: String) : McpException(message) {
    enum class Kind { Idle, Hard }
}
class McpHttpException(val status: Int, val needsAuth: Boolean, message: String, val authChallenge: McpAuthChallenge? = null) : McpException(message)
class McpProtocolException(message: String) : McpException(message)

internal fun mcpFailure(error: Exception, description: String): McpException =
    error as? McpException ?: McpProtocolException(description)

data class McpClientOptions(
    val clientVersion: String,
    val clientName: String = "Min",
    val requestHandler: suspend (method: String, params: JsonObject?) -> JsonElement = { method, _ ->
        if (method == "ping") JsonObject(emptyMap())
        else throw McpRpcException(-32601, "不支持这个 MCP 请求方法。")
    },
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val requestTimeoutMs: Long = 30_000,
    val maxToolPages: Int = 100,
    /** 配置中的服务器名，便于多连接的错误归属；不传时用通用名称。 */
    val serverLabel: String = "MCP服务器",
    /** 只接收固定的中文说明，不包含原帧、头或服务器提供的错误正文。 */
    val onDebug: (String) -> Unit = {},
) {
    init { require(requestTimeoutMs > 0 && maxToolPages > 0) { "MCP 超时和分页上限必须大于零。" } }
    override fun toString(): String = "McpClientOptions(${safeLog(clientName)}, ${safeLog(clientVersion)}, ${safeLog(serverLabel)})"
}

data class McpServerInfo(
    val protocolVersion: String,
    val serverInfo: JsonObject,
    val capabilities: JsonObject,
    val instructions: String?,
) {
    override fun toString(): String = "McpServerInfo(${safeLog(protocolVersion)}, ${safeLog(serverInfo.text("name").orEmpty())})"
}
sealed interface McpConnectionState {
    data object Connecting : McpConnectionState
    data class Ready(val server: McpServerInfo) : McpConnectionState
    data class Closed(val reason: String, val exitCode: Int? = null, val stderrTail: List<String> = emptyList(), val httpStatus: Int? = null, val authChallenge: McpAuthChallenge? = null) : McpConnectionState {
        val needsAuth: Boolean get() = httpStatus == 401 || httpStatus == 403
        override fun toString(): String = "Closed(${safeLog(reason)}, exitCode=$exitCode, httpStatus=$httpStatus, stderrTail=${stderrTail.map(::safeLog)})"
    }
}
sealed interface McpNotification { data object ToolsListChanged : McpNotification }
data class McpProgress(val progress: Double, val total: Double? = null, val message: String? = null) {
    override fun toString(): String = "McpProgress($progress, $total, ${message?.let(::safeLog)})"
}
data class McpToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
) {
    override fun toString(): String = "McpToolAnnotations(${title?.let(::safeLog)}, readOnlyHint=$readOnlyHint, destructiveHint=$destructiveHint, idempotentHint=$idempotentHint, openWorldHint=$openWorldHint)"
}
data class McpToolDef(
    val name: String, val title: String?, val description: String?, val inputSchema: JsonObject,
    val outputSchema: JsonObject?, val annotations: McpToolAnnotations?, val meta: JsonObject?,
) {
    override fun toString(): String = "McpToolDef(${safeLog(name)}, ${title?.let(::safeLog)})"
}
data class McpCallResult(val content: List<McpContent>, val structuredContent: JsonElement?, val isError: Boolean) {
    override fun toString(): String = "McpCallResult(contentCount=${content.size}, isError=$isError)"
}
data class McpPromptMessage(val role: String, val content: McpContent)
sealed interface McpContent {
    data class Text(val text: String) : McpContent {
        override fun toString(): String = "Text(${safeLog(text)})"
    }
    data class Image(val data: String, val mimeType: String) : McpContent {
        override fun toString(): String = "Image(${safeLog(mimeType)}, base64Length=${data.length})"
    }
    data class Audio(val data: String, val mimeType: String) : McpContent {
        override fun toString(): String = "Audio(${safeLog(mimeType)}, base64Length=${data.length})"
    }
    data class ResourceLink(val uri: String, val name: String, val title: String?, val description: String?, val mimeType: String?, val raw: JsonObject) : McpContent {
        override fun toString(): String = "ResourceLink(${safeUrl(uri)}, ${safeLog(name)})"
    }
    data class EmbeddedResource(val uri: String, val mimeType: String?, val text: String?, val blob: String?, val raw: JsonObject) : McpContent {
        override fun toString(): String = "EmbeddedResource(${safeUrl(uri)})"
    }
    data class Unknown(val raw: JsonElement) : McpContent {
        override fun toString(): String = "Unknown"
    }
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal val emptyObject = JsonObject(emptyMap())
internal fun rpcNotification(method: String, params: JsonObject? = null) = buildJsonObject {
    put("jsonrpc", "2.0"); put("method", method); params?.let { put("params", it) }
}

internal fun parseTool(o: JsonObject): McpToolDef {
    val a = o.obj("annotations")
    return McpToolDef(
        o.text("name") ?: throw McpProtocolException("MCP tools/list 返回的工具没有名称。"),
        o.text("title"), o.text("description"),
        o.obj("inputSchema") ?: throw McpProtocolException("MCP tools/list 返回的工具缺少输入 schema。"),
        o.obj("outputSchema"), a?.let { McpToolAnnotations(it.text("title"), it.bool("readOnlyHint"), it.bool("destructiveHint"), it.bool("idempotentHint"), it.bool("openWorldHint")) }, o.obj("_meta"),
    )
}
internal fun parseContent(raw: JsonElement): McpContent {
    val o = raw as? JsonObject ?: return McpContent.Unknown(raw)
    return when (o.text("type")) {
        "text" -> o.text("text")?.let(McpContent::Text)
        "image" -> o.text("data")?.let { data -> o.text("mimeType")?.let { McpContent.Image(data, it) } }
        "audio" -> o.text("data")?.let { data -> o.text("mimeType")?.let { McpContent.Audio(data, it) } }
        "resource_link" -> o.text("uri")?.let { uri -> o.text("name")?.let { McpContent.ResourceLink(uri, it, o.text("title"), o.text("description"), o.text("mimeType"), o) } }
        "resource" -> o.obj("resource")?.let { r -> r.text("uri")?.let { McpContent.EmbeddedResource(it, r.text("mimeType"), r.text("text"), r.text("blob"), o) } }
        else -> null
    } ?: McpContent.Unknown(raw)
}
