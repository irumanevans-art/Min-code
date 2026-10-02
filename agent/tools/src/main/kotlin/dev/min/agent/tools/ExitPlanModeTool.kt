package dev.min.agent.tools

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ExitPlanModeInput(val plan: String)

/**
 * 把计划交给人批准并退出计划模式（与 Claude Code 同名）。
 *
 * **总是要人批准**（[ToolTraits.alwaysAsk]，即使 bypass）：人看过计划才能动手，这一步的意义就是那次批准。
 * 审批请求的摘要放计划正文，界面据此渲染计划卡片。走到 [run] 就说明已获批准：
 * 在计划模式里则声明回到进入之前的模式（从没记下过就回 default）；不在计划模式（用户在等批准时自己切走了，
 * 或模型在计划模式外调用）就不动模式——用户的选择优先，也免得把 bypass 之类的模式悄悄改掉。
 * 被拒绝时内核把用户的说明交给模型，会话留在计划模式。
 */
object ExitPlanModeTool : Tool<ExitPlanModeInput> {
    override val name = "ExitPlanMode"

    override val inputSchema: JsonObject = schemaObject(
        listOf("plan"),
        "plan" to prop("string", "要交给用户批准的计划（Markdown）：改哪些文件、每处怎么改、按什么顺序、改完怎么验证"),
    )

    override val traits = ToolTraits(
        readOnly = true,
        concurrencySafe = false,
        resumePolicy = ResumePolicy.Replay,
        alwaysAsk = true,
    )

    override fun description(profile: HarnessProfile) =
        "在计划模式中把写好的计划交给用户批准。plan 用 Markdown 写清楚：改哪些文件、每处怎么改、按什么顺序、改完怎么验证。" +
            "用户批准后退出计划模式、回到原来的模式，就可以开始实施；用户不批准时仍在计划模式，结果里会带上用户的意见，" +
            "按意见修改后再提交。只在计划确实想清楚之后调用；不要用 AskUserQuestion 去问「计划行不行」。"

    override fun parse(raw: JsonObject): ToolInput<ExitPlanModeInput> = parseArgs {
        val plan = raw.reqString("plan")
        if (plan.isBlank()) throw BadArg("字段 `plan` 不能为空：把要交给用户批准的计划写进来（Markdown）。")
        ExitPlanModeInput(plan.trim())
    }

    // 摘要就是计划正文：审批卡片要让人看到完整的计划再决定，而不是一句标题
    override fun permissionAsk(input: ExitPlanModeInput, ctx: ToolContext) = PermissionAsk(tool = name, summary = input.plan)

    override fun run(input: ExitPlanModeInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        if (ctx.mode != PermissionMode.Plan) {
            okResult("计划已获用户批准。当前不在计划模式，权限模式保持 ${wireName(ctx.mode)} 不变，可以开始实施。")
        } else {
            val back = ctx.modeBeforePlan ?: PermissionMode.Default
            ToolResult(
                listOf(TextPart("计划已获用户批准，已退出计划模式（回到 ${wireName(back)} 模式），可以开始实施。")),
                effects = listOf(SessionEffect.ModeChange(back)),
            )
        }
    }

    /** 模式在日志与协议里的名字（与 Claude Code 同名），给模型看的也用它 */
    private fun wireName(mode: PermissionMode): String =
        (AgentJson.encodeToJsonElement(PermissionMode.serializer(), mode) as JsonPrimitive).content
}
