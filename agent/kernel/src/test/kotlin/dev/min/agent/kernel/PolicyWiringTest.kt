package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.ext.config.AgentDef
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.tools.AgentTool
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 权限引擎接进内核：settings 规则在会话创建时快照进日志、「总是允许」变成会话规则、审批带建议与底线原因、
 * 子 agent 继承规则、`Agent(类型)` 规则。
 */
class PolicyWiringTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 像 Bash：按入参报出命令，权限引擎据此分析；执行只记下跑过什么 */
    private val ran = CopyOnWriteArrayList<String>()
    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
            PermissionAsk(name, "跑命令", commands = listOf(CommandAccess((input["command"] as JsonPrimitive).content)))
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            ran += (input["command"] as JsonPrimitive).content
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ok")))))
        }
    }

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

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        val ids = AtomicLong(0)
        KernelDeps(
            clock = base.clock,
            ids = IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = base.models,
            tools = tools,
            host = HostPorts(fs, FakeSearch(), FakeExec()) { FakeArtifacts() },
            platform = "test",
            zone = base.zone,
            retry = base.retry,
            memoryHome = GuestPath("/root"),
        )
    }

    private fun settings(json: String) = fs.put("/root/.claude/settings.json", json)

    private fun cmd(id: String, command: String) = ToolCallPart(CallId(id), "Bash", args("command" to command))

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    @Test fun `r47 additional directories reach execution and survive reopening without extra settings`() = run {
        fs.scopes["/data"] = dev.min.agent.spi.PathScope.Rootfs
        settings("""{"permissions":{"additionalDirectories":["/data/project"]}}""")
        fs.put("/data/project/.claude/settings.json", """{"permissions":{"allow":["Bash"]}}""")
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(cmd("c1", "touch /data/project/new.txt"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val deps = deps(model, listOf(bash))
        val hub = SessionHub(store, deps, scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.AcceptEdits)
        assertEquals(listOf(GuestPath("/data/project")), events(rt).filterIsInstance<PermissionRulesLoaded>().single().additionalDirectories)
        assertEquals(listOf(GuestPath("/data/project")), rt.state.value.additionalDirectories)
        assertTrue(rt.state.value.rules.isEmpty())
        rt.send(ClientMsgId("u1"), listOf(TextPart("创建")))
        rt.state.first { it.turn == null && it.lastTurn != null || it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        assertEquals(listOf("touch /data/project/new.txt"), ran)
        assertTrue(events(rt).none { it is ApprovalRequested })
        hub.close(rt.sessionId)
        settings("""{"permissions":{"additionalDirectories":["/other"]}}""")
        val reopened = hub.open(rt.sessionId)!!
        assertEquals(listOf(GuestPath("/data/project")), reopened.state.value.additionalDirectories)
    }

    @Test fun `r42 plan non-readonly calls produce an approval card with planning guidance`() = run {
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(cmd("c1", "npm test"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val rt = SessionHub(store, deps(model, listOf(bash)), scope).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Plan)
        rt.send(ClientMsgId("u1"), listOf(TextPart("验证")))
        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        val request = events(rt).filterIsInstance<ApprovalRequested>().single()
        assertTrue(request.ask.summary.contains("计划模式") && request.ask.summary.contains("ExitPlanMode"))
        assertTrue(ran.isEmpty())
        rt.answerApproval((waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId, ApprovalChoice.Once)
        rt.state.first { it.turn == null && it.lastTurn != null }
        assertEquals(listOf("npm test"), ran)
    }

    @Test
    fun `settings rules are snapshotted at creation and decide without asking`() = run {
        settings("""{"permissions": {"allow": ["Bash(npm test *)"], "deny": ["Bash(rm *)"]}}""")
        val model = RoutingModel { req ->
            when {
                req.messages.size == 1 -> reply(cmd("c1", "npm test --silent"), cmd("c2", "rm notes.txt"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val hub = SessionHub(store, deps(model, listOf(bash)), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        val loaded = events(rt).filterIsInstance<PermissionRulesLoaded>().single()
        assertEquals(listOf("Bash(npm test *)", "Bash(rm *)"), loaded.rules.map { it.text }.sorted())
        assertEquals("user", loaded.rules.first().source)

        rt.send(ClientMsgId("u1"), listOf(TextPart("跑一下")))
        val done = rt.state.first { it.turn == null && it.lastTurn != null }
        assertEquals("allow 规则放行，不弹审批", listOf("npm test --silent"), ran)
        assertTrue(events(rt).none { it is ApprovalRequested })
        val denied = done.toolResults.getValue(CallId("c2"))
        assertTrue(denied.isError)
        assertTrue((denied.content.single() as TextPart).text.contains("Bash(rm *)"))

        // 磁盘上的 settings 改了也不影响已经在跑的会话：判定只看日志里的快照
        settings("""{"permissions": {"allow": ["Bash(rm *)"]}}""")
        assertEquals(loaded.rules, rt.state.value.rules)
    }

    @Test
    fun `always-allow answers become session rules that survive reopening`() = run {
        val model = RoutingModel { req ->
            when (req.last()) {
                "切分支" -> reply(cmd("c1", "git checkout -b feat"), stop = StopReason.ToolUse)
                "再切" -> reply(cmd("c2", "git checkout main"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val hub = SessionHub(store, deps(model, listOf(bash)), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.send(ClientMsgId("u1"), listOf(TextPart("切分支")))
        val waiting = rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        val request = events(rt).filterIsInstance<ApprovalRequested>().single()
        assertEquals("Bash(git checkout *)", request.suggestedRule)
        assertNull(request.floorReason)
        val approvalId = (waiting.phaseOf(CallId("c1")) as CallPhase.AwaitingApproval).approvalId
        rt.answerApproval(approvalId, ApprovalChoice.Always(request.suggestedRule!!))
        rt.state.first { it.turn == null && it.lastTurn != null }

        rt.send(ClientMsgId("u2"), listOf(TextPart("再切")))
        rt.state.first { it.turn == null && it.lastTurn?.turnId != waiting.turn!!.turnId }
        assertEquals("会话规则放行第二次，不再问", 1, events(rt).count { it is ApprovalRequested })
        assertEquals(listOf("git checkout -b feat", "git checkout main"), ran)

        val reopened = SessionHub(store, deps(model, listOf(bash)), CoroutineScope(SupervisorJob() + Dispatchers.Default)).open(rt.sessionId)!!
        assertEquals(listOf("Bash(git checkout *)"), reopened.state.value.sessionRules.map { it.text })
        assertEquals("session", reopened.state.value.sessionRules.single().source)
    }

    @Test
    fun `a request that crosses the safety floor says why and offers no always-allow`() = run {
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(cmd("c1", "rm -rf /"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val hub = SessionHub(store, deps(model, listOf(bash)), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), listOf(TextPart("清空")))
        rt.state.first { it.phaseOf(CallId("c1")) is CallPhase.AwaitingApproval }
        val request = events(rt).filterIsInstance<ApprovalRequested>().single()
        assertEquals("递归删除关键路径 /", request.floorReason)
        assertNull(request.suggestedRule)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `children inherit the parent's rules, including session ones`() = run {
        settings("""{"permissions": {"deny": ["Bash(curl *)"], "additionalDirectories": ["/data/project"]}}""")
        val model = RoutingModel { req ->
            when {
                req.last() == "子任务" -> reply(TextPart("完成"))
                req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", args("description" to "查", "prompt" to "子任务", "run_in_background" to false)),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("好"))
            }
        }
        val hub = SessionHub(store, deps(model, listOf(bash, AgentTool)), scope)
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        parent.send(ClientMsgId("u1"), listOf(TextPart("开始")))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }
        val child = hub.get(done.children.keys.single())!!
        assertEquals(listOf("Bash(curl *)"), child.state.value.rules.map { it.text })
        assertEquals("子会话不自己再读 settings", 1, events(child).count { it is PermissionRulesLoaded })
        assertEquals(parent.state.value.additionalDirectories, child.state.value.additionalDirectories)
    }

    @Test
    fun `an Agent(type) deny rule blocks just that type of delegation`() = run {
        settings("""{"permissions": {"deny": ["Agent(Explore)"]}}""")
        val model = RoutingModel { req ->
            when {
                req.last() == "找找" || req.last() == "做做" -> reply(TextPart("完成"))
                req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", args("description" to "找", "prompt" to "找找", "subagent_type" to "Explore", "run_in_background" to false)),
                    ToolCallPart(CallId("a2"), "Agent", args("description" to "做", "prompt" to "做做", "run_in_background" to false)),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("好"))
            }
        }
        val hub = SessionHub(store, deps(model, listOf(bash, AgentTool)), scope)
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        parent.send(ClientMsgId("u1"), listOf(TextPart("开始")))
        val done = parent.state.first { it.turn == null && it.lastTurn != null }
        val explore = done.toolResults.getValue(CallId("a1"))
        assertTrue(explore.isError)
        assertTrue((explore.content.single() as TextPart).text.contains("Agent(Explore)"))
        assertFalse(done.toolResults.getValue(CallId("a2")).isError)
        assertEquals("只有 general-purpose 那个被委派了", 1, done.children.size)
    }

    // ---- 自定义子 agent 的定义 ----

    private fun def(name: String, vararg tweak: (AgentDef) -> AgentDef): AgentDef =
        tweak.fold(AgentDef(name, "说明 $name", "你是 $name", GuestPath("/workspace/.claude/agents/$name.md"), ConfigSource.Project)) { d, f -> f(d) }

    @Test
    fun `custom definitions map Claude Code fields and override built-ins by name`() {
        val catalog = AgentCatalog.of(
            listOf(
                def("reviewer", { it.copy(model = "sonnet", permissionMode = "acceptEdits", effort = "high", disallowedTools = listOf("Bash(git push *)"), maxTurns = 7) }),
                def("Explore", { it.copy(description = "我们自己的探索", tools = listOf("Read")) }),
                def("odd", { it.copy(permissionMode = "yolo", isolation = "worktree", skills = listOf("x")) }),
            ),
        )
        val byName = catalog.definitions.associateBy { it.name }
        assertEquals(setOf("general-purpose", "Plan", "Explore", "reviewer", "odd"), byName.keys)
        val reviewer = byName.getValue("reviewer")
        assertEquals("别名原样保留，解析在用的时候做", "sonnet", reviewer.model)
        assertEquals(PermissionMode.AcceptEdits, reviewer.permissionMode)
        assertEquals(listOf("Bash"), reviewer.disallowedTools)
        assertEquals(7, reviewer.maxTurns)
        assertEquals("你是 reviewer", reviewer.instructions)
        assertEquals("我们自己的探索", byName.getValue("Explore").description)
        assertNull(byName.getValue("odd").permissionMode)
        assertTrue(catalog.notes.any { "yolo" in it })
        assertTrue(byName.getValue("odd").isolationWorktree)
        assertFalse(reviewer.isolationWorktree)
        assertTrue(catalog.notes.any { "skills" in it })
        assertFalse(catalog.notes.any { "isolation" in it })
    }

    @Test
    fun `rule kinds round-trip into the policy engine`() {
        val entries = listOf(RuleKindData.Allow, RuleKindData.Ask, RuleKindData.Deny)
            .map { dev.min.agent.spi.RuleEntry(it, "Bash(ls *)", "user") }
        val parsed = entries.map { dev.min.agent.policy.PermissionRule.from(it)!!.kind }
        assertEquals(listOf(dev.min.agent.policy.RuleKind.Allow, dev.min.agent.policy.RuleKind.Ask, dev.min.agent.policy.RuleKind.Deny), parsed)
        assertNull(dev.min.agent.policy.PermissionRule.from(dev.min.agent.spi.RuleEntry(RuleKindData.Allow, "Bash(", "user")))
        assertNotNull(DecidedBy.Rule("Bash(ls *)", "user"))
    }
}
