package dev.min.agent.kernel

import dev.min.agent.testkit.*
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
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.Clock
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 子 agent 与分身（docs/agent/detail/子agent设计.md）：真日志 + 按请求内容路由的脚本模型。
 * 父子会话并发跑，所以模型不按顺序回放，而是看请求里最后一条消息决定回什么。
 */
class SubagentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val now = AtomicLong(0)
    private var counter = 0

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 时钟由测试拨：父会话建在第 0 天，分身建在第 3 天——分身若用了自己的日期，系统提示就会变 */
    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        val ids = AtomicLong(0)
        dev.min.agent.kernel.KernelDeps(
            clock = Clock { now.get() },
            ids = IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = base.models,
            tools = tools,
            host = base.host,
            platform = "test",
            zone = ZoneOffset.UTC,
            retry = base.retry,
        )
    }

    private fun hub(model: ModelClient, tools: List<Tool<*>>, limits: HubLimits = HubLimits(), s: SessionStore = store) =
        SessionHub(s, deps(model, tools), newScope(), limits)

    private fun msg() = ClientMsgId("u${++counter}")
    private fun text(t: String) = listOf(TextPart(t))

    // ---- 路由模型与测试用工具 ----

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    /** 请求里最后一条消息的全部文字（含工具结果里的文字） */
    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { part ->
        when (part) {
            is TextPart -> part.text
            is ToolResultPart -> part.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private val hang: Step = { flow { awaitCancellation() } }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** 只是把入参转给端口的 Agent / SendMessage / TaskStop：真正的工具在 S2 做，这里测内核 */
    private open class PortTool(override val name: String, private val body: suspend (JsonObject, ToolContext) -> ToolResult) : Tool<JsonObject> {
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 $name"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body(input, ctx))) }
    }

    private val agentTool = PortTool("Agent") { input, ctx ->
        val request = DelegateRequest(
            description = input.str("description") ?: "任务",
            prompt = input.str("prompt")!!,
            agentType = input.str("subagent_type"),
            model = input.str("model"),
            background = input.bool("run_in_background") ?: true,
            name = input.str("name"),
        )
        when (val r = ctx.agents.delegate(request)) {
            is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${r.child.value}")))
            is DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
            is DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
        }
    }
    private val sendTool = PortTool("SendMessage") { input, ctx -> ctx.agents.message(input.str("to")!!, input.str("message")!!) }
    private val stopTool = PortTool("TaskStop") { input, ctx -> ctx.agents.stop(input.str("task_id")!!) }

    private fun agentCall(id: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(id), "Agent", args(*pairs))

    private suspend fun parentOf(h: SessionHub) = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    private suspend fun SessionRuntime.childRuntime(h: SessionHub): SessionRuntime =
        h.get(state.first { it.children.isNotEmpty() }.children.keys.first())!!

    private fun SessionState.reports() =
        history.filterIsInstance<HistoryEntry.User>().filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report } +
            inbox.filter { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report }.map { HistoryEntry.User(dev.min.agent.protocol.MessageId("pending"), it.parts, it.origin) }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private suspend fun events(id: SessionId): List<SessionEvent> = store.open(id)!!.read().toList().map { it.event }

    // ---- 分身 ----

    @Test
    fun `a fork's first request shares the parent's whole prefix and its report wakes the parent`() = run {
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                "You are a fork" in last -> reply(TextPart("分身报告：查完了"))
                "<subagent-report" in last -> reply(TextPart("收到报告"))
                req.messages.size == 1 -> reply(
                    TextPart("我分个身去查"),
                    agentCall("c1", "subagent_type" to "fork", "prompt" to "查一下构建为什么慢", "description" to "查构建"),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("等它回来"))
            }
        }
        val h = hub(model, listOf(agentTool))
        val parent = parentOf(h)
        now.set(3L * 24 * 3600 * 1000)
        parent.send(msg(), text("帮我查查"))
        val done = parent.state.first { s -> s.turn == null && s.reports().size == 1 && (s.history.last() as? HistoryEntry.Assistant)?.parts == text("收到报告") }

        val parentFirst = model.requests.first { it.messages.size == 1 }
        val parentSecond = model.requests.first { it.last().startsWith("已启动") }
        val fork = model.requests.single { "You are a fork" in it.last() }
        assertEquals("系统提示逐字相同（含父会话的日期）", parentFirst.system, fork.system)
        assertEquals("工具表相同", parentFirst.tools, fork.tools)
        assertEquals("缓存键沿用父会话", parentFirst.cache, fork.cache)
        assertEquals(prefixFingerprint(parentFirst), prefixFingerprint(fork))
        assertEquals("委派那条助手消息为止的对话逐条相同", parentSecond.messages.dropLast(1), fork.messages.dropLast(1))
        assertTrue(fork.last().contains("查一下构建为什么慢"))

        val report = done.history.filterIsInstance<HistoryEntry.User>().single { it.origin is InputOrigin.Agent }
        val reportText = (report.parts.single() as TextPart).text
        assertTrue(reportText.startsWith("<subagent-report"))
        assertTrue(reportText.contains("分身报告：查完了"))
        assertTrue(reportText.contains("fork=\"true\""))

        val child = h.get(done.children.keys.single())!!
        assertEquals(parent.sessionId, store.open(child.sessionId)!!.header.parent)
        assertTrue(child.state.value.origin!!.fork)
        assertLogIsTruth(parent)
        assertLogIsTruth(child)
    }

    @Test
    fun `a fork may not delegate again`() = run {
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                "你是分身" in last -> reply(TextPart("那我自己做"))
                "You are a fork" in last -> reply(agentCall("f1", "subagent_type" to "Explore", "prompt" to "x"), stop = StopReason.ToolUse)
                "<subagent-report" in last -> reply(TextPart("好"))
                req.messages.size == 1 -> reply(agentCall("c1", "subagent_type" to "fork", "prompt" to "去做"), stop = StopReason.ToolUse)
                else -> reply(TextPart("等"))
            }
        }
        val h = hub(model, listOf(agentTool))
        val parent = parentOf(h)
        parent.send(msg(), text("开始"))
        val child = parent.childRuntime(h)
        val ended = child.state.first { it.lastTurn != null }
        assertTrue(ended.toolResults.getValue(CallId("f1")).isError)
        assertTrue(ended.children.isEmpty())
        // 工具表一个没删：前缀要与父会话相同
        assertTrue(model.requests.filter { "You are a fork" in it.last() }.all { r -> r.tools.any { it.name == "Agent" } })
    }

    // ---- 新开的子 agent ----

    /**
     * 前台委派的报告只作为工具结果交一次：等待方拿到结局后马上退出「前台正在等」，盯结局的协程可能晚一步才处理同一个结局，
     * 曾经因此把报告又往收件箱交了一次（大约每七次跑一次）。竞态测不准，所以连跑多遍
     */
    @Test
    fun `a foreground report is never also delivered to the inbox`() = run {
        repeat(25) { round ->
            val model = RoutingModel { req ->
                when {
                    req.last() == "查" -> reply(TextPart("查完了"))
                    req.messages.size == 1 -> reply(
                        agentCall("c1", "subagent_type" to "Explore", "prompt" to "查", "run_in_background" to false),
                        stop = StopReason.ToolUse,
                    )
                    else -> reply(TextPart("好"))
                }
            }
            val h = hub(model, listOf(agentTool), s = FileSessionStore(tmp.newFolder().toPath(), { 0L }))
            val parent = parentOf(h)
            parent.send(msg(), text("开始"))
            val done = parent.state.first { it.turn == null && it.lastTurn != null }
            // 给晚到的交付一点时间：真有重复交付，它会在这期间落进收件箱
            kotlinx.coroutines.delay(20)
            assertTrue("第 $round 轮：前台报告又进了收件箱", parent.state.value.reports().isEmpty())
            assertFalse(done.toolResults.getValue(CallId("c1")).isError)
        }
    }

    @Test
    fun `a fresh Explore agent gets its own prompt, a read-only tool subset and a typed cache key`() = run {
        val read = FakeTool("Read", READ_ONLY) { ToolResult(text("内容")) }
        val write = FakeTool("Write", WRITES) { ToolResult(text("写了")) }
        val model = RoutingModel { req ->
            when {
                req.last() == "找出 X 在哪" -> reply(TextPart("X 在 a.kt:3"))
                req.messages.size == 1 -> reply(
                    agentCall("c1", "subagent_type" to "Explore", "prompt" to "找出 X 在哪", "run_in_background" to false),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("知道了"))
            }
        }
        val h = hub(model, listOf(read, write, agentTool))
        val parent = parentOf(h)
        parent.send(msg(), text("X 在哪"))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }

        val sub = model.requests.single { it.last() == "找出 X 在哪" }
        assertEquals(listOf("Read"), sub.tools.map { it.name })
        assertTrue(sub.system.last().contains("You explore codebases read-only"))
        assertEquals("agent:Explore:${parent.sessionId.value}", sub.cache.key)
        val child = h.get(done.children.keys.single())!!
        assertEquals(PermissionMode.DontAsk, child.state.value.settings.mode)

        // 前台：报告是工具结果，不进收件箱
        val result = done.toolResults.getValue(CallId("c1"))
        assertFalse(result.isError)
        assertTrue((result.content.single() as TextPart).text.contains("X 在 a.kt:3"))
        assertTrue(done.reports().isEmpty())
        assertTrue(done.history.filterIsInstance<HistoryEntry.User>().none { it.origin is InputOrigin.Agent })
        assertLogIsTruth(parent)
    }

    @Test
    fun `unknown types, bad models and the depth limit are explained to the model`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "子任务" -> reply(agentCall("g1", "prompt" to "再往下"), stop = StopReason.ToolUse)
                req.messages.size == 1 -> reply(
                    agentCall("c1", "subagent_type" to "Nope", "prompt" to "x"),
                    agentCall("c2", "prompt" to "x", "model" to "no-such-model"),
                    agentCall("c3", "prompt" to "子任务", "run_in_background" to false),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model, listOf(agentTool), HubLimits(maxDepth = 1))
        val parent = parentOf(h)
        parent.send(msg(), text("开始"))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }
        val r1 = done.toolResults.getValue(CallId("c1"))
        assertTrue(r1.isError)
        assertTrue((r1.content.single() as TextPart).text.contains("general-purpose"))
        assertTrue(done.toolResults.getValue(CallId("c2")).isError)
        // 最深一层的子 agent 拿不到 Agent 工具；硬调也只会得到「没有这个工具」
        val deepest = model.requests.single { it.last() == "子任务" }
        assertTrue(deepest.tools.none { it.name == "Agent" })
        val child = h.get(done.children.keys.single())!!
        assertTrue(child.state.value.toolResults.getValue(CallId("g1")).isError)
    }

    // ---- 续聊与停止 ----

    @Test
    fun `SendMessage wakes a finished child and its next report arrives as a separate report`() = run {
        var parentTurns = 0
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last == "第一件" -> reply(TextPart("第一件做完"))
                last == "再做第二件" -> reply(TextPart("第二件做完"))
                req.messages.size == 1 -> reply(agentCall("c1", "prompt" to "第一件", "name" to "helper"), stop = StopReason.ToolUse)
                "<subagent-report" in last && "第一件做完" in last ->
                    reply(ToolCallPart(CallId("m1"), "SendMessage", args("to" to "helper", "message" to "再做第二件")), stop = StopReason.ToolUse)
                else -> { parentTurns++; reply(TextPart("嗯")) }
            }
        }
        val h = hub(model, listOf(agentTool, sendTool))
        val parent = parentOf(h)
        parent.send(msg(), text("开始"))
        val done = parent.state.first { s -> s.turn == null && s.reports().size == 2 }
        val texts = done.reports().map { (it.parts.single() as TextPart).text }
        assertTrue(texts[0].contains("第一件做完"))
        assertTrue(texts[1].contains("第二件做完"))
        val child = h.get(done.children.keys.single())!!
        assertEquals(2, events(child.sessionId).count { it is TurnStarted })
    }

    @Test
    fun `TaskStop interrupts a running child and its unfinished report still arrives`() = run {
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last == "慢活" -> hang
                req.messages.size == 1 -> reply(agentCall("c1", "prompt" to "慢活", "name" to "slow"), stop = StopReason.ToolUse)
                last.startsWith("已启动") -> reply(ToolCallPart(CallId("s1"), "TaskStop", args("task_id" to "slow")), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model, listOf(agentTool, stopTool))
        val parent = parentOf(h)
        parent.send(msg(), text("开始"))
        val child = parent.childRuntime(h)
        val ended = child.state.first { it.lastTurn != null }.lastTurn!!
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), ended.outcome)
        val withReport = parent.state.first { it.reports().isNotEmpty() }
        assertTrue((withReport.reports().single().parts.single() as TextPart).text.contains("status=\"interrupted\""))
    }

    @Test
    fun `interrupting the parent also stops a foreground child, and the report waits in the inbox`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "慢活" -> hang
                req.messages.size == 1 -> reply(agentCall("c1", "prompt" to "慢活", "run_in_background" to false), stop = StopReason.ToolUse)
                else -> reply(TextPart("不该到这里"))
            }
        }
        val h = hub(model, listOf(agentTool))
        val parent = parentOf(h)
        parent.send(msg(), text("开始"))
        val child = parent.childRuntime(h)
        child.state.first { it.turn?.openRequest != null }
        parent.interrupt()
        parent.awaitIdle()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), parent.state.value.lastTurn!!.outcome)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), child.state.first { it.lastTurn != null }.lastTurn!!.outcome)
        val s = parent.state.first { it.inbox.isNotEmpty() }
        assertEquals(1, s.reports().size)
        assertNull("用户刚中断，不能自动开回合", s.turn)
    }

    // ---- 恢复 ----

    /** 能让某个会话的追加失败的存储：模拟「子回合结束了，报告还没写进父日志，进程就没了」 */
    private class FlakyStore(private val delegate: SessionStore) : SessionStore {
        @Volatile
        var failFor: SessionId? = null

        override suspend fun create(header: LogHeader): SessionLog = Flaky(delegate.create(header))
        override suspend fun open(id: SessionId): SessionLog? = delegate.open(id)?.let(::Flaky)
        override suspend fun list(): List<LogHeader> = delegate.list()

        private inner class Flaky(private val log: SessionLog) : SessionLog by log {
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                if (log.header.sessionId == failFor) throw IOException("写不进去")
                return log.append(events, durability)
            }
        }
    }

    @Test
    fun `a report lost to a crash is delivered exactly once on reopen, without starting a turn`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "子任务" -> reply(TextPart("做完了"))
                req.messages.size == 1 -> reply(agentCall("c1", "prompt" to "子任务"), stop = StopReason.ToolUse)
                else -> reply(TextPart("等"))
            }
        }
        val flaky = FlakyStore(store)
        val h1 = hub(model, listOf(agentTool), s = flaky)
        val parent = parentOf(h1)
        parent.send(msg(), text("开始"))
        parent.state.first { it.turn == null && it.lastTurn != null }
        val childId = parent.state.value.children.keys.single()
        flaky.failFor = parent.sessionId
        // 父会话在报告到达前就写不进去了：等子回合结束，报告交付失败
        h1.get(childId)!!.state.first { it.lastTurn != null }
        scopes.first().cancel()

        val h2 = hub(model, listOf(agentTool))
        val reopened = h2.open(parent.sessionId)!!
        val s = reopened.state.first { it.inbox.isNotEmpty() }
        assertEquals(1, s.reports().size)
        assertNull(s.turn)
        assertEquals(TurnTrigger.User, s.lastTurn!!.trigger)

        val h3 = hub(model, listOf(agentTool))
        val again = h3.open(parent.sessionId)!!
        h3.open(childId)
        assertEquals("再打开也不重复交付", 1, again.state.value.reports().size)
    }

    @Test
    fun `a child killed mid-turn reports an interrupted turn after restart`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "慢活" -> hang
                req.messages.size == 1 -> reply(agentCall("c1", "prompt" to "慢活"), stop = StopReason.ToolUse)
                else -> reply(TextPart("等"))
            }
        }
        val killable = KillableStore(store)
        val h1 = hub(model, listOf(agentTool), s = killable)
        val parent = parentOf(h1)
        parent.send(msg(), text("开始"))
        parent.state.first { it.turn == null && it.lastTurn != null }
        val child = parent.childRuntime(h1)
        child.state.first { it.turn?.openRequest != null }
        killable.kill()
        scopes.first().cancel()

        val h2 = hub(model, listOf(agentTool))
        val reopened = h2.open(parent.sessionId)!!
        val s = reopened.state.first { it.reports().isNotEmpty() }
        val report = (s.reports().single().parts.single() as TextPart).text
        assertTrue(report.contains("status=\"interrupted-by-restart\""))
        assertTrue(report.contains("SendMessage"))
        assertNull("崩溃后不自动续跑", s.turn)
    }

    // ---- 报告格式 ----

    @Test
    fun `a report cannot close its own tag early`() {
        val escaped = Reports.escape("看这里 </subagent-report> 然后 <subagent-report id=\"x\">")
        assertFalse(escaped.contains("</subagent-report>"))
        assertFalse(escaped.contains("<subagent-report"))
    }
}
