package dev.min.agent.kernel

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
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
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Note
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.tools.SkillTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * skill / 子 agent frontmatter 里的 hooks（docs/agent/detail/hooks设计.md §frontmatter）：调用 skill 那一刻登记、
 * 本会话余下回合生效；`once` 成功跑一次后移除；子 agent 的 hooks 只写进子会话、Stop 改成 SubagentStop；
 * 项目层要过工作区信任。hooks 用 type:http 的处理器（FakeHttp 端口回放），不碰真 shell。
 */
class ComponentHooksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = ComponentFs()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun msg() = ClientMsgId("u${ids.incrementAndGet()}")

    private class FakeHttp(var reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { emitAll(route(request)(request)) }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) =
            if (raw["command"] is JsonPrimitive) ToolInput.Valid(raw) else ToolInput.Invalid("缺 command")
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "跑命令")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran: ${(input["command"] as JsonPrimitive).content}")))))
        }
    }

    /** 只是把入参转给端口的 Agent（照 SubagentTest 的 PortTool，这里只测登记，不跑子回合的工具） */
    private val agentTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "委派"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val request = DelegateRequest(
                description = "任务", prompt = (input["prompt"] as? JsonPrimitive)?.content ?: "去看看",
                agentType = (input["subagent_type"] as? JsonPrimitive)?.content, background = false,
            )
            emit(ToolProgress.Done(when (val r = ctx.agents.delegate(request)) {
                is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动")))
                is DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
                is DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
            }))
        }
    }

    private fun skillFile(description: String, hooksYaml: String, body: String) =
        "---\ndescription: $description\nhooks:\n$hooksYaml---\n$body"

    /** user 层的 skill「gate」：PreToolUse 的 http 门禁；「announce」：PostToolUse 的 once 通知 */
    private fun seedSkills() {
        fs.files["/root/.claude/skills/gate/SKILL.md"] = skillFile(
            "gated", """
  PreToolUse:
    - matcher: Bash
      hooks:
        - type: http
          url: https://hooks.example/gate
""",
            "Do the gated thing.",
        )
        fs.files["/root/.claude/skills/announce/SKILL.md"] = skillFile(
            "announce", """
  PostToolUse:
    - matcher: Bash
      hooks:
        - type: http
          url: https://hooks.example/once
          once: true
""",
            "Announce things.",
        )
        fs.files["/root/.claude/skills/strict-gate/SKILL.md"] = skillFile(
            "strict gate", """
  PreToolUse:
    - matcher: Bash
      hooks:
        - type: http
          url: https://hooks.example/strict
          once: true
""",
            "Strictly gated thing.",
        )
        fs.files["/root/.claude/skills/stopper/SKILL.md"] = skillFile(
            "stopper", """
  Stop:
    - hooks:
        - type: http
          url: https://hooks.example/stop
""",
            "Stop things.",
        )
    }

    private fun deps(model: ModelClient, http: FakeHttp, trust: Boolean = false, tools: List<Tool<*>> = listOf(bash, SkillTool)): KernelDeps {
        val base = dev.min.agent.testkit.testDeps(model, tools)
        return KernelDeps(
            clock = base.clock,
            ids = base.ids,
            models = base.models,
            tools = tools,
            host = dev.min.agent.kernel.HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, httpHooks = http),
            platform = base.platform,
            zone = base.zone,
            retry = base.retry,
            memoryHome = GuestPath("/root"),
            trustProjectHooks = { trust },
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
        )
    }

    private fun hub(
        model: ModelClient,
        http: FakeHttp,
        trust: Boolean = false,
        agents: List<AgentDefinition> = emptyList(),
        tools: List<Tool<*>> = listOf(bash, SkillTool),
    ): SessionHub {
        seedSkills()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps(model, http, trust, tools), scope, agents = dev.min.agent.kernel.BuiltinAgents.all + agents)
    }

    private suspend fun events(id: SessionId): List<SessionEvent> = store.open(id)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(msg(), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private fun bashCall(id: String, command: String) = ToolCallPart(CallId(id), "Bash", args("command" to command))
    private fun skillCall(id: String, name: String) = ToolCallPart(CallId(id), "Skill", args("skill" to name))
    private fun agentCall(id: String, type: String) = ToolCallPart(CallId(id), "Agent", args("subagent_type" to type, "prompt" to "去看看", "run_in_background" to false))

    // ---- skill 的 frontmatter hooks ----

    @Test
    fun `skill 的 hooks 调用前不生效、调用后跨回合生效`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"门禁不放行"}}""") }
        val model = RoutingModel { req ->
            when (req.lastText()) {
                "开始" -> reply(bashCall("c1", "echo one"), stop = StopReason.ToolUse)
                "ran: echo one" -> reply(skillCall("c2", "gate"), stop = StopReason.ToolUse)
                "再跑" -> reply(bashCall("c4", "echo three"), stop = StopReason.ToolUse)
                // skill 正文带着「Base directory for this skill: …」前缀，用包含匹配
                else -> if (req.lastText().contains("Do the gated thing")) {
                    reply(bashCall("c3", "echo two"), stop = StopReason.ToolUse)
                } else reply(TextPart("被拦了"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("开始")
        // 第一个 Bash 在 skill 调用之前执行：没被门禁碰过
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError.not())
        // skill 调用（模型走的 Skill 工具）之后，同一回合里的第二个 Bash 就被拦了
        val denied = rt.state.value.toolResults.getValue(CallId("c3"))
        assertTrue(denied.isError)
        assertTrue((denied.content.single() as TextPart).text.contains("门禁不放行"))
        assertEquals("门禁只对 skill 登记之后的调用生效", 1, http.sent.size)
        // 下一回合（say 之间会话空闲过）依然生效
        rt.say("再跑")
        assertTrue(rt.state.value.toolResults.getValue(CallId("c4")).isError)
        assertEquals(2, http.sent.size)
        val registered = events(rt.sessionId).filterIsInstance<dev.min.agent.spi.ComponentHooksRegistered>().single()
        assertEquals("skill", registered.kind)
        assertEquals("gate", registered.name)
        assertEquals("user", registered.layer)
        // M2：http 处理器走哪条通道是分流事实，不是问题——不该每次触发都刷一条 Warning
        assertTrue(
            "http 分流说明不落 Warning",
            events(rt.sessionId).filterIsInstance<Note>().none { it.text.contains("http 通道") || it.text.contains("不在本批") },
        )
        assertLogIsTruth(rt)
    }

    @Test
    fun `once 的 hook 成功跑一次后移除`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            when {
                req.lastText().contains("Announce") -> reply(bashCall("c9", "echo"), stop = StopReason.ToolUse)
                req.lastText().startsWith("ran:") -> reply(TextPart("done"))
                req.lastText() == "setup" -> reply(skillCall("c1", "announce"), stop = StopReason.ToolUse)
                // 再跑 / 第三次：真的再调 Bash——once 若没被移除，PostToolUse 会再发一次 http
                req.lastText() == "再跑" -> reply(bashCall("c2", "echo"), stop = StopReason.ToolUse)
                req.lastText() == "第三次" -> reply(bashCall("c3", "echo"), stop = StopReason.ToolUse)
                else -> reply(TextPart("done"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        // 第一回合调 skill 登记 once 门禁 + 调一次 Bash（PostToolUse 成功 → once 应被移除）
        rt.say("setup")
        rt.state.first { s -> s.componentHooks.isEmpty() }
        val updates = events(rt.sessionId).filterIsInstance<dev.min.agent.spi.ComponentHooksUpdated>()
        assertEquals("成功一次后落了整份替换", 1, updates.size)
        assertEquals(0, updates.single().hooks.size)
        // 后续回合真的又跑了 Bash：PostToolUse 没有可跑的处理器，不再发
        rt.say("再跑")
        rt.say("第三次")
        assertEquals("once 成功后不再发（后面两次 Bash 是真触发）", 1, http.sent.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `once 用掉后重开会话仍不回来`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            when {
                req.lastText().contains("Announce") -> reply(bashCall("c9", "echo"), stop = StopReason.ToolUse)
                req.lastText().startsWith("ran:") -> reply(TextPart("done"))
                req.lastText() == "setup" -> reply(skillCall("c1", "announce"), stop = StopReason.ToolUse)
                else -> reply(TextPart("done"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("setup")
        rt.state.first { s -> s.componentHooks.isEmpty() }
        assertEquals(1, http.sent.size)
        // 重开（新 hub 模拟新进程）：once 的移除也折叠回来，不是只在内存里
        val reopenedModel = RoutingModel { req ->
            if (req.lastText() == "继续") reply(bashCall("k1", "echo"), stop = StopReason.ToolUse) else reply(TextPart("done"))
        }
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val reopened = SessionHub(store, deps(reopenedModel, http), scope2).open(rt.sessionId)!!
        assertTrue("重开后 once 用掉的登记不回来", reopened.state.value.componentHooks.none { it.name == "announce" })
        reopened.say("继续")
        assertEquals("重开后也不再发", 1, http.sent.size)
        assertLogIsTruth(reopened)
    }

    @Test
    fun `once 的 hook 失败保留、下次接着跑`() = run {
        val http = FakeHttp { HttpHookResult.Failed("连不上") }
        val model = RoutingModel { req ->
            when {
                req.lastText().contains("Announce") -> reply(bashCall("c9", "echo"), stop = StopReason.ToolUse)
                req.lastText().startsWith("ran:") -> reply(TextPart("done"))
                req.lastText() == "跑" -> reply(skillCall("c1", "announce"), stop = StopReason.ToolUse)
                req.lastText() == "再跑" -> reply(bashCall("c2", "echo"), stop = StopReason.ToolUse)
                else -> reply(TextPart("done"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        rt.state.first { it.componentHooks.isNotEmpty() }
        rt.say("再跑")
        rt.state.first { s -> s.lastTurn != null && s.turn == null }
        assertEquals("失败的 once 保留：两个回合各跑一次", 2, http.sent.size)
        assertTrue("登记没有被撤掉", rt.state.value.componentHooks.isNotEmpty())
        assertLogIsTruth(rt)
    }

    @Test
    fun `重开会话后已登记的 skill hooks 仍然生效`() = run {
        val deny = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"还是不放行"}}"""
        val http = FakeHttp { HttpHookResult.Responded(200, deny) }
        val model = RoutingModel { req ->
            when {
                req.lastText() == "开始" -> reply(bashCall("c1", "echo"), stop = StopReason.ToolUse)
                req.lastText().startsWith("ran:") -> reply(TextPart("done"))
                req.lastText() == "用 gate skill" -> reply(skillCall("c0", "gate"), stop = StopReason.ToolUse)
                // skill 结果（带 Base directory 前缀）之后的 Bash 就被拦
                req.lastText().contains("Do the gated thing") -> reply(bashCall("c5", "echo"), stop = StopReason.ToolUse)
                else -> reply(TextPart("被拦了"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("开始")
        rt.say("用 gate skill")
        // skill 调用之后的同一回合里 Bash 就被拦
        rt.state.first { it.toolResults.containsKey(CallId("c0")) }
        rt.state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.toolCalls == 2 }
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError.not())
        // 重开（新 hub 模拟新进程）：登记从日志折叠回来，门禁还在
        val reopenedModel = RoutingModel { req ->
            if (req.lastText() == "继续") reply(bashCall("k1", "echo"), stop = StopReason.ToolUse) else reply(TextPart("被拦了"))
        }
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val reopened = SessionHub(store, deps(reopenedModel, http), scope2).open(rt.sessionId)!!
        assertTrue("重开后已登记的 skill hooks 仍在", reopened.state.value.componentHooks.any { it.name == "gate" })
        reopened.say("继续")
        assertTrue("重开后门禁照样拦", reopened.state.value.toolResults.getValue(CallId("k1")).isError)
        assertEquals(2, http.sent.size)
        assertLogIsTruth(reopened)
    }

    // ---- 子 agent 的 frontmatter hooks ----

    private fun commandHooks(vararg events: Pair<String, String>): JsonObject = JsonObject(
        events.associate { (name, command) ->
            name to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "hooks" to kotlinx.serialization.json.JsonArray(
                                listOf(JsonObject(mapOf("type" to JsonPrimitive("command"), "command" to JsonPrimitive(command)))),
                            ),
                        ),
                    ),
                ),
            )
        },
    )

    @Test
    fun `子 agent 的 hooks 写进子会话且 Stop 改成 SubagentStop，父会话拿不到`() = run {
        val def = AgentDefinition(
            name = "gatekeeper", description = "带门禁的", instructions = "Be careful.",
            hooks = commandHooks("Stop" to "true", "PostToolUse" to "true"), hooksLayer = "project",
        )
        val model = RoutingModel { req ->
            when {
                req.lastText() == "去吧" -> reply(agentCall("c1", "gatekeeper"), stop = StopReason.ToolUse)
                req.lastText() == "去看看" -> reply(TextPart("子 agent 干完了"))
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model, FakeHttp { HttpHookResult.Responded(200, "{}") }, trust = true, agents = listOf(def), tools = listOf(bash, SkillTool, agentTool))
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("去吧")
        val child = h.get(rt.state.first { it.children.isNotEmpty() }.children.keys.first())!!
        child.awaitIdle()
        val registered = events(child.sessionId).filterIsInstance<dev.min.agent.spi.ComponentHooksRegistered>()
        assertEquals(1, registered.size)
        assertEquals("agent", registered.single().kind)
        assertEquals("gatekeeper", registered.single().name)
        // Stop 改写成了 SubagentStop（子会话结束触发的是它自己的完成事件），PostToolUse 原样
        assertEquals(setOf("SubagentStop", "PostToolUse"), registered.single().hooks.keys)
        assertFalse("父会话不带走子 agent 的 hooks", events(rt.sessionId).any { it is dev.min.agent.spi.ComponentHooksRegistered })
        assertLogIsTruth(child)
        assertLogIsTruth(rt)
    }

    @Test
    fun `未信任目录的项目子 agent hooks 不跑`() = run {
        val def = AgentDefinition(
            name = "gatekeeper", description = "带门禁的", instructions = "Be careful.",
            hooks = commandHooks("Stop" to "true"), hooksLayer = "project",
        )
        val model = RoutingModel { req ->
            when {
                req.lastText() == "去吧" -> reply(agentCall("c1", "gatekeeper"), stop = StopReason.ToolUse)
                req.lastText() == "去看看" -> reply(TextPart("子 agent 干完了"))
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model, FakeHttp { HttpHookResult.Responded(200, "{}") }, trust = false, agents = listOf(def), tools = listOf(bash, SkillTool, agentTool))
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("去吧")
        val child = h.get(rt.state.first { it.children.isNotEmpty() }.children.keys.first())!!
        child.awaitIdle()
        assertTrue("未信任：子会话没有登记", events(child.sessionId).none { it is dev.min.agent.spi.ComponentHooksRegistered })
        assertTrue(
            "父会话收到一条说明",
            events(rt.sessionId).filterIsInstance<Note>().any { it.text.contains("frontmatter hooks 未启用") },
        )
    }

    // ---- M3：once 的成功口径 ----

    @Test
    fun `once 的 hook 用 JSON 拒绝也算拦截，不移除`() = run {
        val deny = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"门禁不放行"}}"""
        val http = FakeHttp { HttpHookResult.Responded(200, deny) }
        val denies = java.util.concurrent.atomic.AtomicInteger(0)
        val model = RoutingModel { req ->
            when {
                req.lastText() == "setup" -> reply(skillCall("c0", "strict-gate"), stop = StopReason.ToolUse)
                req.lastText().contains("Strictly gated") -> reply(bashCall("c1", "echo one"), stop = StopReason.ToolUse)
                // 被拒的调用结果带理由：第一次被拒再试一次，看 once 是否被错误地消耗掉
                req.lastText().contains("不放行") && denies.incrementAndGet() == 1 ->
                    reply(bashCall("c2", "echo two"), stop = StopReason.ToolUse)
                else -> reply(TextPart("还是被拦"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("setup")
        rt.state.first { it.toolResults.containsKey(CallId("c2")) && it.turn == null }
        assertTrue("第一次调用被门禁拒了", rt.state.value.toolResults.getValue(CallId("c1")).isError)
        assertTrue("JSON 拒绝算拦截：once 不消耗，第二次照样被拦", rt.state.value.toolResults.getValue(CallId("c2")).isError)
        assertEquals("两次都发了 http", 2, http.sent.size)
        assertTrue("hook 保留在登记里", rt.state.value.componentHooks.any { it.name == "strict-gate" && it.hooks.isNotEmpty() })
        assertLogIsTruth(rt)
    }

    // ---- L6：重复调用重新登记全量 ----

    @Test
    fun `skill 再次被调用时重新登记全部 hooks，含 once 已用掉的`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val skillCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val model = RoutingModel { req ->
            when {
                // 两次调 skill 的结果都长这样：按次数分流
                req.lastText().contains("Announce") && skillCalls.incrementAndGet() == 1 ->
                    reply(bashCall("c9", "echo"), stop = StopReason.ToolUse)
                req.lastText().contains("Announce") -> reply(bashCall("c11", "echo"), stop = StopReason.ToolUse)
                req.lastText().startsWith("ran:") -> reply(TextPart("done"))
                req.lastText() == "setup" -> reply(skillCall("c1", "announce"), stop = StopReason.ToolUse)
                req.lastText() == "再来" -> reply(skillCall("c10", "announce"), stop = StopReason.ToolUse)
                else -> reply(TextPart("done"))
            }
        }
        val h = hub(model, http)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("setup")
        rt.state.first { it.componentHooks.isEmpty() }
        assertEquals("第一回合用掉 once", 1, http.sent.size)
        // 再调一次 skill：登记整份回来（CC 的 invoke 时 register），后面的 Bash 又会触发一次。
        // 不等「非空」状态——重登记与再次用掉接连发生，StateFlow 合并会跳过中间值，以事件与计数为准
        rt.say("再来")
        assertEquals("重新登记后再发一次", 2, http.sent.size)
        assertEquals("第二次调用又落了一条登记", 2, events(rt.sessionId).count { it is dev.min.agent.spi.ComponentHooksRegistered && (it as dev.min.agent.spi.ComponentHooksRegistered).name == "announce" })
        assertLogIsTruth(rt)
    }

    // ---- M8：组件 hooks 传给子 agent ----

    @Test
    fun `子 agent 继承父会话的组件 hooks，Stop 改成 SubagentStop`() = run {
        val deny = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"门禁不放行"}}"""
        // 门禁 URL 回拒绝，别的（Stop / SubagentStop 通知）回中性——拒绝 Stop 会把回合打回去重跑
        val http = FakeHttp { r ->
            if (r.url.endsWith("/gate")) HttpHookResult.Responded(200, deny) else HttpHookResult.Responded(200, "{}")
        }
        val model = RoutingModel { req ->
            when {
                req.lastText() == "开始" -> reply(skillCall("c1", "gate"), stop = StopReason.ToolUse)
                req.lastText().contains("Do the gated thing") -> reply(skillCall("c2", "stopper"), stop = StopReason.ToolUse)
                req.lastText().contains("Stop things") -> reply(agentCall("c3", "general-purpose"), stop = StopReason.ToolUse)
                req.lastText() == "去看看" -> reply(bashCall("k1", "echo"), stop = StopReason.ToolUse)
                req.lastText().contains("不放行") -> reply(TextPart("被拦了，不跑了"))
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model, http, tools = listOf(bash, SkillTool, agentTool))
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("开始")
        val child = h.get(rt.state.first { it.children.isNotEmpty() }.children.keys.first())!!
        child.awaitIdle()
        val inherited = events(child.sessionId).filterIsInstance<dev.min.agent.spi.ComponentHooksRegistered>()
        // 父会话登记过的 skill hooks 都以快照进了子会话；Stop 改写成 SubagentStop
        val gate = inherited.single { it.name == "gate" }
        assertEquals(setOf("PreToolUse"), gate.hooks.keys)
        val stopper = inherited.single { it.name == "stopper" }
        assertEquals("Stop 在子会话里改写成 SubagentStop", setOf("SubagentStop"), stopper.hooks.keys)
        // 门禁真的在子会话里拦住了 Bash（gate URL 的 http 就是子会话那次发的）
        assertTrue("父会话只调了 Skill，门禁没在父会话跑过", rt.state.value.toolResults.keys.none { it == CallId("k1") })
        assertTrue("子会话的 Bash 被继承的门禁拦了", child.state.value.toolResults.getValue(CallId("k1")).isError)
        assertEquals("门禁只发了一次 http（子会话那次；Stop/SubagentStop 走的是别的 URL）", 1, http.sent.count { it.url.endsWith("/gate") })
        assertLogIsTruth(child)
        assertLogIsTruth(rt)
    }

    // ---- N5：分身与子 agent 走同一份组件 hooks 继承 ----

    @Test
    fun `分身继承父会话的组件 hooks，Stop 改成 SubagentStop`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            when {
                req.lastText() == "开始" ->
                    reply(skillCall("c1", "gate"), skillCall("c2", "stopper"), stop = StopReason.ToolUse)
                req.lastText().contains("gated thing") || req.lastText().contains("Stop things") ->
                    reply(agentCall("c3", "fork"), stop = StopReason.ToolUse)
                req.lastText().contains("去看看") -> reply(TextPart("分身干完了"))
                else -> reply(TextPart("收到"))
            }
        }
        val h = hub(model, http, tools = listOf(bash, SkillTool, agentTool))
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("开始")
        val fork = h.get(rt.state.first { it.children.isNotEmpty() }.children.keys.first())!!
        fork.awaitIdle()
        rt.awaitIdle()
        // 分身与子 agent 同一行代码（inheritedComponentHooks）：登记时刻的快照都进来了，Stop 改写同理
        val inherited = events(fork.sessionId).filterIsInstance<dev.min.agent.spi.ComponentHooksRegistered>()
        assertEquals(setOf("PreToolUse"), inherited.single { it.name == "gate" }.hooks.keys)
        assertEquals("Stop 在分身里同样改写成 SubagentStop", setOf("SubagentStop"), inherited.single { it.name == "stopper" }.hooks.keys)
        assertLogIsTruth(fork)
        assertLogIsTruth(rt)
    }

    // ---- M4：后台 hook 启动失败不打断回合 ----

    @Test
    fun `后台 hook 启动失败只落说明不打断回合`() = run {
        val fs2 = dev.min.agent.testkit.FakeFs()
        fs2.writeAtomic(
            GuestPath("/root/.claude/skills/asyncgate/SKILL.md"),
            skillFile(
                "async gate", """
  PreToolUse:
    - matcher: Bash
      hooks:
        - type: command
          command: echo ok
          async: true
""",
                "Async gated thing.",
            ).toByteArray(),
        )
        val exec = dev.min.agent.testkit.FakeExec { _, _ -> throw java.io.IOException("no disk") }
        val model = RoutingModel { req ->
            when {
                req.lastText() == "开始" -> reply(skillCall("c0", "asyncgate"), stop = StopReason.ToolUse)
                req.lastText().contains("Async gated") -> reply(bashCall("c1", "echo one"), stop = StopReason.ToolUse)
                else -> reply(TextPart("done"))
            }
        }
        val base = dev.min.agent.testkit.testDeps(model, listOf(bash, SkillTool))
        val deps2 = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = listOf(bash, SkillTool),
            host = HostPorts(fs2, base.host.search, exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry, memoryHome = GuestPath("/root"),
        )
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val h = SessionHub(store, deps2, scope2)
        val rt = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("开始")
        rt.state.first { it.toolResults.containsKey(CallId("c1")) && it.turn == null }
        assertTrue("启动失败不拦调用", rt.state.value.toolResults.getValue(CallId("c1")).isError.not())
        assertTrue(
            "落了只含异常类名的说明",
            events(rt.sessionId).filterIsInstance<Note>().any { it.text.contains("启动失败") && it.text.contains("IOException") },
        )
        assertLogIsTruth(rt)
    }

    // ---- L3：启动清扫孤儿暂存目录 ----

    @Test
    fun `Hub 启动时清扫不属于任何运行时的 hookbg 暂存目录`() = run {
        val fs2 = dev.min.agent.testkit.FakeFs()
        fs2.writeAtomic(GuestPath("/root/.min/hooks/hookbg_9/in.json"), "{}".toByteArray())
        fs2.writeAtomic(GuestPath("/root/.min/hooks/notes.txt"), "留着".toByteArray())
        val exec = dev.min.agent.testkit.FakeExec()
        val idle = RoutingModel { _ -> reply(TextPart("x")) }
        val base = dev.min.agent.testkit.testDeps(idle, emptyList())
        val deps2 = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = emptyList(),
            host = HostPorts(fs2, base.host.search, exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry, memoryHome = null,
        )
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        SessionHub(store, deps2, scope2)
        while (exec.runs.none { it.first.script.contains("rm -rf") }) delay(10)
        val rm = exec.runs.single { it.first.script.contains("rm -rf") }
        assertTrue("孤儿目录被清掉：${rm.first.script}", rm.first.script.contains("hookbg_9"))
        assertTrue("目录以外的文件不动", !rm.first.script.contains("notes.txt"))
    }
}

/** 内存文件系统（照 SkillSessionTest）：放 skill 定义文件 */
private class ComponentFs : WorkspaceFs {
    val files: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    override suspend fun stat(path: GuestPath): FileStat? = when {
        path.value in files -> FileStat(FileKind.File, files.getValue(path.value).length.toLong(), 0)
        path.value == "/" || files.keys.any { it.startsWith(path.value + "/") } -> FileStat(FileKind.Directory, 0, 0)
        else -> null
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        val bytes = files.getValue(path.value).toByteArray(Charsets.UTF_8)
        return bytes.copyOf(minOf(bytes.size, limit))
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("加载配置不该写文件")

    override suspend fun list(dir: GuestPath): List<DirEntry> {
        val prefix = dir.value.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .map { DirEntry(it, if (prefix + it in files) FileKind.File else FileKind.Directory) }
    }

    override fun scopeOf(path: GuestPath): PathScope =
        if (path.value == "/workspace" || path.value.startsWith("/workspace/")) PathScope.Workspace else PathScope.Rootfs
}
