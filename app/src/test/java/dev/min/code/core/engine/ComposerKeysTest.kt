package dev.min.code.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6.2 的表逐行，加 §10.2 拍板 2（■ = 全停、排队退回输入框、Esc 三档保留） */
class ComposerKeysTest {
    private val cc = EngineCapabilities.ClaudeCode
    private val codex = EngineCapabilities.Codex
    private val min = EngineCapabilities.MinAgent

    private fun keys(
        turn: TurnProgress? = null,
        resume: ResumeOffer? = null,
        viewing: AgentThread? = null,
        pending: PendingResume? = null,
        caps: EngineCapabilities = min,
        filled: Boolean = false,
        queued: Int = 0,
        stopping: Boolean = false,
        readOnly: Boolean = false,
        ready: Boolean = true,
    ) = composerKeys(turn, resume, viewing, pending, caps, filled, queued, stopping, readOnly, ready)

    // ---- 表第 1 行：回合在跑 ----

    @Test
    fun `running turn shows stop, queued send and the queue hint`() {
        val k = keys(turn = turn(), filled = true)
        assertEquals(LeftKey.Stop(StopAction.Interrupt, KeyTarget.Main, endsSession = false), k.left)
        assertEquals(SendKey(enabled = true, queued = true, target = SendTarget.Main), k.send)
        assertEquals(ComposerHint.Queue, k.hint)
        assertEquals(StopAction.Interrupt, k.esc)
    }

    @Test
    fun `send key lights up only when there is input`() {
        assertFalse(keys(turn = turn(), filled = false).send.enabled)
        assertTrue(keys(turn = turn(), filled = true).send.enabled)
    }

    // ---- 表第 2 行：可以继续 ----

    @Test
    fun `resumable turn turns the stop slot into resume and keeps send independent`() {
        val o = offer()
        val k = keys(resume = o, filled = false)
        assertEquals(LeftKey.Resume(o, KeyTarget.Main), k.left)
        assertEquals(ComposerHint.ResumeOrNew, k.hint)
        assertFalse("没字发送不亮", k.send.enabled)
        assertFalse("空闲时发送不是排队样式", k.send.queued)
        assertEquals("▶ 时 Esc 无事可做", StopAction.Nothing, k.esc)
    }

    @Test
    fun `typed text does not hide resume - both intents stay one tap away`() {
        val o = offer()
        val k = keys(resume = o, filled = true)
        assertEquals(LeftKey.Resume(o, KeyTarget.Main), k.left)
        assertTrue(k.send.enabled)
    }

    // ---- 表第 3 行：继续请求已发出、回合还没起来 ----

    @Test
    fun `resume request in flight shows the spinning ring and forbids a second press`() {
        val o = offer(id = "t9")
        val k = keys(resume = o, pending = PendingResume("t9", reconnecting = false))
        assertEquals(LeftKey.Resuming("t9", reconnecting = false, target = KeyTarget.Main), k.left)
        assertEquals(ComposerHint.ResumeOrNew, k.hint)
    }

    @Test
    fun `a pending press for an offer that is gone is ignored`() {
        val k = keys(resume = offer(id = "t10"), pending = PendingResume("t9", reconnecting = false))
        assertEquals(LeftKey.Resume(offer(id = "t10"), KeyTarget.Main), k.left)
        assertEquals(LeftKey.Hidden, keys(resume = null, pending = PendingResume("t9", false)).left)
    }

    @Test
    fun `once the turn starts the pending press no longer matters and stop takes the slot`() {
        val k = keys(turn = turn(), resume = null, pending = PendingResume("t9", false))
        assertTrue(k.left is LeftKey.Stop)
    }

    // ---- 表第 4 行：CC 需要先 --resume ----

    @Test
    fun `relaunch resume shows the reconnecting hint while pending`() {
        val o = offer(id = "t9", mode = ResumeMode.RelaunchThenPrompted)
        val pending = PendingResume.forPress(o)
        assertTrue(pending.reconnecting)
        val k = keys(resume = o, pending = pending, caps = cc)
        assertEquals(LeftKey.Resuming("t9", reconnecting = true, target = KeyTarget.Main), k.left)
        assertEquals(ComposerHint.Connecting, k.hint)
    }

    @Test
    fun `plain prompted or native resume does not claim to be reconnecting`() {
        assertFalse(PendingResume.forPress(offer(mode = ResumeMode.Prompted)).reconnecting)
        assertFalse(PendingResume.forPress(offer(mode = ResumeMode.Native)).reconnecting)
    }

    // ---- 表第 5 行：空闲、不能继续 ----

    @Test
    fun `idle without an offer collapses the slot`() {
        val k = keys()
        assertEquals(LeftKey.Hidden, k.left)
        assertEquals(ComposerHint.Ready, k.hint)
        assertEquals(SendKey(enabled = false, queued = false, target = SendTarget.Main), k.send)
    }

    @Test
    fun `an engine without resume support never shows the play key`() {
        // B8 之前的 Codex resume = None：即使状态里有 offer 也无视
        assertEquals(LeftKey.Hidden, keys(resume = offer(), caps = EngineCapabilities.CodexWithoutInterrupt).left)
        assertTrue(keys(resume = offer(), caps = codex).left is LeftKey.Resume)
    }

    // ---- 停止的含义（§10.2 拍板 2 + Esc 三档） ----

    @Test
    fun `nothing produced yet withdraws the in-flight message on engines that can`() {
        assertEquals(StopAction.Withdraw, keys(turn = turn(produced = false), caps = cc).esc)
    }

    @Test
    fun `nothing produced on an engine that cannot withdraw is a plain interrupt`() {
        assertEquals(StopAction.Interrupt, keys(turn = turn(produced = false), caps = min).esc)
        assertEquals(StopAction.Interrupt, keys(turn = turn(produced = false), caps = codex).esc)
    }

    @Test
    fun `stop with queued input defaults to interrupt and returning the queue to the composer`() {
        val k = keys(turn = turn(), queued = 2, caps = cc)
        assertEquals(StopAction.InterruptReturnQueued, k.left.let { (it as LeftKey.Stop).action })
        assertTrue("显式的「打断后跑排队」那一档可选", k.canRunQueuedAfterStop)
    }

    @Test
    fun `queued input wins over withdraw even when nothing was produced`() {
        assertEquals(StopAction.InterruptReturnQueued, keys(turn = turn(produced = false), queued = 1, caps = cc).esc)
    }

    @Test
    fun `the explicit run-queued tier is not offered when there is nothing queued`() {
        assertFalse(keys(turn = turn(), queued = 0).canRunQueuedAfterStop)
        assertFalse(keys(resume = offer()).canRunQueuedAfterStop)
    }

    @Test
    fun `an engine that cannot withdraw queued messages cannot return them either`() {
        val noWithdraw = min.copy(withdrawQueued = false)
        assertEquals(StopAction.Interrupt, keys(turn = turn(), queued = 3, caps = noWithdraw).esc)
    }

    @Test
    fun `stop that ends the whole session is flagged when turn interrupt is not wired`() {
        val left = keys(turn = turn(), caps = EngineCapabilities.CodexWithoutInterrupt).left as LeftKey.Stop
        assertTrue(left.endsSession)
        assertFalse("B8 起 Codex 的 ■ 只停本轮", (keys(turn = turn(), caps = codex).left as LeftKey.Stop).endsSession)
        assertFalse((keys(turn = turn(), caps = cc).left as LeftKey.Stop).endsSession)
    }

    @Test
    fun `stopActionFor agrees with composerKeys`() {
        for (caps in listOf(cc, codex, min)) for (produced in listOf(true, false)) for (queued in 0..1) {
            val t = turn(produced = produced)
            assertEquals(stopActionFor(t, queued, caps), keys(turn = t, queued = queued, caps = caps).esc)
        }
        assertEquals(StopAction.Nothing, stopActionFor(null, 0, cc))
    }

    // ---- 子 agent 页 ----

    @Test
    fun `viewing a running subagent maps stop to StopSubagent and send to that thread`() {
        val t = thread("th-1", phase = AgentThread.Phase.Running)
        val k = keys(viewing = t, filled = true, turn = turn())
        assertEquals(LeftKey.Stop(StopAction.StopSubagent, KeyTarget.Thread("th-1"), endsSession = false), k.left)
        assertEquals(SendKey(enabled = true, queued = true, target = SendTarget.Subagent("th-1")), k.send)
        assertEquals(StopAction.StopSubagent, k.esc)
        assertEquals(ComposerHint.Queue, k.hint)
    }

    @Test
    fun `main turn state does not leak into the subagent page`() {
        val t = thread("th-1", phase = AgentThread.Phase.Done)
        assertEquals(LeftKey.Hidden, keys(viewing = t, turn = turn()).left)
    }

    @Test
    fun `a stopped subagent offers its own resume`() {
        val o = offer(id = "child-t")
        val t = thread("th-1", phase = AgentThread.Phase.Interrupted, resume = o)
        val k = keys(viewing = t)
        assertEquals(LeftKey.Resume(o, KeyTarget.Thread("th-1")), k.left)
        assertEquals(ComposerHint.ResumeOrNew, k.hint)
        val pending = keys(viewing = t, pending = PendingResume("child-t", false)).left
        assertEquals(LeftKey.Resuming("child-t", false, KeyTarget.Thread("th-1")), pending)
    }

    @Test
    fun `a subagent that cannot be messaged keeps send disabled`() {
        val t = thread("th-1", phase = AgentThread.Phase.Done, messageable = false)
        val k = keys(viewing = t, filled = true)
        assertFalse(k.send.enabled)
        assertEquals(ComposerHint.NotReady, k.hint)
    }

    @Test
    fun `engines without subagents never show StopSubagent`() {
        val t = thread("th-1", phase = AgentThread.Phase.Running)
        assertEquals(LeftKey.Hidden, keys(viewing = t, caps = codex).left)
    }

    // ---- 整体不可用 ----

    @Test
    fun `stopping read-only or not-ready hides every key and disables send`() {
        for (k in listOf(
            keys(turn = turn(), filled = true, stopping = true),
            keys(resume = offer(), filled = true, readOnly = true),
            keys(turn = turn(), filled = true, ready = false),
        )) {
            assertEquals(LeftKey.Hidden, k.left)
            assertFalse(k.send.enabled)
            assertEquals(ComposerHint.NotReady, k.hint)
            assertEquals(StopAction.Nothing, k.esc)
        }
    }

    @Test
    fun `a running turn beats a stale offer`() {
        val k = keys(turn = turn(), resume = offer())
        assertTrue(k.left is LeftKey.Stop)
    }
}
