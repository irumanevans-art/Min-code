package dev.min.agent.kernel

import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TaskBoard
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskRecord
import dev.min.agent.spi.TaskUpdated
import dev.min.agent.spi.dependencyProblem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * 任务清单的折叠与校验：[SessionState.tasks] 完全由 TaskCreated / TaskUpdated / TaskDeleted 折叠出来。
 *
 * 编号由这里分配：「已分配过的最大编号 + 1」，记在 [SessionState.taskSeq]。删除任务不回退它，
 * 所以一个编号在会话里只指一条任务——模型手里引用过的 "#3" 不会在删掉之后变成另一件事。
 *
 * 清单属于单个会话。子 agent 与分身各有自己的日志、自己的清单，之后互不可见：新开的子 agent 从空清单起步；
 * 分身创建时拿到父清单的**副本**（[forkInheritance] 用已有的任务事件重放出同样的编号、状态与依赖，删过的编号不复用），
 * 因为继承来的对话里有 TaskCreate 的结果，分身拿着同样的编号去 TaskUpdate 就该找得到；之后各改各的。
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
        if (tasks.none { it.id == e.taskId }) "没有任务 #${e.taskId}" else taskBoard().dependencyProblem(e.taskId, e.patch)
    }
    is TaskDeleted -> if (tasks.none { it.id == e.taskId }) "没有任务 #${e.taskId}" else null
    else -> null
}

/** 把一条任务事件折进状态；别的事件原样返回。合法性由调用方（[taskProblem]）先判 */
internal fun SessionState.applyTaskEvent(e: SessionEvent): SessionState = when (e) {
    is TaskCreated -> copy(
        tasks = tasks + TaskRecord(e.taskId, e.subject, e.description, e.activeForm, metadata = e.metadata),
        taskSeq = taskSeq + 1,
    )
    is TaskUpdated -> copy(tasks = tasks.updated(e.taskId, e.patch))
    is TaskDeleted -> copy(tasks = tasks.withoutTask(e.taskId))
    else -> this
}

/** 对一份任务列表套用一次修改。个人清单与团队共享清单共用这一份实现（owner、依赖对称、解锁都在这里） */
internal fun List<TaskRecord>.updated(id: String, p: TaskPatch): List<TaskRecord> = map { t ->
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

/** 删掉一条任务，同时从别的任务的 blocks / blockedBy 里摘掉它（个人清单与团队清单共用） */
internal fun List<TaskRecord>.withoutTask(id: String): List<TaskRecord> =
    filterNot { it.id == id }.map { it.copy(blocks = it.blocks - id, blockedBy = it.blockedBy - id) }
