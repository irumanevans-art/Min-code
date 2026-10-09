package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelException
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.Tool
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 回合状态机的端到端测试：真的日志存储 + 脚本化模型 + 假工具。
 * 每个测试最后都核对「日志即真相」：把日志重新折叠一遍，必须与运行时的内存状态完全相等。
 */
class SessionRuntimeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun input(key: String) = JsonObject(mapOf("k" to JsonPrimitive(key)))
    private fun call(id: String, tool: String, key: String = id) = ToolCallPart(CallId(id), tool, input(key))
    private fun text(t: String) = listOf(TextPart(t))
    private fun msg() = ClientMsgId("u${++counter}")

    private suspend fun newSession(model: ScriptedModel, tools: List<Tool<*>>, mode: PermissionMode = PermissionMode.Default) =
        SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode),
            testDeps(model, tools),
            scope,
        )

    private suspend fun events(): List<SessionEvent> = store.peek(SessionId("s1"))!!.read().toList().map { it.event }

    /** 日志即真相：重新折叠日志得到的状态 == 运行时内存里的状态 */
    private suspend fun assertLogIsTruth(runtime: SessionRuntime) {
        val log = store.peek(SessionId("s1"))!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `a plain text turn`() = run {
        val model = ScriptedModel(reply(TextPart("你好")))
        val rt = newSession(model, emptyList())
        assertTrue(rt.send(msg(), text("在吗")))
        rt.awaitIdle()

        val s = rt.state.value
        assertNull(s.turn)
        assertEquals(listOf(Role.User, Role.Assistant), s.modelMessages().map { it.role })
        assertEquals(text("在吗"), model.requests.single().messages.single().parts)
        assertLogIsTruth(rt)
    }

    @Test
    fun `read-only tools run without asking and results come back in call order`() = run {
        val read = FakeTool("Read", READ_ONLY) { ToolResult(text("内容 ${it["k"]}")) }
        val model = ScriptedModel(
            reply(call("c1", "Read", "a"), call("c2", "Read", "b"), stop = StopReason.ToolUse),
            reply(TextPart("看完了")),
        )
        val rt = newSession(model, listOf(read))
        rt.send(msg(), text("看看 a 和 b"))
        rt.awaitIdle()

        val second = model.requests[1].messages
        val results = second[2].parts.map { it as ToolResultPart }
        assertEquals(listOf(CallId("c1"), CallId("c2")), results.map { it.callId })
        assertFalse(results.any { it.isError })
        assertEquals(2, events().count { it is ToolEffectPending })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a write asks first and runs once approved`() = run {
        var ran = 0
        val write = FakeTool("Write", WRITES) { ran++; ToolResult(text("写好了")) }
        val model = ScriptedModel(reply(call("c1", "Write"), stop = StopReason.ToolUse), reply(TextPart("完成")))
        val rt = newSession(model, listOf(write))
        rt.send(msg(), text("改一下"))

        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        val approvalId = (waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId
        assertEquals("审批前不能执行", 0, ran)
        assertTrue(rt.answerApproval(approvalId, ApprovalChoice.Once))
        assertFalse("同一个审批不能答两次", rt.answerApproval(approvalId, ApprovalChoice.Once))
        rt.awaitIdle()

        assertEquals(1, ran)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a denied write never runs and the model hears the note`() = run {
        var ran = 0
        val write = FakeTool("Write", WRITES) { ran++; ToolResult(text("写好了")) }
        val model = ScriptedModel(reply(call("c1", "Write"), stop = StopReason.ToolUse), reply(TextPart("好的，不改")))
        val rt = newSession(model, listOf(write))
        rt.send(msg(), text("改一下"))

        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        rt.answerApproval((waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId, ApprovalChoice.Deny("别动这个文件"))
        rt.awaitIdle()

        assertEquals(0, ran)
        // 用户写的说明不进 tool_result（官方说明：tool_result 里的文字会被当作不可信内容），而是紧随其后的独立用户文本块
        val parts = model.requests[1].messages[2].parts
        val result = parts.first() as ToolResultPart
        assertTrue(result.isError)
        val resultText = (result.content.single() as TextPart).text
        assertTrue(resultText, resultText.startsWith("用户拒绝了这次调用。") && !resultText.contains("别动这个文件"))
        assertEquals(listOf(TextPart("用户拒绝了你刚才的 Write 调用，并留言：别动这个文件")), parts.drop(1))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a denial without a note leaves no user text and tells the model not to retry as is`() = run {
        val write = FakeTool("Write", WRITES) { ToolResult(text("写好了")) }
        val model = ScriptedModel(reply(call("c1", "Write"), stop = StopReason.ToolUse), reply(TextPart("好的，不改")))
        val rt = newSession(model, listOf(write))
        rt.send(msg(), text("改一下"))

        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        rt.answerApproval((waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId, ApprovalChoice.Deny())
        rt.awaitIdle()

        val parts = model.requests[1].messages[2].parts
        assertEquals(1, parts.size)
        val result = parts.single() as ToolResultPart
        assertTrue(result.isError)
        assertEquals("用户拒绝了这次调用。不要原样重试，换个做法或先问清楚。", (result.content.single() as TextPart).text)
        assertLogIsTruth(rt)
    }

    @Test
    fun `retryable failures are retried under the same request id, others end the turn`() = run {
        val model = ScriptedModel(
            failing(ModelException(ModelFailureKind.Network, "reset", beforeFirstByte = true)),
            reply(TextPart("好了")),
            failing(ModelException(ModelFailureKind.BadRequest, "bad", httpStatus = 400, beforeFirstByte = true)),
        )
        val rt = newSession(model, emptyList())
        rt.send(msg(), text("一"))
        rt.awaitIdle()
        rt.send(msg(), text("二"))
        rt.awaitIdle()

        val log = events()
        val starts = log.filterIsInstance<ModelRequestStarted>()
        assertEquals(listOf(1, 2, 1), starts.map { it.attempt })
        assertEquals(starts[0].requestId, starts[1].requestId)
        assertEquals(listOf(true, false), log.filterIsInstance<ModelRequestFailed>().map { it.willRetry })
        val outcomes = log.filterIsInstance<TurnEnded>().map { it.outcome }
        assertEquals(TurnOutcome.Completed, outcomes[0])
        assertTrue(outcomes[1] is TurnOutcome.Failed)
        assertLogIsTruth(rt)
    }

    @Test
    fun `tool calls in a truncated reply are not executed`() = run {
        var ran = 0
        val read = FakeTool("Read", READ_ONLY) { ran++; ToolResult(text("x")) }
        val model = ScriptedModel(
            reply(call("c1", "Read"), stop = StopReason.MaxTokens, truncated = true),
            reply(TextPart("我分几次来")),
        )
        val rt = newSession(model, listOf(read))
        rt.send(msg(), text("大改"))
        rt.awaitIdle()

        assertEquals(0, ran)
        val result = model.requests[1].messages[2].parts.single() as ToolResultPart
        assertEquals(ToolExecutor.TRUNCATED_MESSAGE, (result.content.single() as TextPart).text)
        assertLogIsTruth(rt)
    }

    @Test
    fun `interrupting a running tool closes the turn with an honest result and does not start queued input`() = run {
        val started = CompletableDeferred<Unit>()
        val slow = FakeTool("Bash", WRITES) { started.complete(Unit); awaitCancellation() }
        val model = ScriptedModel(reply(call("c1", "Bash"), stop = StopReason.ToolUse))
        val rt = newSession(model, listOf(slow), mode = PermissionMode.BypassPermissions)
        rt.send(msg(), text("跑个长任务"))
        started.await()
        rt.send(msg(), text("排队的下一句"))
        rt.interrupt()
        rt.awaitIdle()

        val s = rt.state.value
        assertNull(s.turn)
        val ended = events().filterIsInstance<TurnEnded>().single()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), ended.outcome)
        assertTrue((s.toolResults.getValue(CallId("c1")).content.single() as TextPart).text.contains("结果未知"))
        assertEquals("中断后排队的话不自动发出", 1, s.inbox.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `steering input lands after the current tool batch within the same turn`() = run {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val read = FakeTool("Read", READ_ONLY) { entered.complete(Unit); gate.await(); ToolResult(text("内容")) }
        val model = ScriptedModel(reply(call("c1", "Read"), stop = StopReason.ToolUse), reply(TextPart("收到，也处理了插话")))
        val rt = newSession(model, listOf(read))
        rt.send(msg(), text("看看"))
        entered.await()
        rt.send(msg(), text("顺便看看 b"), Delivery.Steer)
        gate.complete(Unit)
        rt.awaitIdle()

        val second = model.requests[1].messages
        assertEquals(text("顺便看看 b"), second.last().parts)
        assertEquals(1, events().count { it is TurnStarted })
        assertLogIsTruth(rt)
    }

    @Test
    fun `input queued during a turn starts the next turn afterwards`() = run {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val read = FakeTool("Read", READ_ONLY) { entered.complete(Unit); gate.await(); ToolResult(text("内容")) }
        val model = ScriptedModel(
            reply(call("c1", "Read"), stop = StopReason.ToolUse),
            reply(TextPart("第一件完成")),
            reply(TextPart("第二件完成")),
        )
        val rt = newSession(model, listOf(read))
        rt.send(msg(), text("第一件"))
        entered.await()
        rt.send(msg(), text("第二件"))
        gate.complete(Unit)
        rt.awaitIdle()

        assertEquals(2, events().count { it is TurnStarted })
        assertEquals(text("第二件"), model.requests[2].messages.last().parts)
        assertLogIsTruth(rt)
    }

    @Test
    fun `what a tool saw of a file is visible to later tools through the log`() = run {
        val path = GuestPath("/workspace/a.kt")
        val stamp = FileStamp(path, size = 12, modifiedMs = 99)
        val reader = FakeTool("Read", READ_ONLY) { ToolResult(text("内容"), touched = listOf(stamp)) }
        var seenByWriter: FileStamp? = null
        val writer = object : Tool<JsonObject> by FakeTool("Write", WRITES, { ToolResult(text("ok")) }) {
            override fun run(input: JsonObject, ctx: ToolContext) = flow {
                seenByWriter = ctx.lastSeen(path)
                emit(ToolProgress.Done(ToolResult(text("写好了"))))
            }
        }
        val model = ScriptedModel(
            reply(call("c1", "Read"), stop = StopReason.ToolUse),
            reply(call("c2", "Write"), stop = StopReason.ToolUse),
            reply(TextPart("好了")),
        )
        val rt = newSession(model, listOf(reader, writer), mode = PermissionMode.BypassPermissions)
        rt.send(msg(), text("先读再改"))
        rt.awaitIdle()

        assertEquals(stamp, seenByWriter)
        assertLogIsTruth(rt)
    }

    @Test
    fun `resending the same input is accepted once`() = run {
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = newSession(model, emptyList())
        val id = msg()
        assertTrue(rt.send(id, text("一次")))
        assertFalse(rt.send(id, text("一次")))
        rt.awaitIdle()
        assertEquals(1, model.requests.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `reopening after the process died mid-approval recovers a legal idle session`() = run {
        val write = FakeTool("Write", WRITES) { ToolResult(text("写好了")) }
        val model = ScriptedModel(reply(call("c1", "Write"), stop = StopReason.ToolUse))
        // 进程死亡 = 从此一个字节都写不进去（连中断的收尾都写不了）。只取消协程测的是「用户中断」，不是死亡
        val dying = KillableStore(store)
        val rt = SessionRuntime.create(
            dying,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default),
            testDeps(model, listOf(write)),
            scope,
        )
        rt.send(msg(), text("改一下"))
        rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        dying.kill()
        scope.cancel()

        // 新「进程」打开同一个会话
        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reopened = SessionRuntime.open(store, SessionId("s1"), testDeps(ScriptedModel(), listOf(write)), fresh)!!
            val s = reopened.state.value
            assertNull(s.turn)
            s.modelMessages() // 每个调用都有结果，历史合法
            assertTrue((s.toolResults.getValue(CallId("c1")).content.single() as TextPart).text.startsWith("这次调用没有执行"))
            assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), events().filterIsInstance<TurnEnded>().single().outcome)
            assertLogIsTruth(reopened)
        } finally {
            fresh.cancel()
        }
    }
}
