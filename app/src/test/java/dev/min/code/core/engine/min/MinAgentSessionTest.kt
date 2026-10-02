package dev.min.code.core.engine.min

import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话包装：订阅在对象造出来时就接上，状态 / 流 / 摘要对着真内核一起走 */
class MinAgentSessionTest {
    private var kernel: TestKernel? = null
    @After fun tearDown() { kernel?.close() }

    private fun session(model: dev.min.agent.llm.ModelClient): MinAgentSession {
        val k = TestKernel(model).also { kernel = it }
        return MinAgentSession(runBlocking { k.newRoot() }, FakeHost(k), k.scope)
    }

    @Test fun stateHasAnInitialValueRightAfterConstruction() {
        val s = session(ScriptedModel())
        assertEquals("m1", s.state.value.settings.model!!.id)
        assertEquals("/workspace", s.state.value.cwd)
        assertTrue(s.state.value.items.isEmpty()); assertNull(s.state.value.turn)
    }

    @Test fun streamShowsTheDeltaWhileGeneratingAndClearsOnceTheAnswerIsSaved() {
        val gate = CompletableDeferred<Unit>()
        val s = session(ScriptedModel(gated(gate, "typing...")))
        runBlocking { s.submit(SessionCommand.Send("a", UserInput("go"))) }
        assertEquals("typing...", awaitValue { s.stream.value.text.takeIf { it.isNotEmpty() } })
        // 正在生成的那块不在 items 里
        assertTrue(s.state.value.items.none { it is dev.min.code.core.session.ChatItem.AssistantText })
        gate.complete(Unit)
        runBlocking { s.runtime.awaitIdle() }
        awaitValue { s.state.value.takeIf { st -> st.turn == null && st.items.any { it is dev.min.code.core.session.ChatItem.AssistantText } } }
        awaitValue { s.stream.value.takeIf { it.isEmpty } }
    }

    @Test fun summaryKeepsTheProcessAliveOnlyWhileATurnRuns() {
        val gate = CompletableDeferred<Unit>()
        val s = session(ScriptedModel(gated(gate)))
        assertFalse(s.summary.value.keepAlive)
        runBlocking { s.submit(SessionCommand.Send("a", UserInput("go"))) }
        awaitValue { s.summary.value.takeIf { it.keepAlive && it.busy } }
        gate.complete(Unit)
        runBlocking { s.runtime.awaitIdle() }
        awaitValue { s.summary.value.takeIf { !it.keepAlive && !it.busy } }
        assertEquals("working", s.summary.value.lastAssistantText)
    }

    @Test fun titleOverrideFlowsIntoTheState() {
        val k = TestKernel(ScriptedModel()).also { kernel = it }
        val host = FakeHost(k)
        val s = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        host.titles.value = "My title"
        awaitValue { s.state.value.takeIf { it.title == "My title" } }
    }

    @Test fun indexIsWrittenAfterAChangeAndReleaseStopsTheCollectors() {
        val k = TestKernel(ScriptedModel(answer("a"))).also { kernel = it }
        val host = FakeHost(k)
        val s = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        runBlocking { s.submit(SessionCommand.Send("a", UserInput("go"))) }
        awaitValue(5_000) { host.indexed.lastOrNull()?.takeIf { it.lastTurn != null } }
        s.release()
        val before = host.indexed.size
        runBlocking { s.runtime.send(dev.min.agent.protocol.ClientMsgId("late"), listOf(dev.min.agent.protocol.TextPart("x"))) }
        Thread.sleep(1_400)
        assertEquals("释放后不再写索引（删除会话时靠它防止摘要被写回来）", before, host.indexed.size)
    }

    @Test fun closeDelegatesToTheEngineWithTheReason() {
        val k = TestKernel(ScriptedModel()).also { kernel = it }
        val host = FakeHost(k)
        val s = MinAgentSession(runBlocking { k.newRoot() }, host, k.scope)
        runBlocking { s.close(dev.min.code.core.engine.CloseReason.UserEnded) }
        assertEquals(listOf(dev.min.code.core.engine.CloseReason.UserEnded), host.closed)
    }

    @Test fun duplicateAcrossTheWholeSessionIsStillDetected() {
        val s = session(ScriptedModel(answer("a")))
        assertEquals(CommandResult.Accepted, runBlocking { s.submit(SessionCommand.Send("x", UserInput("go"))) })
        assertEquals(CommandResult.Duplicate, runBlocking { s.submit(SessionCommand.Send("x", UserInput("go"))) })
    }
}
