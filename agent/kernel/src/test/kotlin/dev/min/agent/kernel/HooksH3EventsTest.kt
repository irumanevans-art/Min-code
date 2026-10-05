package dev.min.agent.kernel

import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
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
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * H3 剩下五个事件接进内核（docs/agent/detail/hooks设计.md §7 事件表）：SessionStart(resume)、PermissionRequest、
 * SubagentStart、SessionEnd、Notification。这里不碰真网络：http hook 端口是假的，只记下发了什么、回什么；
 * 输入字段按 CC 原文逐字段断言（research/scratch/hooks.md 各事件的 input 一节）。
 */
class HooksH3EventsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class FakeHttp(var reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    /** 像 Bash：默认要人批准（模式 Default 下判定是 Ask） */
    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) =
            if (raw["command"] is JsonPrimitive) ToolInput.Valid(raw) else ToolInput.Invalid("缺 command")

        override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
            PermissionAsk(name, "跑命令", commands = listOf(CommandAccess((input["command"] as JsonPrimitive).content)))

        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran: ${(input["command"] as JsonPrimitive).content}")))))
        }
    }

    private fun hook(event: String, matcher: String? = null, url: String = "https://hooks.example/x") = JsonObject(
        mapOf(
            event to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            matcher?.let { put("matcher", JsonPrimitive(it)) }
                            put(
                                "hooks",
                                kotlinx.serialization.json.JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("http"),
                                                "url" to JsonPrimitive(url),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            ),
        ),
    )

    private fun hub(model: ModelClient, http: FakeHttp, trust: Boolean = false, tools: List<Tool<*>> = listOf(bash)): SessionHub =
        SessionHub(store, hubDeps(model, http, trust, tools), CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })

    private fun hubDeps(model: ModelClient, http: FakeHttp, trust: Boolean = false, tools: List<Tool<*>> = listOf(bash)): KernelDeps =
        KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = tools,
            host = HostPorts(FakeFs(), FakeSearch(), dev.min.agent.testkit.FakeExec(), { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = null,
            trustProjectHooks = { trust },
        )

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    // ---- SessionStart(resume) ----

    @Test
    fun `opening a session as the user runs SessionStart with source resume, once per process`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"上次改到一半：网关那部分"}}""",
            )
        }
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SessionStart", "resume"))))),
        )
        rt.say("干活")
        assertTrue(hub.close(rt.sessionId))

        val reopened = hub.open(rt.sessionId, resumedByUser = true)!!
        assertEquals("只有 resume 那一次（startup 的那次没配上 matcher）", 1, http.sent.size)
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"hook_event_name\":\"SessionStart\""))
        assertTrue(body, body.contains("\"source\":\"resume\""))
        assertTrue(body, body.contains("\"session_id\":\"${rt.sessionId.value}\""))
        assertTrue("通用字段不带 tool_use_id 一类工具字段", !body.contains("tool_name"))
        val ran = events(rt).filterIsInstance<HooksRan>().single()
        assertEquals("SessionStart", ran.event)

        // 上下文挂到重开后第一条人发的消息上（与 startup 同一条路），赶在第一个请求之前
        reopened.say("接着改")
        val resumedMessage = model.requests.last().messages.last()
        assertEquals("接着改", (resumedMessage.parts.first() as TextPart).text)
        val reminder = (resumedMessage.parts.last() as TextPart).text
        assertTrue(reminder, reminder.contains("上次改到一半：网关那部分"))

        // 同一进程里第二次打开不再跑（CC 的 --resume 是每次启动跑一次，进程内不算新的一次）
        assertTrue(hub.close(rt.sessionId))
        hub.open(rt.sessionId, resumedByUser = true)
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `internal opens and child sessions never run SessionStart(resume)`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "委派一个") {
                reply(ToolCallPart(CallId("c1"), "Agent", args("description" to "看看", "prompt" to "去看看", "subagent_type" to "Explore")), stop = StopReason.ToolUse)
            } else reply(TextPart("好"))
        }
        val hub = hub(model, http, tools = listOf(bash, dev.min.agent.tools.AgentTool))
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("委派一个")
        val child = rt.state.value.children.keys.single()
        // 后台委派的报告会在 say 返回后异步送达并唤醒一个新回合：等那个回合真正结束再关，
        // 别让 close 在「回合已启动、TurnStarted 还没落盘」的空窗里卸下还在写的运行时
        rt.state.first { s -> s.turn == null && s.lastTurn?.trigger == dev.min.agent.spi.TurnTrigger.Notification }

        assertTrue(hub.close(rt.sessionId))
        // 内部打开（补交报告那条路）：不带标志，不算用户打开
        hub.open(rt.sessionId)
        // 子会话即使带着标志也不跑（只有根会话有 SessionStart）
        hub.open(child, resumedByUser = true)
        assertTrue(http.sent.isEmpty())
        assertTrue(events(rt).none { it is HooksRan })
    }

    @Test
    fun `the startup matcher does not match a resumed session`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SessionStart", "startup"))))),
        )
        rt.say("干活")
        assertEquals("创建时 startup 跑了一次", 1, http.sent.size)
        assertTrue(hub.close(rt.sessionId))
        hub.open(rt.sessionId, resumedByUser = true)
        assertEquals("matcher 是 startup，resume 不匹配", 1, http.sent.size)
    }

    // ---- PermissionRequest ----

    /** 配了 PermissionRequest（matcher = Bash）的快照 */
    private fun permHook() = HooksLoaded(listOf(HookSourceData("user", hook("PermissionRequest", "Bash"))))

    private fun decision(body: String) = """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":$body}}"""

    private fun bashModel(command: String) = RoutingModel { req ->
        if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to command)), stop = StopReason.ToolUse)
        else reply(TextPart("好"))
    }

    @Test
    fun `an allow answers the approval for the user without a card`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, decision("""{"behavior":"allow"}""")) }
        val model = bashModel("rm -rf build")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.say("清理")
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"hook_event_name\":\"PermissionRequest\""))
        assertTrue(body, body.contains("\"tool_name\":\"Bash\""))
        assertTrue(body, body.contains("\"tool_input\":{\"command\":\"rm -rf build\"}"))
        assertTrue(body, body.contains("\"permission_mode\":\"default\""))
        assertTrue("PermissionRequest 的输入没有 tool_use_id（CC 原文）", !body.contains("tool_use_id"))
        val done = rt.state.value
        assertEquals("hook 答了 allow，工具照常执行", "ran: rm -rf build", (done.toolResults.getValue(CallId("c1")).content.single() as TextPart).text)
        val log = events(rt)
        val resolved = log.filterIsInstance<dev.min.agent.spi.ApprovalResolved>().single()
        assertEquals(dev.min.agent.spi.DecidedBy.Hook("PermissionRequest"), resolved.decidedBy)
        assertEquals(dev.min.agent.spi.ApprovalChoice.Once, resolved.choice)
    }

    @Test
    fun `a deny refuses the call with the hook's message for the model`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, decision("""{"behavior":"deny","message":"公司策略禁止删库"}""")) }
        val model = bashModel("rm -rf build")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.say("清理")
        val result = rt.state.value.toolResults.getValue(CallId("c1"))
        assertTrue(result.isError)
        val text = (result.content.single() as TextPart).text
        assertTrue(text, text.contains("PermissionRequest hook 拒绝了这次调用") && text.contains("公司策略禁止删库"))
        val resolved = events(rt).filterIsInstance<dev.min.agent.spi.ApprovalResolved>().single()
        assertEquals(dev.min.agent.spi.ApprovalChoice.Deny("公司策略禁止删库"), resolved.choice)
        assertEquals(dev.min.agent.spi.DecidedBy.Hook("PermissionRequest"), resolved.decidedBy)
    }

    @Test
    fun `a deny with interrupt ends the turn like continue false`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, decision("""{"behavior":"deny","message":"维护窗口，先别动","interrupt":true}""")) }
        val model = bashModel("rm -rf build")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.say("看看")
        val done = rt.state.value
        assertEquals(dev.min.agent.spi.TurnOutcome.Interrupted(dev.min.agent.spi.InterruptedBy.Hook), done.lastTurn!!.outcome)
        assertEquals("调用被拒，没有执行", true, done.toolResults.getValue(CallId("c1")).isError)
        assertTrue(events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("维护窗口") })
    }

    @Test
    fun `no decision means the human card, after PreToolUse has had its say`() = run {
        // PreToolUse 与 PermissionRequest 都配了：PreToolUse 先跑（合流图顺序），PermissionRequest 在判定是 Ask 之后
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = bashModel("rm -rf build")
        val hooks = HooksLoaded(
            listOf(
                HookSourceData("user", hook("PreToolUse", "Bash")),
                HookSourceData("user", hook("PermissionRequest", "Bash", url = "https://hooks.example/perm")),
            ),
        )
        val rt = hubWith(PermissionMode.Default, model, http, hooks)
        rt.send(ClientMsgId("u1"), listOf(TextPart("清理")))
        rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        val order = http.sent.map { it.url }
        assertEquals(listOf("https://hooks.example/x", "https://hooks.example/perm"), order)
        // 人答了才执行
        rt.answerApproval(
            (rt.state.value.turn!!.calls.getValue(CallId("c1")).phase as CallPhase.AwaitingApproval).approvalId,
            dev.min.agent.spi.ApprovalChoice.Once,
        )
        rt.state.first { it.turn == null }
        assertEquals("ran: rm -rf build", (rt.state.value.toolResults.getValue(CallId("c1")).content.single() as TextPart).text)
    }

    @Test
    fun `the floor keeps asking despite a hook allow`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, decision("""{"behavior":"allow"}""")) }
        val model = bashModel("rm -rf /")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.send(ClientMsgId("u1"), listOf(TextPart("清空")))
        rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        val requested = events(rt).filterIsInstance<dev.min.agent.spi.ApprovalRequested>().single()
        assertNotNull("安全底线的问人，hook 的 allow 越不过", requested.floorReason)
        assertTrue(events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("底线") })
    }

    @Test
    fun `an alwaysAsk tool never takes a hook allow`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, decision("""{"behavior":"allow"}""")) }
        val alwaysAsk = object : Tool<JsonObject> {
            override val name = "ExitPlanMode"
            override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
            override val traits = ToolTraits(alwaysAsk = true)
            override fun description(profile: HarnessProfile) = "批计划"
            override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
            override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "批计划")
            override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
                emit(ToolProgress.Done(ToolResult(listOf(TextPart("计划批了")))))
            }
        }
        val planModel = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "ExitPlanMode", args()), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val deps = hubDeps(planModel, http, tools = listOf(alwaysAsk))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions, initial = listOf(permHook2()))
        rt.send(ClientMsgId("u1"), listOf(TextPart("看计划")))
        rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        assertEquals("alwaysAsk 的问人，hook 的 allow 无效", 1, events(rt).filterIsInstance<dev.min.agent.spi.ApprovalRequested>().size)
        assertTrue(events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("底线") })
    }

    @Test
    fun `an allow may rewrite the input, which is then re-evaluated`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(200, decision("""{"behavior":"allow","updatedInput":{"command":"timeout 60 make"}}"""))
        }
        val model = bashModel("make")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.send(ClientMsgId("u1"), listOf(TextPart("构建")))
        // 改写成另一条仍要问人的命令：重评后照常问人（不再跑第二遍 hook），人批了就用改写后的入参执行
        rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        assertEquals("hook 只跑一次", 1, http.sent.size)
        val ask = (rt.state.value.turn!!.calls.getValue(CallId("c1")).phase as CallPhase.AwaitingApproval).ask
        assertEquals("审批卡上的是改写后的命令", "timeout 60 make", ask.commands.single().raw)
        rt.answerApproval(
            (rt.state.value.turn!!.calls.getValue(CallId("c1")).phase as CallPhase.AwaitingApproval).approvalId,
            dev.min.agent.spi.ApprovalChoice.Once,
        )
        rt.state.first { it.turn == null }
        assertEquals("ran: timeout 60 make", (rt.state.value.toolResults.getValue(CallId("c1")).content.single() as TextPart).text)
        assertEquals(
            "timeout 60 make",
            (events(rt).filterIsInstance<dev.min.agent.spi.ToolInputRewritten>().single().input["command"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `a rewrite into floor territory goes back to the human`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(200, decision("""{"behavior":"allow","updatedInput":{"command":"rm -rf /"}}"""))
        }
        val model = bashModel("make")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.send(ClientMsgId("u1"), listOf(TextPart("构建")))
        // 改写后的入参重评又踩到底线：照常问人
        rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        assertNotNull(events(rt).filterIsInstance<dev.min.agent.spi.ApprovalRequested>().single().floorReason)
    }

    @Test
    fun `updatedPermissions is ignored with a note`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(200, decision("""{"behavior":"allow","updatedPermissions":[{"type":"setMode","mode":"bypassPermissions"}]}"""))
        }
        val model = bashModel("make")
        val rt = hubWith(PermissionMode.Default, model, http, permHook())
        rt.say("构建")
        val ran = events(rt).filterIsInstance<HooksRan>().single { it.event == "PermissionRequest" }
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("updatedPermissions") && it.contains("忽略") })
        assertEquals("模式没有被 hook 改掉", PermissionMode.Default, rt.state.value.settings.mode)
    }

    /** 配了 PermissionRequest（matcher = ExitPlanMode）的快照 */
    private fun permHook2() = HooksLoaded(listOf(HookSourceData("user", hook("PermissionRequest", "ExitPlanMode"))))

    /** Default 模式 + 指定 hooks 的会话（bash 的判定是 Ask，才轮得到 PermissionRequest） */
    private suspend fun hubWith(
        mode: PermissionMode,
        model: ModelClient,
        http: FakeHttp,
        hooks: HooksLoaded,
    ): SessionRuntime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, hubDeps(model, http), scope)
        return hub.create(GuestPath("/workspace"), TEST_MODEL, mode, initial = listOf(hooks))
    }

    // ---- SubagentStart ----

    @Test
    fun `SubagentStart injects context at the subagent's first message`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"SubagentStart","additionalContext":"遵守安全审查清单 SEC-1"}}""",
            )
        }
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "委派一个") {
                reply(ToolCallPart(CallId("c1"), "Agent", args("description" to "看看", "prompt" to "去看看", "subagent_type" to "Explore")), stop = StopReason.ToolUse)
            } else reply(TextPart("好"))
        }
        val hub = hub(model, http, tools = listOf(bash, dev.min.agent.tools.AgentTool))
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SubagentStart", "Explore"))))),
        )
        rt.say("委派一个")
        val child = rt.state.value.children.keys.single()
        rt.state.first { s -> s.children.getValue(child).reportedTurns.isNotEmpty() && s.turn == null }

        val body = http.sent.single().body
        assertTrue(body, body.contains("\"hook_event_name\":\"SubagentStart\""))
        assertTrue(body, body.contains("\"agent_type\":\"Explore\""))
        assertTrue(body, body.contains("\"agent_id\":\"${child.value}\""))
        // 上下文挂在子会话的第一条输入（委派指令）上，赶在子会话的第一个请求之前
        val childEvents = store.open(child)!!.read().toList().map { it.event }
        val contexts = childEvents.filterIsInstance<dev.min.agent.spi.HookContextAdded>()
        assertEquals(1, contexts.size)
        assertEquals(dev.min.agent.spi.HookContextTarget.NextMessage, contexts.single().target)
        assertEquals("SubagentStart", contexts.single().event)
        val childRequest = model.requests.first { req -> req.messages.size == 1 && req.messages.single().parts.filterIsInstance<TextPart>().joinToString("") { it.text } != "委派一个" }
        val delegation = childRequest.messages.single()
        val reminder = (delegation.parts.last() as TextPart).text
        assertTrue(reminder, reminder.contains("<system-reminder>") && reminder.contains("SEC-1"))
        // 日志即真相：子会话自己的日志里记了这批 hook
        assertEquals("SubagentStart", childEvents.filterIsInstance<HooksRan>().single().event)
    }

    @Test
    fun `a fork gets its SubagentStart context after the inherited tool results`() = run {
        val http = FakeHttp {
            HttpHookResult.Responded(
                200,
                """{"hookSpecificOutput":{"hookEventName":"SubagentStart","additionalContext":"分身须知：接着上一条往下干"}}""",
            )
        }
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "分身一个") {
                reply(ToolCallPart(CallId("c1"), "Agent", args("description" to "接着干", "prompt" to "把剩下的做完", "subagent_type" to "fork", "run_in_background" to false)), stop = StopReason.ToolUse)
            } else reply(TextPart("好"))
        }
        val hub = hub(model, http, tools = listOf(bash, dev.min.agent.tools.AgentTool))
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SubagentStart"))))),
        )
        rt.say("分身一个")
        rt.state.first { it.turn == null }
        val child = rt.state.value.children.keys.single()
        val childEvents = store.open(child)!!.read().toList().map { it.event }
        val contexts = childEvents.filterIsInstance<dev.min.agent.spi.HookContextAdded>()
        assertEquals(1, contexts.size)
        val target = contexts.single().target
        assertTrue("分身挂在继承来的最后那条助手消息的工具结果后面", target is dev.min.agent.spi.HookContextTarget.ToolResults)
        // 挂靠的消息在子会话历史里（继承来的那条），reminder 在分身第一个请求里、排在工具结果之后
        val anchor = (target as dev.min.agent.spi.HookContextTarget.ToolResults).assistantMessageId
        val forkRt = hub.get(child)!!
        assertTrue(forkRt.state.value.history.any { it.messageId == anchor && it is HistoryEntry.Assistant })
        val forkRequest = model.requests.first { req -> req.messages.any { m -> m.parts.any { p -> (p as? TextPart)?.text?.contains("分身须知") == true } } }
        val partIndex = forkRequest.messages.indexOfFirst { m -> m.parts.any { p -> (p as? TextPart)?.text?.contains("分身须知") == true } }
        val reminderParts = forkRequest.messages[partIndex].parts
        assertTrue(
            "reminder 在工具结果之后",
            reminderParts.indexOfLast { it is TextPart && it.text.contains("分身须知") } >
                reminderParts.indexOfFirst { it is dev.min.agent.protocol.ToolResultPart },
        )
    }

    @Test
    fun `the matcher filters SubagentStart by agent type`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "委派一个") {
                reply(ToolCallPart(CallId("c1"), "Agent", args("description" to "看看", "prompt" to "去看看", "subagent_type" to "Explore")), stop = StopReason.ToolUse)
            } else reply(TextPart("好"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(
            store, hubDeps(model, http, tools = listOf(bash, dev.min.agent.tools.AgentTool)), scope,
        )
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SubagentStart", "Plan"))))),
        )
        rt.say("委派一个")
        val child = rt.state.value.children.keys.single()
        rt.state.first { s -> s.children.getValue(child).reportedTurns.isNotEmpty() && s.turn == null }
        assertTrue("matcher 是 Plan，Explore 不匹配", http.sent.isEmpty())
    }

    // ---- SessionEnd ----

    @Test
    fun `closing a root session runs SessionEnd before it leaves memory`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SessionEnd"))))),
        )
        rt.say("干活")
        assertTrue(hub.close(rt.sessionId, SessionEndReasons.PROMPT_INPUT_EXIT))
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"hook_event_name\":\"SessionEnd\""))
        assertTrue(body, body.contains("\"reason\":\"prompt_input_exit\""))
        assertTrue(body, body.contains("\"session_id\":\"${rt.sessionId.value}\""))
        assertEquals("SessionEnd", events(rt).filterIsInstance<HooksRan>().single().event)
        // 已卸下后再 close 是空操作（同一进程里 SessionEnd 只跑一次）
        assertTrue(hub.close(rt.sessionId).not())
    }

    @Test
    fun `deleting an offline session still runs SessionEnd before the log goes`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val first = SessionHub(store, hubDeps(model, http), firstScope)
        val rt = first.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SessionEnd", "other"))))),
        )
        rt.say("干活")
        firstScope.cancel()
        // 换一个 Hub（等于换进程）：根会话不在线，删除前也临时打开跑一遍 SessionEnd
        val second = SessionHub(store, hubDeps(model, http), CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })
        assertTrue(second.delete(rt.sessionId))
        assertEquals("删日志之前跑过一次 SessionEnd", 1, http.sent.count { it.body.contains("\"reason\":\"other\"") })
        assertTrue(store.open(rt.sessionId) == null)
    }

    @Test
    fun `a busy tree does not run SessionEnd`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = object : ModelClient {
            override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { kotlinx.coroutines.awaitCancellation() }
        }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("SessionEnd"))))),
        )
        rt.send(ClientMsgId("u1"), listOf(TextPart("干活")))
        rt.state.first { it.turn != null }
        assertTrue("回合在跑，close 拒绝", hub.close(rt.sessionId).not())
        assertTrue(http.sent.isEmpty())
        rt.interrupt()
        rt.state.first { it.turn == null }
    }

}
