package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 入参：要等的服务器（空 = 本会话全部还在连的），等多久（毫秒，已换算） */
data class WaitForMcpInput(val servers: List<String>, val timeoutMs: Long)

/**
 * 等还在后台连接的 MCP 服务器（与 Claude Code 同名，语义照其 tools-reference 的 WaitForMcpServers）：
 * 服务器启动慢，第一回合的工具表里还没有它的工具时，模型等它连上再继续，不必重启会话。
 * 只在工具搜索（ToolSearch）关闭时出现：开着时 ToolSearch 自己负责等。等待有上限，超时仍没连上的照实报。
 *
 * 只读：等的是连接状态，不改任何东西。
 */
object WaitForMcpServersTool : Tool<WaitForMcpInput> {
    override val name = "WaitForMcpServers"

    /** 单次最长等多少秒 */
    const val MAX_TIMEOUT_SECONDS = 120L

    /** 不写 timeout 时等多少秒 */
    const val DEFAULT_TIMEOUT_SECONDS = 30L

    override val inputSchema = schemaObject(
        emptyList(),
        "servers" to JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive("要等的 MCP 服务器名字；不写就等本会话全部还在连的"),
                "items" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            ),
        ),
        "timeout" to prop("integer", "最多等几秒，1 到 $MAX_TIMEOUT_SECONDS，默认 $DEFAULT_TIMEOUT_SECONDS"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false)

    override fun description(profile: HarnessProfile) =
        "等还在后台连接的 MCP 服务器连上（或失败），然后它们的工具就能用了。需要的服务器还没连上时用这个，不要反复重试工具。" +
            "只在工具搜索（ToolSearch）关闭时出现，工具搜索开着时由 ToolSearch 负责等。最多等 $MAX_TIMEOUT_SECONDS 秒，超时仍没连上的会如实告诉你。"

    override fun parse(raw: JsonObject): ToolInput<WaitForMcpInput> = parseArgs {
        val servers = when (val e = raw["servers"]) {
            null, is kotlinx.serialization.json.JsonNull -> emptyList()
            is JsonArray -> e.mapIndexed { i, s ->
                (s as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: throw BadArg("字段 `servers[$i]` 必须是非空的服务器名字。")
            }
            else -> throw BadArg("字段 `servers` 必须是服务器名字的数组。")
        }
        val seconds = raw.optLong("timeout", 1) ?: DEFAULT_TIMEOUT_SECONDS
        if (seconds > MAX_TIMEOUT_SECONDS) throw BadArg("字段 `timeout` 最多 $MAX_TIMEOUT_SECONDS 秒，收到 $seconds。")
        WaitForMcpInput(servers, seconds * 1000)
    }

    override fun permissionAsk(input: WaitForMcpInput, ctx: ToolContext) = PermissionAsk(name, "等待 MCP 服务器连接")

    override fun run(input: WaitForMcpInput, ctx: ToolContext): Flow<ToolProgress> = flow {
        val configured = ctx.mcpWait.states()
        if (configured.isEmpty()) {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("本会话没有 MCP 服务器，没有可以等的。")), isError = true)))
            return@flow
        }
        val unknown = input.servers.filter { it !in configured }
        val after = ctx.mcpWait.await(input.servers, input.timeoutMs)
        val shown = if (input.servers.isEmpty()) after else after.filterKeys { it in input.servers }
        val lines = shown.map { (name, state) -> "- $name：${LABELS[state] ?: state}" } +
            unknown.map { "- $it：本会话没有这台服务器。" }
        val text = when {
            lines.isEmpty() -> "没有需要等的 MCP 服务器。"
            shown.values.all { it == "connected" } && unknown.isEmpty() -> "MCP 服务器都已连上：\n" + lines.joinToString("\n")
            else -> "等待结束，MCP 服务器状态：\n" + lines.joinToString("\n")
        }
        emit(ToolProgress.Done(ToolResult(listOf(TextPart(text)), isError = unknown.isNotEmpty())))
    }

    private val LABELS = mapOf(
        "connected" to "已连上",
        "pending" to "仍在连接（超时，未等到）",
        "failed" to "连接失败",
        "needs-auth" to "需要登录",
        "disabled" to "已关闭",
    )
}
