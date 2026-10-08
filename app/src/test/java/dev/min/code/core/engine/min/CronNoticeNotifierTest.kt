package dev.min.code.core.engine.min

import dev.min.agent.kernel.CronNotice
import dev.min.code.R
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CronNoticeNotifierTest {
    @Test
    fun `every kind of notice has its own title`() {
        assertEquals(R.string.cron_notice_ended, cronNoticeTitleRes(CronNotice.Kind.Expired))
        assertEquals(R.string.cron_notice_budget_stopped, cronNoticeTitleRes(CronNotice.Kind.BudgetStopped))
        assertEquals(R.string.cron_notice_skipped, cronNoticeTitleRes(CronNotice.Kind.Skipped))
        assertEquals("新增一种通知时这里要有人补标题", 3, CronNotice.Kind.entries.size)
    }

    @Test
    fun `notification ids are stable per task and do not collide across tasks or sessions`() {
        val s1 = SessionRef(EngineKind.MinAgent, "s_1")
        val s2 = SessionRef(EngineKind.MinAgent, "s_2")
        assertEquals(cronNoticeNotificationId(s1, "c1"), cronNoticeNotificationId(s1, "c1"))
        assertNotEquals(cronNoticeNotificationId(s1, "c1"), cronNoticeNotificationId(s1, "c2"))
        assertNotEquals(cronNoticeNotificationId(s1, "c1"), cronNoticeNotificationId(s2, "c1"))
        assertEquals("落在专属的号段里，不撞审批 / 完成通知", 9, cronNoticeNotificationId(s1, "c1") / 1_000_000)
    }
}
