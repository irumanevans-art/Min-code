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
import dev.min.agent.spi.HttpPolicyData
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnOutcome
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
        event: String = "PreToolUse",
    ) =
        JsonObject(
            mapOf(
                event to kotlinx.serialization.json.JsonArray(
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
        fs: FakeFs = FakeFs(),
        memoryHome: GuestPath? = null,
        trustProject: Boolean = false,
        idFor: (String) -> String = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
        mode: PermissionMode = PermissionMode.BypassPermissions,
        initial: List<SessionEvent> = emptyList(),
        hiddenEnv: Set<String> = emptySet(),
        exec: FakeExec = FakeExec(),
        envFn: ((String) -> String?)? = null,
    ): SessionRuntime {
        val deps = KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> idFor(prefix) },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = tools,
            host = HostPorts(fs, FakeSearch(), exec, { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = memoryHome,
            trustProjectHooks = { trustProject },
            env = envFn ?: { if (it in hiddenEnv) null else env[it] },
            hiddenEnv = hiddenEnv,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, dev.min.agent.protocol.SessionId("s_hook_${ids.incrementAndGet()}"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode),
            deps,
            scope,
            initial = listOfNotNull(hooks) + initial,
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
            listOf(HookSourceData("user", httpHook("https://hooks.example/x"))),
            httpPolicy = HttpPolicyData(allowedUrls = listOf("https://other.example/y")),
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

    private fun lsModel() = RoutingModel { req ->
        if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "ls")), stop = StopReason.ToolUse)
        else reply(TextPart("好"))
    }

    @Test
    fun `an empty allowedUrls policy blocks every http hook`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val hooks = HooksLoaded(
            listOf(HookSourceData("user", httpHook("https://hooks.example/x"))),
            httpPolicy = HttpPolicyData(allowedUrls = emptyList()),
        )
        val rt = session(lsModel(), hooks, http)
        rt.say("跑一下")
        assertTrue("allowedHttpHookUrls: [] = 拦掉所有 http hook", http.sent.isEmpty())
    }

    @Test
    fun `a url pattern with a wildcard lets matching http hooks through`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val hooks = HooksLoaded(
            listOf(HookSourceData("user", httpHook("https://Hooks.Example.com/pre/tool"))),
            httpPolicy = HttpPolicyData(allowedUrls = listOf("https://hooks.example.com/*")),
        )
        val rt = session(lsModel(), hooks, http)
        rt.say("跑一下")
        assertEquals(1, http.sent.size)
    }

    @Test
    fun `an empty allowedEnvVars policy interpolates nothing`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val headers = JsonObject(mapOf("Authorization" to JsonPrimitive("Bearer \$TOKEN")))
        val hooks = HooksLoaded(
            listOf(HookSourceData("user", httpHook("https://hooks.example/x", allowedEnvVars = listOf("TOKEN"), headers = headers))),
            httpPolicy = HttpPolicyData(allowedEnvVars = emptyList()),
        )
        val rt = session(lsModel(), hooks, http, env = mapOf("TOKEN" to "t0k"))
        rt.say("跑一下")
        assertEquals("Bearer ", http.sent.single().headers["Authorization"])
    }

    @Test
    fun `old logs that carried the gates on each source still decode (unknown keys ignored)`() {
        val old = """{"type":"hooks_loaded","sources":[{"source":"user","hooks":{},"allowedHttpHookUrls":["https://a/*"]}],"notes":["x"]}"""
        val e = dev.min.agent.protocol.AgentJson.decodeFromString(SessionEvent.serializer(), old) as HooksLoaded
        assertEquals(listOf("x"), e.notes)
        assertNull(e.httpPolicy)
    }

    private val userHttpHook = """"hooks":${httpHook("https://hooks.example/x")}"""

    private suspend fun loadedFrom(fs: FakeFs, trust: Boolean): HooksLoaded? {
        val rt = session(RoutingModel { reply(TextPart("好")) }, null, FakeHttp { HttpHookResult.Responded(200, "{}") },
            fs = fs, memoryHome = GuestPath("/root"), trustProject = trust)
        return store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksLoaded>().lastOrNull()
    }

    @Test
    fun `an untrusted project layer cannot switch off the user's hooks`() = runBlocking {
        val fs = FakeFs()
        fs.put("/root/.claude/settings.json", "{$userHttpHook}")
        fs.put("/workspace/.claude/settings.json", """{"disableAllHooks":true}""")
        fs.put("/workspace/.claude/settings.local.json", """{"disableAllHooks":true}""")
        val loaded = loadedFrom(fs, trust = false)!!
        assertEquals(listOf("user"), loaded.sources.map { it.source })
        assertEquals(2, loaded.notes.size)
        assertTrue(loaded.notes.joinToString(), loaded.notes.all { it.contains("disableAllHooks") && it.contains("还没被信任") })
    }

    @Test
    fun `a trusted project layer's disableAllHooks does switch them off`() = runBlocking {
        val fs = FakeFs()
        fs.put("/root/.claude/settings.json", "{$userHttpHook}")
        fs.put("/workspace/.claude/settings.json", """{"disableAllHooks":true}""")
        assertNull(loadedFrom(fs, trust = true))
    }

    @Test
    fun `a managed gate without hooks and untrusted project gates end up in the frozen policy`() = runBlocking {
        val fs = FakeFs()
        fs.put("/root/.claude/settings.json", "{$userHttpHook}")
        fs.put("/etc/claude-code/managed-settings.json", """{"allowedHttpHookUrls":["https://corp.example/*"]}""")
        fs.put("/workspace/.claude/settings.json", """{"allowedHttpHookUrls":["https://hooks.example/*"]}""")
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val rt = session(lsModel(), null, http, fs = fs, memoryHome = GuestPath("/root"), trustProject = false)
        val loaded = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksLoaded>().single()
        assertEquals(listOf("https://corp.example/*"), loaded.httpPolicy!!.allowedUrls)
        rt.say("跑一下")
        assertTrue("managed 的闸挡住了用户的 http hook，项目层的闸没被信任所以放不了行", http.sent.isEmpty())
    }

    private val allowAll = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}"""

    private fun askRule(text: String) = PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Ask, text, "user")))

    @Test
    fun `a hook allow cannot override an ask rule, the call still goes to the user`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { HttpHookResult.Responded(200, allowAll) }
        val rt = session(lsModel(), envlessHook(), http, mode = PermissionMode.Default, initial = listOf(askRule("Bash(ls *)")))
        rt.send(ClientMsgId("u-ask"), listOf(TextPart("看看")))
        val asking = rt.state.first { it.turn?.calls?.get(CallId("c1"))?.phase is CallPhase.AwaitingApproval }
        assertEquals("hook 确实回了 allow", 1, http.sent.size)
        assertTrue(asking.turn!!.calls.getValue(CallId("c1")).phase is CallPhase.AwaitingApproval)
        assertNull("没人批准之前不能执行", asking.toolResults[CallId("c1")])
    } }

    @Test
    fun `a hook allow still skips the prompt when no ask rule is in the way`() = runBlocking {
        val http = FakeHttp { HttpHookResult.Responded(200, allowAll) }
        val rt = session(lsModel(), envlessHook(), http, mode = PermissionMode.Default, initial = listOf(askRule("Bash(git push *)")))
        rt.say("看看")
        assertEquals(1, http.sent.size)
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).let { !it.isError })
    }

    @Test
    fun `an http client that throws is a note, not a failed turn`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { error("boom https://hooks.example/x?token=SECRET") }
        val rt = session(lsModel(), envlessHook(), http)
        val done = rt.say("跑一下")
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        assertTrue("结论中性，调用照常执行", rt.state.value.toolResults.getValue(CallId("c1")).let { !it.isError })
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertEquals("neutral", ran.decision)
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("HTTP 请求失败") })
        assertTrue("异常信息里的令牌不进日志", ran.problems.none { it.contains("SECRET") })
    } }

    @Test
    fun `an exception that escapes the http runner itself is contained by the driver`() = runBlocking { withTimeout(20_000) {
        // 环境变量读取口抛异常：发生在执行器之外（组请求头用的环境表），只有 HookDriver 的兜底管得到
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x", allowedEnvVars = listOf("BOOM")))))
        val rt = session(lsModel(), hooks, FakeHttp { HttpHookResult.Responded(200, "{}") }, envFn = { error("env down") })
        rt.say("跑一下")
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("http hook 批执行失败") })
    } }

    @Test
    fun `a throwing http hook on SessionStart does not stop the session from being created`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { throw java.io.IOException("down") }
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x", matcher = "startup", event = "SessionStart"))))
        val rt = session(RoutingModel { reply(TextPart("好")) }, hooks, http)
        rt.say("你好")
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
    } }

    @Test
    fun `a command executor that throws is also contained`() = runBlocking { withTimeout(20_000) {
        val commandHook = HooksLoaded(listOf(HookSourceData("user", kotlinx.serialization.json.Json.parseToJsonElement(
            """{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"true"}]}]}""",
        ) as JsonObject)))
        // 批 id 带点：批执行器的前置检查会抛 IllegalArgumentException（在它自己的 try 之外）
        val rt = session(lsModel(), commandHook, FakeHttp { HttpHookResult.Responded(200, "{}") },
            idFor = { prefix -> if (prefix == "hooks") "hooks.bad" else "${prefix}_${ids.incrementAndGet()}" })
        rt.say("跑一下")
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("command hook 批执行失败") })
    } }

    @Test
    fun `a truncated http response body is reported in the problems`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { HttpHookResult.Responded(200, "{", truncated = true) }
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x"))))
        val rt = session(lsModel(), hooks, http)
        rt.say("跑一下")
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("已截断") && it.contains("https://hooks.example/x") })
    } }

    @Test
    fun `urls in the problems lose their userinfo and query string`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { HttpHookResult.Failed("refused") }
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://user:PW@hooks.example/x?token=SECRET"))),
            httpPolicy = HttpPolicyData(allowedUrls = listOf("https://other.example/*")))
        val rt = session(lsModel(), hooks, http)
        rt.say("跑一下")
        // 被闸门拦下的那条说明也不带令牌
        val events = store.open(rt.sessionId)!!.read().toList().map { it.event }
        val text = events.filterIsInstance<HooksRan>().flatMap { it.problems } + events.filterIsInstance<dev.min.agent.spi.Note>().map { it.text }
        assertTrue(text.joinToString(), text.any { it.contains("https://hooks.example/x") })
        assertTrue(text.joinToString(), text.none { it.contains("SECRET") || it.contains("PW") })

        val open = FakeHttp { HttpHookResult.Failed("refused") }
        val rt2 = session(lsModel(), HooksLoaded(listOf(HookSourceData("user", httpHook("https://user:PW@hooks.example/x?token=SECRET")))), open)
        rt2.say("跑一下")
        val events2 = store.open(rt2.sessionId)!!.read().toList().map { it.event }
        val text2 = events2.filterIsInstance<HooksRan>().flatMap { it.problems } + events2.filterIsInstance<dev.min.agent.spi.Note>().map { it.text }
        assertTrue("失败说明里署名的 URL 也一样", text2.any { it.contains("https://hooks.example/x") } && text2.none { it.contains("SECRET") || it.contains("PW") })

        // 超时说明（HookDriver 自己署名的那条）也一样
        val slow = FakeHttp { HttpHookResult.TimedOut }
        // PreToolUse 超时会升成 ask（要人确认），这里只看说明，换成超时中性的 PostToolUse
        val rt3 = session(lsModel(), HooksLoaded(listOf(HookSourceData("user", httpHook("https://user:PW@hooks.example/x?token=SECRET", event = "PostToolUse")))), slow)
        rt3.say("跑一下")
        val ran3 = store.open(rt3.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran3.problems.joinToString(), ran3.problems.any { it.contains("超时") && it.contains("https://hooks.example/x") })
        assertTrue(ran3.problems.joinToString(), ran3.problems.none { it.contains("SECRET") || it.contains("PW") })
    } }

    @Test
    fun `an allowedEnvVars entry naming a hidden variable interpolates to empty and says why`() = runBlocking { withTimeout(20_000) {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val headers = JsonObject(mapOf("Authorization" to JsonPrimitive("Bearer \$KEY")))
        val hooks = HooksLoaded(listOf(HookSourceData("user", httpHook("https://hooks.example/x", allowedEnvVars = listOf("KEY"), headers = headers))))
        val rt = session(lsModel(), hooks, http, env = mapOf("KEY" to "sk-model-key"), hiddenEnv = setOf("KEY"))
        rt.say("跑一下")
        assertEquals("Bearer ", http.sent.single().headers["Authorization"])
        val ran = store.open(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single()
        assertTrue(ran.problems.joinToString(), ran.problems.any { it.contains("KEY") && it.contains("空串") })
    } }

    @Test
    fun `the command batch and the http batch run concurrently`() = runBlocking { withTimeout(30_000) {
        val httpSeen = java.util.concurrent.CountDownLatch(1)
        var execSawHttp = false
        val exec = FakeExec { command, _ ->
            // 批脚本本身（不是清理命令）一直等到 http 请求发出：串行的话 http 永远排在它后面，这里会等到超时
            if (command.script.contains("batch.sh")) execSawHttp = httpSeen.await(5, java.util.concurrent.TimeUnit.SECONDS)
            dev.min.agent.spi.ExecResult.Exited(0, "", false, 1)
        }
        val http = FakeHttp { httpSeen.countDown(); HttpHookResult.Responded(200, "{}") }
        val both = JsonObject(
            mapOf("PreToolUse" to kotlinx.serialization.json.JsonArray(listOf(
                JsonObject(mapOf("matcher" to JsonPrimitive("Bash"), "hooks" to kotlinx.serialization.json.JsonArray(listOf(
                    JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive("https://hooks.example/x"))),
                    JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive("true"))),
                )))),
            ))),
        )
        val rt = session(lsModel(), HooksLoaded(listOf(HookSourceData("user", both))), http, exec = exec)
        rt.say("跑一下")
        assertTrue("http 请求发出时，command 批还在跑", execSawHttp)
    } }

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
