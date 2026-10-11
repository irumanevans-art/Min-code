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
    if (parts.isEmpty()) ToolResult(listOf(TextPart("MCP prompt 没有可用的文本、图片或资源。")), isError = true)
    else ResultBudget.apply(ToolResult(parts), MCP_RESULT_BUDGET_CHARS, deps.host.artifacts(sessionId), dev.min.agent.protocol.CallId("prompt-${deps.ids.next("p")}"))
} catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
catch (e: Exception) { McpResults.failure(server, e) }

/** 此刻生效的 MCP 工具（全名 `mcp__服务器__工具`）；还没有快照 = 空。`--permission-prompt-tool` 用来找提示工具 */
fun SessionRuntime.mcpToolEntries(): List<dev.min.agent.spi.McpToolEntry> = state.value.mcpTools?.tools.orEmpty()

/**
 * 按全名调用一个已连接的 MCP 工具，不走审批 / hooks / 日志（它是 `--permission-prompt-tool` 本身，不是模型的一次工具调用）。
 * 找不到这个工具返回 null；失败一律是错误结果（isError），不抛异常（取消照常传出）
 */
suspend fun SessionRuntime.callMcpToolByName(name: String, arguments: JsonObject): ToolResult? {
    val entry = mcpToolEntries().firstOrNull { it.name == name } ?: return null
    return mcpCaller.call(
        McpCallRequest(
            server = entry.server, tool = entry.tool, arguments = arguments, sessionId = sessionId,
            callId = dev.min.agent.protocol.CallId("prompt-${deps.ids.next("p")}"), artifacts = deps.host.artifacts(sessionId), progress = {},
        ),
    )
}

/** MCP 工具结果里服务器给的文本（去掉给模型看的包装、只取文本块）：权限提示工具的结果要按 JSON 解，不能带包装 */
fun mcpResultText(result: ToolResult): String =
    result.content.filterIsInstance<TextPart>().joinToString("\n\n") { McpResults.unwrap(it.text) }
