package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TaskCreated
import dev.min.agent.spi.TaskDeleted
import dev.min.agent.spi.TaskUpdated
import dev.min.agent.spi.ToolResult

/*
 * 工具结果对会话的副作用（ToolResult.effects）→ 落盘事件，以及计划模式的「回到哪」。
 * 工具只声明，内核来落：与 ToolOutcome 同一次提交，结果与设置、结果与任务清单不会一个写进去、另一个没写。
 */

/**
 * 结果里声明的副作用对应的事件，按声明顺序；紧跟在 ToolOutcome 之后写。
 * [state] 是提交时的会话状态：任务编号在这里分配（工具只预告），也用它筛掉不成立的任务效果
 * （目标任务不存在、依赖成环）——筛掉时记一条给人看的警告，不让日志带着一条折叠不了的事件。
 */
internal fun ToolResult.effectEvents(state: SessionState): List<SessionEvent> {
    // 工作副本：随着生成的任务事件前进，同一个结果里连着声明两次创建时第二次拿到下一个编号
    var working = state
    val events = mutableListOf<SessionEvent>()
    for (effect in effects) {
        val event: SessionEvent = when (effect) {
            is SessionEffect.ModeChange -> SettingsChanged(mode = effect.mode)
            // 只改父会话对子 agent 的记账，Fold 在 ToolOutcome 上直接应用，不另写事件
            is SessionEffect.ReportDelivered -> continue
            is SessionEffect.TaskCreate ->
                TaskCreated(working.nextTaskId(), effect.subject, effect.description, effect.activeForm, effect.metadata)
            is SessionEffect.TaskUpdate -> TaskUpdated(effect.taskId, effect.patch)
            is SessionEffect.TaskDelete -> TaskDeleted(effect.taskId)
        }
        val problem = working.taskProblem(event)
        if (problem != null) {
            events += Note(NoteLevel.Warning, "任务清单的一次修改被忽略：$problem")
            continue
        }
        working = working.applyTaskEvent(event)
        events += event
    }
    return events
}

/**
 * 一次模式变更之后的「进计划模式之前的模式」：从别的模式切进 Plan 时记下原模式；
 * 已经在 Plan 里再切 Plan 不覆盖（否则记成 Plan，退出时就回不去了）；其余情况保持不变。
 */
internal fun SessionSettings.modeBeforePlanAfter(newMode: PermissionMode?): PermissionMode? =
    if (newMode == PermissionMode.Plan && mode != PermissionMode.Plan) mode else modeBeforePlan
