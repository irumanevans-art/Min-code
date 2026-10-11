package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.callMcpToolByName
import dev.min.agent.kernel.mcpResultText
import dev.min.agent.kernel.mcpToolEntries
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.TextPart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

/**
 * `--permission-prompt-tool mcp__服务器__工具`（CLI reference：「Specify an MCP tool to handle permission prompts in
 * non-interactive mode」）：要审批的调用（根会话与子 agent，changelog：后台子 agent 的请求也走它）和 AskUserQuestion
 * 经这个 MCP 工具问。入参 `{tool_name, input, tool_use_id}`（形状取自 SDK 的 CanUseTool 回调：工具名、入参、调用 id），
 * 结果是文本内容块里的 PermissionResult JSON。
 *
 * 原文规定的几件事：
 * - 工具不在已连接的 MCP 工具里：`Error: MCP tool <名字> (passed via --permission-prompt-tool) not found. Available MCP tools: …`，
 *   非交互运行以这个错误、退出码 1 收场（errors 页）——这里是 [fatal]，并中断当前回合；这次调用按拒绝处理；
 * - 标了 `requiresUserInteraction` 的 MCP 工具，提示工具回 allow 也转成拒绝（文案 `MCP tool requires user interaction;
 *   not supported via --permission-prompt-tool`，MCP 页）；
 * - `--permission-prompts none` 时不问它（由调用方不装配本类）。
 *
 * 结论解析是安全判定（[decideMcpResult]）：工具报错、结果不是 JSON 对象、没有 behavior、behavior 不认识、`updatedInput`
 * 改写了入参、调用本身抛异常，一律拒绝。
 */
internal class McpApprovalHost(
    private val tool: String,
    private val stderr: PrintStream,
    /** 此刻已连接的 MCP 工具（全名）；测试里换成假的 */
    private val listTools: suspend () -> List<dev.min.agent.spi.McpToolEntry>,
    /** 按全名调用一个 MCP 工具；null = 调不了 */
    private val call: suspend (String, JsonObject) -> dev.min.agent.spi.ToolResult?,
    /** 中断当前回合（找不到提示工具时） */
    private val interrupt: () -> Unit,
) : ApprovalHost {
    companion object {
        fun forRuntime(runtime: SessionRuntime, tool: String, stderr: PrintStream) =
            McpApprovalHost(tool, stderr, { runtime.mcpToolEntries() }, { name, args -> runtime.callMcpToolByName(name, args) }, { runtime.interrupt() })
    }

    @Volatile override var fatal: String? = null
        private set

    override suspend fun ask(ask: StdioControl.HostAsk, rewritable: Boolean): StdioControl.AskDecision {
        val entries = listTools()
        if (entries.none { it.name == tool }) {
            val message = "Error: MCP tool $tool (passed via --permission-prompt-tool) not found. Available MCP tools: " +
                entries.joinToString(", ") { it.name }.ifEmpty { "none" }
            if (fatal == null) {
                fatal = message
                stderr.println(message)
                // 原文：第一次要权限决定时就以这个错误收场——不让回合带着没人审批的状态继续跑
                interrupt()
            }
            return StdioControl.AskDecision(StdioControl.denial("找不到权限提示工具 $tool，按拒绝处理"))
        }
        val arguments = buildJsonObject {
            put("tool_name", ask.tool)
            put("input", ask.input)
            put("tool_use_id", ask.callId.value)
        }
        val result = try {
            call(tool, arguments)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return StdioControl.AskDecision(StdioControl.denial("权限提示工具调用失败（${e::class.simpleName}），按拒绝处理"))
        } ?: return StdioControl.AskDecision(StdioControl.denial("权限提示工具现在不可用，按拒绝处理"))
        val text = mcpResultText(result)
        val decision = decideMcpResult(result.isError, text, ask.input, rewritable)
        // 标了 requiresUserInteraction 的工具必须有人当面点头：提示工具（程序）的 allow 不算（MCP 页原文）
        if (decision.choice == dev.min.agent.spi.ApprovalChoice.Once && entries.firstOrNull { it.name == ask.tool }?.requiresUserInteraction == true) {
            return StdioControl.AskDecision(StdioControl.denial("MCP tool requires user interaction; not supported via --permission-prompt-tool"))
        }
        return decision
    }
}

/**
 * MCP 权限提示工具的结果 → 答复（纯函数）：[isError] 的结果一律拒绝（文本里写什么都不看）；文本必须是 JSON 对象；
 * 之后与 stdio 宿主走同一处 [StdioControl.decideBody]
 */
internal fun decideMcpResult(isError: Boolean, text: String, original: JsonObject, rewritable: Boolean = false): StdioControl.AskDecision {
    if (isError) return StdioControl.AskDecision(StdioControl.denial("权限提示工具返回了错误，按拒绝处理：${text.take(200)}"))
    val parsed = try {
        AgentJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return StdioControl.AskDecision(StdioControl.denial("权限提示工具的结果不是 JSON，按拒绝处理"))
    } catch (e: IllegalArgumentException) {
        return StdioControl.AskDecision(StdioControl.denial("权限提示工具的结果不是 JSON，按拒绝处理"))
    }
    val body = parsed as? JsonObject
        ?: return StdioControl.AskDecision(StdioControl.denial("权限提示工具的结果不是 JSON 对象，按拒绝处理"))
    return StdioControl.decideBody(body, original, rewritable)
}
