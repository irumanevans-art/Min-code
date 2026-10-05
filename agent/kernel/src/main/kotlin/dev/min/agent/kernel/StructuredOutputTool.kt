package dev.min.agent.kernel

import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.protocol.TextPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * `--json-schema` 的会话方案。运行时选项（同 `--max-turns`）：属于一次 CLI 运行而不是会话，
 * 不进日志；schema 决定 StructuredOutput 工具的入参形状，因此会进**请求**（工具表），但那是请求本来就有的东西
 */
class StructuredOutputPlan(val schema: JsonObject)

/**
 * 交最终答案的工具（`--json-schema`，工具式结构化输出）：入参 schema 就是用户给的 schema，
 * 校验（[JsonSchema]）发生在 parse——不合格的调用直接得到带路径与原因的错误结果，模型重交。
 * 执行没有副作用；内核在结果落盘的同一次提交里写 [dev.min.agent.spi.StructuredOutputDelivered]，
 * 回合随即收尾（TurnRunner 看到已交卷就不再请求模型）。各家函数调用对结构的约束比
 * 「只输出 JSON」的文字指令可靠，这是选工具式的原因（任务单已拍板）
 */
internal class StructuredOutputTool(private val plan: StructuredOutputPlan) : Tool<JsonObject> {
    override val name = NAME
    override val inputSchema = plan.schema
    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)
    override fun description(profile: HarnessProfile) =
        "交出本次任务的最终答案。参数必须严格符合给定的 JSON Schema；这是收尾动作，整个任务只调用一次。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> {
        val errors = JsonSchema.validate(plan.schema, raw)
        return if (errors.isEmpty()) {
            ToolInput.Valid(raw)
        } else {
            ToolInput.Invalid("结构化输出没有通过校验（${errors.size} 处）：" + errors.joinToString("；") + "。请修正后重新调用 $NAME。")
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(NAME, "交出结构化答案")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ToolResult(listOf(TextPart("已收到结构化答案，任务结束。")))))
    }

    companion object {
        const val NAME = "StructuredOutput"
    }
}
