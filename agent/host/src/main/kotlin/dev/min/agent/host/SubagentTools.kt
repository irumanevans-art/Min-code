package dev.min.agent.host

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEffect
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
import kotlinx.serialization.json.booleanOrNull

/** 子 agent 工具只转发端口；持久化、报告、续聊与恢复由 SessionHub 负责。 */
object AgentTool : Tool<JsonObject> {
    override val name = "Agent"
    override val traits = ToolTraits(readOnly = false, concurrencySafe = false)
    override val inputSchema = subagentSchema(
        listOf("description", "prompt"),
        "description" to subagentProperty("string", "用 3–5 个词概括子任务，给用户看"),
        "prompt" to subagentProperty("string", "完整的子任务指令；新开子 agent 看不到父会话历史"),
        "subagent_type" to subagentProperty("string", "默认 general-purpose；Explore 只读探索，Plan 只读规划，fork 继承父会话上下文"),
        "model" to subagentProperty("string", "已配置的模型 id 或 provider/model；省略或 inherit 继承父模型，fork 忽略此项"),
        "run_in_background" to subagentProperty("boolean", "默认 true：立即返回 id，完成时通知；false：等待报告作为工具结果"),
        "name" to subagentProperty("string", "可选名字，后续 SendMessage / TaskStop 可用它定位子 agent"),
    )

    override fun description(profile: HarnessProfile): String {
        val common = "委派独立子任务。general-purpose 做通用任务；Explore 只读查找代码；Plan 只读调研并给出计划；" +
            "fork 继承当前完整上下文和模型，分身必须直接执行，不能再委派。" +
            "默认后台运行，返回子 agent id，完成时收到报告；收到报告之前不要假设结果。" +
            "run_in_background=false 时等待报告。用 SendMessage 续聊，用 TaskStop 中断。"
        return when (profile.family) {
            ModelFamily.Claude, ModelFamily.Gpt -> common +
                "需要已有上下文的长构建、大范围排查或并行试方案时用 fork；需要专门角色或更便宜的模型时新开。" +
                "新开子 agent 看不到父会话历史，prompt 必须给齐任务背景、目标与约束；description 用 3–5 个词概括。"
            else -> common + "例如查找函数定义：subagent_type=Explore，prompt 写清函数名和搜索范围。新开时 prompt 要给齐背景。"
        }
    }

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, "description")
        requireSubagentString(raw, "prompt")
        for (key in listOf("subagent_type", "model", "name")) {
            if (key in raw) requireSubagentString(raw, key)
        }
        raw["run_in_background"]?.let { value ->
            val primitive = value as? JsonPrimitive
            if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                throw InvalidSubagentInput("字段 `run_in_background` 必须是布尔值 true 或 false。")
            }
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "委派子任务：${input.subagentString("description")}")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val request = DelegateRequest(
            description = input.subagentString("description")!!,
            prompt = input.subagentString("prompt")!!,
            agentType = input.subagentString("subagent_type"),
            model = input.subagentString("model"),
            background = (input["run_in_background"] as? JsonPrimitive)?.booleanOrNull ?: true,
            name = input.subagentString("name"),
        )
        val result = when (val delegated = ctx.agents.delegate(request)) {
            is DelegateResult.Finished -> ToolResult(
                listOf(TextPart(delegated.report)), isError = delegated.isError,
                effects = listOf(SessionEffect.ReportDelivered(delegated.child, delegated.childTurn)),
            )
            is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${delegated.child.value}")))
            is DelegateResult.Rejected -> ToolResult(listOf(TextPart(delegated.messageForModel)), isError = true)
        }
        emit(ToolProgress.Done(result))
    }
}

object SendMessageTool : Tool<JsonObject> {
    override val name = "SendMessage"
    override val traits = ToolTraits(readOnly = false, concurrencySafe = true)
    override val inputSchema = subagentSchema(
        listOf("to", "message"),
        "to" to subagentProperty("string", "子 agent 的 id 或名字；子 agent 用 main 向父会话发消息"),
        "message" to subagentProperty("string", "要发送的消息或后续任务指令"),
    )
    override fun description(profile: HarnessProfile) =
        "向子 agent 的 id 或名字发送 message；子 agent 可用 to=main 发给父会话。" +
            "对方正在运行时作为插话送达，已结束时唤醒并开始新回合，完成后返回报告。消息不包含发送者的历史。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, "to")
        requireSubagentString(raw, "message")
    }
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "向 ${input.subagentString("to")} 发消息")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ctx.agents.message(input.subagentString("to")!!, input.subagentString("message")!!)))
    }
}

object TaskStopTool : Tool<JsonObject> {
    override val name = "TaskStop"
    override val traits = ToolTraits(readOnly = false, concurrencySafe = true)
    override val inputSchema = subagentSchema(
        listOf("task_id"),
        "task_id" to subagentProperty("string", "要中断的子 agent id 或名字"),
    )
    override fun description(profile: HarnessProfile) =
        "按 task_id（子 agent id 或名字）中断当前回合；未完成报告仍会送回，可用 SendMessage 续聊。只支持子 agent，暂不支持后台 shell。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, "task_id")
    }
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "停止任务 ${input.subagentString("task_id")}")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ctx.agents.stop(input.subagentString("task_id")!!)))
    }
}

private fun subagentSchema(required: List<String>, vararg properties: Pair<String, JsonObject>) = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(properties.toMap()),
        "required" to JsonArray(required.map(::JsonPrimitive)),
    ),
)

private fun subagentProperty(type: String, description: String) = JsonObject(
    mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)),
)

private fun JsonObject.subagentString(key: String): String? = (this[key] as? JsonPrimitive)?.content

private class InvalidSubagentInput(message: String) : IllegalArgumentException(message)

private fun requireSubagentString(raw: JsonObject, key: String) {
    val value = raw[key] as? JsonPrimitive
    if (value == null || !value.isString || value.content.isBlank()) {
        throw InvalidSubagentInput("字段 `$key` 必须是非空字符串。")
    }
}

private inline fun validateSubagentInput(raw: JsonObject, validate: () -> Unit): ToolInput<JsonObject> = try {
    validate()
    ToolInput.Valid(raw)
} catch (e: InvalidSubagentInput) {
    ToolInput.Invalid(e.message!!)
}
