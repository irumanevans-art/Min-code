package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.GoalCheckIn
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalDeferred
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalPaused
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GoalVerdict
import dev.min.agent.spi.HooksDisabledBy
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Note
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/goal` 的纯逻辑：折叠、参数解读、check-in 节奏、防空转、错误分类、评估对话的截断、hooks 禁用的拒绝（不跑模型，见 GoalSessionTest） */
class GoalTest {
    private fun fold(vararg events: SessionEvent): GoalState =
        events.fold(GoalState()) { state, e -> state.applyGoal(e) }

    private val minute = 60_000L

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
    fun `an unrecoverable error keeps its detail in the last ended goal`() {
        val state = fold(GoalSet("A"), GoalCleared(GoalEndReason.Error, "认证失效"))
        assertEquals("认证失效", state.lastEnded?.detail)
    }

    @Test
    fun `clearing when nothing is active changes nothing`() {
        val before = fold(GoalSet("A"), GoalCleared(GoalEndReason.Achieved))
        assertEquals(before, before.applyGoal(GoalCleared(GoalEndReason.User)))
    }

    @Test
    fun `resuming keeps the condition and resets turns, check-ins and the wait`() {
        val state = fold(GoalSet("A"), GoalEvaluated(GoalVerdict.NotMet), GoalDeferred(5L), GoalCheckIn(2, 6L, idle = true), GoalResumed(9L))
        assertEquals("A", state.condition)
        assertEquals(0, state.turns)
        assertEquals(0, state.checkIns)
        assertEquals(0, state.idleCheckIns)
        assertNull(state.waitSinceMs)
    }

    @Test
    fun `a user message clears the check-in counts and resumes a paused goal`() {
        val user = InputReceived(ClientMsgId("u1"), listOf(TextPart("继续")), Delivery.Queue, InputOrigin.User)
        val state = fold(GoalSet("A"), GoalCheckIn(3, 1L, idle = true), GoalPaused("速率限制"), user)
        assertEquals(0, state.checkIns)
        assertEquals(0, state.idleCheckIns)
        assertNull("暂停的目标在用户下一句话时恢复", state.paused)
    }

    @Test
    fun `the first deferral starts the wait and later ones do not move it`() {
        val state = fold(GoalSet("A"), GoalDeferred(100L), GoalDeferred(200L))
        assertEquals(100L, state.waitSinceMs)
    }

    @Test
    fun `an evaluation ends the wait and a pause`() {
        val state = fold(GoalSet("A"), GoalDeferred(100L), GoalPaused("x"), GoalEvaluated(GoalVerdict.NotMet, "y"))
        assertNull(state.waitSinceMs)
        assertNull(state.paused)
    }

    @Test
    fun `idle rounds count consecutive turns without tool use and restart after a tool round`() {
        val a = fold(GoalSet("A"), GoalEvaluated(GoalVerdict.NotMet), GoalEvaluated(GoalVerdict.NotMet))
        assertEquals(2, a.idleRounds)
        val b = a.applyGoal(GoalEvaluated(GoalVerdict.NotMet, toolUse = true))
        assertEquals("调过工具之后这一轮从 1 重新数", 1, b.idleRounds)
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
        assertEquals(30 * minute, GoalCheckInPolicy.gapMs(1, 30))
        assertEquals(60 * minute, GoalCheckInPolicy.gapMs(2, 30))
        assertEquals(120 * minute, GoalCheckInPolicy.gapMs(3, 30))
        assertEquals("封顶为第一次的 4 倍", 120 * minute, GoalCheckInPolicy.gapMs(4, 30))
    }

    @Test
    fun `the next check-in is due one gap after the wait started or the last check-in`() {
        val waiting = fold(GoalSet("A"), GoalDeferred(1_000L))
        assertEquals(1_000L + 30 * minute, GoalCheckInPolicy.dueAtMs(waiting, 30))
        val afterFirst = waiting.applyGoal(GoalCheckIn(1, 1_000L + 30 * minute))
        assertEquals("第二次等两倍，从第一次送出的时刻数", 1_000L + 30 * minute + 60 * minute, GoalCheckInPolicy.dueAtMs(afterFirst, 30))
        assertFalse(GoalCheckInPolicy.isDue(afterFirst, 30, 1_000L + 30 * minute + 59 * minute))
        assertTrue(GoalCheckInPolicy.isDue(afterFirst, 30, 1_000L + 30 * minute + 60 * minute))
    }

    @Test
    fun `zero minutes turns check-ins off and at most three idle ones are allowed`() {
        assertNull(GoalCheckInPolicy.gapMs(1, 0))
        assertFalse(GoalCheckInPolicy.mayIdleCheckIn(GoalState(condition = "A"), 0))
        assertTrue(GoalCheckInPolicy.mayIdleCheckIn(fold(GoalSet("A"), GoalCheckIn(2, 1L, idle = true)), 30))
        val three = fold(GoalSet("A"), GoalCheckIn(1, 1L, idle = true), GoalCheckIn(2, 2L, idle = true), GoalCheckIn(3, 3L, idle = true))
        assertFalse(GoalCheckInPolicy.mayIdleCheckIn(three, 30))
    }

    @Test
    fun `turn-end check-ins do not use up the idle allowance`() {
        val state = fold(GoalSet("A"), GoalCheckIn(1, 1L, idle = false), GoalCheckIn(2, 2L, idle = false), GoalCheckIn(3, 3L, idle = false))
        assertEquals(0, state.idleCheckIns)
        assertTrue(GoalCheckInPolicy.mayIdleCheckIn(state, 30))
    }

    @Test
    fun `the env var sets the first interval and zero or junk is read as stated`() {
        assertEquals(30L, goalCheckInMinutes { null })
        assertEquals(0L, goalCheckInMinutes { "0" })
        assertEquals(5L, goalCheckInMinutes { " 5 " })
        assertEquals("不是整数按默认", 30L, goalCheckInMinutes { "半小时" })
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
        assertTrue("评估器被告知对话在 conversation 字段里", handler.prompt!!.contains("conversation"))
    }

    // ---- 评估器看到的对话 ----

    private fun user(text: String) = ModelMessage(Role.User, listOf(TextPart(text)))
    private fun assistant(text: String) = ModelMessage(Role.Assistant, listOf(TextPart(text)))

    @Test
    fun `a short transcript is the whole conversation in order`() {
        val text = goalTranscript(listOf(user("第一句：把测试修绿"), assistant("好，我先看看")))
        assertTrue(text.startsWith("[用户]\n第一句：把测试修绿"))
        assertTrue(text.indexOf("第一句") < text.indexOf("好，我先看看"))
    }

    @Test
    fun `a long tool result is cut to its head and says how much was left out`() {
        val long = "x".repeat(GOAL_TOOL_RESULT_KEEP + 500)
        val text = goalTranscript(listOf(ModelMessage(Role.User, listOf(ToolResultPart(CallId("c1"), listOf(TextPart(long)))))))
        assertTrue(text.contains("省略 500 字"))
        assertTrue("结果的开头保留", text.contains("x".repeat(GOAL_TOOL_RESULT_KEEP)))
        assertFalse(text.contains("x".repeat(GOAL_TOOL_RESULT_KEEP + 1)))
    }

    @Test
    fun `a tool call keeps only the start of its input`() {
        val input = JsonObject(mapOf("command" to JsonPrimitive("gradle test " + "y".repeat(400))))
        val text = goalTranscript(listOf(ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("c1"), "Bash", input)))))
        assertTrue(text.contains("调用工具 Bash"))
        assertFalse(text.contains("y".repeat(300)))
    }

    @Test
    fun `over budget the transcript keeps the first user message and the newest ones, and marks the gap`() {
        val messages = mutableListOf(user("完成条件：测试全绿"))
        repeat(40) { i -> messages += assistant("中间第 $i 条：" + "填".repeat(200)) }
        messages += assistant("最后结论：全绿了")
        val text = goalTranscript(messages, budgetChars = 2_000)
        assertTrue("条件在开头", text.startsWith("[用户]\n完成条件：测试全绿"))
        assertTrue("最近的内容在末尾", text.contains("最后结论：全绿了"))
        assertTrue("中间有省略标记", Regex("省略了 \\d+ 条消息").containsMatchIn(text))
        assertTrue("不超出预算", text.length <= 2_000)
    }

    @Test
    fun `a single over-long newest message keeps its end where the conclusion usually is`() {
        val messages = listOf(user("条件"), assistant("开头" + "中".repeat(5_000) + "结论在这里"))
        val text = goalTranscript(messages, budgetChars = 1_000)
        assertTrue(text.endsWith("结论在这里"))
        assertTrue(text.length <= 1_000)
    }

    // ---- 错误的分类与处理 ----

    @Test
    fun `auth, unavailable model and context overflow are unrecoverable`() {
        assertTrue(unrecoverableFailure(ModelFailureKind.Auth, "401"))
        assertTrue(unrecoverableFailure(ModelFailureKind.ModelUnavailable, "404"))
        assertTrue(unrecoverableFailure(ModelFailureKind.ContextOverflow, "太长"))
    }

    @Test
    fun `an exhausted credit balance is unrecoverable whatever kind the provider sent it as`() {
        assertTrue(unrecoverableFailure(ModelFailureKind.BadRequest, "Your credit balance is too low"))
        assertTrue(unrecoverableFailure(ModelFailureKind.BadRequest, "insufficient_quota"))
        assertFalse(unrecoverableFailure(ModelFailureKind.BadRequest, "参数不对"))
    }

    @Test
    fun `rate limits and other errors are not unrecoverable`() {
        assertFalse(unrecoverableFailure(ModelFailureKind.RateLimited, "429"))
        assertFalse(unrecoverableFailure(ModelFailureKind.Overloaded, "过载"))
        assertFalse(unrecoverableFailure(null, "回合超过上限"))
    }

    @Test
    fun `an unrecoverable failure clears the goal with its reason and a warning, others pause it`() {
        val cleared = goalFailureEvents("A", ModelFailureKind.Auth, "认证失效")
        assertEquals(GoalCleared(GoalEndReason.Error, "认证失效"), cleared.first())
        val note = cleared.last() as Note
        assertTrue(note.text.startsWith("Goal cleared after an unrecoverable error"))
        assertTrue(note.text.contains("/goal <条件>"))

        val paused = goalFailureEvents("A", ModelFailureKind.RateLimited, "速率限制")
        assertEquals(GoalPaused("速率限制"), paused.first())
        assertTrue((paused.last() as Note).text.startsWith("Goal paused"))
    }

    @Test
    fun `no goal means no failure events`() {
        assertTrue(goalFailureEvents(null, ModelFailureKind.Auth, "认证失效").isEmpty())
    }

    // ---- hooks 禁用时拒绝 ----

    @Test
    fun `hooks off on purpose refuse the goal and say why, for each reason`() {
        assertNull(goalRefusal(null))
        assertTrue(goalRefusal(HooksDisabledBy.DisableAllHooks)!!.contains("disableAllHooks"))
        assertTrue(goalRefusal(HooksDisabledBy.ManagedOnly)!!.contains("allowManagedHooksOnly"))
    }
}
