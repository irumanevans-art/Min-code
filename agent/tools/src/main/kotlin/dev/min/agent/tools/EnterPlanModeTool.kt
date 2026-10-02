package dev.min.agent.tools

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

/**
 * 进入计划模式（与 Claude Code 同名）：动手之前先只读地摸清情况、想好方案，再用 [ExitPlanModeTool] 交给人批准。
 *
 * 工具不改会话状态，只在结果里声明 [SessionEffect.ModeChange]，由内核与结果同一次落盘；
 * 计划模式下的限制（非只读工具一律拒绝）由现有权限引擎执行，这里不重复。
 * traits：只读、不需审批（进计划模式只会让 agent 更受限）、恢复策略 Replay（没有外部副作用，没落盘就等于没发生）。
 */
object EnterPlanModeTool : Tool<Unit> {
    override val name = "EnterPlanMode"

    const val ENTERED_MESSAGE =
        "已进入计划模式：现在只能读文件、搜索和用 AskUserQuestion 向用户提问，写文件、执行命令都会被拒绝。" +
            "把情况摸清、想清楚方案后，用 ExitPlanMode 把计划交给用户批准；批准后才能开始改动。"

    const val ALREADY_MESSAGE = "已经在计划模式中。想清楚方案后，用 ExitPlanMode 把计划交给用户批准。"

    override val inputSchema: JsonObject = schemaObject(emptyList())

    override val traits = ToolTraits(readOnly = true, concurrencySafe = false, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "进入计划模式：在动手做复杂或有歧义的改动之前，先只读地摸清情况、想好方案，交给用户批准后再实施。" +
            "适合要改多个文件、有几种做法需要取舍、或需求还不清楚的任务；简单直接的小改动和纯问答不要用。" +
            "计划模式下只能读文件、搜索和用 AskUserQuestion 提问，任何改动都会被拒绝；方案想好后用 ExitPlanMode 提交计划。"

    // 没有入参；模型多给的字段一律忽略，不为它多要一个来回
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)

    override fun permissionAsk(input: Unit, ctx: ToolContext) = PermissionAsk(tool = name, summary = "进入计划模式（先只读地出计划）")

    override fun run(input: Unit, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        // 已在计划模式时不再声明切换：重复切进 Plan 虽然无害，但日志里多一条没有意义的设置变更
        if (ctx.mode == PermissionMode.Plan) {
            okResult(ALREADY_MESSAGE)
        } else {
            ToolResult(listOf(TextPart(ENTERED_MESSAGE)), effects = listOf(SessionEffect.ModeChange(PermissionMode.Plan)))
        }
    }
}
