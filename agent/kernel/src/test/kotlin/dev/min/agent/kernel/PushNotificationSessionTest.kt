package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PushSink
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.PushNotificationTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** PushNotification 的内核一侧：模型调用经 ToolContext.push 落到宿主的出口，结果照实回给模型 */
class PushNotificationSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class Recording(var accept: Boolean = true) : PushSink {
        val sent = mutableListOf<Pair<String?, String>>()
        override suspend fun push(title: String?, message: String): Boolean {
            sent += title to message
            return accept
        }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private fun session(model: ScriptedModel, sink: PushSink): SessionHub =
        SessionHub(store, testDeps(model, listOf(PushNotificationTool), pushSink = sink), newScope())

    private val push = ToolCallPart(CallId("p1"), "PushNotification", args("message" to "构建好了", "title" to "CI"))

    @Test
    fun `a notification the model asks for reaches the host sink, and the model is told it went out`() = run {
        val sink = Recording()
        val model = ScriptedModel(reply(push, stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = session(model, sink).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.send(ClientMsgId("u1"), listOf(TextPart("跑完了就通知我")))
        rt.awaitIdle()

        assertEquals(listOf<Pair<String?, String>>("CI" to "构建好了"), sink.sent)
        val outcome = events(rt).filterIsInstance<ToolOutcome>().single()
        assertFalse(outcome.result.isError)
        assertTrue(outcome.result.content.filterIsInstance<TextPart>().single().text.contains("已发出"))
    }

    @Test
    fun `a host that cannot notify gives the model an error result, not a success`() = run {
        val sink = Recording(accept = false)
        val model = ScriptedModel(reply(push, stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = session(model, sink).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.send(ClientMsgId("u1"), listOf(TextPart("跑完了就通知我")))
        rt.awaitIdle()

        val outcome = events(rt).filterIsInstance<ToolOutcome>().single()
        assertTrue(outcome.result.isError)
    }
}
