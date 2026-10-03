package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.SessionCreated
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * `type:http` 接进会话（docs/agent/detail/hooks设计.md §8 H3）：POST 由宿主端口代发，
 * 应答按 CC 的「HTTP response handling」翻译成与 command 同一套的结论。
 *
 * 这里不碰真网络：端口是假的，只记下发了什么、回什么。
 */
class HttpHookSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

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
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran")))))
        }
    }

    private fun httpHook(
        url: String,
        timeout: Int? = null,
        allowedEnvVars: List<String>? = null,
        headers: JsonObject = JsonObject(emptyMap()),
        matcher: String = "Bash",
    ) =
        JsonObject(
            mapOf(
                "PreToolUse" to kotlinx.serialization.json.JsonArray(
                    listOf(
                        JsonObject(
                            buildMap {
                                put("matcher", JsonPrimitive(matcher))
                                put(
                                    "hooks",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            JsonObject(
                                                buildMap {
                                                    put("type", JsonPrimitive("http"))
                                                    put("url", JsonPrimitive(url))
                                                    if (headers.isNotEmpty()) put("headers", headers)
                                                    if (allowedEnvVars != null) put("allowedEnvVars", kotlinx.serialization.json.JsonArray(allowedEnvVars.map { JsonPrimitive(it) }))
                                                    if (timeout != null) put("timeout", JsonPrimitive(timeout))
                                                },
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

    private suspend fun session(
        model: ModelClient,
        hooks: HooksLoaded?,
        http: FakeHttp,
        env: Map<String, String> = emptyMap(),
        tools: List<Tool<*>> = listOf(bash),
    ): SessionRuntime {
        val deps = KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = tools,
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            env = { env[it] },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, dev.min.agent.protocol.SessionId("s_hook_${ids.incrementAndGet()}"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps,
            scope,
            initial = listOfNotNull(hooks),
        )
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null }
    }

    private fun envlessHook() = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x"))))

    @Test
    fun `a 2xx json body decides the call like a command hook would`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"公司策略"}}""") }
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "ls")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val rt = session(model, envlessHook(), http)
        rt.say("跑一下")
        assertEquals(1, http.sent.size)
        assertTrue("事件 JSON 原文 POST 出去", http.sent.single().body.contains("\"hook_event_name\":\"PreToolUse\""))
        assertEquals("https://hooks.example/x", http.sent.single().url)
        val ran = rt.state.value.let { _ -> store.open(rt.sessionId)!!.read().toList().map { it.event } }
            .filterIsInstance<HooksRan>().single()
        assertEquals("deny 与 command 的 deny 同一结论", "block", ran.decision)
    }

    @Test
    fun `a url outside allowedHttpHookUrls is skipped and never sent`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val hooks = HooksLoaded(
            listOf(HookSourceData("user", httpHook("https://hooks.example/x"), allowedHttpHookUrls = listOf("https://other.example/y"))),
        )
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "ls")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val rt = session(model, hooks, http)
        rt.say("跑一下")
        assertTrue("闸门拦下就不该发请求", http.sent.isEmpty())
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertEquals("neutral", ran.decision)
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("allowedHttpHookUrls") })
    }

    @Test
    fun `header interpolation only reads the names both gates allow`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val headers = JsonObject(mapOf("Authorization" to JsonPrimitive("Bearer \$TOKEN"), "X-Other" to JsonPrimitive("\$SECRET")))
        val hooks = HooksLoaded(
            listOf(HookSourceData("user", httpHook("https://hooks.example/x", allowedEnvVars = listOf("TOKEN", "SECRET"), headers = headers))),
        )
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "ls")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        // 只有 TOKEN 在进程环境里；SECRET 没有 → 空串。settings 的闸没定义 = 不额外限制
        val rt = session(model, hooks, http, env = mapOf("TOKEN" to "t0k"))
        rt.say("跑一下")
        val sent = http.sent.single()
        assertEquals("Bearer t0k", sent.headers["Authorization"])
        assertEquals("", sent.headers["X-Other"])
    }

    @Test
    fun `an http hook that cannot connect is a note, not a silent pass`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Failed("connection refused") }
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "ls")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val rt = session(model, envlessHook(), http)
        rt.say("跑一下")
        assertTrue(http.sent.isNotEmpty())
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran.problems.joinToString(), ran.problems.isNotEmpty())
    }

    /** 只读、可并发：两个一起来时本该进并行批（并行批不经过 PreToolUse） */
    private val reader = object : Tool<JsonObject> {
        override val name = "Peek"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "看一眼"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "看一眼")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("看到了")))))
        }
    }

    @Test
    fun `an http-only gate still sees read-only calls that would otherwise run in parallel`() = runBlocking {
        val deny = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"不许看"}}"""
        val http = FakeHttp { HttpHookResult.Responded(200, deny) }
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x", matcher = "Peek"))))
        val model = RoutingModel { req ->
            if (req.messages.size == 1) {
                reply(ToolCallPart(CallId("p1"), "Peek", args()), ToolCallPart(CallId("p2"), "Peek", args()), stop = StopReason.ToolUse)
            } else reply(TextPart("好"))
        }
        val rt = session(model, hooks, http, tools = listOf(reader))
        rt.say("看两眼")
        assertEquals("两个调用都过了门禁", 2, http.sent.size)
        val results = rt.state.value.toolResults
        assertTrue("门禁说不许就都没执行", listOf("p1", "p2").all { results.getValue(CallId(it)).isError })
    }

    @Test
    fun `no http handler means the port is never called`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { reply(TextPart("好")) }
        val rt = session(model, null, http)
        rt.say("随口一句")
        assertTrue(http.sent.isEmpty())
        assertTrue(store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().isEmpty())
    }
}
