package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.dependencyProblem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 任务清单四件套：TaskCreate / TaskGet / TaskList / TaskUpdate（名字与入参照 Claude Code 的 Agent SDK 文档）。
 * 模型用它规划多步骤的工作、汇报进度；TodoWrite（一次写整张清单）默认已被 Claude Code 关掉，这里不做。
 *
 * 清单是会话状态：工具只读 [ToolContext.tasks] 这份视图，改动一律在结果里声明 [SessionEffect]（TaskCreate / TaskUpdate /
 * TaskDelete），由内核与 ToolOutcome 同一次落盘、分配编号——工具自己不存任何东西。
 *
 * traits 的理由：四个都标只读，TaskCreate / TaskUpdate 也不例外。它们只改会话自己的清单，碰不到工作区的文件、命令和网络；
 * 若按「会写」处理，默认模式下每次推进一格进度都要弹一次审批，清单就成了打扰而不是帮助，计划模式里也没法用它规划。
 * TaskGet / TaskList 另外并发安全；TaskCreate / TaskUpdate 不并发——编号要一个一个分配，同一条消息里的几次调用按顺序落盘。
 * 恢复策略都是 Replay：效果只随 ToolOutcome 一起落盘，执行到一半被杀就等于没发生，重跑无害。
 */

private val TASK_TRAITS_READ = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)
private val TASK_TRAITS_WRITE = ToolTraits(readOnly = true, concurrencySafe = false, resumePolicy = ResumePolicy.Replay)

// ---- 入参零件 ----

private const val ID_HINT = "任务编号（TaskCreate 的结果、TaskList 里的 #号后面那串数字）"

/**
 * Claude Code 会在执行前把几个常见的写错的键名修成规范名（id / task_id → taskId，active_form → activeForm），
 * 这里照做：模型少一个来回，也与它在 Claude Code 里被训练出的习惯一致。
 */
private fun JsonObject.withCanonicalKeys(): JsonObject {
    val aliases = mapOf("id" to "taskId", "task_id" to "taskId", "active_form" to "activeForm")
    val fixed = aliases.filter { (alias, canonical) -> alias in this && canonical !in this }
    if (fixed.isEmpty()) return this
    return JsonObject(filterKeys { it !in fixed } + fixed.map { (alias, canonical) -> canonical to getValue(alias) })
}

/** 编号：字符串或整数都收；模型照结果里的「#3」抄过来时去掉 # */
private fun idOf(e: JsonElement, field: String): String {
    val p = e as? JsonPrimitive
    val id = p?.content?.trim()?.removePrefix("#")?.trim()
    if (p == null || id.isNullOrEmpty()) throw BadArg("字段 `$field` 必须是任务编号（字符串，如 \"1\"）。")
    return id
}

private fun JsonObject.taskId(): String {
    val e = this["taskId"]?.takeUnless { it is JsonNull } ?: throw BadArg("缺少必填字段 `taskId`（$ID_HINT）。")
    return idOf(e, "taskId")
}

private fun JsonObject.idList(field: String): List<String> {
    val e = this[field]?.takeUnless { it is JsonNull } ?: return emptyList()
    val array = e as? JsonArray ?: throw BadArg("字段 `$field` 必须是任务编号的数组（如 [\"2\",\"3\"]）。")
    return array.map { idOf(it, field) }.distinct()
}

private fun JsonObject.metadataArg(): JsonObject {
    val e = this["metadata"]?.takeUnless { it is JsonNull } ?: return JsonObject(emptyMap())
    return e as? JsonObject ?: throw BadArg("字段 `metadata` 必须是对象（键值对）。")
}

private fun nonBlank(field: String, value: String): String {
    val v = value.trim()
    if (v.isEmpty()) throw BadArg("字段 `$field` 不能为空。")
    return v
}

private fun metadataProp(description: String) = buildJsonObject {
    put("type", "object")
    put("description", description)
}

private fun idArrayProp(description: String) = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

// ---- 结果文字零件 ----

private fun TaskRecord.label() = "#$id「$subject」"

private fun TaskBoard.unfinishedBlockers(t: TaskRecord): List<TaskRecord> =
    t.blockedBy.mapNotNull { this[it] }.filter { it.status != TaskStatus.Completed }

private fun TaskStatus.wire() = when (this) {
    TaskStatus.Pending -> "pending"
    TaskStatus.InProgress -> "in_progress"
    TaskStatus.Completed -> "completed"
}

private fun notFound(board: TaskBoard, id: String): String =
    if (board.tasks.isEmpty()) "没有编号为 #$id 的任务：任务清单现在是空的。用 TaskCreate 新建。"
    else "没有编号为 #$id 的任务。现有任务：${board.tasks.joinToString("、") { "#${it.id}" }}（用 TaskList 查看）。"

// ---- TaskCreate ----

data class TaskCreateInput(val subject: String, val description: String, val activeForm: String?, val metadata: JsonObject)

object TaskCreateTool : Tool<TaskCreateInput> {
    override val name = "TaskCreate"

    override val inputSchema: JsonObject = schemaObject(
        listOf("subject", "description"),
        "subject" to prop("string", "任务的简短标题，用祈使句，写清要达成什么（如「修复登录流程的鉴权错误」）"),
        "description" to prop("string", "任务的详细说明：背景、要做什么、怎样算完成，让别人接手也看得懂"),
        "activeForm" to prop("string", "任务进行中时界面显示的进行时短语（如「正在修复鉴权错误」）；不写就显示 subject"),
        "metadata" to metadataProp("要挂在任务上的任意键值对"),
    )

    override val traits = TASK_TRAITS_WRITE

    override fun description(profile: HarnessProfile) =
        "在任务清单里新建一条任务，用来规划多步骤的工作、向用户汇报进度。" +
            "适合需要三步以上的复杂任务、用户一次给了好几件事、用户明确要求列清单的场合；一两步就能做完的小事、纯问答不要用。" +
            "subject 是祈使句的短标题，description 写清背景和完成标准，activeForm 是进行中显示的进行时短语（不写就显示 subject）。" +
            "新任务一律是 pending，编号由系统分配、在结果里返回；之后用 TaskUpdate 开始、完成它或设置依赖。" +
            "新建前先用 TaskList 看看有没有重复的任务。"

    override fun parse(raw: JsonObject): ToolInput<TaskCreateInput> = parseArgs {
        val obj = raw.withCanonicalKeys()
        TaskCreateInput(
            subject = nonBlank("subject", obj.reqString("subject")),
            description = nonBlank("description", obj.reqString("description")),
            activeForm = obj.optString("activeForm")?.trim()?.takeIf { it.isNotEmpty() },
            metadata = obj.metadataArg(),
        )
    }

    override fun permissionAsk(input: TaskCreateInput, ctx: ToolContext) =
        PermissionAsk(tool = name, summary = "新建任务：${input.subject.clip(80)}")

    override fun run(input: TaskCreateInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        // 编号由内核分配，这里只是把视图给的「下一个编号」预告给模型；创建不并发，预告与实际一致
        val id = ctx.tasks.nextId
        ToolResult(
            listOf(TextPart("已创建任务 #$id「${input.subject}」（pending）。")),
            effects = listOf(SessionEffect.TaskCreate(input.subject, input.description, input.activeForm, input.metadata)),
        )
    }
}

// ---- TaskGet ----

data class TaskGetInput(val taskId: String)

object TaskGetTool : Tool<TaskGetInput> {
    override val name = "TaskGet"

    override val inputSchema: JsonObject = schemaObject(
        listOf("taskId"),
        "taskId" to prop("string", "要查看的$ID_HINT"),
    )

    override val traits = TASK_TRAITS_READ

    override fun description(profile: HarnessProfile) =
        "按编号取一条任务的完整内容：标题、详细说明、状态、负责人、依赖关系。" +
            "开始做一条任务之前、或要更新它之前，先读一遍它的最新状态；只想看全部任务的概要用 TaskList。"

    override fun parse(raw: JsonObject): ToolInput<TaskGetInput> = parseArgs { TaskGetInput(raw.withCanonicalKeys().taskId()) }

    override fun permissionAsk(input: TaskGetInput, ctx: ToolContext) = PermissionAsk(tool = name, summary = "查看任务 #${input.taskId}")

    override fun run(input: TaskGetInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        val board = ctx.tasks
        val t = board[input.taskId] ?: return@doneFlow failResult(notFound(board, input.taskId))
        okResult(describe(board, t))
    }

    private fun describe(board: TaskBoard, t: TaskRecord): String = buildString {
        append('#').append(t.id).append(" [").append(t.status.wire()).append("] ").append(t.subject).append('\n')
        append(t.description)
        t.activeForm?.let { append("\n进行时短语：").append(it) }
        t.owner?.let { append("\n负责人：").append(it) }
        if (t.blocks.isNotEmpty()) append("\n阻塞（它们要等本任务完成）：").append(t.blocks.joinToString("、") { "#$it" })
        if (t.blockedBy.isNotEmpty()) {
            append("\n被阻塞于：").append(
                t.blockedBy.joinToString("、") { id -> "#$id" + if (board[id]?.status == TaskStatus.Completed) "（已完成）" else "" },
            )
        }
        if (t.metadata.isNotEmpty()) append("\nmetadata：").append(t.metadata)
    }
}

// ---- TaskList ----

object TaskListTool : Tool<Unit> {
    override val name = "TaskList"

    override val inputSchema: JsonObject = schemaObject(emptyList())

    override val traits = TASK_TRAITS_READ

    override fun description(profile: HarnessProfile) =
        "列出任务清单里全部任务的概要：编号、标题、状态、负责人、还在等哪些未完成的任务。" +
            "用来看整体进度、找下一件可以做的事（状态 pending、没有未完成阻塞者的任务；条件相同时先做编号小的）、核对有没有重复。" +
            "要看某条任务的详细说明用 TaskGet。"

    // 没有入参；模型多给的字段一律忽略
    override fun parse(raw: JsonObject): ToolInput<Unit> = ToolInput.Valid(Unit)

    override fun permissionAsk(input: Unit, ctx: ToolContext) = PermissionAsk(tool = name, summary = "查看任务清单")

    override fun run(input: Unit, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        val board = ctx.tasks
        if (board.tasks.isEmpty()) return@doneFlow okResult("任务清单是空的。")
        val count = { s: TaskStatus -> board.tasks.count { it.status == s } }
        okResult(
            buildString {
                append("共 ${board.tasks.size} 个任务：${count(TaskStatus.Completed)} 已完成、")
                append("${count(TaskStatus.InProgress)} 进行中、${count(TaskStatus.Pending)} 待处理\n")
                board.tasks.forEach { t ->
                    append('#').append(t.id).append(" [").append(t.status.wire()).append("] ").append(t.subject)
                    t.owner?.let { append("（负责人：").append(it).append('）') }
                    // 已完成的阻塞者不再算阻塞，免得模型以为这条还做不了
                    val blockers = board.unfinishedBlockers(t)
                    if (blockers.isNotEmpty()) append("（被 ").append(blockers.joinToString("、") { "#${it.id}" }).append(" 阻塞）")
                    append('\n')
                }
            }.trimEnd(),
        )
    }
}

// ---- TaskUpdate ----

data class TaskUpdateInput(
    val taskId: String,
    val status: TaskStatus?,
    /** status = "deleted"：删除任务，其余字段忽略 */
    val delete: Boolean,
    val subject: String?,
    val description: String?,
    val activeForm: String?,
    val owner: String?,
    val metadata: JsonObject,
    val addBlocks: List<String>,
    val addBlockedBy: List<String>,
)

object TaskUpdateTool : Tool<TaskUpdateInput> {
    override val name = "TaskUpdate"

    private val STATUSES = listOf("pending", "in_progress", "completed", "deleted")

    override val inputSchema: JsonObject = schemaObject(
        listOf("taskId"),
        "taskId" to prop("string", "要更新的$ID_HINT"),
        "status" to prop("string", "新状态：pending → in_progress → completed；deleted 表示永久删除这条任务", STATUSES),
        "subject" to prop("string", "新的标题"),
        "description" to prop("string", "新的详细说明"),
        "activeForm" to prop("string", "新的进行时短语；传空串清掉"),
        "addBlocks" to idArrayProp("本任务要阻塞的任务编号：那些任务要等本任务完成才能做。只增不减"),
        "addBlockedBy" to idArrayProp("阻塞本任务的任务编号：要先完成它们才能做本任务。只增不减"),
        "owner" to prop("string", "负责人；传空串清掉"),
        "metadata" to metadataProp("要合并进任务的键值对；值为 null 的键会被删除"),
    )

    override val traits = TASK_TRAITS_WRITE

    override fun description(profile: HarnessProfile) =
        "更新任务清单里的一条任务：改状态、标题、说明，设置依赖、负责人，或删除。更新前先用 TaskGet 读最新状态，免得覆盖别处的改动。" +
            "状态按 pending → in_progress → completed 流转：开始做之前标 in_progress，真正做完了立刻标 completed，不要攒到最后一起标；" +
            "同一时间尽量只有一条 in_progress。测试没过、实现不完整、卡在没解决的问题上时不要标 completed，保持 in_progress，" +
            "必要时另建一条任务写清阻碍。status 设为 deleted 永久删除任务（同时从别的任务的依赖里摘掉，此时其他字段忽略）。" +
            "addBlocks / addBlockedBy 设置依赖，只增不减，不允许自己依赖自己或形成循环；metadata 按键合并，值为 null 的键会被删除。"

    override fun parse(raw: JsonObject): ToolInput<TaskUpdateInput> = parseArgs {
        val obj = raw.withCanonicalKeys()
        val statusText = obj.optString("status")?.trim()
        val normalized = statusText?.lowercase()?.replace('-', '_')?.replace(' ', '_')
        if (normalized != null && normalized !in STATUSES) {
            throw BadArg("字段 `status` 只能是 ${STATUSES.joinToString(" / ")}，收到 `$statusText`。")
        }
        val input = TaskUpdateInput(
            taskId = obj.taskId(),
            status = when (normalized) {
                "pending" -> TaskStatus.Pending
                "in_progress" -> TaskStatus.InProgress
                "completed" -> TaskStatus.Completed
                else -> null
            },
            delete = normalized == "deleted",
            subject = obj.optString("subject")?.let { nonBlank("subject", it) },
            description = obj.optString("description")?.let { nonBlank("description", it) },
            // 空串 = 清掉（折叠里的约定）：不在这里 trim 成 null，否则「清掉」与「没给」分不开
            activeForm = obj.optString("activeForm")?.trim(),
            owner = obj.optString("owner")?.trim(),
            metadata = obj.metadataArg(),
            addBlocks = obj.idList("addBlocks"),
            addBlockedBy = obj.idList("addBlockedBy"),
        )
        if (!input.delete && input.toPatch().isEmpty) {
            throw BadArg("没有要更新的字段：除 `taskId` 外至少给一项（status、subject、description、activeForm、owner、metadata、addBlocks、addBlockedBy）。")
        }
        input
    }

    private fun TaskUpdateInput.toPatch() = TaskPatch(status, subject, description, activeForm, owner, metadata, addBlocks, addBlockedBy)

    override fun permissionAsk(input: TaskUpdateInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        summary = if (input.delete) "删除任务 #${input.taskId}" else "更新任务 #${input.taskId}",
    )

    override fun run(input: TaskUpdateInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow {
        val board = ctx.tasks
        val task = board[input.taskId] ?: return@doneFlow failResult(notFound(board, input.taskId))
        if (input.delete) {
            return@doneFlow ToolResult(
                listOf(TextPart("已删除任务 ${task.label()}。")),
                effects = listOf(SessionEffect.TaskDelete(task.id)),
            )
        }
        val requested = input.toPatch()
        board.dependencyProblem(task.id, requested)?.let { return@doneFlow failResult("没有更新：$it。") }

        // 只声明真正有变化的部分：同样的值再写一遍不产生事件，结果里也如实说「没有变化」
        val patch = requested.copy(
            status = input.status?.takeIf { it != task.status },
            subject = input.subject?.takeIf { it != task.subject },
            description = input.description?.takeIf { it != task.description },
            activeForm = input.activeForm?.takeIf { it.ifEmpty { null } != task.activeForm },
            owner = input.owner?.takeIf { it.ifEmpty { null } != task.owner },
            metadata = JsonObject(
                input.metadata.filter { (k, v) -> if (v is JsonNull) k in task.metadata else task.metadata[k] != v },
            ),
            addBlocks = input.addBlocks.filter { it !in task.blocks },
            addBlockedBy = input.addBlockedBy.filter { it !in task.blockedBy },
        )
        if (patch.isEmpty) return@doneFlow okResult("任务 ${task.label()} 没有变化：给出的值与现状相同。")

        val text = buildString {
            append("已更新任务 ${task.label()}：").append(changes(task, patch).joinToString("；")).append("。")
            if (patch.status == TaskStatus.InProgress) {
                val blockers = board.unfinishedBlockers(task)
                if (blockers.isNotEmpty()) append("注意：它还被未完成的 ${blockers.joinToString("、") { "#${it.id}" }} 阻塞。")
            }
            if (patch.status == TaskStatus.Completed) {
                val left = board.tasks.count { it.id != task.id && it.status != TaskStatus.Completed }
                append(if (left == 0) "清单里的任务都已完成。" else "还有 $left 个任务没完成，可以用 TaskList 找下一个。")
            }
        }
        ToolResult(listOf(TextPart(text)), effects = listOf(SessionEffect.TaskUpdate(task.id, patch)))
    }

    private fun changes(task: TaskRecord, p: TaskPatch): List<String> = buildList {
        p.status?.let { add("状态 ${task.status.wire()} → ${it.wire()}") }
        if (p.subject != null) add("标题")
        if (p.description != null) add("说明")
        if (p.activeForm != null) add("进行时短语")
        p.owner?.let { add(if (it.isEmpty()) "清掉负责人" else "负责人 $it") }
        if (p.metadata.isNotEmpty()) add("metadata")
        if (p.addBlocks.isNotEmpty()) add("阻塞 ${p.addBlocks.joinToString("、") { "#$it" }}")
        if (p.addBlockedBy.isNotEmpty()) add("被 ${p.addBlockedBy.joinToString("、") { "#$it" }} 阻塞")
    }
}
