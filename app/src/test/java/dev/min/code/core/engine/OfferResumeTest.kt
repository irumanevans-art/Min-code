package dev.min.code.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §6.1「出现条件」「不出现」逐条 */
class OfferResumeTest {
    private val min = EngineCapabilities.MinAgent
    private val cc = EngineCapabilities.ClaudeCode

    private fun offer(
        caps: EngineCapabilities = min,
        running: Boolean = false,
        stopping: Boolean = false,
        canRun: Boolean = true,
        end: TurnEnd? = TurnEnd.Interrupted(InterruptCause.User),
        tail: HistoryTail = HistoryTail.UserMessage,
        pending: Int = 0,
        relaunch: Boolean = false,
    ) = offerResume(caps, running, stopping, canRun, end, since = 700L, tail = tail, pendingInputs = pending, offerId = "t7", needsRelaunch = relaunch)

    // ---- 条件 1：没有回合在跑，也没有正在关闭 ----

    @Test
    fun `no offer while a turn runs or the session is closing`() {
        assertNull(offer(running = true))
        assertNull(offer(stopping = true))
    }

    // ---- 条件 2：结局 ----

    @Test
    fun `user stop, failure, process death, engine exit and limit all offer resume`() {
        assertEquals(ResumeReason.UserStopped, offer(end = TurnEnd.Interrupted(InterruptCause.User))?.reason)
        assertEquals(ResumeReason.Failed(FailureKind.Auth), offer(end = TurnEnd.Failed(FailureKind.Auth, "401"))?.reason)
        assertEquals(ResumeReason.Failed(null), offer(end = TurnEnd.Failed(null, "x"))?.reason)
        assertEquals(ResumeReason.ProcessDeath(700L), offer(end = TurnEnd.Interrupted(InterruptCause.ProcessDeath))?.reason)
        assertEquals(ResumeReason.EngineExited, offer(end = TurnEnd.Interrupted(InterruptCause.EngineExited))?.reason)
        assertEquals(ResumeReason.Limit, offer(end = TurnEnd.Interrupted(InterruptCause.Limit))?.reason)
    }

    @Test
    fun `a completed turn never offers resume`() {
        assertNull(offer(end = TurnEnd.Completed))
        assertNull(offer(end = null))
    }

    @Test
    fun `a withdrawn turn never offers resume - the message already went back to the composer`() {
        assertNull(offer(caps = cc, end = TurnEnd.Interrupted(InterruptCause.Withdrawn)))
    }

    // ---- 条件 3：模型还欠一个回答 ----

    @Test
    fun `the model owes an answer after a user message, tool results or an assistant message with calls`() {
        for (tail in listOf(HistoryTail.UserMessage, HistoryTail.ToolResults, HistoryTail.AssistantWithToolCalls)) {
            assertEquals("tail=$tail", "t7", offer(tail = tail)?.offerId)
        }
    }

    @Test
    fun `a complete final answer does not offer resume - it would become an assistant prefill`() {
        assertNull(offer(tail = HistoryTail.AssistantFinal))
        assertNull(offer(tail = HistoryTail.Empty))
    }

    @Test
    fun `a complete final answer with queued inputs does offer resume - resuming processes the inbox`() {
        val o = offer(tail = HistoryTail.AssistantFinal, pending = 1)
        assertEquals(1, o?.pendingInputs)
    }

    @Test
    fun `pending inputs are counted on an ordinary offer too`() {
        assertEquals(2, offer(pending = 2)?.pendingInputs)
    }

    // ---- 条件 4：引擎与会话 ----

    @Test
    fun `an engine without resume support never offers it`() {
        assertNull(offer(caps = EngineCapabilities.Codex))
        assertEquals("t7", offer(caps = EngineCapabilities.CodexWithTurnInterrupt)?.offerId)
    }

    @Test
    fun `a session that cannot run any more offers nothing - provider removed or engine unavailable`() {
        assertNull(offer(canRun = false))
    }

    // ---- mode ----

    @Test
    fun `native engines resume natively, prompted ones prompt, and a dead process needs a relaunch first`() {
        assertEquals(ResumeMode.Native, offer(caps = min)?.mode)
        assertEquals(ResumeMode.Prompted, offer(caps = cc)?.mode)
        assertEquals(ResumeMode.RelaunchThenPrompted, offer(caps = cc, relaunch = true, end = TurnEnd.Interrupted(InterruptCause.EngineExited))?.mode)
        // Native 的引擎没有「先接回」这回事
        assertEquals(ResumeMode.Native, offer(caps = min, relaunch = true)?.mode)
    }

    @Test
    fun `the offer carries when it stopped and a brief for notifications`() {
        val o = offer()!!
        assertEquals(700L, o.since)
        assertEquals(ResumeBrief("t7", ResumeReason.UserStopped), o.brief())
    }

    @Test
    fun `the continue prompt matches the official CLI default`() {
        assertEquals("Continue from where you left off.", CONTINUE_PROMPT)
    }
}
