package dev.min.agent.mcp

import kotlinx.serialization.json.*

/** 把握手和请求的线形状放在同一代里，现代 discover 不必渗进公开客户端。 */
internal interface ProtocolGeneration {
    suspend fun initialize(options: McpClientOptions, request: suspend (String, JsonObject?) -> JsonElement): McpServerInfo
    fun request(id: Long, method: String, params: JsonObject?): JsonObject
    fun initializedNotification(): JsonObject?
}
internal object Legacy20251125 : ProtocolGeneration {
    private val supported = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    override suspend fun initialize(options: McpClientOptions, request: suspend (String, JsonObject?) -> JsonElement): McpServerInfo {
        val result = request("initialize", buildJsonObject {
            put("protocolVersion", "2025-11-25")
            put("capabilities", options.capabilities)
            putJsonObject("clientInfo") { put("name", options.clientName); put("version", options.clientVersion) }
        }) as? JsonObject ?: throw McpProtocolException("MCP initialize 响应不是对象。")
        val version = result.text("protocolVersion")
        if (version !in supported) throw McpProtocolException("MCP initialize 返回了不支持的协议版本；支持 2025-11-25、2025-06-18、2025-03-26、2024-11-05。")
        return McpServerInfo(version!!,
            result.obj("serverInfo") ?: throw McpProtocolException("MCP initialize 缺少服务器信息。"),
            result.obj("capabilities") ?: throw McpProtocolException("MCP initialize 缺少服务器能力。"), result.text("instructions"))
    }
    override fun request(id: Long, method: String, params: JsonObject?) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); params?.let { put("params", it) }
    }
    override fun initializedNotification(): JsonObject = rpcNotification("notifications/initialized")
}
