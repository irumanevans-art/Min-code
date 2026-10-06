package dev.min.agent.kernel

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.*
import dev.min.agent.policy.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import kotlin.math.ceil

internal fun selectMcpSearchMode(snapshot: McpToolsSnapshot, window: Int, setting: String?, denied: Boolean): McpToolSearchConfigured {
    val tokens = ceil(snapshot.tools.sumOf { it.name.length.toLong() + it.description.length + it.inputSchema.toString().length } / 3.2).toInt()
    val value = setting?.trim()?.lowercase()
    val threshold = value?.removePrefix("auto:")?.takeIf { value.startsWith("auto:") }?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: 10.0
    val strategy = when (value) { "true", "false" -> value; else -> "auto:$threshold" }
    val enabled = !denied && when (value) {
        "true" -> true
        "false" -> false
        else -> tokens > window * threshold / 100.0
    }
    return McpToolSearchConfigured(enabled, tokens, window, threshold, strategy)
}

internal fun SessionState.deniesToolSearch(deps: KernelDeps): Boolean = PolicyEngine.deniesWholeTool("ToolSearch", PolicyContext(
    mode=settings.mode, rules=(rules + sessionRules).mapNotNull(PermissionRule::from), cwd=settings.cwd, home=deps.memoryHome ?: GuestPath("/root"),
))

internal fun SessionState.usesMcpToolSearch(deps: KernelDeps) = mcpToolSearch?.enabled == true && !deniesToolSearch(deps)

internal fun searchMcpTools(tools: List<McpToolEntry>, query: String): List<McpToolEntry> {
    if (query.startsWith("select:")) {
        val names = query.removePrefix("select:").split(',', ' ').filter { it.isNotBlank() }
        return names.mapNotNull { name -> tools.firstOrNull { it.name == name } }.distinctBy { it.name }.take(5)
    }
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
    return tools.mapIndexed { index, tool ->
        val name = tool.name.lowercase(); val raw = tool.tool.lowercase(); val server = tool.server.lowercase(); val description = tool.description.lowercase()
        val score = words.sumOf { word ->
            (if (name == word || raw == word) 60 else if (name.startsWith(word) || raw.startsWith(word)) 25 else if (word in name) 15 else 0) +
                (if (server == word) 20 else if (word in server) 10 else 0) + (if (word in description) 3 else 0)
        }
        Triple(tool, score, index)
    }.filter { it.second > 0 }.sortedWith(compareByDescending<Triple<McpToolEntry, Int, Int>> { it.second }.thenBy { it.third }).take(5).map { it.first }
}

internal class McpToolSearchTool(private val tools: List<McpToolEntry>, private val loaded: List<String>) : Tool<String> {
    override val name = "ToolSearch"
    override val traits = ToolTraits(readOnly=true, concurrencySafe=false, resumePolicy=ResumePolicy.Replay)
    override val inputSchema = buildJsonObject {
        put("type", "object"); putJsonObject("properties") { putJsonObject("query") { put("type", "string") } }
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
    }
    override fun description(profile: HarnessProfile) = "搜索 MCP 工具名、服务器名与描述，最多返回五个工具并加载到下一次请求。query 可用 select:完整工具名 精确选择。"
    override fun parse(raw: JsonObject): ToolInput<String> = (raw["query"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { ToolInput.Valid(it.content) } ?: ToolInput.Invalid("query 必须是非空文本。")
    override fun permissionAsk(input: String, ctx: ToolContext) = PermissionAsk(name, "搜索 MCP 工具：$input")
    override fun run(input: String, ctx: ToolContext) = flow {
        val selected = searchMcpTools(tools, input)
        val names = selected.filter { !it.alwaysLoad && it.name !in loaded }.map { it.name }
        val text = JsonArray(selected.map { buildJsonObject { put("name", it.name); put("description", it.description) } }).toString()
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(text)), effects = if (names.isEmpty()) emptyList() else listOf(SessionEffect.McpLoadTools(names)))))
    }
}
