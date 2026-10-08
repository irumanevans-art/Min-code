package dev.min.agent.tools

import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamOp
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * agent 团队的三个工具（docs/agent/detail/agent团队与邮箱设计.md）：TeamCreate / TeamTask / TeamDisband。
 * 工具只把入参转成 [TeamOp] 交给 [ToolContext.agents]；成员怎么建、任务权限怎么判、名额怎么排都在内核的 SessionHub，
 * 和子 agent 三件套一个分工。团队内发消息不另做工具——就是 SendMessage（to = 成员名 / lead / all）。
 *
 * traits：和子 agent 三件套同理标只读——它们碰不到工作区；成员各自的工具调用各自过自己的权限判定，团队不给额外权限。
 * 都不并发（团队写入串行，任务编号一个一个分配）；恢复策略保持默认的 Unknown：重复建团队 / 重复提任务不是无害的。
 */
private val TEAM_TRAITS = ToolTraits(readOnly = true, concurrencySafe = false)

/** TeamCreate 会建子会话让别的会话干活：检查点的工具闸要等它（[ToolTraits.delegates]） */
private val TEAM_CREATE_TRAITS = TEAM_TRAITS.copy(delegates = true)

object TeamCreateTool : Tool<JsonObject> {
    override val name = "TeamCreate"
    override val traits = TEAM_CREATE_TRAITS
    override val inputSchema = subagentSchema(
        listOf("members"),
        "members" to JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive("成员规格，一次声明完（之后不能加人）。每项：name（成员名，不能是 lead / all / main）、prompt（开场任务，写齐背景与目标）、agent_type（可选，默认 general-purpose；不能是 fork）"),
                "items" to subagentSchema(
                    listOf("name", "prompt"),
                    "name" to subagentProperty("string", "成员名，之后 SendMessage / TeamTask 用它指这个成员"),
                    "prompt" to subagentProperty("string", "成员的开场任务；成员看不到你的对话，背景要给齐"),
                    "agent_type" to subagentProperty("string", "子 agent 类型，默认 general-purpose"),
                ),
            ),
        ),
    )

    override fun description(profile: HarnessProfile) =
        "建立 agent 团队：声明若干成员，每个成员是独立运行的子会话，可以同时干活、互相发消息、共享一份任务清单；你是负责人（lead）。" +
            "同时运行的成员数有上限（手机默认 3 个），超出的排队，前一个结束后才启动。成员完成后的报告交给你，不会直接给用户。" +
            "成员的工具调用审批照常由用户在界面上答，团队不给成员额外权限。成员不能再开团队。一次只能有一个进行中的团队，用 TeamDisband 解散后才能建新的。" +
            "建好后用 TeamTask 管任务清单，用 SendMessage 给成员发消息（to = 成员名，lead，或 all 广播）。收到报告之前不要假设成员的结果。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        val members = raw["members"] as? JsonArray ?: throw InvalidSubagentInput("字段 `members` 必须是成员规格的数组。")
        if (members.isEmpty()) throw InvalidSubagentInput("字段 `members` 至少要有一个成员。")
        members.forEachIndexed { i, m ->
            val o = m as? JsonObject ?: throw InvalidSubagentInput("members[$i] 必须是对象（name、prompt、可选 agent_type）。")
            try {
                requireSubagentString(o, "name")
                requireSubagentString(o, "prompt")
                if ("agent_type" in o) requireSubagentString(o, "agent_type")
            } catch (e: InvalidSubagentInput) {
                throw InvalidSubagentInput("members[$i]：${e.message}")
            }
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
        PermissionAsk(name, "建立 agent 团队（${specs(input).joinToString("、") { it.name }}）")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        emit(ToolProgress.Done(ctx.agents.team(TeamOp.Create(specs(input)))))
    }

    private fun specs(input: JsonObject): List<TeamMemberSpec> = (input.getValue("members") as JsonArray).map { m ->
        val o = m as JsonObject
        TeamMemberSpec(o.subagentString("name")!!.trim(), o.subagentString("agent_type")?.trim()?.takeIf { it.isNotEmpty() }, o.subagentString("prompt")!!)
    }
}

object TeamTaskTool : Tool<JsonObject> {
    override val name = "TeamTask"
    override val traits = TEAM_TRAITS

    private val ACTIONS = listOf("create", "claim", "done", "list")

    override val inputSchema = subagentSchema(
        listOf("action"),
        "action" to subagentProperty("string", "create 新建任务 / claim 认领 / done 完成 / list 查看团队与任务清单"),
        "title" to subagentProperty("string", "create 用：任务标题"),
        "description" to subagentProperty("string", "create 用：任务说明，可省略"),
        "task_id" to subagentProperty("string", "claim / done 用：任务编号（list 或 create 的结果里 # 后面那串数字）"),
        "member" to subagentProperty("string", "claim 用：认领给谁。省略 = 认领给自己；只有负责人能指派给别人或改派"),
        "summary" to subagentProperty("string", "done 用：一句话结论，可省略"),
    )

    override fun description(profile: HarnessProfile) =
        "团队共享的任务清单（和你自己的 TaskCreate 清单是两份）。负责人和成员都能 create；成员只能 claim 给自己、只能 done 自己认领的任务，" +
            "负责人可以指派、改派、完成任何一条。违规的操作会被拒绝并说明原因。list 返回成员状态与全部任务。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        requireSubagentString(raw, "action")
        val action = raw.subagentString("action")!!.trim()
        if (action !in ACTIONS) throw InvalidSubagentInput("字段 `action` 必须是 ${ACTIONS.joinToString(" / ")} 之一。")
        when (action) {
            "create" -> requireSubagentString(raw, "title")
            "claim", "done" -> requireSubagentString(raw, "task_id")
        }
        for (key in listOf("description", "member", "summary")) {
            if (key in raw) requireSubagentString(raw, key)
        }
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "团队任务：${input.subagentString("action")}")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val op = when (input.subagentString("action")!!.trim()) {
            "create" -> TeamOp.TaskCreate(input.subagentString("title")!!, input.subagentString("description").orEmpty())
            "claim" -> TeamOp.TaskClaim(input.subagentString("task_id")!!, input.subagentString("member"))
            "done" -> TeamOp.TaskDone(input.subagentString("task_id")!!, input.subagentString("summary"))
            else -> TeamOp.Status
        }
        emit(ToolProgress.Done(ctx.agents.team(op)))
    }
}

object TeamDisbandTool : Tool<JsonObject> {
    override val name = "TeamDisband"
    override val traits = TEAM_TRAITS
    override val inputSchema = subagentSchema(
        emptyList(),
        "reason" to subagentProperty("string", "completed（活干完了，默认）/ user_ended（用户叫停）"),
        "note" to subagentProperty("string", "可选的一句说明"),
    )

    override fun description(profile: HarnessProfile) =
        "解散团队（只有负责人能用）：所有成员停下，已有的报告照常交给你；任务清单保留可查。解散后才能建新团队。"

    override fun parse(raw: JsonObject): ToolInput<JsonObject> = validateSubagentInput(raw) {
        if ("reason" in raw) {
            requireSubagentString(raw, "reason")
            if (raw.subagentString("reason") !in REASONS) throw InvalidSubagentInput("字段 `reason` 必须是 ${REASONS.keys.joinToString(" / ")} 之一。")
        }
        if ("note" in raw) requireSubagentString(raw, "note")
    }

    override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "解散 agent 团队")

    override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
        val reason = REASONS.getValue(input.subagentString("reason") ?: "completed")
        emit(ToolProgress.Done(ctx.agents.team(TeamOp.Disband(reason, input.subagentString("note")))))
    }

    private val REASONS = mapOf("completed" to TeamDisbandReason.Completed, "user_ended" to TeamDisbandReason.UserEnded)
}
