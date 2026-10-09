package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.ext.hooks.TaskCompletedInput
import dev.min.agent.ext.hooks.TaskCreatedInput
import dev.min.agent.protocol.TurnId
import kotlinx.serialization.json.jsonObject

/**
 * TaskCreated / TaskCompleted（hooks.md :2445–2468）：个人清单与团队共享清单共用这一处，
 * 两条路只差「改动怎么落」——个人清单是工具声明的副作用（[ToolExecutor] 在提交前摘掉被拒的那条），
 * 团队清单是 [TeamCoordinator] 直接落的团队事件（在落之前问）。
 *
 * 只认 exit 2 / `decision: block`：这两个事件的 `continue: false` 按原文被忽略（Claude 继续工作），
 * 所以 [HookOutcome.Decision.Stop] 与放行一样处理。返回 null = 放行，否则是要还给模型的理由。
 */
internal class TaskHooks(private val hooks: HookDriver) {
    fun watchesCreation(): Boolean = hooks.has(HookEvent.TaskCreated, null)
    fun watchesCompletion(): Boolean = hooks.has(HookEvent.TaskCompleted, null)

    suspend fun refuseCreation(turnId: TurnId, task: TaskFacts): String? {
        if (!watchesCreation()) return null
        val input = TaskCreatedInput(task.id, task.subject, task.description, task.teammate, task.team)
        val specific = HookJson.encodeToJsonElement(TaskCreatedInput.serializer(), input).jsonObject
        return blockedReason(hooks.run(HookEvent.TaskCreated, null, specific, turnId), "TaskCreated")
    }

    suspend fun refuseCompletion(turnId: TurnId, task: TaskFacts): String? {
        if (!watchesCompletion()) return null
        val input = TaskCompletedInput(task.id, task.subject, task.description, task.teammate, task.team)
        val specific = HookJson.encodeToJsonElement(TaskCompletedInput.serializer(), input).jsonObject
        return blockedReason(hooks.run(HookEvent.TaskCompleted, null, specific, turnId), "TaskCompleted")
    }

    private fun blockedReason(outcome: HookOutcome?, event: String): String? =
        if (outcome?.decision == HookOutcome.Decision.Block) outcome.reason ?: "$event hook 没有给出理由" else null
}

/** 交给 hook 的任务事实。[teammate] / [team] 只有团队清单有（个人清单为 null，hook 输入里缺席） */
internal data class TaskFacts(
    val id: String,
    val subject: String,
    val description: String?,
    val teammate: String? = null,
    val team: String? = null,
)
