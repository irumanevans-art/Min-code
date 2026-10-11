package dev.min.agent.tools

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

/*
 * 子 agent 工具只转发端口；持久化、报告、续聊与恢复由内核的 SessionHub 负责（docs/agent/detail/子agent设计.md §8）。
 *
 * 三个工具都标只读、可并发（设计 §7）：它们不碰工作区——委派出去的子 agent 的每个工具调用各自过子会话的权限判定。
 * 标成会写的话，默认模式下每次委派都要弹审批、计划模式下直接被拒，Explore / Plan 这类只读委派就用不了了。
 * 同一条消息里的几次委派因此并行跑（多个前台子 agent 同时干活，与 Claude Code 一致）。
 */
private val SUBAGENT_TRAITS = ToolTraits(readOnly = true, concurrencySafe = true)

/** Agent 与 SendMessage 会让子 agent 干活：检查点的工具闸要等它们（[ToolTraits.delegates]） */
private val DELEGATING_TRAITS = SUBAGENT_TRAITS.copy(delegates = true)

/** 一种可委派的子 agent 类型（给模型看的名字与一句说明） */
data class AgentType(val name: String, val description: String)

/** 只有内置类型的 Agent 工具（单测、没有自定义子 agent 的宿主） */
val AgentTool: Tool<JsonObject> = AgentToolOf(emptyList())

/**
 * Agent 工具。[customTypes] 是 `.claude/agents/` 里的自定义类型，列进说明里——模型不知道有哪些类型就不会用。
 * 由组合根在启动时按配置建一次：工具说明在进程内不变，属于稳定前缀（改了 `.claude/agents/` 要重启才生效，与记忆同理）。
 */
class AgentToolOf(customTypes: List<AgentType>) : Tool<JsonObject> {
    private val customSection: String = customTypesSection(customTypes)

    override val name = "Agent"
    override val traits = DELEGATING_TRAITS
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
        } + customSection
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

    // 类型进 specifiers：用户写 `Agent(Explore)` 的 deny / allow 规则才对得上（与 Claude Code 一致）
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(
        name,
        "委派子任务：${input.subagentString("description")}",
        specifiers = listOf(input.subagentString("subagent_type") ?: "general-purpose"),
    )

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
            is DelegateResult.Started -> ToolResult(listOf(TextPart(started(delegated))))
            is DelegateResult.Rejected -> ToolResult(listOf(TextPart(delegated.messageForModel)), isError = true)
        }
        emit(ToolProgress.Done(result))
    }
}

/** 后台委派的回执：模型最容易犯的错是接着往下写、假装已经知道结果，所以明说「等报告」 */
private fun started(r: DelegateResult.Started): String {
    val who = r.name?.let { "「$it」（${r.child.value}）" } ?: r.child.value
    val kind = if (r.fork) "分身" else "子 agent"
    return "已在后台启动$kind $who。它完成时你会收到报告；在那之前不要假设它的结果，可以先做不依赖它的事。"
}

object SendMessageTool : Tool<JsonObject> {
    override val name = "SendMessage"
    override val traits = DELEGATING_TRAITS
    override val inputSchema = subagentSchema(
        listOf("to", "message"),
        "to" to subagentProperty("string", "子 agent 的 id 或名字；子 agent 用 main 向父会话发消息；也可以是本机其他会话的名字或 id"),
        "message" to subagentProperty("string", "要发送的消息或后续任务指令"),
    )
    override fun description(profile: HarnessProfile) =
        "向子 agent 的 id 或名字发送 message；子 agent 可用 to=main 发给父会话。" +
            "对方正在运行时作为插话送达，已结束时唤醒并开始新回合，完成后返回报告。消息不包含发送者的历史。" +
            "在 agent 团队里，to 可以是队友的名字、lead（负责人）或 all（广播给全体）；团队邮件也是这条路。" +
            "to 也可以是本机其他 Claude 会话的名字或 id（ListAgents 能列出）：消息以「来自会话 X」的形式进对方对话，不是用户的话；" +
            "对方的用户未批准、或对方设为拒收时不会送达，你会收到结论。名字重复时用 id。" +
            "不要请对方去做你自己这边被拒绝、或权限不允许的操作：那要交回给你的用户。"

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
    override val traits = SUBAGENT_TRAITS
    override val inputSchema = subagentSchema(
        listOf("task_id"),
        "task_id" to subagentProperty("string", "要停止的任务 id：后台命令、watch，或子 agent / 队友 / 有名字的后台 agent 的 id 或名字"),
        "shell_id" to subagentProperty("string", "已弃用，等同 task_id"),
    )
    override fun description(profile: HarnessProfile) =
        "按 task_id 停止一个在跑的后台任务：转后台的 Bash 命令、Monitor 的 watch（杀整棵进程树）；" +
            "也可以是子 agent、agent 团队的队友或有名字的后台 agent（id 或名字），此时是中断它的当前回合，未完成报告仍会送回，可用 SendMessage 续聊。" +
            "停一个子 agent 时它起的 watch 一并停掉。找不到 id 时，错误里列出正在跑的后台任务和 agent（id + 描述）。"

    /** `shell_id` 是 CC 里的旧字段名：只给了它就当 task_id 用 */
    private fun JsonObject.targetId(): String? = subagentString("task_id") ?: subagentString("shell_id")

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, if ("task_id" in raw || "shell_id" !in raw) "task_id" else "shell_id")
    }
    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "停止任务 ${input.targetId()}")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ctx.agents.stop(input.targetId()!!)))
    }
}

/**
 * 自定义类型的清单。每条说明截到 [MAX_TYPE_DESCRIPTION] 字、总共最多 [MAX_CUSTOM_TYPES] 种：
 * 这段每次请求都带，用户的 agents 目录再大也不能把工具说明撑爆（CC 在说明合计超 15,000 token 时也会警告）。
 */
private fun customTypesSection(types: List<AgentType>): String {
    if (types.isEmpty()) return ""
    val shown = types.take(MAX_CUSTOM_TYPES)
    return buildString {
        append("\n\n这个项目还定义了这些子 agent 类型（subagent_type 写名字）：")
        for (t in shown) {
            val d = t.description.replace('\n', ' ').trim()
            append("\n- ").append(t.name).append("：").append(if (d.length > MAX_TYPE_DESCRIPTION) d.take(MAX_TYPE_DESCRIPTION) + "…" else d)
        }
        if (types.size > shown.size) append("\n（另有 ${types.size - shown.size} 种未列出，写名字一样能用）")
    }
}

private const val MAX_CUSTOM_TYPES = 30
private const val MAX_TYPE_DESCRIPTION = 200

internal fun subagentSchema(required: List<String>, vararg properties: Pair<String, JsonObject>) = JsonObject(
    mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(properties.toMap()),
        "required" to JsonArray(required.map(::JsonPrimitive)),
    ),
)

internal fun subagentProperty(type: String, description: String) = JsonObject(
    mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)),
)

internal fun JsonObject.subagentString(key: String): String? = (this[key] as? JsonPrimitive)?.content

internal class InvalidSubagentInput(message: String) : IllegalArgumentException(message)

internal fun requireSubagentString(raw: JsonObject, key: String) {
    val value = raw[key] as? JsonPrimitive
    if (value == null || !value.isString || value.content.isBlank()) {
        throw InvalidSubagentInput("字段 `$key` 必须是非空字符串。")
    }
}

internal inline fun validateSubagentInput(raw: JsonObject, validate: () -> Unit): ToolInput<JsonObject> = try {
    validate()
    ToolInput.Valid(raw)
} catch (e: InvalidSubagentInput) {
    ToolInput.Invalid(e.message!!)
}
