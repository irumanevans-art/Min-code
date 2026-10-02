package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.tools.AgentTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * M2-B 要的内核接口（docs/agent/detail/M2-B-界面接入设计.md §5.9 的 K1–K11）：
 * 在线集合与卸下、删除整棵树、带初始事件新建、增量带请求 id、失败种类、中断时间、续跑分隔、续跑判定、收件箱入口、
 * 最近一次请求的用量、待处理交互聚合。
 */
class HubSurfaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    /** 存储的时钟每次 +100：事件时间各不相同，「中断于何时」才测得出来 */
    private val storeClock = AtomicLong(1_000)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { storeClock.addAndGet(100) }) }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { part ->
        when (part) {
            is TextPart -> part.text
            is ToolResultPart -> part.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private val hang: Step = { flow { awaitCancellation() } }

    /** 要人批准的写工具 */
    private val write = object : Tool<JsonObject> {
        override val name = "Write"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "写"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "写一个文件")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("写好了")))))
        }
    }

    private fun hub(model: ModelClient, tools: List<Tool<*>> = listOf(write, AgentTool), scope: CoroutineScope = newScope()) =
        SessionHub(store, testDeps(model, tools), scope)

    private suspend fun SessionHub.root() = create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    private suspend fun events(id: dev.min.agent.protocol.SessionId): List<SessionEvent> =
        store.open(id)!!.read().toList().map { it.event }

    // ---- K1 在线集合与卸下 ----

    @Test
    fun `online and running track sessions, and close refuses while a turn runs`() = run {
        val model = RoutingModel { req -> if (req.last() == "慢") hang else reply(TextPart("好")) }
        val h = hub(model)
        val rt = h.root()
        assertEquals(setOf(rt.sessionId), h.online.value)
        rt.send(ClientMsgId("u1"), listOf(TextPart("慢")))
        h.running.first { rt.sessionId in it }
        assertFalse("有回合在跑不能卸下", h.close(rt.sessionId))

        rt.interrupt()
        rt.awaitIdle()
        h.running.first { it.isEmpty() }
        assertTrue(h.close(rt.sessionId))
        assertTrue(h.online.value.isEmpty())
        assertNull(h.get(rt.sessionId))
        // 日志不动，能原样重开
        val reopened = h.open(rt.sessionId)!!
        assertEquals(rt.state.value.lastSeq, reopened.state.value.lastSeq)
        assertEquals(setOf(rt.sessionId), h.online.value)
    }

    // ---- K2 删除整棵树 ----

    @Test
    fun `delete interrupts the whole tree and removes every descendant`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "子任务" -> hang
                req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", args("description" to "后台", "prompt" to "子任务")),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("等"))
            }
        }
        val h = hub(model)
        val root = h.root()
        root.send(ClientMsgId("u1"), listOf(TextPart("开始")))
        val child = h.get(root.state.first { it.children.isNotEmpty() }.children.keys.single())!!
        child.state.first { it.turn?.openRequest != null }

        assertTrue(h.delete(root.sessionId))
        assertTrue(store.list().isEmpty())
        assertFalse(Files.exists(tmp.root.toPath().resolve(root.sessionId.value)))
        assertFalse(Files.exists(tmp.root.toPath().resolve(child.sessionId.value)))
        assertTrue(h.online.value.isEmpty())
        assertTrue("子会话的回合已被中断", child.state.value.turn == null)
    }

    // ---- K3 带初始事件新建 ----

    @Test
    fun `create writes initial events right after SessionCreated`() = run {
        val h = hub(RoutingModel { reply(TextPart("好")) })
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default, initial = listOf(Note(NoteLevel.Info, "供应商：测试")))
        val log = events(rt.sessionId)
        assertTrue(log[0] is SessionCreated)
        assertEquals(Note(NoteLevel.Info, "供应商：测试"), log[1])
    }

    // ---- K4 增量带请求 id ----

    @Test
    fun `live text carries the id of the request it belongs to`() = run {
        val model = RoutingModel { req ->
            if (req.messages.size == 1) {
                { flow {
                    emit(ModelStreamEvent.Started(null))
                    emit(ModelStreamEvent.TextDelta("先写"))
                    emit(ModelStreamEvent.Completed(
                        dev.min.agent.spi.ModelMessage(dev.min.agent.spi.Role.Assistant, listOf(TextPart("先写"), ToolCallPart(CallId("w1"), "Write", JsonObject(emptyMap())))),
                        Usage(inputTokens = 10, outputTokens = 2), StopReason.ToolUse, false,
                    ))
                } }
            } else {
                { flow {
                    emit(ModelStreamEvent.Started(null))
                    emit(ModelStreamEvent.TextDelta("写完了"))
                    emit(ModelStreamEvent.Completed(
                        dev.min.agent.spi.ModelMessage(dev.min.agent.spi.Role.Assistant, listOf(TextPart("写完了"))),
                        Usage(inputTokens = 30, outputTokens = 3, cacheReadTokens = 7), StopReason.EndTurn, false,
                    ))
                } }
            }
        }
        val h = hub(model)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val texts = CopyOnWriteArrayList<LiveUpdate.Text>()
        val collector = newScope().launch { rt.live.collect { if (it is LiveUpdate.Text) texts += it } }
        rt.send(ClientMsgId("u1"), listOf(TextPart("写")))
        rt.state.first { it.turn == null && it.lastTurn != null }
        collector.cancel()
        val requestIds = events(rt.sessionId).filterIsInstance<ModelRequestStarted>().map { it.requestId }
        assertEquals(2, requestIds.distinct().size)
        assertEquals(requestIds, texts.map { it.requestId })
        // K10：最近一次请求的用量
        assertEquals(Usage(inputTokens = 30, outputTokens = 3, cacheReadTokens = 7), rt.state.value.lastRequestUsage)
    }

    // ---- K5 失败种类 ----

    @Test
    fun `a failed turn records what kind of failure ended it`() = run {
        val h = hub(RoutingModel { failing(ModelException(ModelFailureKind.Auth, "401", httpStatus = 401, beforeFirstByte = true)) })
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("在吗")))
        val ended = rt.state.first { it.lastTurn != null }.lastTurn!!
        assertEquals(ModelFailureKind.Auth, (ended.outcome as TurnOutcome.Failed).kind)
        val hint = rt.state.value.resumeHint()
        assertNotNull("失败的回合可以续跑", hint)
    }

    // ---- K6 中断时间 + K8 续跑判定 ----

    @Test
    fun `a turn killed with the process remembers its last activity and offers to resume`() = run {
        // 第一次请求卡住（等进程被杀），▶ 之后那次正常回答
        val calls = AtomicLong(0)
        val model = RoutingModel { if (calls.incrementAndGet() == 1L) hang else reply(TextPart("好")) }
        val killable = KillableStore(store)
        val h1 = SessionHub(killable, testDeps(model, listOf(write)), newScope())
        val rt = h1.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("慢")))
        val running = rt.state.first { it.turn?.openRequest != null }
        val lastBeforeDeath = running.turn!!.lastEventAtMs
        killable.kill()
        scopes.first().cancel()

        val h2 = hub(model)
        val reopened = h2.open(rt.sessionId)!!
        val end = events(rt.sessionId).filterIsInstance<TurnEnded>().single()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), end.outcome)
        assertEquals(lastBeforeDeath, end.lastActivityAtMs)
        val hint = reopened.state.value.resumeHint()!!
        assertEquals(end.turnId, hint.offerId)
        assertEquals(lastBeforeDeath, hint.sinceMs)
        assertEquals(0, hint.pendingInputs)

        // K7：▶ 之后的回合在转录里留分隔
        reopened.resume()
        val after = reopened.state.first { it.turn == null && it.lastTurn?.trigger == TurnTrigger.Resume }
        assertTrue(after.presentation.any { (it.record.event as? TurnStarted)?.trigger == TurnTrigger.Resume })
        assertNull("续跑完成后不再提示", after.resumeHint())
    }

    @Test
    fun `resume hint follows the rules of section 6_3`() {
        val user = HistoryEntry.User(MessageId("m1"), listOf(TextPart("问")))
        val answered = HistoryEntry.Assistant(MessageId("m2"), listOf(TextPart("答完了")))
        val calling = HistoryEntry.Assistant(MessageId("m3"), listOf(ToolCallPart(CallId("c1"), "Write", JsonObject(emptyMap()))))
        fun ended(outcome: TurnOutcome) = EndedTurn(TurnId("t1"), TurnTrigger.User, outcome, Usage(), 0, 0, lastActivityAtMs = 42)
        val base = fold(
            dev.min.agent.spi.LogHeader(1, dev.min.agent.protocol.SessionId("s"), 0),
            listOf(dev.min.agent.spi.EventRecord(1, 0, SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default))),
        )
        val stopped = ended(TurnOutcome.Interrupted(InterruptedBy.User))
        assertNotNull(base.copy(history = listOf(user), lastTurn = stopped).resumeHint())
        assertNotNull(base.copy(history = listOf(user, calling), lastTurn = ended(TurnOutcome.Failed("网络", ModelFailureKind.Network))).resumeHint())
        assertNull("回答已经完整、收件箱空：不给", base.copy(history = listOf(user, answered), lastTurn = stopped).resumeHint())
        val queued = PendingInput(ClientMsgId("q"), listOf(TextPart("还有")), Delivery.Queue)
        assertEquals(1, base.copy(history = listOf(user, answered), lastTurn = stopped, inbox = listOf(queued)).resumeHint()!!.pendingInputs)
        assertNull("正常完成：不给", base.copy(history = listOf(user), lastTurn = ended(TurnOutcome.Completed)).resumeHint())
        assertNull("从没跑过：不给", base.resumeHint())
        assertEquals(42, base.copy(history = listOf(user), lastTurn = ended(TurnOutcome.Interrupted(InterruptedBy.Limit))).resumeHint()!!.sinceMs)
    }

    // ---- K9 收件箱入口 ----

    @Test
    fun `runInbox starts a turn from queued input after an interrupt`() = run {
        val model = RoutingModel { req -> if (req.last() == "慢") hang else reply(TextPart("收到：${req.last()}")) }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("慢")))
        rt.state.first { it.turn?.openRequest != null }
        rt.send(ClientMsgId("u2"), listOf(TextPart("排队那条")), Delivery.Queue)
        rt.interrupt()
        rt.awaitIdle()
        assertEquals("被中断的回合不自动开下一回合", 1, rt.state.value.inbox.size)
        rt.runInbox()
        val done = rt.state.first { it.turn == null && it.inbox.isEmpty() && it.lastTurn?.outcome == TurnOutcome.Completed }
        assertEquals(listOf(TextPart("收到：排队那条")), (done.history.last() as HistoryEntry.Assistant).parts)
    }

    // ---- K11 待处理交互聚合 ----

    @Test
    fun `pending aggregates approvals from the whole tree with their place in it`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "子任务" -> reply(ToolCallPart(CallId("cw"), "Write", JsonObject(emptyMap())), stop = StopReason.ToolUse)
                req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", args("description" to "后台写", "prompt" to "子任务")),
                    ToolCallPart(CallId("pw"), "Write", JsonObject(emptyMap())),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val root = h.root()
        root.send(ClientMsgId("u1"), listOf(TextPart("开始")))
        val both = h.pending.first { it.size == 2 }
        val mine = both.single { it.session == root.sessionId }
        val childs = both.single { it.session != root.sessionId }
        assertNull(mine.parent)
        assertEquals(root.sessionId, childs.root)
        assertEquals(root.sessionId, childs.parent)
        assertEquals("general-purpose", childs.agentType)
        assertTrue("按发起时间排", both.map { it.atMs } == both.map { it.atMs }.sorted())

        val approval = childs.what as HubPending.What.Approval
        assertEquals("Write", approval.tool)
        assertTrue(h.get(childs.session)!!.answerApproval(approval.approvalId, ApprovalChoice.Once))
        val left = h.pending.first { it.size == 1 }.single()
        assertEquals(root.sessionId, left.session)
    }
}
