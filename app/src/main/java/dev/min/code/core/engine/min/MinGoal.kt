package dev.min.code.core.engine.min

import dev.min.agent.kernel.GoalState
import dev.min.code.core.engine.GoalStatus
import dev.min.code.core.engine.GoalView

/**
 * 内核的 [GoalState] -> 界面的 [GoalView]（纯函数）。状态的先后：暂停压过推迟（暂停时后台工作已经收尾，推迟标记是旧的）。
 * 没有进行中的目标返回 null。
 */
fun minGoalView(goal: GoalState): GoalView? {
    val condition = goal.condition ?: return null
    val status = when {
        goal.paused != null -> GoalStatus.Paused
        goal.waitSinceMs != null -> GoalStatus.Deferred
        else -> GoalStatus.Active
    }
    return GoalView(
        condition = condition,
        evaluations = goal.turns,
        lastReason = goal.lastReason,
        status = status,
        pausedReason = goal.paused,
    )
}
