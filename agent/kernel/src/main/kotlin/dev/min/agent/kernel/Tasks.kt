package dev.min.agent.kernel

import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskUpdated
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * 任务清单的折叠与校验：[SessionState.tasks] 完全由 TaskCreated / TaskUpdated / TaskDeleted 折叠出来。
 *
 * 编号由这里分配：「已分配过的最大编号 + 1」，记在 [SessionState.taskSeq]。删除任务不回退它，
 * 所以一个编号在会话里只指一条任务——模型手里引用过的 "#3" 不会在删掉之后变成另一件事。
 *
 * 清单属于单个会话。子 agent 与分身各有自己的日志、自己的清单，互不可见（分身继承的是父对话的文字，
 * 不是它的清单：继承只搬消息与工具结果，任务事件不在其中）。
 */

/** 给工具看的视图：当前清单 + 下一次创建会拿到的编号 */
internal fun SessionState.taskBoard() = TaskBoard(tasks, nextTaskId())

internal fun SessionState.nextTaskId(): String = (taskSeq + 1).toString()

/**
 * 这条任务事件在当前状态上不合法的原因；合法（或根本不是任务事件）返回 null。
 * 折叠用它守住日志的完整性（违反即内核 bug，大声失败）；落事件之前也用它筛一遍工具声明的效果——
 * 工具已经按同一份视图校验过，这里是最后一道防线，不是常规路径。
 */
internal fun SessionState.taskProblem(e: SessionEvent): String? = when (e) {
    is TaskCreated ->
        if (e.taskId != nextTaskId()) "任务编号 ${e.taskId} 不是下一个编号 ${nextTaskId()}" else null
    is TaskUpdated -> {
        if (tasks.none { it.id == e.taskId }) "没有任务 ${e.taskId}" else dependencyProblem(e.taskId, e.patch)
    }
    is TaskDeleted -> if (tasks.none { it.id == e.taskId }) "没有任务 ${e.taskId}" else null
    else -> null
}

private fun SessionState.dependencyProblem(id: String, patch: TaskPatch): String? {
    // 一边加一边在工作副本上判环：同一次补丁里先后加的两条边合起来成环也要拦住
    var graph = tasks.associate { it.id to it.blocks }
    fun add(from: String, to: String): String? {
        if (from !in graph) return "没有任务 $from"
        if (to !in graph) return "没有任务 $to"
        if (reaches(graph, to, from)) return "任务 $from 与 $to 之间会形成循环依赖"
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

/** 把一条任务事件折进状态；别的事件原样返回。合法性由调用方（[taskProblem]）先判 */
internal fun SessionState.applyTaskEvent(e: SessionEvent): SessionState = when (e) {
    is TaskCreated -> copy(
        tasks = tasks + TaskRecord(e.taskId, e.subject, e.description, e.activeForm, metadata = e.metadata),
        taskSeq = taskSeq + 1,
    )
    is TaskUpdated -> copy(tasks = tasks.updated(e.taskId, e.patch))
    is TaskDeleted -> copy(
        tasks = tasks.filterNot { it.id == e.taskId }
            .map { it.copy(blocks = it.blocks - e.taskId, blockedBy = it.blockedBy - e.taskId) },
    )
    else -> this
}

private fun List<TaskRecord>.updated(id: String, p: TaskPatch): List<TaskRecord> = map { t ->
    when {
        t.id == id -> t.copy(
            status = p.status ?: t.status,
            subject = p.subject ?: t.subject,
            description = p.description ?: t.description,
            activeForm = p.activeForm.cleared(t.activeForm),
            owner = p.owner.cleared(t.owner),
            metadata = t.metadata.merged(p.metadata),
            blocks = (t.blocks + p.addBlocks).distinct(),
            blockedBy = (t.blockedBy + p.addBlockedBy).distinct(),
        )
        // 另一侧保持对称：我阻塞它 ⇔ 它被我阻塞
        t.id in p.addBlocks -> t.copy(blockedBy = (t.blockedBy + id).distinct())
        t.id in p.addBlockedBy -> t.copy(blocks = (t.blocks + id).distinct())
        else -> t
    }
}

/** null = 不改；空串 = 清掉；其余 = 换成新值 */
private fun String?.cleared(old: String?): String? = when (this) {
    null -> old
    "" -> null
    else -> this
}

/** 按键合并；值为 JSON null 的键从结果里删掉 */
private fun JsonObject.merged(patch: JsonObject): JsonObject =
    if (patch.isEmpty()) this
    else JsonObject((this + patch).filterValues { it !is JsonNull })
