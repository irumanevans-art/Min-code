package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.spi.GoalCheckIn
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GoalVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/goal` 的纯逻辑：折叠、参数解读、check-in 节奏、评估的推迟与处理器形状（不跑模型，见 GoalSessionTest） */
class GoalTest {
    private fun fold(vararg events: dev.min.agent.spi.SessionEvent): GoalState =
        events.fold(GoalState()) { state, e -> state.applyGoal(e) }

    @Test
    fun `setting a goal starts it at zero turns and evaluations count turns`() {
        val state = fold(GoalSet("测试全绿"), GoalEvaluated(GoalVerdict.NotMet, "还有红的"), GoalEvaluated(GoalVerdict.NotMet, "还差一个"))
        assertEquals("测试全绿", state.condition)
        assertEquals(2, state.turns)
        assertEquals("还差一个", state.lastReason)
    }

    @Test
    fun `a goal that is achieved clears and is remembered as the last ended goal`() {
        val state = fold(GoalSet("lint 干净"), GoalEvaluated(GoalVerdict.Met), GoalCleared(GoalEndReason.Achieved))
        assertNull(state.condition)
        assertEquals(GoalEndReason.Achieved, state.lastEnded?.reason)
        assertEquals("lint 干净", state.lastEnded?.condition)
        assertEquals(1, state.lastEnded?.turns)
    }

    @Test
    fun `a goal the user clears is not remembered as achieved`() {
        val state = fold(GoalSet("A"), GoalCleared(GoalEndReason.User))
        assertNull(state.condition)
        assertEquals(GoalEndReason.User, state.lastEnded?.reason)
    }

    @Test
    fun `clearing when nothing is active changes nothing`() {
        val before = fold(GoalSet("A"), GoalCleared(GoalEndReason.Achieved))
        assertEquals(before, before.applyGoal(GoalCleared(GoalEndReason.User)))
    }

    @Test
    fun `resuming keeps the condition and resets turns and check-ins`() {
        val state = fold(GoalSet("A"), GoalEvaluated(GoalVerdict.NotMet), GoalCheckIn(2), GoalResumed(9L))
        assertEquals("A", state.condition)
        assertEquals(0, state.turns)
        assertEquals(0, state.checkIns)
    }

    @Test
    fun `every clear word clears the goal and the empty argument shows`() {
        val clearWords = listOf("clear", "stop", "off", "reset", "none", "cancel")
        for (word in clearWords) {
            assertEquals("词 $word", GoalCommand.Clear, parseGoalCommand(word))
            assertEquals("大写 $word", GoalCommand.Clear, parseGoalCommand(word.uppercase()))
        }
        assertEquals(GoalCommand.Show, parseGoalCommand("   "))
        assertEquals(GoalCommand.Set("测试全绿 and lint 干净"), parseGoalCommand("  测试全绿 and lint 干净 "))
    }

    @Test
    fun `check-ins back off thirty minutes then one hour then two hours`() {
        val minute = 60_000L
        assertEquals(30 * minute, GoalCheckInPolicy.gapMs(1, 30))
        assertEquals(60 * minute, GoalCheckInPolicy.gapMs(2, 30))
        assertEquals(120 * minute, GoalCheckInPolicy.gapMs(3, 30))
        assertEquals("封顶为第一次的 4 倍", 120 * minute, GoalCheckInPolicy.gapMs(4, 30))
    }

    @Test
    fun `zero minutes turns check-ins off and at most three are allowed`() {
        assertNull(GoalCheckInPolicy.gapMs(1, 0))
        assertFalse(GoalCheckInPolicy.mayCheckIn(0, 0))
        assertTrue(GoalCheckInPolicy.mayCheckIn(2, 30))
        assertFalse(GoalCheckInPolicy.mayCheckIn(3, 30))
    }

    @Test
    fun `background work defers the evaluation`() {
        assertTrue(goalEvaluationDeferred(1))
        assertFalse(goalEvaluationDeferred(0))
    }

    @Test
    fun `the evaluator is a continue-on-block prompt Stop handler carrying the condition`() {
        val handler = goalEvaluationHandler("所有测试通过")
        assertEquals(HookEvent.Stop, handler.event)
        assertEquals("prompt", handler.type)
        assertTrue(handler.continueOnBlock)
        assertTrue(handler.prompt!!.contains("所有测试通过"))
        assertTrue(handler.prompt!!.contains("\$ARGUMENTS"))
    }
}
