package dev.min.agent.kernel

import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
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
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `--bare` / `--safe-mode`（[KernelDeps.customizations]）：同一个「什么都有」的工作区在三种启动档下各建一次会话，
 * 对照 CC 原文的跳过清单（cc-cli-reference :72/:125、cc-env-vars 的 CLAUDE_CODE_SIMPLE / CLAUDE_CODE_SAFE_MODE）。
 * 真日志 + 内存文件系统；hook 用 FakeExec 记录跑没跑；MCP 断言首份快照（服务器有没有被发现）。
 */
class BareSafeModeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val fs = FakeFs()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    /** 「什么都有」的工作区。临时用户目录（/root）放用户层 CLAUDE.md 与自动记忆；[managed] 再放托管层的一份 */
    private fun seed(managed: Boolean = false) {
        fs.put("/workspace/CLAUDE.md", "project-md-marker")
        fs.put("/root/.claude/CLAUDE.md", "user-md-marker")
        fs.put("/root/.claude/projects/-workspace/memory/MEMORY.md", "auto-note-marker")
        fs.put("/workspace/.claude/skills/x/SKILL.md", "---\ndescription: X skill\n---\nX body")
        fs.put("/workspace/.claude/commands/y.md", "---\ndescription: Y command\n---\nY body")
        fs.put("/workspace/.claude/agents/z.md", "---\nname: z\ndescription: Z agent\n---\nZ instructions")
        fs.put(
            "/workspace/.claude/settings.json",
            """{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"echo project-hook"}]}]},""" +
                """"permissions":{"deny":["Read(secret.txt)"]},"outputStyle":"Explanatory"}""",
        )
        fs.put("/workspace/.mcp.json", """{"mcpServers":{"srv":{"type":"stdio","command":"unused"}}}""")
        fs.put("/workspace/.claude/workflows/w.js", "export const meta = {\"name\":\"w\",\"description\":\"saved workflow\"};")
        if (managed) {
            fs.put("/etc/claude-code/managed-settings.json", """{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"echo managed-hook"}]}]}}""")
            fs.put("/etc/claude-code/.claude/skills/m/SKILL.md", "---\ndescription: managed skill\n---\nM body")
        }
    }

    private val read = object : Tool<JsonObject> {
        override val name = "Read"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true)
        override fun description(profile: HarnessProfile) = "读文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = (input["file_path"] as? JsonPrimitive)?.content ?: "/dev/null"
            return PermissionAsk(name, "读文件", paths = listOf(PathAccess(GuestPath(path), AccessKind.Read, PathScope.Workspace)))
        }
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("read ok")))))
        }
    }

    /** 把入参转给委派端口的 Agent 工具（SubagentTest 同款）：真正的委派在内核，这里测它 */
    private val agentTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "委派"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "委派")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            fun str(key: String) = (input[key] as? JsonPrimitive)?.content
            val request = DelegateRequest(
                description = str("description") ?: "任务", prompt = str("prompt")!!,
                agentType = str("subagent_type"), background = false,
            )
            when (val r = ctx.agents.delegate(request)) {
                is DelegateResult.Finished -> emit(ToolProgress.Done(ToolResult(listOf(TextPart(r.report)), isError = r.isError)))
                is DelegateResult.Started -> emit(ToolProgress.Done(ToolResult(listOf(TextPart("started")))))
                is DelegateResult.Rejected -> emit(ToolProgress.Done(ToolResult(listOf(TextPart(r.messageForModel)), isError = true)))
            }
        }
    }

    /** 按请求内容路由的模型（SubagentTest 同款）：子 agent 的请求带着 z 的角色说明，据此分岔 */
    private class RoutingModel(private val route: (ModelRequest) -> Step) : dev.min.agent.llm.ModelClient {
        override fun stream(request: ModelRequest): Flow<dev.min.agent.llm.ModelStreamEvent> = flow { emitAll(route(request)(request)) }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("") { (it as? TextPart)?.text ?: "" }

    private fun deps(model: dev.min.agent.llm.ModelClient, tools: List<Tool<JsonObject>>, customizations: Customizations, exec: FakeExec): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(fs, base.host.search, exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC,
            memoryHome = GuestPath("/root"),
            trustProjectHooks = { true },
            customizations = customizations,
            mcpFirstSnapshotWaitMs = 500,
            // savedWorkflows 只在引擎可用时列目录；这里不真跑工作流，给个用不上的引擎
            workflowEngine = dev.min.agent.spi.WorkflowEngine { _, _, _ -> JsonPrimitive(1) },
        )
    }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0

    private fun hub(customizations: Customizations, model: dev.min.agent.llm.ModelClient, tools: List<Tool<JsonObject>>, exec: FakeExec): SessionHub {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        // 目录照组合根的全量读法建（DesktopApp 会先剥掉再建 Hub）；这里故意带全：即便定义在，被跳过的东西也不许被加载回来
        val agents = runBlocking { ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root")) }.agents
        return SessionHub(store, deps(model, tools, customizations, exec), scope, agents = AgentCatalog.of(agents).definitions)
    }

    private suspend fun createIn(h: SessionHub): SessionRuntime =
        h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    private suspend fun events(rt: SessionRuntime): List<dev.min.agent.spi.SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private fun readSecretCall() = ToolCallPart(CallId("c1"), "Read", args("file_path" to "/workspace/secret.txt"))

    // ---- 对照组 ----

    @Test
    fun `对照组：普通启动清单里的全部加载到`() = run {
        seed()
        val exec = FakeExec()
        val model = ScriptedModel(reply(readSecretCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val h = hub(Customizations.Full, model, listOf(read), exec)
        val rt = createIn(h)
        val s = rt.state.value
        // 记忆：用户层 + 项目层 + 自动记忆
        assertTrue(s.memory.any { it.path.value.endsWith("/root/.claude/CLAUDE.md") && it.source == MemorySource.User })
        assertTrue(s.memory.any { it.path.value.endsWith("/workspace/CLAUDE.md") && it.source == MemorySource.Project })
        assertNotNull(s.autoMemory)
        // skills + 命令
        val entries = s.skills!!.entries
        assertTrue(entries.any { it.name == "x" && it.kind == SkillKind.Skill })
        assertTrue(entries.any { it.name == "y" && it.kind == SkillKind.Command })
        // hooks：项目层加载并真的跑了
        val loaded = events(rt).filterIsInstance<HooksLoaded>().single()
        assertTrue(loaded.sources.any { it.source == "project" })
        assertTrue(events(rt).filterIsInstance<HooksRan>().any { it.event == "SessionStart" && it.handlers == 1 })
        assertTrue("hook 命令要真的执行过", exec.runs.isNotEmpty())
        // 输出样式、工作流
        assertEquals("Explanatory", s.outputStyle?.name)
        assertEquals(listOf("w"), rt.savedWorkflows().map { it.name })
        // MCP：一回合之后首份快照在，服务器被发现了
        rt.say("查一下")
        val withMcp = rt.state.value
        assertNotNull(withMcp.mcpServers)
        assertTrue(withMcp.mcpServers!!.servers.any { it.name == "srv" })
        // deny 规则照常生效
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    // ---- --bare ----

    @Test
    fun `--bare：清单里的都不发现，权限规则照常，输出样式与工作流照常`() = run {
        seed()
        val exec = FakeExec()
        val model = ScriptedModel(reply(readSecretCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val h = hub(Customizations.Bare, model, listOf(read), exec)
        val rt = createIn(h)
        val s = rt.state.value
        // CLAUDE.md 与自动记忆都不加载（系统提示里因此没有它们）
        assertTrue("bare 不该加载记忆：${s.memory}", s.memory.isEmpty())
        assertNull("bare 不该开自动记忆", s.autoMemory)
        // 没有 skills 与命令
        assertNull("bare 不该加载 skills：${s.skills}", s.skills)
        // 没跑 hook
        assertTrue(s.hooks.isEmpty())
        assertTrue(events(rt).filterIsInstance<HooksRan>().isEmpty())
        assertFalse("bare 不该执行任何 hook 命令", exec.runs.isNotEmpty())
        // 输出样式与工作流不在 bare 的跳过清单里，照常
        assertEquals("Explanatory", s.outputStyle?.name)
        assertEquals(listOf("w"), rt.savedWorkflows().map { it.name })
        // 一回合之后 MCP 快照也不该出现（服务器没被发现）
        rt.say("查一下")
        assertNull("bare 不该发现 MCP 服务器：${rt.state.value.mcpServers}", rt.state.value.mcpServers)
        // deny 规则照常生效
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    // ---- 内置 skill 与启动档 ----

    /**
     * 内置 skill 是「skills」的一部分：完整档进清单，--bare 与 --safe-mode 都整体不进（CC 的跳过清单点名 skills，
     * 内置的也不从磁盘发现，但照同一条「整体不发现」处理；这是按原文的读法，不是 CC 二进制里的实测）。
     */
    @Test
    fun `内置 skill：完整档进清单`() = run {
        seed()
        val rt = createIn(hub(Customizations.Full, ScriptedModel(reply(TextPart("done"))), listOf(read), FakeExec()))
        val names = rt.state.value.skills!!.entries.filter { it.source == "内置" }.map { it.name }
        assertEquals(listOf("loop", "code-review", "simplify", "batch"), names)
    }

    @Test
    fun `内置 skill：--bare 整体不进清单`() = run {
        seed()
        val rt = createIn(hub(Customizations.Bare, ScriptedModel(reply(TextPart("done"))), listOf(read), FakeExec()))
        assertNull("bare 不该有内置 skill", rt.state.value.skills)
    }

    @Test
    fun `内置 skill：--safe-mode 整体不进清单`() = run {
        seed()
        val rt = createIn(hub(Customizations.Safe, ScriptedModel(reply(TextPart("done"))), listOf(read), FakeExec()))
        assertNull("safe 不该有内置 skill", rt.state.value.skills)
    }

    // ---- --safe-mode ----

    @Test
    fun `--safe-mode：用户项目层不加载，托管 hooks 照跑，托管 skills 不加载`() = run {
        seed(managed = true)
        val exec = FakeExec()
        val model = ScriptedModel(reply(readSecretCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val h = hub(Customizations.Safe, model, listOf(read), exec)
        val rt = createIn(h)
        val s = rt.state.value
        assertTrue(s.memory.isEmpty())
        assertNull(s.autoMemory)
        assertNull("safe 连托管 skills 也不加载：${s.skills}", s.skills)
        assertNull("safe 不该有输出样式", s.outputStyle)
        assertEquals("safe 下工作流不加载", emptyList<SavedWorkflow>(), rt.savedWorkflows())
        // hooks：只剩托管层的，而且真的跑了
        val loaded = events(rt).filterIsInstance<HooksLoaded>().single()
        assertEquals(listOf("managed"), loaded.sources.map { it.source })
        val ran = events(rt).filterIsInstance<HooksRan>().filter { it.event == "SessionStart" }
        assertTrue("托管 hook 要照跑：$ran", ran.isNotEmpty() && ran.all { it.handlers >= 1 })
        // 一次 hook 执行占两条 exec（跑批脚本 + 清理），命令本体在生成的 batch.sh 里（FakeFs 记下了写入）
        val hookRuns = exec.runs.map { it.first.script }.filter { it.startsWith("sh ") }
        assertEquals(1, hookRuns.size)
        val batchBody = fs.writes.filter { it.first.value.endsWith("batch.sh") }.single().second.decodeToString()
        assertTrue("跑的该是托管那条：$batchBody", batchBody.contains("managed-hook") && !batchBody.contains("project-hook"))
        // MCP 没连（托管配的也不连——原文保留清单里没有它）
        rt.say("查一下")
        assertNull("safe 不该发现 MCP 服务器：${rt.state.value.mcpServers}", rt.state.value.mcpServers)
        // deny 规则照常生效（权限照常）
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).isError)
    }

    // ---- system reminder ----

    /**
     * Min 没有「读过的文件在磁盘上变了」的文件监视提醒；内核自己往请求里附加的提醒是 Fold 在 CwdChanged 上
     * 写的「工作目录变了」一段（连同该目录重读到的记忆小节），模型视图统一包成 <system-reminder>。
     * CC 原文（headless 页 bare mode）：bare 下这类随消息附加的提醒一概不加。
     */
    @Test
    fun `对照组：普通档把换目录提醒包成 system-reminder 发给模型`() = run {
        seed()
        val model = ScriptedModel(reply(TextPart("done")))
        val h = hub(Customizations.Full, model, listOf(read), FakeExec())
        val rt = createIn(h)
        rt.changeCwd(GuestPath("/workspace/sub"), null)
        rt.say("查一下")
        val request = model.requests.single()
        assertTrue(
            "普通档的请求该带换目录提醒：${request.messages.last().parts}",
            request.messages.any { m -> m.parts.any { it is TextPart && it.text.contains("<system-reminder>") && it.text.contains("Working directory changed") } },
        )
    }

    @Test
    fun `--bare 不给请求附加 system reminder`() = run {
        seed()
        val model = ScriptedModel(reply(TextPart("done")))
        val h = hub(Customizations.Bare, model, listOf(read), FakeExec())
        val rt = createIn(h)
        rt.changeCwd(GuestPath("/workspace/sub"), null)
        rt.say("查一下")
        val request = model.requests.single()
        assertTrue(
            "bare 的请求不该有 system reminder：${request.messages.flatMap { m -> m.parts.filterIsInstance<TextPart>().map { it.text } }}",
            request.messages.none { m -> m.parts.any { it is TextPart && "<system-reminder>" in it.text } },
        )
    }

    @Test
    fun `同一个 Hub 的两个会话分别加载配置，换目录与重开仍各用各档`() = run {
        seed(managed = true)
        fs.put("/workspace/sub/CLAUDE.md", "nested memory")
        val h = hub(Customizations.Full, ScriptedModel(reply(TextPart("done"))), listOf(read), FakeExec())
        val full = createIn(h)
        val safe = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default,
            loadingModes = dev.min.agent.spi.SessionLoadingModes(safe = true, restricted = true))
        assertTrue(full.state.value.memory.isNotEmpty())
        assertTrue(full.state.value.skills!!.entries.isNotEmpty())
        assertTrue(safe.state.value.memory.isEmpty())
        assertNull(safe.state.value.skills)
        assertNull(safe.state.value.outputStyle)
        assertTrue(safe.savedWorkflows().isEmpty())
        assertEquals(listOf("managed"), events(safe).filterIsInstance<HooksLoaded>().single().sources.map { it.source })
        assertFalse(full.deps.settingsInputs.restricted)
        assertTrue(safe.deps.settingsInputs.restricted)
        safe.changeCwd(GuestPath("/workspace/sub"), null)
        assertNull(safe.memoryDiscovery)
        assertTrue(safe.state.value.cwdMemory.isEmpty())
        val reopened = SessionRuntime.open(store, safe.sessionId, full.deps, scopes.last())!!
        assertEquals(Customizations.Safe, reopened.deps.customizations)
        assertTrue(reopened.deps.settingsInputs.restricted)
        assertNull(reopened.memoryDiscovery)
        assertNotNull(full.memoryDiscovery)
    }

    @Test
    fun `safe restricted 子会话与分身继承父档，进程保持 Full`() = run {
        seed()
        var type = "z"
        val model = RoutingModel { req ->
            when {
                req.messages.last().parts.any { it is ToolResultPart } -> reply(TextPart("done"))
                req.lastText() == "去查" || "You are a fork" in req.lastText() || req.system.any { "Z instructions" in it } -> reply(TextPart("child done"))
                else -> reply(ToolCallPart(CallId("delegate"), "Agent", args("description" to "查一下", "prompt" to "去查", "subagent_type" to type)), stop = StopReason.ToolUse)
            }
        }
        val h = hub(Customizations.Full, model, listOf(agentTool), FakeExec())
        for (selectedType in listOf("z", "fork")) {
        type = selectedType
        val parent = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default,
            loadingModes = dev.min.agent.spi.SessionLoadingModes(safe = true, restricted = true))
        parent.say("委派出去")
        val child = h.get(parent.state.value.children.keys.single())!!
        assertEquals(parent.state.value.created.loadingModes, child.state.value.created.loadingModes)
        assertEquals(Customizations.Safe, child.deps.customizations)
        assertTrue(child.deps.settingsInputs.restricted)
        assertTrue(child.state.value.memory.isEmpty())
        assertNull(child.state.value.skills)
        val branch = h.branch(parent.sessionId)
        assertEquals(parent.state.value.created.loadingModes, branch.state.value.created.loadingModes)
        assertTrue(branch.deps.settingsInputs.restricted)
        }
    }

    // ---- 子 agent ----

    @Test
    fun `--bare 下委派的子 agent 看不到项目里的记忆文件与 skills`() = run {
        seed()
        val exec = FakeExec()
        val childReport = "child-report-marker"
        val model = RoutingModel { req ->
            // 子 agent 的系统提示带 z 的角色说明；父会话的请求不带
            when {
                req.system.any { it.contains("Z instructions") } -> reply(TextPart(childReport))
                req.messages.last().parts.any { it is ToolResultPart } -> reply(TextPart("done"))
                else -> reply(
                    ToolCallPart(CallId("delegate"), "Agent", args(
                        "description" to "查一下", "prompt" to "去查", "subagent_type" to "z",
                    )),
                    stop = StopReason.ToolUse,
                )
            }
        }
        val h = hub(Customizations.Bare, model, listOf(agentTool), exec)
        val parent = createIn(h)
        parent.say("委派出去")
        val childId = parent.state.value.children.keys.single()
        val child = checkNotNull(h.get(childId))
        val cs = child.state.value
        assertTrue("子 agent 不该加载记忆：${cs.memory}", cs.memory.isEmpty())
        assertNull("子 agent 不该有 skills：${cs.skills}", cs.skills)
        val childEvents = store.peek(childId)!!.read().toList().map { it.event }
        assertTrue(childEvents.filterIsInstance<dev.min.agent.spi.MemoryLoaded>().isEmpty())
        assertTrue(childEvents.filterIsInstance<dev.min.agent.spi.SkillsLoaded>().isEmpty())
        // 委派本身照常完成：报告作为 Agent 工具结果交回父会话
        val report = parent.state.value.toolResults[CallId("delegate")]?.content?.filterIsInstance<TextPart>()?.joinToString("") { it.text }
        assertTrue("父会话该拿到子 agent 的报告：$report", report?.contains(childReport) == true)
    }
}
