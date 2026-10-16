package dev.min.agent.kernel

import dev.min.agent.policy.McpPatterns
import dev.min.agent.policy.PermissionRule
import dev.min.agent.policy.PolicyContext
import dev.min.agent.policy.PolicyEngine
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.McpToolEntry
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/*
 * MCP 工具在内核里的样子（docs/agent/detail/MCP设计.md §5、§6）：一个 MCP 工具就是一个普通 Tool（适配器），
 * 走现有的审批、hooks、结果预算与日志。协议、连接、配置在 :agent:mcp / :agent:ext，经 [McpCaller] 接进来。
 */

/** 一次 MCP 工具调用要的东西。[progress] 收服务器的进度说明（界面的工具卡上显示） */
internal data class McpCallRequest(
    val server: String,
    val tool: String,
    val arguments: JsonObject,
    val sessionId: SessionId,
    val callId: CallId,
    /** 结果里的图片原图、音频、二进制资源落到这里（会话附件） */
    val artifacts: dev.min.agent.spi.ArtifactSink,
    val progress: (String) -> Unit,
    val resourceUri: String? = null,
    /** 这次工具执行 span 的 `traceparent`（[dev.min.agent.spi.ToolContext.traceparent]）；HTTP 传输随请求发出，null = 不传播 */
    val traceparent: String? = null,
)

/**
 * MCP 工具的调用口：McpManager 实现（按服务器找连接、超时与取消、结果转换）。
 * 调用方协程被取消时实现要发 `notifications/cancelled` 并让取消照常传出去；其余失败一律变成错误结果，不抛异常
 */
internal fun interface McpCaller {
    suspend fun call(request: McpCallRequest): ToolResult
    suspend fun listResources(servers: List<String>): List<JsonObject> = emptyList()
    suspend fun listPrompts(): List<dev.min.agent.ext.mcp.McpPromptCommand> = emptyList()
    suspend fun getPrompt(server: String, name: String, arguments: JsonObject): List<dev.min.agent.mcp.McpPromptMessage> = error("服务器不支持 prompts。")
    suspend fun readResource(server: String, uri: String, artifacts: dev.min.agent.spi.ArtifactSink, id: String): ToolResult =
        ToolResult(listOf(TextPart("MCP 服务器 $server 不支持读取资源。")), isError = true)
    suspend fun readResourceFor(server: String, uri: String, ctx: ToolContext): ToolResult = readResource(server, uri, ctx.artifacts, ctx.callId.value)

    companion object {
        val Unavailable = McpCaller { r ->
            ToolResult(listOf(TextPart("MCP 服务器 ${r.server} 现在不可用（这个环境没有接上 MCP）。")), isError = true)
        }
    }
}

/** MCP 工具的结果预算：CC 默认 25,000 token，按每 token 约 3.2 个字符折算 */
internal const val MCP_RESULT_BUDGET_CHARS = 80_000

/** `_meta["anthropic/maxResultSizeChars"]` 的上限（CC 同） */
internal const val MCP_MAX_RESULT_CHARS = 500_000

/**
 * 一个 MCP 工具的适配器（设计 §6）：traits 的 `readOnly` 恒为假（默认问人），`concurrencySafe` 取服务器自称的只读
 * （只影响能不能并行），进程被杀后不重放（结果未知）。`requiresUserInteraction` 的工具任何模式都问人。
 * 入参原样转给服务器：schema 校验是服务器的事，内核只在快照时排除 schema 不合法的工具。
 */
internal class McpToolAdapter(private val entry: McpToolEntry, private val caller: McpCaller) : Tool<JsonObject> {
    override val name: String = entry.name
    override val inputSchema: JsonObject = entry.inputSchema
    override val traits = ToolTraits(
        readOnly = false,
        concurrencySafe = entry.readOnlyHint,
        resumePolicy = ResumePolicy.Unknown,
        resultBudgetChars = entry.maxResultChars?.coerceIn(1, MCP_MAX_RESULT_CHARS) ?: MCP_RESULT_BUDGET_CHARS,
        alwaysAsk = entry.requiresUserInteraction,
    )

    override fun description(profile: HarnessProfile): String = entry.description

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = ToolInput.Valid(raw)

    override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk =
        PermissionAsk(name, "调用 MCP 服务器 ${entry.server} 的工具 ${entry.tool}", mcpServer = McpNames.sanitize(entry.server))

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = channelFlow {
        val result = caller.call(
            McpCallRequest(entry.server, entry.tool, input, ctx.sessionId, ctx.callId, ctx.artifacts, { text -> trySend(ToolProgress.Status(text)) }, traceparent = ctx.traceparent()),
        )
        send(ToolProgress.Done(result))
    }
}

/** 全名分配（设计 §5.1） */
internal typealias McpNames = dev.min.agent.spi.McpNames

/** 快照时对服务器给的工具定义做的整形（设计 §5.2，与 CC 同） */
internal object McpShaping {
    /** 工具说明与服务器说明的截断长度（CC 同，`CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` 的默认值；CC 2.1.296 起从 2048 提到 4096） */
    const val MAX_DESCRIPTION = 4096

    private val PROPERTY_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun truncate(text: String, max: Int = MAX_DESCRIPTION): String = if (text.length <= max) text else text.take(max) + "…"

    sealed interface Shaped {
        /** [descriptionPrefix] 是压平根级组合时要加在工具说明前面的一句话 */
        data class Ok(val schema: JsonObject, val descriptionPrefix: String?) : Shaped

        data class Excluded(val reason: String) : Shaped
    }

    /**
     * 根级 `anyOf` / `oneOf` / `allOf` 压平成一个 object：allOf 合并属性并保留各分支的 required；
     * anyOf / oneOf 合并属性，各分支的 required 只写进说明、不强制（强制了会把合法的组合拒掉）。
     * 顶层属性名必须是 1–64 个 `[A-Za-z0-9_.-]`，否则排除这个工具（各家 API 对属性名都有这条限制）。
     * 完整的 JSON Schema 元模式校验没做：坏 schema 由供应商的 400 兜底（那时会给出是哪个工具）
     */
    fun shape(raw: JsonObject): Shaped {
        val type = (raw["type"] as? JsonPrimitive)?.content
        if (type != null && type != "object") return Shaped.Excluded("inputSchema 的根类型是 $type，不是 object")
        var prefix: String? = null
        val flattened = when {
            raw["allOf"] is JsonArray -> flatten(raw, "allOf", requiredEnforced = true)
            raw["anyOf"] is JsonArray -> flatten(raw, "anyOf", requiredEnforced = false).also { prefix = it.second }
            raw["oneOf"] is JsonArray -> flatten(raw, "oneOf", requiredEnforced = false).also { prefix = it.second }
            else -> raw to null
        }.first
        val withType = if (flattened["type"] == null) JsonObject(flattened + ("type" to JsonPrimitive("object"))) else flattened
        val properties = withType["properties"] as? JsonObject
        properties?.keys?.firstOrNull { !PROPERTY_NAME.matches(it) }?.let { bad ->
            return Shaped.Excluded("参数名「$bad」不合规（只能是 1–64 个字母、数字或 _ . -）")
        }
        return Shaped.Ok(withType, prefix)
    }

    private fun flatten(raw: JsonObject, key: String, requiredEnforced: Boolean): Pair<JsonObject, String?> {
        val branches = (raw[key] as JsonArray).mapNotNull { it as? JsonObject }
        val properties = LinkedHashMap<String, JsonElement>()
        (raw["properties"] as? JsonObject)?.forEach { (k, v) -> properties[k] = v }
        branches.forEach { b -> (b["properties"] as? JsonObject)?.forEach { (k, v) -> properties.putIfAbsent(k, v) } }
        val baseRequired = requiredOf(raw)
        val branchRequired = branches.map(::requiredOf)
        val required = if (requiredEnforced) (baseRequired + branchRequired.flatten()).distinct() else baseRequired
        val out = LinkedHashMap<String, JsonElement>(raw)
        out.remove(key)
        out["type"] = JsonPrimitive("object")
        out["properties"] = JsonObject(properties)
        if (required.isEmpty()) out.remove("required") else out["required"] = JsonArray(required.map(::JsonPrimitive))
        val note = if (requiredEnforced) null else {
            val combos = branchRequired.filter { it.isNotEmpty() }.joinToString("；") { it.joinToString("、") }
            if (combos.isEmpty()) null else "参数有几种组合，每次按其中一种给全：$combos。"
        }
        return JsonObject(out) to note
    }

    private fun requiredOf(o: JsonObject): List<String> =
        (o["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
}

/**
 * 子 agent 定义里的 MCP 写法：`mcp__*` 全部，`mcp__服务器` 与 `mcp__服务器__*` 只管规范化名整个等于它的那台服务器，否则精确
 * （与权限规则同一套，见 [McpPatterns]）。[server] 是原始服务器名；[grant] 为真是 `tools:`（放行），为假是 `disallowedTools:`
 */
internal fun matchesMcpPattern(pattern: String, toolName: String, server: String, grant: Boolean): Boolean =
    McpPatterns.matches(pattern, toolName, McpNames.sanitize(server), grant)

/**
 * 这个会话的模型看得到的 MCP 工具：最后一份快照里的，去掉子 agent 定义不给的、整工具 deny 规则禁掉的（设计 §6）。
 * 纯函数（只看状态与依赖）：同一段日志组出同一张工具表
 */
internal fun SessionState.visibleMcpTools(deps: KernelDeps): List<McpToolEntry> {
    val all = mcpTools?.tools.orEmpty()
    if (all.isEmpty()) return emptyList()
    val o = origin
    val policy = PolicyContext(
        mode = settings.mode,
        rules = (rules + sessionRules).mapNotNull(PermissionRule::from),
        cwd = settings.cwd,
        home = deps.memoryHome ?: GuestPath("/root"),
    )
    return all.filter { t ->
        val byAgent = o == null || (o.mcpDenied.none { matchesMcpPattern(it, t.name, t.server, grant = false) } &&
            (o.mcpTools?.any { matchesMcpPattern(it, t.name, t.server, grant = true) } ?: true))
        byAgent && !PolicyEngine.deniesWholeTool(t.name, policy, McpNames.sanitize(t.server))
    }
}

internal fun SessionState.visibleMcpResourceServers(deps: KernelDeps): List<String> {
    val policy = PolicyContext(mode=settings.mode, rules=(rules + sessionRules).mapNotNull(PermissionRule::from), cwd=settings.cwd, home=deps.memoryHome ?: GuestPath("/root"))
    return mcpTools?.resourceServers.orEmpty().filter { server ->
        val name = "mcp__${McpNames.sanitize(server)}"
        val agent = origin
        (agent == null || (agent.mcpDenied.none { matchesMcpPattern(it, name, server, grant = false) } && (agent.mcpTools?.any { matchesMcpPattern(it, name, server, grant = true) } ?: true))) &&
            !PolicyEngine.deniesWholeTool(name, policy, McpNames.sanitize(server))
    }
}

/** 系统提示里「MCP 服务器说明」那一段的原料：只列模型看得到工具的服务器，按服务器名排序 */
internal fun SessionState.mcpInstructionsFor(visible: List<McpToolEntry>, search: Boolean = mcpToolSearch?.enabled == true): Map<String, String> {
    val servers = visible.map { it.server }.toSet()
    val instructions = mcpTools?.instructions.orEmpty().filterKeys { it in servers }.toSortedMap()
    if (!search) return instructions
    return servers.sorted().associateWith { server ->
        listOfNotNull(instructions[server], "MCP 工具名称（通过 ToolSearch 查询并加载）：" + visible.filter { it.server == server }.joinToString(", ") { it.name }).joinToString("\n")
    }
}
