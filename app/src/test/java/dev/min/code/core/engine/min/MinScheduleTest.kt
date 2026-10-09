package dev.min.code.core.engine.min

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.spi.CRON_SOURCE_WAKEUP
import dev.min.agent.spi.CronBoard
import dev.min.agent.spi.CronCancelled
import dev.min.agent.spi.CronFired
import dev.min.agent.spi.CronScheduled
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.CronTask
import dev.min.code.core.engine.ScheduledStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 定时任务清单（会话设置、列表标记）与通知正文：内核只给事实，措辞在这里按界面语言组。 */
class MinScheduleTest {
    private val minute = 60_000L
    private val t0 = 1_791_446_400_000L // 2026-10-08 08:00 UTC

    private fun task(
        id: String, intervalMs: Long? = 30 * minute, cron: String? = null, recurring: Boolean = true, budget: Double? = null,
        status: CronStatus = CronStatus.Active, source: String? = null, lastFiredAtMs: Long? = null, fires: Int = 0,
    ) = CronTask(
        id = id, prompt = "检查构建 $id", recurring = recurring, maxBudgetUsd = budget, scheduledAtMs = t0, cron = cron,
        intervalMs = if (cron == null) intervalMs else null, expiresAtMs = if (recurring) t0 + 7 * 24 * 60 * minute else null,
        source = source, fires = fires, lastFiredAtMs = lastFiredAtMs, status = status,
    )

    private fun board(vararg tasks: CronTask, ceiling: Double? = null, floors: Map<String, Long> = emptyMap()) =
        CronBoard(tasks.toList(), "c9", t0, ZoneOffset.UTC, null, ceiling, null, floors)

    @Test fun `no board or no task means no schedule section`() {
        assertNull(minScheduleView(null, true))
        assertNull(minScheduleView(board(), true))
    }

    @Test fun `an active interval task shows its next fire from the engine's floor, not from the creation time`() {
        val view = minScheduleView(board(task("c1"), floors = mapOf("c1" to t0 + 3 * 60 * minute)), enabled = true)!!
        // 会话在三小时后重开：下一次从重开的那一刻往后数，不是几小时前就该有的时刻
        assertEquals(t0 + 3 * 60 * minute + 30 * minute, view.tasks.single().nextFireAtMs)
        assertEquals(1, view.activeCount)
        assertTrue(view.enabled)
    }

    @Test fun `a cron expression task keeps the expression and finds its next time`() {
        val view = minScheduleView(board(task("c1", cron = "0 9 * * *")), true)!!
        val t = view.tasks.single()
        assertEquals("0 9 * * *", t.cron); assertNull(t.intervalMs)
        assertEquals("每天 09:00，最近的一个在 t0 之后的当天 09:00", t0 + 60 * minute, t.nextFireAtMs)
    }

    @Test fun `the effective cap is the lower of the task's and the host ceiling, and none when neither is set`() {
        val view = minScheduleView(board(task("c1", budget = 0.5), task("c2", budget = 0.05), task("c3"), ceiling = 0.1), true)!!
        assertEquals(listOf(0.1, 0.05, 0.1), view.tasks.map { it.budgetUsd })
        assertEquals(0.1, view.ceilingUsd!!, 1e-9)
        assertNull(minScheduleView(board(task("c1")), true)!!.tasks.single().budgetUsd)
    }

    @Test fun `ended tasks keep their status and no next time, wakeups are marked`() {
        val view = minScheduleView(
            board(
                task("c1", status = CronStatus.Cancelled), task("c2", recurring = false, status = CronStatus.Fired, fires = 1),
                task("c3", status = CronStatus.Expired), task("c4", recurring = false, source = CRON_SOURCE_WAKEUP),
            ),
            true,
        )!!
        assertEquals(listOf(ScheduledStatus.Cancelled, ScheduledStatus.Fired, ScheduledStatus.Expired, ScheduledStatus.Active), view.tasks.map { it.status })
        assertEquals(listOf(null, null, null), view.tasks.take(3).map { it.nextFireAtMs })
        assertEquals(listOf(false, false, false, true), view.tasks.map { it.wakeup })
        assertEquals("只有进行中的算数", 1, view.activeCount)
    }

    @Test fun `the global switch is carried so the list can say nothing will fire`() {
        assertFalse(minScheduleView(board(task("c1")), enabled = false)!!.enabled)
    }

    // ---- 列表标记 ----

    @Test fun `the drawer counts the active scheduled tasks of a session`() {
        val s = foldOf(
            CronScheduled("c1", "a", true, null, t0, intervalMs = 30 * minute),
            CronScheduled("c2", "b", true, null, t0, intervalMs = 60 * minute),
            CronScheduled("c3", "c", false, null, t0, intervalMs = 10 * minute),
            CronCancelled("c2"),
            CronFired("c3", t0 + 11 * minute, ClientMsgId("cron:c3:1")),
        )
        assertEquals("c2 取消了、c3 一次性已触发：只剩 c1", 1, indexEntryOf(s, 0, 0, 0).activeCrons)
        assertEquals(MinIndexEntry.SCHEMA, indexEntryOf(s, 0, 0, 0).schema)
        assertEquals(0, indexEntryOf(foldOf(), 0, 0, 0).activeCrons)
    }
}
