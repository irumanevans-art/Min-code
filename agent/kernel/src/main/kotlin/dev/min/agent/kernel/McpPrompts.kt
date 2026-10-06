package dev.min.agent.kernel

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ToolResult
import dev.min.agent.mcp.McpCallResult
import dev.min.agent.mcp.McpContent
import kotlinx.serialization.json.JsonObject

suspend fun SessionRuntime.listMcpPrompts(): List<dev.min.agent.ext.mcp.McpPromptCommand> = mcpCaller.listPrompts()

suspend fun SessionRuntime.getMcpPrompt(server: String, prompt: String, arguments: JsonObject): ToolResult = try {
    val parts = mcpCaller.getPrompt(server, prompt, arguments).flatMapIndexed { index, message ->
        when (message.content) {
            is McpContent.Text, is McpContent.Image, is McpContent.EmbeddedResource -> {
                val converted = McpResults.toToolResult(server, "prompt:$prompt", "prompt-${deps.ids.next("p")}-$index", McpCallResult(listOf(message.content), null, false), deps.host.artifacts(sessionId))
                // 所有消息合为本轮用户输入；标注原角色，不能把服务器文本变成真实助手历史。
                listOf(TextPart("[MCP prompt $server/$prompt，原角色 ${message.role}]")) + converted.content
            }
            else -> emptyList()
        }
    }
    if (parts.isEmpty()) ToolResult(listOf(TextPart("MCP prompt 没有可用的文本、图片或资源。")), isError = true) else ToolResult(parts)
} catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
catch (e: Exception) { McpResults.failure(server, e) }
