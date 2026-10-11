package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.partsToSend
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelFallback
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeTool
import dev.min.agent.testkit.READ_ONLY
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.failing
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * 模型回退链（`fallbackModel`）端到端：真日志 + 脚本化模型。重试预算是测试里的 3 次（见 [testDeps]），
 * 主模型是 [TEST_MODEL]，回退模型各自有自己的脚本客户端，谁被调用了几次一目了然。
 * 每个测试最后做「日志即真相」断言。
 */
class FallbackSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val fb1 = ModelRef("test", "fb1")
    private val fb2 = ModelRef("other", "fb2")

    private fun overloaded() = ModelException(ModelFailureKind.Overloaded, "529 overloaded", httpStatus = 529, beforeFirstByte = true)
    private fun failure(kind: ModelFailureKind) = ModelException(kind, "boom $kind", beforeFirstByte = true)
    private fun failures(n: Int, e: () -> ModelException = ::overloaded): Array<Step> = Array(n) { failing(e()) }

    /** 按请求内容路由的模型：记下每个请求，谁发的、发了几次都能数出来 */
    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun deps(
        models: Map<ModelRef, ModelClient>,
        chain: List<ModelRef>,
        tools: List<Tool<*>> = emptyList(),
        profiles: Map<ModelRef, dev.min.agent.llm.ModelProfile> = emptyMap(),
    ): KernelDeps = testDeps(models.getValue(TEST_MODEL), tools).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids,
            models = { ref ->
                val client = checkNotNull(models[ref]) { "没有配置模型 ${ref.providerId}:${ref.modelId}" }
                ResolvedModel(profiles[ref] ?: TEST_PROFILE.copy(ref = ref), client)
            },
            tools = tools, host = base.host, platform = "test", zone = base.zone, retry = base.retry,
            fallbackModels = chain,
        )
    }

    private suspend fun session(id: String, d: KernelDeps): SessionRuntime = SessionRuntime.create(
        store,
        LogHeader(LOG_FORMAT_VERSION, SessionId(id), createdAtMs = 0),
        SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
        d,
        newScope(),
    )

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private val ModelRequest.refId get() = profile.ref

    @Test
    fun `overload retries run out and the next model takes the same request`() = run {
        val primary = ScriptedModel(*failures(3))
        val backup = ScriptedModel(reply(TextPart("回退模型的回答")))
        val rt = session("s-overload", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        val done = rt.say("你好")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        assertEquals("主模型把重试预算（3 次）用完才换", 3, primary.requests.size)
        assertEquals(1, backup.requests.size)
        assertEquals(fb1, backup.requests.single().refId)
        val log = events(rt)
        val fallback = log.filterIsInstance<ModelFallback>().single()
        assertEquals(TEST_MODEL, fallback.from)
        assertEquals(fb1, fallback.to)
        assertEquals(ModelFailureKind.Overloaded, fallback.reason)
        assertTrue(fallback.message.contains("529"))
        // 顺序：三次失败 → 换模型 → 在回退模型上从第 1 次重新数
        val shape = log.mapNotNull {
            when (it) {
                is ModelRequestStarted -> "start:${it.model.modelId}:${it.attempt}"
                is ModelRequestFailed -> "fail:${it.attempt}:${it.willRetry}"
                is ModelFallback -> "fallback"
                else -> null
            }
        }
        assertEquals(
            listOf(
                "start:scripted:1", "fail:1:true", "start:scripted:2", "fail:2:true", "start:scripted:3", "fail:3:false",
                "fallback", "start:fb1:1",
            ),
            shape,
        )
        // 回合结束后状态回到主模型：回退只管这一个回合
        assertNull(done.turn)
        assertEquals(TEST_MODEL, done.activeModel)
        assertEquals(TEST_MODEL, done.settings.model)
        assertLogIsTruth(rt)
    }

    @Test
    fun `state shows the fallback while the turn is running`() = run {
        val primary = ScriptedModel(*failures(3))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backup = ScriptedModel({ flow { gate.await(); emitAll(reply(TextPart("好"))(it)) } })
        val rt = session("s-live", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        rt.send(ClientMsgId("u${++counter}"), listOf(TextPart("你好")))
        val during = rt.state.first { it.turn?.fallback != null }
        val inUse = during.turn!!.fallback!!
        assertEquals(TEST_MODEL, inUse.primary)
        assertEquals(fb1, inUse.model)
        assertEquals(ModelFailureKind.Overloaded, inUse.reason)
        assertEquals("界面据此显示「正在用回退模型」", fb1, during.activeModel)
        gate.complete(Unit)
        rt.awaitIdle()
        assertNull(rt.state.value.turn)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a chain that runs dry fails the turn as before and says which way it went`() = run {
        val primary = ScriptedModel(*failures(3))
        val backup = ScriptedModel(*failures(3))
        val rt = session("s-dry", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        val done = rt.say("你好")

        val outcome = done.lastTurn!!.outcome as TurnOutcome.Failed
        assertEquals(ModelFailureKind.Overloaded, outcome.kind)
        assertTrue(outcome.message, outcome.message.contains("fb1"))
        assertEquals(3, primary.requests.size)
        assertEquals(3, backup.requests.size)
        assertEquals("只在真换了的时候写一次事件，链用尽不再写", 1, events(rt).count { it is ModelFallback })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a chain of two tries both in order`() = run {
        val primary = ScriptedModel(*failures(3))
        val second = ScriptedModel(*failures(3))
        val third = RoutingModel { reply(TextPart("第三个接住了")) }
        val rt = session("s-two", deps(mapOf(TEST_MODEL to primary, fb1 to second, fb2 to third), listOf(fb1, fb2)))
        val done = rt.say("你好")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        val hops = events(rt).filterIsInstance<ModelFallback>().map { it.from to it.to }
        assertEquals(listOf(TEST_MODEL to fb1, fb1 to fb2), hops)
        assertEquals(fb2, third.requests.single().refId)
        assertLogIsTruth(rt)
    }

    @Test
    fun `an unavailable model switches at once without spending retries`() = run {
        val primary = ScriptedModel(failing(failure(ModelFailureKind.ModelUnavailable)))
        val backup = ScriptedModel(reply(TextPart("好")))
        val rt = session("s-gone", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        val done = rt.say("你好")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        assertEquals("不可用没有重试的意义", 1, primary.requests.size)
        assertEquals(ModelFailureKind.ModelUnavailable, events(rt).filterIsInstance<ModelFallback>().single().reason)
        assertLogIsTruth(rt)
    }

    @Test
    fun `server errors that outlast the retries also fall back`() = run {
        val primary = ScriptedModel(*failures(3) { failure(ModelFailureKind.Server) })
        val backup = ScriptedModel(reply(TextPart("好")))
        val rt = session("s-5xx", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        assertEquals(TurnOutcome.Completed, rt.say("你好").lastTurn?.outcome)
        assertEquals(ModelFailureKind.Server, events(rt).filterIsInstance<ModelFallback>().single().reason)
    }

    @Test
    fun `failures a different model cannot fix never fall back`() = run {
        for (kind in listOf(
            ModelFailureKind.Auth, ModelFailureKind.BadRequest, ModelFailureKind.RateLimited,
            ModelFailureKind.Network, ModelFailureKind.ContextOverflow, ModelFailureKind.Other,
        )) {
            val primary = ScriptedModel(*failures(3) { failure(kind) })
            val backup = ScriptedModel(reply(TextPart("不该走到这里")))
            val rt = session("s-no-$kind", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
            val done = rt.say("你好")

            val outcome = done.lastTurn!!.outcome as TurnOutcome.Failed
            assertEquals("$kind", kind, outcome.kind)
            assertEquals("$kind：回退模型一个请求都不该收到", 0, backup.requests.size)
            assertEquals("$kind", 0, events(rt).count { it is ModelFallback })
            assertLogIsTruth(rt)
        }
    }

    @Test
    fun `without a chain nothing changes`() = run {
        val primary = ScriptedModel(*failures(3))
        val rt = session("s-none", deps(mapOf(TEST_MODEL to primary), emptyList()))
        val outcome = rt.say("你好").lastTurn!!.outcome as TurnOutcome.Failed
        assertEquals(ModelFailureKind.Overloaded, outcome.kind)
        assertFalse(outcome.message.contains("回退"))
        assertEquals(0, events(rt).count { it is ModelFallback })
    }

    @Test
    fun `a cross provider entry brings its own profile and client`() = run {
        val primary = ScriptedModel(*failures(3))
        val other = RoutingModel { reply(TextPart("另一家的回答")) }
        val smaller = TEST_PROFILE.copy(ref = fb2, contextWindow = 32_000, maxOutputTokens = 1_000, supportsImages = true)
        val rt = session("s-cross", deps(mapOf(TEST_MODEL to primary, fb2 to other), listOf(fb2), profiles = mapOf(fb2 to smaller)))
        val done = rt.say("你好")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        val request = other.requests.single()
        assertEquals("other", request.profile.ref.providerId)
        assertEquals("目录里的能力跟着模型走", smaller, request.profile)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a switch to another provider leaves a note saying where the conversation now goes`() = run {
        val primary = ScriptedModel(*failures(3))
        val other = RoutingModel { reply(TextPart("另一家的回答")) }
        val rt = session("s-cross-note", deps(mapOf(TEST_MODEL to primary, fb2 to other), listOf(fb2)))
        rt.say("你好")

        // 对话内容换了供应商，要在对话里说一声（与顾问的数据去向说明同一口径）
        val note = events(rt).filterIsInstance<Note>().single { it.text.contains("other:fb2") }
        assertTrue(note.text, note.text.contains("发给"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `the rest of the turn stays on the fallback and the next turn tries the primary first`() = run {
        val read = FakeTool("Read", READ_ONLY) { dev.min.agent.spi.ToolResult(listOf(TextPart("内容"))) }
        val callRead = ToolCallPart(CallId("c1"), "Read", JsonObject(mapOf("k" to JsonPrimitive("a"))))
        // 主模型：第一回合 3 次过载；第二回合恢复了
        val primary = ScriptedModel(*failures(3), reply(TextPart("主模型回来了")))
        val backup = ScriptedModel(
            reply(callRead, stop = StopReason.ToolUse),
            reply(TextPart("读完了")),
        )
        val rt = session("s-scope", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1), listOf(read)))

        val first = rt.say("读一下")
        assertEquals(TurnOutcome.Completed, first.lastTurn?.outcome)
        assertEquals("工具批之后的下一个请求也在回退模型上，不再先撞一遍主模型", 2, backup.requests.size)
        assertEquals(3, primary.requests.size)

        val second = rt.say("再来")
        assertEquals(TurnOutcome.Completed, second.lastTurn?.outcome)
        assertEquals("下一回合先试主模型", 4, primary.requests.size)
        assertEquals(1, events(rt).count { it is ModelFallback })
        assertLogIsTruth(rt)
    }

    @Test
    fun `thinking from the primary is dropped for the fallback so the request stays legal`() = run {
        val thought = ThinkingPart("主模型的思考", TEST_MODEL, signature = "sig-primary")
        val primary = ScriptedModel(
            reply(thought, TextPart("第一轮回答")),
            *failures(3),
        )
        val backup = RoutingModel { reply(TextPart("回退模型接着答")) }
        val rt = session("s-think", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        rt.say("第一轮")
        val done = rt.say("第二轮")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        val request = backup.requests.single()
        // 内核照实交出历史（含主模型的思考块，origin 记着出身）；发送时投影按目标模型丢掉别家的
        val inHistory = request.messages.flatMap { it.parts }.filterIsInstance<ThinkingPart>()
        assertEquals(listOf(thought), inHistory)
        val sent = request.messages.filter { it.role == dev.min.agent.spi.Role.Assistant }.flatMap {
            partsToSend(it.parts, fb1, dev.min.agent.llm.ReasoningEcho.Always, inToolLoop = false)
        }
        assertTrue("别家的思考块不能发给回退模型：$sent", sent.none { it is ThinkingPart })
        assertEquals("助手消息的正文还在", listOf("第一轮回答"), sent.filterIsInstance<TextPart>().map { it.text })
        assertLogIsTruth(rt)
    }

    @Test
    fun `an unusable entry is skipped with a note and the next one is tried`() = run {
        val primary = ScriptedModel(*failures(3))
        val third = RoutingModel { reply(TextPart("好")) }
        // fb1 没有配置（解析器抛异常）：跳过，接着试 fb2
        val rt = session("s-skip", deps(mapOf(TEST_MODEL to primary, fb2 to third), listOf(fb1, fb2)))
        val done = rt.say("你好")

        assertEquals(TurnOutcome.Completed, done.lastTurn?.outcome)
        val log = events(rt)
        assertEquals(fb2, log.filterIsInstance<ModelFallback>().single().to)
        val note = log.filterIsInstance<Note>().single { it.text.contains("fb1") }
        assertTrue(note.text, note.text.contains("已跳过"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a child agent falls back on its own and leaves the parent's model alone`() = run {
        val agentTool = object : Tool<JsonObject> {
            override val name = "Agent"
            override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
            override val traits = dev.min.agent.spi.ToolTraits(readOnly = true, concurrencySafe = true)
            override fun description(profile: dev.min.agent.spi.HarnessProfile) = "测试用 Agent"
            override fun parse(raw: JsonObject) = dev.min.agent.spi.ToolInput.Valid(raw)
            override fun permissionAsk(input: JsonObject, ctx: dev.min.agent.spi.ToolContext) = dev.min.agent.spi.PermissionAsk(name, name)
            override fun run(input: JsonObject, ctx: dev.min.agent.spi.ToolContext) = flow {
                val r = ctx.agents.delegate(
                    dev.min.agent.spi.DelegateRequest("查", "查一下", null, null, background = false, name = null),
                )
                val report = (r as? dev.min.agent.spi.DelegateResult.Finished)?.report ?: "没有报告：$r"
                emit(dev.min.agent.spi.ToolProgress.Done(dev.min.agent.spi.ToolResult(listOf(TextPart(report)))))
            }
        }
        fun ModelRequest.lastText() = messages.last().parts.filterIsInstance<TextPart>().joinToString { it.text }
        // 主模型：父会话正常工作；子 agent 的请求一律过载
        val primary = RoutingModel { req ->
            when {
                req.lastText() == "查一下" -> failing(overloaded())
                req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", JsonObject(emptyMap())), stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("父会话收尾"))
            }
        }
        val backup = RoutingModel { reply(TextPart("子报告")) }
        val hub = SessionHub(store, deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1), listOf(agentTool)), newScope())
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        parent.send(ClientMsgId("u${++counter}"), listOf(TextPart("开始")))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }

        val child = hub.get(done.children.keys.single())!!
        assertEquals(TurnOutcome.Completed, child.state.value.lastTurn?.outcome)
        assertEquals(1, events(child).count { it is ModelFallback })
        assertEquals("父会话的模型没被动", 0, events(parent).count { it is ModelFallback })
        assertEquals(TEST_MODEL, parent.state.value.settings.model)
        assertEquals(TEST_MODEL, child.state.value.settings.model)
        assertEquals("子 agent 的请求在回退模型上", 1, backup.requests.size)
        assertLogIsTruth(parent)
        assertLogIsTruth(child)
    }

    @Test
    fun `the turn log of a switched turn replays to the same state at every prefix`() = run {
        val primary = ScriptedModel(*failures(3))
        val backup = ScriptedModel(reply(TextPart("好")))
        val rt = session("s-prefix", deps(mapOf(TEST_MODEL to primary, fb1 to backup), listOf(fb1)))
        rt.say("你好")
        val log = store.peek(rt.sessionId)!!
        val records = log.read().toList()
        // 每个前缀都能折叠（严格折叠会对不合法的顺序抛异常），回退期间的前缀里 activeModel 是回退模型
        val seen = records.indices.map { n -> fold(log.header, records.take(n + 1)).activeModel }
        assertTrue(seen.contains(fb1))
        assertEquals(TEST_MODEL, seen.last())
        assertTrue(records.last().event is TurnEnded)
    }
}
