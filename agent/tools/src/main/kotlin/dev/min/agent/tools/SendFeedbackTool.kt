package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.FeedbackDraft
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

/**
 * 起草一份关于 Claude Code / Min 的反馈（产品问题，或本会话里模型自己的行为问题），放进本机的草稿队列。
 *
 * 与 Claude Code 的 SendFeedback 同名同义，但发送这一步换成了 Min 的口径（05 对标清单已定）：CC 把草稿排在本机、
 * 由用户决定发不发；Min 没有发送通道，草稿只是在 App 里出现一张「诊断包草稿」卡片，用户可以查看、编辑、
 * 导出成 zip（自己拿去发），或丢弃。这个工具**什么都不发**，也没有任何发送方法，结果里照实写「未发送」。
 */
object SendFeedbackTool : Tool<FeedbackDraft> {
    override val name = "SendFeedback"

    /** 标题与正文的上限：草稿是给人看的，过长的正文说明模型在倒原始日志，应当摘要 */
    const val MAX_TITLE = 120
    const val MAX_BODY = 8000

    override val inputSchema = schemaObject(
        listOf("title", "body"),
        "title" to prop("string", "反馈的一句话标题，最多 $MAX_TITLE 字"),
        "body" to prop("string", "反馈正文：发生了什么、在哪个工具或命令上、涉及的事实（报错原文、步骤），最多 $MAX_BODY 字；不要贴密钥、令牌或整段日志"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false)

    override fun description(profile: HarnessProfile) =
        "起草一份关于产品的反馈草稿，放进本机草稿队列，由用户决定要不要导出。适用于：某个工具或命令反复失败、你无法完成用户要的事、" +
            "用户指出你犯的错、或你自己注意到的错误。这个工具**不会发出任何东西**，结果会写明「未发送」；" +
            "不要对用户说反馈已经发出，只能说草稿已放进队列。正文里不要放密钥、令牌或整段日志。"

    override fun parse(raw: JsonObject): ToolInput<FeedbackDraft> = parseArgs {
        val title = raw.optString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadArg("字段 `title` 必须是非空字符串。")
        val body = raw.optString("body")?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadArg("字段 `body` 必须是非空字符串。")
        if (title.length > MAX_TITLE) throw BadArg("字段 `title` 最多 $MAX_TITLE 字，收到 ${title.length} 字。")
        if (body.length > MAX_BODY) throw BadArg("字段 `body` 最多 $MAX_BODY 字，收到 ${body.length} 字。")
        FeedbackDraft(title, body)
    }

    override fun permissionAsk(input: FeedbackDraft, ctx: ToolContext) =
        PermissionAsk(name, "起草一份反馈草稿：${input.title}（不会发出）")

    override fun run(input: FeedbackDraft, ctx: ToolContext): Flow<ToolProgress> = flow {
        val queued = ctx.feedback.queue(input)
        val result = if (queued) {
            ToolResult(listOf(TextPart("已放进本机草稿队列（未发送）：${input.title}。草稿只在用户那边，由用户决定导出或丢弃。")))
        } else {
            ToolResult(listOf(TextPart("这个环境没有草稿队列，反馈草稿没有保存。")), isError = true)
        }
        emit(ToolProgress.Done(result))
    }
}
