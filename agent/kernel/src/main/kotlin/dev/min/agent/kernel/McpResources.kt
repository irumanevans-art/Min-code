package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*

internal fun isMcpAppResource(resource: JsonObject): Boolean {
    val uri = (resource["uri"] ?: resource["uriTemplate"])?.jsonPrimitive?.content.orEmpty()
    val mime = resource["mimeType"]?.jsonPrimitive?.content.orEmpty().lowercase().replace(" ", "")
    return uri.startsWith("ui://", ignoreCase = true) || (mime.substringBefore(';') == "text/html" && mime.split(';').drop(1).any { it == "profile=mcp-app" || it == "profile=\"mcp-app\"" })
}

internal class McpResourceTool(private val read: Boolean, private val servers: List<String>, private val caller: McpCaller) : Tool<JsonObject> {
    override val name = if (read) "ReadMcpResource" else "ListMcpResources"
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Unknown, resultBudgetChars = MCP_RESULT_BUDGET_CHARS)
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("server") { put("type", "string") }
            if (read) putJsonObject("uri") { put("type", "string") }
        }
        if (read) put("required", JsonArray(listOf("server", "uri").map(::JsonPrimitive)))
    }
    override fun description(profile: HarnessProfile) = if (read) "读取 MCP 服务器资源的 URI。" else "列出 MCP 资源与资源模板；server 可选。"
    override fun parse(raw: JsonObject): ToolInput<JsonObject> {
        val server = (raw["server"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (server != null && server !in servers) return ToolInput.Invalid("这个服务器不在会话的资源清单里。")
        if (read && (server == null || (raw["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content.isNullOrBlank())) return ToolInput.Invalid("需要 server 与 uri。")
        return ToolInput.Valid(raw)
    }
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, description(HarnessProfile(ModelFamily.Other, EditStyle.StrReplace)))
    override fun run(input: JsonObject, ctx: ToolContext) = flow {
        val server = input["server"]?.jsonPrimitive?.content
        val result = try {
            if (read) caller.readResourceFor(server!!, input["uri"]!!.jsonPrimitive.content, ctx)
            else ToolResult(listOf(TextPart(JsonArray(caller.listResources(server?.let(::listOf) ?: servers).filterNot(::isMcpAppResource)).toString())))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
        catch (e: Exception) { McpResults.failure(server ?: "资源列表", e) }
        emit(ToolProgress.Done(result))
    }
}

internal suspend fun SessionRuntime.attachMcpResources(parts: List<Part>, id: ClientMsgId): List<Part> {
    val mentions = parts.filterIsInstance<TextPart>().flatMap { text -> RESOURCE_MENTION.findAll(text.text).map { it.groupValues[1] to it.groupValues[2].trimEnd('.', ',', ';', ':', ')', ']', '}') }.filter { it.second.isNotEmpty() }.toList() }.distinct()
    if (mentions.isEmpty()) return parts
    val attached = mutableListOf<Part>()
    for ((index, mention) in mentions.withIndex()) {
        val result = try { mcpCaller.readResource(mention.first, mention.second, deps.host.artifacts(sessionId), "input-${id.value}-$index") }
        catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e }
        catch (e: Exception) { McpResults.failure(mention.first, e) }
        val text = result.content.filterIsInstance<TextPart>().joinToString("\n") { it.text }
        val path = catchingNonCancellation { deps.host.artifacts(sessionId).put("mcp/input-${id.value}-$index.txt", text.toByteArray()) }.getOrElse { "附件保存失败，内容已随消息记录" }
        val contentId = preserve(text.toByteArray())
        attached += TextPart("[MCP 附件 @${mention.first}:${mention.second}，$path${contentId?.let { "，内容 ${it.value}" }.orEmpty()}${if (result.isError) "；读取失败，消息照常发送" else ""}]")
        // 内容与图片直接随 InputReceived 落盘，复开和压缩只重放这份输入，不再读取服务器。
        attached += result.content
    }
    return parts + attached
}

private val RESOURCE_MENTION = Regex("(?<![\\p{L}\\p{N}@])@([A-Za-z0-9_-]+):([^\\s]+)")
