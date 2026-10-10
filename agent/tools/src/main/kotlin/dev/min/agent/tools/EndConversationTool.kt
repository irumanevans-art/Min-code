package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * 结束会话（与 Claude Code 同名，语义照其 tools-reference 的 EndConversation）：只在持续的辱骂性输入，且先前已警告过、引导无效时，
 * 或用户明确要求演示这个工具并确认要结束时使用。情绪、粗话、任务做不好、有害请求都不算。
 *
 * 工具本身只声明 [SessionEffect.ConversationEnd]：停下回合、落 conversation_ended、锁住会话都在内核（TurnRunner / SessionRuntime）。
 * traits：[ToolTraits.exemptFromPolicy]——不问权限、不跑 PreToolUse hook、deny 规则无效（CC 的口径：这个工具除了结束会话什么都不做，
 * 安全闸只有在它自己关不掉时才管用）。只读：它不读写文件，也不改任何数据。
 */
object EndConversationTool : Tool<Unit> {
    override val name = END_CONVERSATION_TOOL_NAME

    override val inputSchema: JsonObject = schemaObject(emptyList())

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false, exemptFromPolicy = true)

    override fun description(profile: HarnessProfile) =
        "结束这个会话。只在两种情况下使用：一是对方持续辱骂，且你已在前面的消息里明确警告过、尝试引导后仍无效；" +
            "二是用户明确要求你演示这个工具，并确认要结束会话。一般的情绪、粗话、任务做得不顺、有害请求都不算（有害请求直接拒绝即可，不必结束会话）。" +
            "结束后会话锁住，新消息不再处理，只能新开会话或用 /clear 继续。"

    // 没有入参；模型多给的字段一律忽略
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)

    // 不问权限（traits.exemptFromPolicy），permissionAsk 不会被调用；给个说明只是为了满足接口
    override fun permissionAsk(input: Unit, ctx: ToolContext) = dev.min.agent.spi.PermissionAsk(tool = name, summary = "结束会话")

    override fun run(input: Unit, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(
            ToolProgress.Done(
                ToolResult(
                    listOf(TextPart("会话即将结束：这一回合停在这里，之后的消息不再处理。")),
                    effects = listOf(SessionEffect.ConversationEnd),
                ),
            ),
        )
    }
}

/** 内核按这个名字识别 EndConversation（子 agent 不给、会话锁住的判断）。与工具的 [EndConversationTool.name] 必须一致 */
const val END_CONVERSATION_TOOL_NAME = "EndConversation"
