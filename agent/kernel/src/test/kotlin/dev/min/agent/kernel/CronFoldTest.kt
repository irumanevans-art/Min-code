package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.CRON_MAX_ACTIVE
import dev.min.agent.spi.CronCancelled
import dev.min.agent.spi.CronExpiry
import dev.min.agent.spi.CronExpired
import dev.min.agent.spi.CronFired
import dev.min.agent.spi.CronScheduled
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SubagentOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset

/** 定时任务状态的折叠（纯函数，不起会话）：每种转换与每种非法转换 */
class CronFoldTest {
    private val header = LogHeader(1, SessionId("root"), 0)
    private val model = ModelRef("test", "m")

    private fun foldOf(vararg events: SessionEvent): SessionState {
        val all = listOf<SessionEvent>(SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default)) + events
        return fold(header, all.mapIndexed { i, e -> EventRecord(i + 1L, 100L + i, e) })
    }

    private fun scheduled(id: String = "c1", recurring: Boolean = true, cron: String? = null, interval: Long? = 600_000, at: Long = 1_000, budget: Double? = null) =
        CronScheduled(id, "检查构建", recurring, budget, at, cron = cron, intervalMs = interval.takeIf { cron == null }, expiresAtMs = if (recurring) at + 604_800_000 else null)

    private fun assertRejected(state: SessionState, event: SessionEvent, part: String) {
        val problem = state.cronProblem(event)
        assertNotNull("应该被拒：$event", problem)
        assertTrue("原因 \"$problem\" 里应有 \"$part\"", problem!!.contains(part))
        try {
            state.apply(EventRecord(state.lastSeq + 1, 999, event))
            fail("折叠不该收下非法事件")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("定时任务事件不合法"))
        }
    }

    @Test
    fun `a scheduled task folds in with its schedule, budget and expiry`() {
        val state = foldOf(scheduled(cron = "0 9 * * 1-5", budget = 0.25))
        val task = state.crons.single()
        assertEquals("c1", task.id)
        assertEquals("0 9 * * 1-5", task.cron)
        assertNull(task.intervalMs)
        assertEquals(0.25, task.maxBudgetUsd!!, 0.0)
        assertNull("不给上限就是不限，没有默认值", foldOf(scheduled()).crons.single().maxBudgetUsd)
        assertEquals(CronStatus.Active, task.status)
        assertEquals(1_000L + 604_800_000, task.expiresAtMs)
        assertEquals("c2", state.nextCronId())
    }

    @Test
    fun `a recurring task keeps firing and counts its runs`() {
        val state = foldOf(
            scheduled(),
            CronFired("c1", 700_000, ClientMsgId("cron:c1:1")),
            CronFired("c1", 1_300_000, ClientMsgId("cron:c1:2")),
        )
        val task = state.crons.single()
        assertEquals(2, task.fires)
        assertEquals(1_300_000L, task.lastFiredAtMs)
        assertTrue(task.active)
        assertEquals("下一次从上次触发往后数", 1_300_000L + 600_000, task.nextFireAt(ZoneOffset.UTC))
    }

    @Test
    fun `a one shot ends after its single fire`() {
        val state = foldOf(scheduled(recurring = false), CronFired("c1", 700_000, ClientMsgId("cron:c1:1")))
        val task = state.crons.single()
        assertEquals(CronStatus.Fired, task.status)
        assertEquals(1, task.fires)
        assertEquals("一次性任务已触发", task.endedBecause)
        assertRejected(state, CronFired("c1", 800_000, ClientMsgId("cron:c1:2")), "已经结束")
    }

    @Test
    fun `cancelling and expiring end a task with the reason`() {
        val cancelled = foldOf(scheduled(), CronCancelled("c1"))
        assertEquals(CronStatus.Cancelled, cancelled.crons.single().status)
        assertEquals("已取消", cancelled.crons.single().endedBecause)

        val expired = foldOf(scheduled(), CronExpired("c1", CronExpiry.Budget, "这次运行花了 \$0.0600，到了单次上限 \$0.0500"))
        assertEquals(CronStatus.Expired, expired.crons.single().status)
        assertTrue(expired.crons.single().endedBecause!!.startsWith("花费上限"))

        for (end in listOf(cancelled, expired)) {
            assertRejected(end, CronCancelled("c1"), "已经结束")
            assertRejected(end, CronExpired("c1", CronExpiry.Age), "已经结束")
            assertRejected(end, CronFired("c1", 5, ClientMsgId("x")), "已经结束")
        }
        assertEquals("编号不复用", "c2", cancelled.nextCronId())
    }

    @Test
    fun `events for unknown tasks are rejected`() {
        val state = foldOf(scheduled())
        assertRejected(state, CronFired("c9", 5, ClientMsgId("x")), "没有定时任务 c9")
        assertRejected(state, CronCancelled("c9"), "没有定时任务 c9")
        assertRejected(state, CronExpired("c9", CronExpiry.Age), "没有定时任务 c9")
    }

    @Test
    fun `invalid schedules are rejected`() {
        val empty = foldOf()
        assertRejected(empty, scheduled(id = "c2"), "不是下一个编号")
        assertRejected(empty, CronScheduled("c1", " ", true, null, 1, intervalMs = 600_000), "要有提示词")
        assertRejected(empty, CronScheduled("c1", "x", true, null, 1), "恰好给一个")
        assertRejected(empty, CronScheduled("c1", "x", true, null, 1, cron = "* * * * *", intervalMs = 600_000), "恰好给一个")
        assertRejected(empty, CronScheduled("c1", "x", true, null, 1, intervalMs = 30_000), "最短一分钟")
        assertRejected(empty, CronScheduled("c1", "x", true, null, 1, cron = "61 * * * *"), "分钟")
        assertRejected(empty, scheduled(budget = 0.0), "大于 0")
        assertRejected(empty, scheduled(budget = Double.POSITIVE_INFINITY), "大于 0")
        assertRejected(empty, scheduled(budget = Double.NaN), "大于 0")
    }

    @Test
    fun `at most fifty tasks can be active and finished ones do not count`() {
        val fifty = (1..CRON_MAX_ACTIVE).map { scheduled(id = "c$it") }
        val full = foldOf(*fifty.toTypedArray())
        assertRejected(full, scheduled(id = "c${CRON_MAX_ACTIVE + 1}"), "最多同时有 $CRON_MAX_ACTIVE 个")
        val room = foldOf(*fifty.toTypedArray(), CronCancelled("c7"))
        assertNull(room.cronProblem(scheduled(id = "c${CRON_MAX_ACTIVE + 1}")))
    }

    @Test
    fun `sub agents and team members cannot schedule, and the tool view says so`() {
        val child = SubagentOrigin(
            parent = SessionId("root"), root = SessionId("root"), parentCall = CallId("c"), agentType = "general-purpose",
            fork = false, depth = 1, cacheKey = "k",
        )
        val member = foldOf(child)
        assertRejected(member, scheduled(), "只能建在根会话里")
        assertNotNull(member.cronBoard(0, ZoneOffset.UTC).refusal)
        assertNull(foldOf().cronBoard(0, ZoneOffset.UTC).refusal)
    }

    @Test
    fun `the tool view carries tasks, next id and clock`() {
        val state = foldOf(scheduled(), CronCancelled("c1"), scheduled(id = "c2"))
        val board = state.cronBoard(42, ZoneOffset.UTC)
        assertEquals(listOf("c2"), board.active.map { it.id })
        assertEquals("c3", board.nextId)
        assertEquals(42L, board.nowMs)
        assertEquals(CronStatus.Cancelled, board["c1"]!!.status)
    }
}
