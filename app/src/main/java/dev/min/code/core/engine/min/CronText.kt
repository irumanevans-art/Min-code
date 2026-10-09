package dev.min.code.core.engine.min

import android.content.Context
import dev.min.agent.kernel.CronNotice
import dev.min.agent.kernel.CronOverrun
import dev.min.agent.spi.CronExpiry
import dev.min.code.R

/**
 * 定时任务的措辞（通知、清单共用）：内核只给结构化事实，句子在这里按界面语言组。
 * 叶子短语在 [CronText]（读资源），组句的规则（哪种事实说哪句）是纯函数，单测用替身钉住。
 */
interface CronText {
    /** 「每 30 分钟」「cron 0 9 * * 1-5」 */
    fun schedule(cron: String?, intervalMs: Long?): String

    fun money(usd: Double): String

    /** 为什么被花费上限停了 */
    fun overrun(overrun: CronOverrun): String
    fun skipped(cronId: String, schedule: String, limit: String, recurring: Boolean): String
    fun budgetStopped(cronId: String, schedule: String, overrun: String, willRunAgain: Boolean): String
    fun pausedByBudget(cronId: String, schedule: String, overrun: String?, consecutive: Int): String
    fun expiredAge(cronId: String, schedule: String): String
    fun expiredMissed(cronId: String, schedule: String): String
    fun expiredNoPricing(cronId: String, schedule: String): String
}

/** 通知正文：由 [CronNotice] 的结构化事实组一句话，没有哪项事实时退回内核给的中文原句（不让通知变空） */
fun cronNoticeBody(notice: CronNotice, text: CronText): String {
    val schedule = text.schedule(notice.cron, notice.intervalMs)
    val id = notice.cronId
    return when (notice.kind) {
        CronNotice.Kind.Skipped -> notice.limitUsd?.let { text.skipped(id, schedule, text.money(it), notice.recurring) }
        CronNotice.Kind.BudgetStopped -> notice.overrun?.let { text.budgetStopped(id, schedule, text.overrun(it), notice.willRunAgain) }
        CronNotice.Kind.Expired -> when (notice.expiry) {
            CronExpiry.Age -> text.expiredAge(id, schedule)
            CronExpiry.Missed -> text.expiredMissed(id, schedule)
            CronExpiry.NoPricing -> text.expiredNoPricing(id, schedule)
            CronExpiry.Budget -> text.pausedByBudget(id, schedule, notice.overrun?.let(text::overrun), notice.consecutiveOverruns)
            null -> null
        }
    } ?: notice.text
}

/** 间隔拆成「N 个什么单位」：整天优先，其次整小时，其余按分钟（至少一分钟，内核的下限） */
fun intervalUnit(intervalMs: Long): Pair<Long, IntervalUnit> {
    val minutes = (intervalMs / MINUTE_MS).coerceAtLeast(1)
    return when {
        minutes % MINUTES_PER_DAY == 0L -> minutes / MINUTES_PER_DAY to IntervalUnit.Days
        minutes % MINUTES_PER_HOUR == 0L -> minutes / MINUTES_PER_HOUR to IntervalUnit.Hours
        else -> minutes to IntervalUnit.Minutes
    }
}

enum class IntervalUnit { Minutes, Hours, Days }

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24L * 60

/** 读资源的实现，跟着界面语言走 */
class ResCronText(private val context: Context) : CronText {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun schedule(cron: String?, intervalMs: Long?): String {
        if (cron != null) return s(R.string.cron_schedule_cron, cron)
        val (n, unit) = intervalUnit(intervalMs ?: 0L)
        return s(
            when (unit) {
                IntervalUnit.Minutes -> R.string.cron_schedule_minutes
                IntervalUnit.Hours -> R.string.cron_schedule_hours
                IntervalUnit.Days -> R.string.cron_schedule_days
            },
            n.toInt(),
        )
    }

    override fun money(usd: Double) = formatCost(dev.min.agent.llm.Cost(usd, "USD"))

    override fun overrun(overrun: CronOverrun) = when (overrun.cause) {
        CronOverrun.Cause.Spent -> s(R.string.cron_overrun_spent, money(overrun.spentUsd), money(overrun.limitUsd))
        CronOverrun.Cause.UnpricedRequest -> s(R.string.cron_overrun_unpriced_request)
        CronOverrun.Cause.UnpricedModel -> s(R.string.cron_overrun_unpriced_model)
    }

    override fun skipped(cronId: String, schedule: String, limit: String, recurring: Boolean) =
        s(if (recurring) R.string.cron_body_skipped_recurring else R.string.cron_body_skipped_once, cronId, schedule, limit)

    override fun budgetStopped(cronId: String, schedule: String, overrun: String, willRunAgain: Boolean) =
        s(if (willRunAgain) R.string.cron_body_budget_stopped_again else R.string.cron_body_budget_stopped_done, cronId, schedule, overrun)

    override fun pausedByBudget(cronId: String, schedule: String, overrun: String?, consecutive: Int) =
        if (overrun == null) s(R.string.cron_body_paused, cronId, schedule, consecutive)
        else s(R.string.cron_body_paused_last, cronId, schedule, consecutive, overrun)

    override fun expiredAge(cronId: String, schedule: String) = s(R.string.cron_body_expired_age, cronId, schedule)
    override fun expiredMissed(cronId: String, schedule: String) = s(R.string.cron_body_expired_missed, cronId, schedule)
    override fun expiredNoPricing(cronId: String, schedule: String) = s(R.string.cron_body_expired_no_pricing, cronId, schedule)
}
