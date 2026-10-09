package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionHub
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.CRON_SOURCE_WAKEUP
import dev.min.agent.spi.CronBoard
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.CronTask
import dev.min.code.core.engine.ScheduleControl
import dev.min.code.core.engine.ScheduleView
import dev.min.code.core.engine.ScheduledStatus
import dev.min.code.core.engine.ScheduledTaskView

/**
 * 会话的定时任务 -> 界面的 [ScheduleView]（纯函数）。[board] 是内核给模型 CronList 的同一份视图：
 * “下次”必须用它带的下限算（会话重开的时刻、缺价格跳过过的那一刻），否则重开后显示的是几天前就该有的时刻。
 * 一条任务都没有（也没建过）返回 null——设置里不画一个空段。
 */
fun minScheduleView(board: CronBoard?, enabled: Boolean): ScheduleView? {
    if (board == null || board.tasks.isEmpty()) return null
    return ScheduleView(
        enabled = enabled,
        ceilingUsd = board.budgetCeilingUsd,
        tasks = board.tasks.map { it.toView(board) },
    )
}

private fun CronTask.toView(board: CronBoard) = ScheduledTaskView(
    id = id,
    prompt = prompt,
    recurring = recurring,
    cron = cron,
    intervalMs = intervalMs,
    nextFireAtMs = if (active) nextFireAt(board.zone, board.nextFireFloors[id] ?: 0L) else null,
    expiresAtMs = expiresAtMs,
    budgetUsd = board.effectiveBudgetUsd(maxBudgetUsd),
    fires = fires,
    status = when (status) {
        CronStatus.Active -> ScheduledStatus.Active
        CronStatus.Fired -> ScheduledStatus.Fired
        CronStatus.Cancelled -> ScheduledStatus.Cancelled
        CronStatus.Expired -> ScheduledStatus.Expired
    },
    wakeup = source == CRON_SOURCE_WAKEUP,
)

/** 清单上的“删除”接到 Hub 上（与模型的 CronDelete 同一个事件、同一把锁） */
class MinScheduleControl(private val hub: SessionHub, private val root: SessionId) : ScheduleControl {
    override suspend fun delete(taskId: String): Boolean = hub.cancelCron(root, taskId)
}
