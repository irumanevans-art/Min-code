package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSnapshots
import dev.min.agent.testkit.FakeTool
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.WRITES
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.AgentToolOf
import dev.min.agent.tools.EditTool
import dev.min.agent.tools.ReadTool
import dev.min.agent.tools.WriteTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 回退的并发与边界（检查点评审的那几条）：停止键打不断恢复、回退期间来的话、子 agent 的报告、回合中途插的话、
 * 仓库坏了、快照被打断、最早那次编辑没备份、外部改动的判定。
 */
class RewindRaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()
    private val snapshots = FakeSnapshots(fs)
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private var counter = 0

    private val shell = FakeTool("Shell", WRITES) { input ->
        val path = (input["path"] as JsonPrimitive).content
        val content = (input["content"] as? JsonPrimitive)?.content
        if (content == null) fs.delete(path) else fs.put(path, content)
        ToolResult(listOf(TextPart("ok")))
    }
    private val tools: List<Tool<*>> = listOf(ReadTool, WriteTool, EditTool, shell)

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, snapshots = snapshots),
            platform = base.platform, zone = base.zone, retry = base.retry,
        )
    }

    private suspend fun session(model: ModelClient) = SessionRuntime.create(
        store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions), deps(model, tools), scope,
    )

    private suspend fun events(id: SessionId = SessionId("s1")): List<SessionEvent> = store.peek(id)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private fun write(id: String, path: String, content: String) = ToolCallPart(CallId(id), "Write", args("file_path" to path, "content" to content))
    private fun read(id: String, path: String) = ToolCallPart(CallId(id), "Read", args("file_path" to path))
    private fun edit(id: String, path: String, old: String, new: String) =
        ToolCallPart(CallId(id), "Edit", args("file_path" to path, "old_string" to old, "new_string" to new))
    private fun sh(id: String, path: String, content: String?) = ToolCallPart(CallId(id), "Shell", args("path" to path, "content" to content))
    private fun tool(vararg calls: ToolCallPart) = reply(*calls, stop = StopReason.ToolUse)
    private fun done(text: String = "done") = reply(TextPart(text))
    private fun SessionRuntime.point(index: Int) = state.value.rewindPoints[index].messageId

    /** 第一轮新建 a.txt；第二轮 Shell 改 b.txt。末尾还留一步回复给之后的一轮 */
    private suspend fun twoTurns(): SessionRuntime {
        fs.put("/workspace/b.txt", "orig")
        val rt = session(
            ScriptedModel(
                tool(write("w1", "/workspace/a.txt", "A1")), done("one"),
                tool(sh("s1", "/workspace/b.txt", "changed")), done("two"),
                done("three"),
            ),
        )
        rt.say("第一轮")
        rt.say("第二轮")
        return rt
    }

    @Test
    fun `the stop button does not cut a restore off halfway`() = run {
        val rt = twoTurns()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        snapshots.beforeRestore = { entered.complete(Unit); release.await() }
        val outcome = scope.async { rt.rewind(rt.point(1), RewindScope.Code) }
        entered.await()
        rt.interrupt()
        release.complete(Unit)
        assertTrue(outcome.await() is RewindOutcome.Done)
        assertEquals("orig", fs.text("/workspace/b.txt"))
        assertTrue(events().any { it is FilesRestored })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a message sent during a rewind waits for it, then gets its turn`() = run {
        val rt = twoTurns()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        snapshots.beforeRestore = { entered.complete(Unit); release.await() }
        val outcome = scope.async { rt.rewind(rt.point(1), RewindScope.Code) }
        entered.await()
        rt.send(ClientMsgId("u-during"), listOf(TextPart("等一下")))
        delay(50)
        assertNull("回退期间不开回合", rt.state.value.turn)
        release.complete(Unit)
        assertTrue(outcome.await() is RewindOutcome.Done)
        rt.awaitIdle()
        assertTrue("回退完接着处理那句话", rt.state.value.history.any { (it as? HistoryEntry.User)?.parts == listOf(TextPart("等一下")) })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a user message slipped into a turn the model already answered is not a rewind point`() = run {
        val hold = CompletableDeferred<Unit>()
        val model = ScriptedModel(done("one"), { r -> flow { hold.await(); emitAll(done("woke")(r)) } }, done("steer"))
        val rt = session(model)
        rt.say("第一轮")
        // 子 agent 报告唤醒的回合：开头只提升了报告；模型回话之后用户插的话是插话
        rt.receive(
            ClientMsgId("report-1"), listOf(TextPart("报告")), Delivery.Steer,
            InputOrigin.Agent(SessionId("child"), AgentInputKind.Report, TurnId("t")), startTurn = TurnTrigger.Notification,
        )
        while (rt.state.value.turn == null) delay(10)
        rt.send(ClientMsgId("u-steer"), listOf(TextPart("插一句")), Delivery.Steer)
        hold.complete(Unit)
        rt.awaitIdle()
        assertTrue(rt.state.value.history.any { (it as? HistoryEntry.User)?.parts == listOf(TextPart("插一句")) })
        assertEquals(1, rt.state.value.rewindPoints.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a broken snapshot store breaks neither the turn nor the editing tools`() = run {
        snapshots.storeFails = true
        val rt = session(ScriptedModel(tool(write("w1", "/workspace/a.txt", "A")), done()))
        rt.say("写")
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn?.outcome)
        assertEquals("A", fs.text("/workspace/a.txt"))
        assertTrue(events().none { it is CheckpointTaken })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a turn stopped while the snapshot runs still records that the checkpoint was not taken`() = run {
        val started = CompletableDeferred<Unit>()
        snapshots.beforeSnapshot = { started.complete(Unit); awaitCancellation() }
        val hang: Step = { flow { awaitCancellation() } }
        val rt = session(ScriptedModel(hang))
        rt.send(ClientMsgId("u1"), listOf(TextPart("开始")))
        started.await()
        rt.interrupt()
        rt.awaitIdle()
        val taken = events().filterIsInstance<CheckpointTaken>().single()
        assertNull(taken.manifest)
        assertTrue(taken.reason!!, taken.reason!!.contains("停下"))
        assertEquals(InterruptedBy.User, (rt.state.value.lastTurn?.outcome as TurnOutcome.Interrupted).by)
        assertLogIsTruth(rt)
    }

    @Test
    fun `when the earliest edit has no stored original the file is unrestorable, not restored to a later version`() = run {
        fs.put("/workspace/a.txt", "v0")
        snapshots.unavailable = "太大"
        snapshots.preserveFailures = 2 // 第一次编辑前后两份都没存成（宿主仓库一时写不进）
        val rt = session(
            ScriptedModel(
                tool(read("r1", "/workspace/a.txt")),
                tool(edit("e1", "/workspace/a.txt", "v0", "v1")),
                tool(edit("e2", "/workspace/a.txt", "v1", "v2")),
                done(),
            ),
        )
        rt.say("改两次")
        val preview = rt.previewRewind(rt.point(0))!!
        assertEquals(listOf("/workspace/a.txt"), preview.unrestorable.map { it.value })
        assertTrue(preview.editRestores.isEmpty())
        assertTrue(rt.rewind(rt.point(0), RewindScope.Code) is RewindOutcome.Refused)
        assertEquals("v2", fs.text("/workspace/a.txt"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a file written by an editing tool and then changed by something else counts as an outside change`() = run {
        val rt = session(
            ScriptedModel(
                done("one"),
                tool(write("w1", "/workspace/a.txt", "A"), write("w2", "/workspace/x.txt", "X"), sh("s1", "/workspace/a.txt", "B")),
                done("two"),
            ),
        )
        rt.say("第一轮")
        rt.say("第二轮")
        val preview = rt.previewRewind(rt.point(0))!!
        assertEquals(listOf("/workspace/a.txt"), preview.external.map { it.value })
        assertLogIsTruth(rt)
    }

    /** 父会话第二轮委派一个后台子 agent，子 agent 挂着不结束 */
    private class Routing(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    @Test
    fun `rewinding the conversation stops children delegated after the point and drops their reports`() = run {
        val model = Routing { req ->
            val last = req.lastText()
            when {
                req.messages.size == 1 && last.contains("慢活") -> { _ -> flow { awaitCancellation() } }
                req.messages.last().parts.any { it is ToolResultPart } -> done("派了")
                last.contains("派个活") -> tool(
                    ToolCallPart(CallId("a1"), "Agent", args("description" to "慢活", "prompt" to "慢活", "run_in_background" to true)),
                )
                else -> done("好")
            }
        }
        val hub = SessionHub(store, deps(model, tools + AgentToolOf(emptyList())), scope)
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        parent.say("先聊")
        parent.say("派个活")
        val child = parent.state.value.children.keys.single()
        while (hub.get(child)?.state?.value?.turn == null) delay(10)

        val outcome = parent.rewind(parent.point(1), RewindScope.Conversation)
        assertTrue(outcome is RewindOutcome.Done)
        assertNull("子 agent 停了", hub.get(child)?.state?.value?.turn)
        delay(200) // 报告若要送，这时早该送到了
        val s = parent.state.value
        assertTrue("它的报告不进收件箱", s.inbox.none { (it.origin as? InputOrigin.Agent)?.session == child })
        assertTrue(s.children.getValue(child).rewound)

        parent.say("重来")
        assertFalse("回退后的对话里没有它的报告", model.requests.last().messages.any { m ->
            m.parts.any { it is TextPart && it.text.contains("<subagent-report") }
        })
        assertLogIsTruth(parent)
    }
}
