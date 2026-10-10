package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.PermissionAsk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** 入参：[title] 可空（宿主用应用名兜底），[message] 必填且不能只有空白 */
data class PushNotificationInput(val title: String?, val message: String)

/**
 * 发一条系统通知（与 Claude Code 同名，语义照其 PushNotification）：长任务跑完、定时任务有结果、需要人回来看一眼时用。
 * 不要拿来汇报普通进度或闲聊。宿主没有能发通知的通道（没有权限、无界面）时，结果是错误，模型不能以为用户已经收到。
 *
 * 工具只转发给 [ToolContext.push]：手机上是系统通知（复用告警通道），桌面 CLI 打印一行。traits：只读、不需审批（CC 的口径）。
 */
object PushNotificationTool : Tool<PushNotificationInput> {
    override val name = "PushNotification"

    override val inputSchema = schemaObject(
        listOf("message"),
        "message" to prop("string", "通知正文，一两句话说清发生了什么、需不需要用户回来看"),
        "title" to prop("string", "可选的通知标题；不写用应用名"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false)

    override fun description(profile: HarnessProfile) =
        "发一条系统通知，让用户不在屏幕前也能知道：长任务跑完、定时任务出了结果、或者需要用户回来做决定。" +
            "只用于值得打断用户的事，不要用来汇报普通进度。发不出去时（没有通知权限等）会返回错误。"

    override fun parse(raw: JsonObject): ToolInput<PushNotificationInput> = parseArgs {
        val message = raw.reqString("message")
        if (message.isBlank()) throw BadArg("字段 `message` 不能为空白。")
        PushNotificationInput(title = raw.optString("title")?.takeIf { it.isNotBlank() }, message = message)
    }

    override fun permissionAsk(input: PushNotificationInput, ctx: ToolContext) = PermissionAsk(name, "发一条系统通知")

    override fun run(input: PushNotificationInput, ctx: ToolContext): Flow<ToolProgress> = flow {
        val sent = ctx.push.push(input.title, input.message)
        emit(
            ToolProgress.Done(
                if (sent) ToolResult(listOf(TextPart("已发出通知。")))
                else ToolResult(listOf(TextPart("这个环境没有发出通知（没有可用的通知通道或权限）。不要以为用户已经收到。")), isError = true),
            ),
        )
    }
}
