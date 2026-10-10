package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.UserFile
import dev.min.agent.spi.UserFileDisplay
import dev.min.agent.spi.UserFileSink
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.SendUserFileTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SendUserFile 的内核一侧：读的是会话的工作区文件，交给的是宿主的 [UserFileSink]，结果照实回给模型 */
class SendUserFileSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class Recording(var accept: Boolean = true) : UserFileSink {
        val got = mutableListOf<UserFile>()
        override suspend fun deliver(file: UserFile): Boolean {
            got += file
            return accept
        }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private fun callSend(path: String) = ToolCallPart(
        CallId("f1"), "SendUserFile",
        args("files" to JsonArray(listOf(JsonPrimitive(path))), "caption" to "周报", "display" to "render"),
    )

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u1"), listOf(TextPart(text)))
        awaitIdle()
    }

    @Test
    fun `a workspace file the model sends reaches the host sink with its bytes, caption and display`() = run {
        val fs = FakeFs().also { it.put("/workspace/out/report.md", "# 周报\n") }
        val sink = Recording()
        val model = ScriptedModel(reply(callSend("/workspace/out/report.md"), stop = StopReason.ToolUse), reply(TextPart("发好了")))
        val hub = SessionHub(store, testDeps(model, listOf(SendUserFileTool), fs = fs, userFileSink = sink), newScope())
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("把周报发给我")

        val f = sink.got.single()
        assertEquals("report.md", f.name)
        assertEquals("/workspace/out/report.md", f.path.value)
        assertEquals("周报", f.caption)
        assertEquals(UserFileDisplay.Render, f.display)
        assertEquals("# 周报\n", f.bytes.decodeToString())
        val outcome = events(rt).filterIsInstance<ToolOutcome>().single()
        assertFalse(outcome.result.isError)
    }

    @Test
    fun `a sink that cannot take the file gives the model an error result`() = run {
        val fs = FakeFs().also { it.put("/workspace/a.md", "x") }
        val sink = Recording(accept = false)
        val model = ScriptedModel(reply(callSend("/workspace/a.md"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val hub = SessionHub(store, testDeps(model, listOf(SendUserFileTool), fs = fs, userFileSink = sink), newScope())
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("发给我")

        assertTrue(events(rt).filterIsInstance<ToolOutcome>().single().result.isError)
    }
}
