package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.McpServerInfo
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.McpToolEntry
import dev.min.agent.spi.McpToolsSnapshot
import dev.min.agent.spi.PermissionRulesLoaded
import dev.min.agent.spi.RuleEntry
import dev.min.agent.spi.RuleKindData
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.Durability
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** MCP 工具在会话里的样子（MCP设计 §5.3、§6、§11）：快照进工具表与系统提示、回合开头生效、调用经调用口、权限口径 */
class McpSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private var counter = 0

    /** 替身调用口：记下每次调用，回一段文字，顺带报一次进度 */
    private val calls = CopyOnWriteArrayList<McpCallRequest>()
    private val caller = McpCaller { r ->
        calls += r
        r.progress("处理中")
        ToolResult(listOf(TextPart("${r.server}/${r.tool} 收到 ${r.arguments}")))
    }

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 一个内置工具：MCP 工具要排在它后面 */
    private val builtin = dev.min.agent.testkit.FakeTool("Probe", dev.min.agent.testkit.READ_ONLY) { ToolResult(listOf(TextPart("ok"))) }

    private suspend fun session(model: ModelClient, mode: PermissionMode = PermissionMode.BypassPermissions): SessionRuntime =
        SessionRuntime.create(store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode), testDeps(model, listOf(builtin)), scope)
            .also { it.mcpCaller = caller }

    private suspend fun events(): List<SessionEvent> = store.peek(SessionId("s1"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private fun entry(server: String, tool: String, readOnly: Boolean = false, interactive: Boolean = false) = McpToolEntry(
        name = "mcp__${server}__$tool", server = server, tool = tool, description = "$server 的 $tool",
        inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"))), readOnlyHint = readOnly, requiresUserInteraction = interactive,
    )

    private val servers = McpServersLoaded(listOf(McpServerInfo("github", "user", "http", "connected", toolCount = 2)))

    private fun snapshot(vararg tools: McpToolEntry, instructions: Map<String, String> = emptyMap(), note: String? = null) =
        McpToolsSnapshot(tools.toList(), instructions, note)

    private fun call(id: String, name: String) = ToolCallPart(CallId(id), name, args("q" to "x"))

    @Test
    fun `the snapshot's tools follow the built-in tools and the server instructions reach the system prompt`() = run {
        val model = ScriptedModel(reply(call("c1", "mcp__github__search"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = session(model)
        rt.loadMcp(servers, snapshot(entry("github", "search", readOnly = true), instructions = mapOf("github" to "先搜再建 issue。")))
        rt.say("搜一下")

        val request = model.requests.first()
        assertEquals("内置工具在前、MCP 工具在后", listOf("Probe", "mcp__github__search"), request.tools.map { it.name })
        assertTrue(request.system.joinToString("\n").contains("先搜再建 issue。"))
        assertEquals("github", calls.single().server)
        assertEquals("search", calls.single().tool)
        val outcome = events().filterIsInstance<ToolOutcome>().single()
        assertTrue(outcome.result.content.filterIsInstance<TextPart>().single().text.contains("github/search 收到"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a staged change takes effect only when the next turn starts`() = run {
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = session(model)
        rt.loadMcp(servers, snapshot(entry("github", "search")))
        rt.say("第一轮")
        rt.stageMcp(null, snapshot(entry("github", "search"), entry("github", "create"), note = "新增 github 的 1 个工具"))
        assertEquals("排着的变化不进日志", 1, rt.state.value.mcpTools!!.tools.size)
        rt.say("第二轮")
        assertEquals(listOf("Probe", "mcp__github__search"), model.requests[0].tools.map { it.name })
        assertEquals(listOf("Probe", "mcp__github__search", "mcp__github__create"), model.requests[1].tools.map { it.name })
        assertLogIsTruth(rt)
    }

    @Test
    fun `MCP tools ask in default mode, read-only hints do not skip approval`() = run {
        val model = ScriptedModel(reply(call("c1", "mcp__github__search"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = session(model, PermissionMode.Default)
        rt.loadMcp(servers, snapshot(entry("github", "search", readOnly = true)))
        rt.send(ClientMsgId("u1"), listOf(TextPart("搜")))
        while (rt.state.value.turn?.calls?.values?.none { it.phase is CallPhase.AwaitingApproval } != false) delay(10)
        val approval = events().filterIsInstance<ApprovalRequested>().single()
        rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
        rt.awaitIdle()
        assertEquals(1, calls.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a tool that requires user interaction asks even when everything is allowed`() = run {
        val model = ScriptedModel(reply(call("c1", "mcp__github__merge"), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = session(model, PermissionMode.BypassPermissions)
        rt.loadMcp(servers, snapshot(entry("github", "merge", interactive = true)))
        rt.send(ClientMsgId("u1"), listOf(TextPart("合并")))
        while (rt.state.value.turn?.calls?.values?.none { it.phase is CallPhase.AwaitingApproval } != false) delay(10)
        assertTrue(calls.isEmpty())
        rt.interrupt()
        rt.awaitIdle()
        assertLogIsTruth(rt)
    }

    @Test
    fun `a server allow rule reaches only its own server, judged by the server the tool belongs to`() = run {
        // 服务器 github__x 的工具全名也以 mcp__github__ 开头：CC 2.1.299 起 mcp__github 不再放行它
        val model = ScriptedModel(
            reply(call("c1", "mcp__github__search"), stop = StopReason.ToolUse),
            reply(call("c2", "mcp__github__x__search"), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = session(model, PermissionMode.Default)
        rt.commit(listOf(PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Allow, "mcp__github", "user", GuestPath("/root"))))), Durability.Sync)
        rt.loadMcp(servers, snapshot(entry("github", "search"), entry("github__x", "search")))
        rt.send(ClientMsgId("u1"), listOf(TextPart("搜")))
        while (rt.state.value.turn?.calls?.values?.none { it.phase is CallPhase.AwaitingApproval } != false) delay(10)
        assertEquals("规则放行的已经跑了", listOf("github"), calls.map { it.server })
        val approval = events().filterIsInstance<ApprovalRequested>().single()
        assertEquals("github__x", approval.ask.mcpServer)
        rt.interrupt()
        rt.awaitIdle()
        assertLogIsTruth(rt)
    }

    @Test
    fun `a whole-tool deny rule removes the tool from the table`() = run {
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = session(model)
        rt.commit(listOf(PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Deny, "mcp__github__delete", "user", GuestPath("/root"))))), Durability.Sync)
        rt.loadMcp(servers, snapshot(entry("github", "search"), entry("github", "delete")))
        rt.say("看看")
        assertEquals(listOf("Probe", "mcp__github__search"), model.requests.single().tools.map { it.name })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a subagent sees only the MCP tools its definition names`() = run {
        val rt = session(ScriptedModel())
        rt.loadMcp(servers, snapshot(entry("github", "search"), entry("github", "create"), entry("slack", "post")))
        val s = rt.state.value
        fun visibleFor(tools: List<String>?, denied: List<String>) = s.copy(
            origin = dev.min.agent.spi.SubagentOrigin(
                parent = SessionId("p"), root = SessionId("p"), parentCall = CallId("c"), agentType = "t", fork = false, depth = 1,
                cacheKey = "k", tools = tools, mcpTools = tools?.filter { it.startsWith("mcp__") }, mcpDenied = denied,
            ),
        ).visibleMcpTools(rt.deps).map { it.name }
        assertEquals("没写 tools：全部", 3, visibleFor(null, emptyList()).size)
        assertEquals("写了 tools 没列 MCP：一个都没有", emptyList<String>(), visibleFor(listOf("Read"), emptyList()))
        assertEquals(listOf("mcp__github__search", "mcp__github__create"), visibleFor(listOf("Read", "mcp__github"), emptyList()))
        assertEquals(listOf("mcp__github__search"), visibleFor(listOf("mcp__github__*"), listOf("mcp__github__create")))
        assertFalse(visibleFor(null, listOf("mcp__*")).isNotEmpty())
    }

    @Test
    fun `a fork inherits the MCP snapshot and its first request keeps the parent's prefix`() = run {
        val requests = CopyOnWriteArrayList<dev.min.agent.llm.ModelRequest>()
        val model = object : ModelClient {
            override fun stream(request: dev.min.agent.llm.ModelRequest): kotlinx.coroutines.flow.Flow<dev.min.agent.llm.ModelStreamEvent> = kotlinx.coroutines.flow.flow {
                requests += request
                val last = request.messages.last().parts.joinToString { p ->
                    (p as? TextPart)?.text ?: (p as? dev.min.agent.protocol.ToolResultPart)?.content?.filterIsInstance<TextPart>()?.joinToString { it.text }.orEmpty()
                }
                val step = when {
                    "You are a fork" in last -> reply(TextPart("分身报告"))
                    request.messages.last().parts.any { it is dev.min.agent.protocol.ToolResultPart } -> reply(TextPart("派了"))
                    else -> reply(
                        ToolCallPart(CallId("a1"), "Agent", args("description" to "分身", "prompt" to "去查", "subagent_type" to dev.min.agent.spi.FORK_AGENT_TYPE, "run_in_background" to false)),
                        stop = StopReason.ToolUse,
                    )
                }
                emitAll(step(request))
            }
        }
        val hub = SessionHub(store, testDeps(model, listOf(dev.min.agent.tools.AgentToolOf(emptyList()))), scope)
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        parent.mcpCaller = caller
        parent.loadMcp(servers, snapshot(entry("github", "search"), instructions = mapOf("github" to "说明")))
        parent.say("分个身")
        val child = parent.state.value.children.keys.single()
        val childState = hub.get(child)!!.state.value
        assertEquals("分身带着同一份快照", parent.state.value.mcpTools, childState.mcpTools)
        val parentFirst = requests.first()
        val forkFirst = requests.first { r -> r.messages.last().parts.any { (it as? dev.min.agent.protocol.ToolResultPart)?.content?.any { c -> c is TextPart && "You are a fork" in c.text } == true } }
        assertEquals("工具表逐字相同（含 MCP 工具）", parentFirst.tools, forkFirst.tools)
        assertEquals("系统提示逐字相同（含 MCP 说明）", parentFirst.system, forkFirst.system)
    }
}
