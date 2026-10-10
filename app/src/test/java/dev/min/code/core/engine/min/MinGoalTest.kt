package dev.min.code.core.engine.min

import dev.min.agent.kernel.GoalState
import dev.min.code.core.engine.GoalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 内核的目标状态 -> 界面的 [dev.min.code.core.engine.GoalView]：状态的先后与字段的搬运 */
class MinGoalTest {
    @Test fun `no goal in progress maps to no view`() {
        assertNull(minGoalView(GoalState()))
    }

    @Test fun `an active goal carries its condition, evaluations and latest reason`() {
        val view = minGoalView(GoalState(condition = "测试全绿", turns = 3, lastReason = "还有一个用例红"))!!
        assertEquals("测试全绿", view.condition)
        assertEquals(3, view.evaluations)
        assertEquals("还有一个用例红", view.lastReason)
        assertEquals(GoalStatus.Active, view.status)
        assertNull(view.pausedReason)
    }

    @Test fun `a goal waiting on background work is deferred`() {
        assertEquals(GoalStatus.Deferred, minGoalView(GoalState(condition = "A", waitSinceMs = 5L))!!.status)
    }

    @Test fun `a paused goal says why, and pausing wins over deferral`() {
        val view = minGoalView(GoalState(condition = "A", waitSinceMs = 5L, paused = "速率限制"))!!
        assertEquals(GoalStatus.Paused, view.status)
        assertEquals("速率限制", view.pausedReason)
    }

    @Test fun `a goal that already ended is not shown as in progress`() {
        val ended = GoalState(lastEnded = dev.min.agent.kernel.GoalRecord("A", dev.min.agent.spi.GoalEndReason.Achieved, 2, null))
        assertNull(minGoalView(ended))
    }
}
