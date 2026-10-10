package dev.min.agent.cli

import dev.min.agent.kernel.GoalRecord
import dev.min.agent.kernel.GoalState
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalPaused
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GoalVerdict
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/goal` 的命令行口径：状态文字、参数的取法、`-p` 跑完后没达成的说明（纯函数，不跑模型；端到端见 HeadlessCompatTest） */
class GoalCliTest {
    private fun record(event: SessionEvent, seq: Long = 1L) = EventRecord(seq, 0L, event)

    @Test
    fun `no goal and nothing achieved says No goal set`() {
        assertEquals("No goal set", goalStatusText(GoalState()))
    }

    @Test
    fun `an active goal shows its condition, evaluations and the latest reason`() {
        val text = goalStatusText(GoalState(condition = "测试全绿", turns = 3, lastReason = "还有一个用例红"))
        assertTrue(text, text.startsWith("◎ /goal active：测试全绿"))
        assertTrue(text, text.contains("已评估 3 次"))
        assertTrue(text, text.contains("最近理由：还有一个用例红"))
    }

    @Test
    fun `a paused goal says why and that the next message resumes it`() {
        val text = goalStatusText(GoalState(condition = "A", paused = "速率限制"))
        assertTrue(text, text.contains("目标已暂停：速率限制"))
        assertTrue(text, text.contains("下一句话"))
    }

    @Test
    fun `a goal waiting on background work says evaluation is deferred`() {
        val text = goalStatusText(GoalState(condition = "A", waitSinceMs = 5L))
        assertTrue(text, text.contains("评估推迟到它结束"))
    }

    @Test
    fun `the last ended goal is shown by how it ended`() {
        assertTrue(goalStatusText(GoalState(lastEnded = GoalRecord("A", GoalEndReason.Achieved, 2, null))).startsWith("上一个目标已达成：A（评估 2 次）"))
        assertTrue(goalStatusText(GoalState(lastEnded = GoalRecord("B", GoalEndReason.Impossible, 1, "依赖下线"))).contains("判定不可能"))
        assertTrue(goalStatusText(GoalState(lastEnded = GoalRecord("C", GoalEndReason.Error, 0, null, "认证失效"))).contains("认证失效"))
    }

    @Test
    fun `the argument is taken from the whole line only for goal`() {
        assertEquals("", goalArgumentOf("/goal"))
        assertEquals("测试全绿", goalArgumentOf("  /goal   测试全绿 "))
        assertNull(goalArgumentOf("/goals"))
        assertNull(goalArgumentOf("请 /goal 不要"))
    }

    @Test
    fun `a run that never set a goal has no goal reason`() {
        assertNull(goalUnmetReason(GoalState(condition = "A"), listOf(record(GoalEvaluated(GoalVerdict.NotMet)))))
    }

    @Test
    fun `a goal still set at the end says it stopped short, with the reason`() {
        val events = listOf(record(GoalSet("A")), record(GoalPaused("速率限制")))
        val reason = goalUnmetReason(GoalState(condition = "A", paused = "速率限制"), events)
        assertEquals("目标没有达成就停下了（A）：速率限制", reason)
    }

    @Test
    fun `an achieved goal is not a failure`() {
        val events = listOf(record(GoalSet("A")), record(GoalCleared(GoalEndReason.Achieved)))
        assertNull(goalUnmetReason(GoalState(lastEnded = GoalRecord("A", GoalEndReason.Achieved, 1, null)), events))
    }

    @Test
    fun `an impossible or unrecoverable end is reported with its reason`() {
        val events = listOf(record(GoalSet("A")), record(GoalCleared(GoalEndReason.Impossible)))
        val reason = goalUnmetReason(GoalState(lastEnded = GoalRecord("A", GoalEndReason.Impossible, 1, "下线了")), events)
        assertTrue(reason, reason!!.contains("下线了"))
    }

    @Test
    fun `input messages in the run do not count as setting a goal`() {
        val input = InputReceived(ClientMsgId("u"), emptyList(), Delivery.Queue, InputOrigin.User)
        assertNull(goalUnmetReason(GoalState(condition = "A"), listOf(record(input))))
    }
}
