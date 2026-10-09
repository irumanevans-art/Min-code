package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 任务清单（TaskCreate / TaskGet / TaskList / TaskUpdate）进日志的形状。
 *
 * 清单是会话状态，不是工具的私有缓存：模型看到的任务编号、进程重启后还在的清单、终端上的进度行，都来自日志折叠出的
 * `SessionState.tasks`。工具不直接改它，只在 [ToolResult.effects] 里声明（[SessionEffect.TaskCreate] 等），
 * 内核在写 ToolOutcome 的同一次提交里把效果落成下面这几条事件——与计划模式切模式同一条路（docs/agent/02-总体架构.md §1）。
 *
 * 清单属于**单个会话**：子 agent 是各自的会话，各有各的清单，不回写父会话的。
 * 分身例外的只是起点：它继承的对话里有父会话的任务编号，所以创建时拿到父清单的一份副本（用这几种事件重放出来，
 * 见 kernel 的 forkInheritance），之后各改各的。
 *
 * 格式纪律同 SessionEvent.kt：变体与字段只加不改不删，默认值发布后不能改。
 */

/** 与 Claude Code 一致的三态。删除不是一种状态，见 [TaskDeleted] */
@Serializable
enum class TaskStatus {
    @SerialName("pending") Pending,
    @SerialName("in_progress") InProgress,
    @SerialName("completed") Completed,
}

/**
 * 折叠出来的一条任务，只在内存里（不直接落盘，落盘的是事件）。
 * [id] 由内核分配：会话内从 "1" 递增、删除后也不复用，模型在后续调用里原样引用它。
 * [blocks] 与 [blockedBy] 两侧由折叠保持对称：A 阻塞 B，则 B 的 blockedBy 里一定有 A。
 */
data class TaskRecord(
    val id: String,
    val subject: String,
    val description: String,
    /** 进行中时给界面显示的进行时短语（「正在修登录」）；没给就显示 [subject] */
    val activeForm: String? = null,
    val status: TaskStatus = TaskStatus.Pending,
    val owner: String? = null,
    val blocks: List<String> = emptyList(),
    val blockedBy: List<String> = emptyList(),
    /** 调用方自己挂的键值，内核不解释 */
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * 对一条任务的修改。每个字段「没给 = 不改」。
 * [owner] 与 [activeForm] 给空串表示清掉；[metadata] 按键合并，值为 null 的键表示删除该键；
 * [addBlocks] / [addBlockedBy] 只增不减（要拆依赖就删掉那条任务），重复的忽略。
 */
@Serializable
data class TaskPatch(
    val status: TaskStatus? = null,
    val subject: String? = null,
    val description: String? = null,
    val activeForm: String? = null,
    val owner: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val addBlocks: List<String> = emptyList(),
    val addBlockedBy: List<String> = emptyList(),
) {
    /** 什么都不改 */
    val isEmpty: Boolean
        get() = status == null && subject == null && description == null && activeForm == null && owner == null &&
            metadata.isEmpty() && addBlocks.isEmpty() && addBlockedBy.isEmpty()
}

/**
 * 工具看到的清单视图（[ToolContext.tasks]）：当前清单的只读快照，加上「下一次创建会拿到的编号」。
 * 编号由内核分配，工具只是在给模型的结果里预告它——创建类工具不并发（连续的创建一个一个落盘），预告因此与实际一致。
 */
data class TaskBoard(
    val tasks: List<TaskRecord> = emptyList(),
    val nextId: String = "1",
    /** 非空 = 这是团队的共享清单（处于团队中的会话读写的就是它，不是个人清单）；见 [TeamView] */
    val team: TeamView? = null,
) {
    operator fun get(id: String): TaskRecord? = tasks.firstOrNull { it.id == id }
}

/** 新建一条任务。编号 [taskId] 由内核分配（紧跟已分配的最大编号），状态固定从 pending 开始 */
@Serializable
@SerialName("task_created")
data class TaskCreated(
    val taskId: String,
    val subject: String,
    val description: String,
    val activeForm: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
) : SessionEvent

/** 修改一条任务（状态、文字、负责人、依赖）。不含删除 */
@Serializable
@SerialName("task_updated")
data class TaskUpdated(val taskId: String, val patch: TaskPatch) : SessionEvent

/** 删除一条任务，同时从别的任务的 blocks / blockedBy 里摘掉它。编号不会被复用 */
@Serializable
@SerialName("task_deleted")
data class TaskDeleted(val taskId: String) : SessionEvent

/**
 * 给任务 [id] 加上 [patch] 里的依赖会不会出问题：目标任务不存在、指向自己、或形成循环（A 等 B、B 又等 A，
 * 两个任务就永远都做不了）。没问题返回 null。工具在回答模型之前用它校验，内核在落事件之前再用它把关——
 * 两边是同一份判断，所以工具说「可以」的，内核不会再拒。
 * 一边加一边在工作副本上判环：同一次补丁里先后加的两条边合起来成环也要拦住。
 */
fun TaskBoard.dependencyProblem(id: String, patch: TaskPatch): String? {
    var graph = tasks.associate { it.id to it.blocks }
    fun add(from: String, to: String): String? {
        if (from !in graph) return "没有任务 #$from"
        if (to !in graph) return "没有任务 #$to"
        if (reaches(graph, to, from)) {
            return if (from == to) "任务 #$from 不能依赖它自己" else "任务 #$from 与 #$to 之间会形成循环依赖（#$to 已经直接或间接在等 #$from）"
        }
        graph = graph + (from to (graph.getValue(from) + to).distinct())
        return null
    }
    patch.addBlocks.forEach { b -> add(id, b)?.let { return it } }
    patch.addBlockedBy.forEach { a -> add(a, id)?.let { return it } }
    return null
}

/** 沿「阻塞」边能否从 [from] 走到 [to]（含 from == to） */
private fun reaches(graph: Map<String, List<String>>, from: String, to: String): Boolean {
    val seen = mutableSetOf<String>()
    val stack = ArrayDeque(listOf(from))
    while (stack.isNotEmpty()) {
        val at = stack.removeLast()
        if (at == to) return true
        if (seen.add(at)) stack.addAll(graph[at].orEmpty())
    }
    return false
}

/**
 * 处于 agent 团队里时，任务清单工具看到的是**团队的共享清单**（Claude Code 同：队友与负责人共用 TaskCreate / TaskGet / TaskList /
 * TaskUpdate，清单换成团队那一份）。这个视图告诉工具「现在是团队清单」以及「我是谁」——认领要把 owner 写成自己的名字。
 */
data class TeamView(
    val teamId: String,
    /** 看这份清单的人在团队里的名字：负责人 = [TEAM_LEAD]，成员 = 规格名 */
    val viewer: String,
    /** 全部成员名（不含负责人），给 TaskList 的抬头用 */
    val members: List<String>,
)
