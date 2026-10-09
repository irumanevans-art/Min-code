package dev.min.code.core.engine

/**
 * 一个会话的定时任务（[EngineCapabilities.schedule]，只有 Min 有）：模型用 CronCreate / ScheduleWakeup 建的，
 * 用户在这里能看到清单、删掉。与模型 CronList 看到的是同一份。
 *
 * 日程是结构化的（cron 表达式或固定间隔），措辞由界面按语言组。
 */
data class ScheduleView(
    /** 全局开关（设置里的「定时任务」）。关着时清单还在，但不会触发 */
    val enabled: Boolean,
    /** 宿主设的单次花费天花板（设置里的 `cronMaxBudgetUsd`）；null = 没设 */
    val ceilingUsd: Double?,
    val tasks: List<ScheduledTaskView>,
) {
    /** 进行中的任务数：会话列表上的标记、设置面板的概览读它 */
    val activeCount: Int get() = tasks.count { it.status == ScheduledStatus.Active }
}

enum class ScheduledStatus { Active, Fired, Cancelled, Expired }

/**
 * @param cron 5 字段表达式；与 [intervalMs] 恰好一个非空
 * @param nextFireAtMs 下一次触发的时刻（只有进行中的才有；表达式永远匹配不到也是 null）
 * @param expiresAtMs 过期时刻（循环任务建后 7 天；一次性任务没有）
 * @param budgetUsd 这个任务每次运行实际生效的花费上限（任务自己的与宿主天花板取小）；null = 不限
 * @param wakeup 是 ScheduleWakeup 排的唤醒（不是用户 / 模型显式建的循环任务）
 */
data class ScheduledTaskView(
    val id: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String?,
    val intervalMs: Long?,
    val nextFireAtMs: Long?,
    val expiresAtMs: Long?,
    val budgetUsd: Double?,
    val fires: Int,
    val status: ScheduledStatus,
    val wakeup: Boolean,
)

/** 清单上的“删除”（[EngineCapabilities.schedule] 为真才有）。true = 取消了一条进行中的任务；不存在 / 已结束是 false */
interface ScheduleControl {
    suspend fun delete(taskId: String): Boolean
}
