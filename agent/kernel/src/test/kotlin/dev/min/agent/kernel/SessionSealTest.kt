package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 卸下会话之前的封存：卸下之后旧的运行时对象可能还被别的协程拿着（例如正要交付子 agent 报告的），
 * 而再次打开会建一个新的运行时写同一份日志——两个写者会把日志写坏。封住之后一条都不能写。
 */
class SessionSealTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun report(n: Int) = InputOrigin.Agent(SessionId("child"), AgentInputKind.Report, TurnId("t$n"))

    @Test
    fun `a sealed runtime writes nothing and starts no turn`() = run {
        val hub = SessionHub(store, testDeps(ScriptedModel(reply(TextPart("一"))), emptyList()), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.send(ClientMsgId("u1"), listOf(TextPart("第一句")))
        rt.awaitIdle()
        val before = store.open(rt.sessionId)!!.read().toList().size
        assertTrue(hub.close(rt.sessionId))
        // 卸下之后还拿着它的人（迟到的报告交付、界面残留的引用）
        assertFalse(rt.send(ClientMsgId("u2"), listOf(TextPart("迟到的话"))))
        assertFalse(rt.receive(ClientMsgId("r1"), listOf(TextPart("报告")), Delivery.Steer, report(1), startTurn = TurnTrigger.Notification))
        delay(100)
        assertEquals("日志一条都没多", before, store.open(rt.sessionId)!!.read().toList().size)
        // 重新打开的是新的运行时，日志完好、照常能用
        val reopened = hub.open(rt.sessionId)!!
        assertEquals(before.toLong(), reopened.state.value.lastSeq)
    }

    @Test
    fun `a runtime with a turn running cannot be sealed, and the hub refuses to close it`() = run {
        val hold = CompletableDeferred<Unit>()
        val model = ScriptedModel({ request -> flow { hold.await(); emitAll(reply(TextPart("好"))(request)) } })
        val hub = SessionHub(store, testDeps(model, emptyList()), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.send(ClientMsgId("u1"), listOf(TextPart("说")))
        assertFalse(rt.sealIfIdle())
        assertFalse(hub.close(rt.sessionId))
        // 没封住就不能留下封存的痕迹：照常收输入
        assertTrue(rt.send(ClientMsgId("u2"), listOf(TextPart("再说")), Delivery.Queue))
        hold.complete(Unit)
        rt.interrupt()
        rt.awaitIdle()
    }
}
