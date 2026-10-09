package dev.min.code.core.engine.min

import dev.min.agent.kernel.CronNotice
import dev.min.agent.kernel.CronOverrun
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.CronExpiry
import org.junit.Assert.assertEquals
import org.junit.Test

/** 定时任务通知的正文：内核只给事实，措辞在这里按界面语言组。 */
class CronTextTest {
    private val minute = 60_000L

    /** 把每个叶子短语写成它的名字加参数，断言“哪种事实说了哪句” */
    private object Phrases : CronText {
        override fun schedule(cron: String?, intervalMs: Long?) = cron?.let { "cron($it)" } ?: "every($intervalMs)"
        override fun money(usd: Double) = "$$usd"
        override fun overrun(overrun: CronOverrun) = "overrun:${overrun.cause}"
        override fun skipped(cronId: String, schedule: String, limit: String, recurring: Boolean) = "skipped:$cronId:$schedule:$limit:$recurring"
        override fun budgetStopped(cronId: String, schedule: String, overrun: String, willRunAgain: Boolean) = "stopped:$cronId:$schedule:$overrun:$willRunAgain"
        override fun pausedByBudget(cronId: String, schedule: String, overrun: String?, consecutive: Int) = "paused:$cronId:$schedule:$overrun:$consecutive"
        override fun expiredAge(cronId: String, schedule: String) = "age:$cronId:$schedule"
        override fun expiredMissed(cronId: String, schedule: String) = "missed:$cronId:$schedule"
        override fun expiredNoPricing(cronId: String, schedule: String) = "noprice:$cronId:$schedule"
    }

    private fun notice(kind: CronNotice.Kind, block: (CronNotice) -> CronNotice = { it }) =
        block(CronNotice(SessionId("s"), "c1", kind, "日志里的中文原句", intervalMs = 30 * minute))

    @Test fun `every kind of notice is phrased from its facts, in the host's words`() {
        assertEquals("skipped:c1:every(1800000):$0.1:true", cronNoticeBody(notice(CronNotice.Kind.Skipped) { it.copy(limitUsd = 0.1) }, Phrases))
        assertEquals(
            "stopped:c1:every(1800000):overrun:Spent:true",
            cronNoticeBody(notice(CronNotice.Kind.BudgetStopped) { it.copy(overrun = CronOverrun(CronOverrun.Cause.Spent, 0.05, 0.04), willRunAgain = true) }, Phrases),
        )
        assertEquals(
            "一次性任务 / 唤醒触发过就不会再跑", "stopped:c1:every(1800000):overrun:UnpricedRequest:false",
            cronNoticeBody(notice(CronNotice.Kind.BudgetStopped) { it.copy(overrun = CronOverrun(CronOverrun.Cause.UnpricedRequest, 0.0, 0.04)) }, Phrases),
        )
        assertEquals(
            "paused:c1:cron(0 9 * * *):overrun:Spent:2",
            cronNoticeBody(
                notice(CronNotice.Kind.Expired) { it.copy(cron = "0 9 * * *", intervalMs = null, expiry = CronExpiry.Budget, consecutiveOverruns = 2, overrun = CronOverrun(CronOverrun.Cause.Spent, 0.05, 0.04)) },
                Phrases,
            ),
        )
        assertEquals("age:c1:every(1800000)", cronNoticeBody(notice(CronNotice.Kind.Expired) { it.copy(expiry = CronExpiry.Age) }, Phrases))
        assertEquals("missed:c1:every(1800000)", cronNoticeBody(notice(CronNotice.Kind.Expired) { it.copy(expiry = CronExpiry.Missed) }, Phrases))
        assertEquals("noprice:c1:every(1800000)", cronNoticeBody(notice(CronNotice.Kind.Expired) { it.copy(expiry = CronExpiry.NoPricing) }, Phrases))
    }

    @Test fun `a notice without the facts falls back to the kernel's sentence instead of going blank`() {
        assertEquals("日志里的中文原句", cronNoticeBody(notice(CronNotice.Kind.Skipped), Phrases))
        assertEquals("日志里的中文原句", cronNoticeBody(notice(CronNotice.Kind.BudgetStopped), Phrases))
        assertEquals("日志里的中文原句", cronNoticeBody(notice(CronNotice.Kind.Expired), Phrases))
    }

    @Test fun `an interval is written in whole days, else whole hours, else minutes and never below a minute`() {
        assertEquals(2L to IntervalUnit.Days, intervalUnit(2 * 24 * 60 * minute))
        assertEquals(3L to IntervalUnit.Hours, intervalUnit(3 * 60 * minute))
        assertEquals(90L to IntervalUnit.Minutes, intervalUnit(90 * minute))
        assertEquals(1L to IntervalUnit.Minutes, intervalUnit(1_000))
        assertEquals("整天优先：24 小时写成 1 天", 1L to IntervalUnit.Days, intervalUnit(24 * 60 * minute))
    }
}
