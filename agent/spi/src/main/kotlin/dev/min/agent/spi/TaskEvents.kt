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
 * 清单属于**单个会话**：子 agent（含分身）是各自的会话，各有各的清单，既不继承也不回写父会话的——
 * 分身继承的是父对话的文字，清单的事件不在继承之列。
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
