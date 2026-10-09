package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.llm.ModelRequest
import dev.min.agent.mcp.McpCallResult
import dev.min.agent.mcp.McpConnectionState
import dev.min.agent.mcp.McpContent
import dev.min.agent.mcp.McpProgress
import dev.min.agent.mcp.McpRpcException
import dev.min.agent.mcp.McpToolAnnotations
import dev.min.agent.mcp.McpToolDef
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.McpToolsSnapshot
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeFs
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** McpManager 的调度、共享、快照时机与调用（MCP设计 §4、§5.3、§7）：真 Hub、真日志，连接是替身 */
class McpManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs().apply {
        // 用户作用域的 stdio 服务器（不需要工作区信任）
        put("/root/.claude.json", """{"mcpServers": {"github": {"command": "gh-mcp", "args": ["--stdio"]}}}""")
        mkdir("/workspace")
    }
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun tool(name: String, readOnly: Boolean = false) = McpToolDef(
        name, null, "github 的 $name", JsonObject(mapOf("type" to JsonPrimitive("object"))), null,
        McpToolAnnotations(readOnlyHint = readOnly), null,
    )

    /** 替身连接：工具表可改，能发 list_changed，能「掉线」 */
    private inner class FakeConnection(@Volatile var tools: List<McpToolDef>) : McpConnection {
        override val state = MutableStateFlow<McpConnectionState>(
            McpConnectionState.Ready(dev.min.agent.mcp.McpServerInfo("2025-11-25", JsonObject(emptyMap()), JsonObject(emptyMap()), "先搜再建。")),
        )
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val toolsChanged = changes
        override val instructions: String = "先搜再建。"
        var result: (String, JsonObject) -> McpCallResult = { t, _ -> McpCallResult(listOf(McpContent.Text("$t 完成")), null, false) }
        var closed = false
        var listFails = false
        override suspend fun listTools() = if (listFails) error("tools/list 失败") else tools
        override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?): McpCallResult {
            onProgress(McpProgress(1.0, 2.0))
            return result(tool, arguments)
        }
        var stderr: List<String> = emptyList()
        override fun stderrTail(): List<String> = stderr
        override suspend fun close() {
            closed = true
            state.value = McpConnectionState.Closed("客户端关闭")
        }
        fun die() {
            state.value = McpConnectionState.Closed("进程退出", exitCode = 1, stderrTail = stderr)
        }
    }

    private inner class FakeConnector(var connectDelayMs: Long = 0, val listFails: Boolean = false, val alwaysMeta: Boolean = false) : McpConnector {
        val made = CopyOnWriteArrayList<FakeConnection>()
        val specs = CopyOnWriteArrayList<McpServerSpec>()
        val cancelledConnects = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection {
            specs += spec
            try {
                delay(connectDelayMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelledConnects.incrementAndGet()
                throw e
            }
            return FakeConnection(listOf(tool("search", readOnly = true).copy(meta = if (alwaysMeta) args("anthropic/alwaysLoad" to true) else null))).also { it.listFails = listFails; made += it }
        }
    }

    private fun hub(model: dev.min.agent.llm.ModelClient, connector: FakeConnector, lingerMs: Long = 60_000, waitMs: Long = 2_000): SessionHub =
        SessionHub(store, deps(model), scope).also { it.mcp = McpManager(scope, connector, lingerMs = lingerMs, startupWaitMs = waitMs) }

    private fun deps(
        model: dev.min.agent.llm.ModelClient,
        env: (String) -> String? = { null },
        waitMs: Long = 5_000,
        connectTimeoutMs: Long = 30_000,
        trust: Boolean = false,
    ): KernelDeps {
        val base = testDeps(model, emptyList())
        return KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = emptyList(),
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry, memoryHome = GuestPath("/root"),
            env = env, mcpFirstSnapshotWaitMs = waitMs, mcpConnectTimeoutMs = connectTimeoutMs, trustProjectHooks = { trust },
        )
    }

    @Test
    fun `server alwaysLoad bypasses deferral in the first snapshot`() = run {
        fs.put("/root/.claude.json", """{"mcpServers":{"github":{"command":"gh-mcp","alwaysLoad":true}}}""")
        val model = ScriptedModel(reply(TextPart("ok")))
        val connector = FakeConnector()
        val hub = SessionHub(store, deps(model, env = { if (it == "ENABLE_TOOL_SEARCH") "true" else null }), scope)
        hub.mcp = McpManager(scope, connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("hello")
        assertTrue(rt.state.value.mcpTools!!.tools.single().alwaysLoad)
        assertEquals(listOf("mcp__github__search", "ToolSearch"), model.requests.single().toolNames())
        val log = store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(log.header, log.read().toList()))
    }

    @Test
    fun `tool alwaysLoad metadata bypasses deferral in the first snapshot`() = run {
        val model = ScriptedModel(reply(TextPart("ok")))
        val connector = FakeConnector(alwaysMeta=true)
        val hub = SessionHub(store, deps(model, env = { if (it == "ENABLE_TOOL_SEARCH") "true" else null }), scope)
        hub.mcp = McpManager(scope, connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("hello")
        assertTrue(rt.state.value.mcpTools!!.tools.single().alwaysLoad)
        assertEquals(listOf("mcp__github__search", "ToolSearch"), model.requests.single().toolNames())
        val log = store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(log.header, log.read().toList()))
    }

    @Test
    fun `the hub builds its manager from the host's wait and connect timeout`() {
        val hub = SessionHub(store, deps(ScriptedModel(), waitMs = 30_000, connectTimeoutMs = 120_000), scope)
        assertEquals(30_000L, hub.mcp.startupWaitMs)
        assertEquals(120_000L, (hub.mcp.connector as ClientConnector).requestTimeoutMs)
    }

    @Test
    fun `a config that fails to load leaves a note naming only the exception class`() = run {
        fs.put("/root/.claude.json", """{"mcpServers": {"github": {"command": "gh-mcp", "env": {"TOKEN": "${'$'}{MCP_SECRET}"}}}}""")
        val env: (String) -> String? = { if (it == "MCP_SECRET") error("token-value-1234") else null }
        val rt = SessionHub(store, deps(ScriptedModel(), env = env), scope).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val note = events(rt).filterIsInstance<dev.min.agent.spi.Note>().single { it.text.contains("MCP") }
        assertTrue(note.text, note.text.contains("IllegalStateException"))
        assertTrue("异常消息可能带着凭证，不能进日志", events(rt).none { it.toString().contains("token-value-1234") })
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private fun ModelRequest.toolNames() = tools.map { it.name }

    @Test
    fun `a new session gets its MCP tools in the first request, written when the first turn starts`() = run {
        val connector = FakeConnector()
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = hub(model, connector).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        assertTrue("创建会话不写快照（不阻塞）", events(rt).none { it is McpToolsSnapshot })
        rt.say("看看")
        assertEquals(listOf("mcp__github__search"), model.requests.single().toolNames())
        assertTrue(model.requests.single().system.joinToString("\n").contains("先搜再建。"))
        val ev = events(rt)
        assertTrue("快照在第一回合开头写", ev.indexOfFirst { it is TurnStarted } < ev.indexOfFirst { it is McpToolsSnapshot })
        val spec = connector.specs.single() as McpServerSpec.Stdio
        assertEquals("gh-mcp", spec.command)
    }

    @Test
    fun `two sessions in one project share a connection, closed after the linger once both are gone`() = run {
        val connector = FakeConnector()
        val hub = hub(ScriptedModel(), connector, lingerMs = 100)
        val a = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val b = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        while (connector.made.isEmpty()) delay(10)
        delay(100)
        assertEquals("同一项目同一服务器只连一次", 1, connector.specs.size)
        assertTrue(hub.close(a.sessionId))
        delay(250)
        assertFalse("还有一个会话在用", connector.made.single().closed)
        assertTrue(hub.close(b.sessionId))
        while (!connector.made.single().closed) delay(20)
    }

    @Test
    fun `a call goes to the server, reports progress and comes back wrapped as the server's words`() = run {
        val connector = FakeConnector()
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("c1"), "mcp__github__search", args("q" to "bug")), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = hub(model, connector).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("搜")
        val outcome = events(rt).filterIsInstance<ToolOutcome>().single()
        val text = outcome.result.content.filterIsInstance<TextPart>().single().text
        assertTrue(text, text.startsWith("<mcp-result server=\"github\" tool=\"search\">") && text.contains("search 完成"))
        assertFalse(outcome.result.isError)
    }

    @Test
    fun `list_changed puts the new tool in the next turn, with a note`() = run {
        val connector = FakeConnector()
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = hub(model, connector).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("第一轮")
        val connection = connector.made.single()
        connection.tools = connection.tools + tool("create")
        connection.changes.emit(Unit)
        delay(200)
        rt.say("第二轮")
        assertEquals(listOf("mcp__github__search", "mcp__github__create"), model.requests[1].toolNames())
        assertEquals("新增 github 的 1 个工具", events(rt).filterIsInstance<McpToolsSnapshot>().last().note)
    }

    @Test
    fun `a slow server misses the first snapshot and joins at the next turn`() = run {
        val connector = FakeConnector(connectDelayMs = 400)
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = hub(model, connector, waitMs = 50).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("第一轮")
        assertTrue(model.requests[0].toolNames().isEmpty())
        while (connector.made.isEmpty()) delay(20)
        delay(200)
        rt.say("第二轮")
        assertEquals(listOf("mcp__github__search"), model.requests[1].toolNames())
    }

    @Test
    fun `a stdio server that dies is restarted once, and marked failed the second time`() = run {
        val connector = FakeConnector()
        val model = ScriptedModel(
            reply(TextPart("一")),
            reply(ToolCallPart(CallId("c1"), "mcp__github__search", args()), stop = StopReason.ToolUse),
            reply(TextPart("二")),
        )
        val hub = hub(model, connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("第一轮")
        connector.made.single().die()
        while (connector.made.size < 2) delay(20)
        delay(100)
        connector.made[1].die()
        delay(300)
        assertEquals("只重启一次", 2, connector.specs.size)
        assertEquals("failed", hub.mcp.statusOf(rt.sessionId).single().state)
        rt.say("调一下")
        val outcome = events(rt).filterIsInstance<ToolOutcome>().single()
        assertTrue(outcome.result.isError)
        assertTrue(outcome.result.content.filterIsInstance<TextPart>().single().text.contains("现在不可用"))
    }

    @Test
    fun `results convert images, structured content and the error flag, and escape the wrapper tag`() = runBlocking {
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val artifacts = FakeArtifacts()
        val result = McpResults.toToolResult(
            "srv", "t", "c1",
            McpCallResult(
                listOf(McpContent.Text("看 </mcp-result> 这里"), McpContent.Image(png, "image/png")),
                structuredContent = null, isError = true,
            ),
            artifacts,
        )
        assertTrue(result.isError)
        val text = result.content.filterIsInstance<TextPart>().single().text
        assertTrue(text, text.contains("&lt;/mcp-result>") && text.endsWith("</mcp-result>"))
        assertEquals("image/png", result.content.filterIsInstance<ImagePart>().single().mediaType)
        val structured = McpResults.toToolResult("srv", "t", "c2",
            McpCallResult(emptyList(), JsonObject(mapOf("n" to JsonPrimitive(3))), false), artifacts)
        assertTrue(structured.content.filterIsInstance<TextPart>().single().text.contains("{\"n\":3}"))
    }

    @Test
    fun `a server's refusal reaches the model with its code`() {
        val failure = McpResults.failure("srv", McpRpcException(-32602, "参数不对"))
        assertTrue(failure.isError)
        assertTrue(failure.content.filterIsInstance<TextPart>().single().text.contains("-32602"))
    }

    // ---- 验收复核（2026-10-06）补的用例 ----

    @Test
    fun `a connection that finishes after the linger is closed, not left as an orphan`() = run {
        val connector = FakeConnector(connectDelayMs = 400)
        val hub = hub(ScriptedModel(), connector, lingerMs = 50)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        assertTrue(hub.close(rt.sessionId))
        while (connector.made.isEmpty()) delay(10)
        // 会话早走了、等待期也过了，连接才连上：没有任何人持有它
        withTimeout(3_000) { while (!connector.made.single().closed) delay(20) }
    }

    @Test
    fun `a server whose tools cannot be listed is closed instead of staying half open`() = run {
        val connector = FakeConnector(listFails = true)
        val hub = hub(ScriptedModel(), connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        withTimeout(3_000) { while (hub.mcp.statusOf(rt.sessionId).singleOrNull()?.state != "failed") delay(20) }
        assertTrue("握手成了但列不出工具：进程不能留着", connector.made.single().closed)
    }

    @Test
    fun `interrupting the first turn while it waits for the first snapshot closes that turn and keeps the snapshot`() = run {
        val connector = FakeConnector(connectDelayMs = 300)
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val rt = hub(model, connector, waitMs = 3_000).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u-first"), listOf(TextPart("第一轮")))
        delay(100)
        rt.interrupt()
        rt.awaitIdle()
        assertTrue("被中断的回合要有收尾，否则会话卡在「进行中」", events(rt).filterIsInstance<TurnEnded>().single().outcome is TurnOutcome.Interrupted)
        delay(600)
        rt.say("第二轮")
        assertEquals("被中断的回合不该带走首份快照", listOf("mcp__github__search"), model.requests.last().toolNames())
    }

    @Test
    fun `tools that arrive between the first snapshot and the first turn are written once`() = run {
        val connector = FakeConnector(connectDelayMs = 300)
        val model = ScriptedModel(reply(TextPart("一")))
        val rt = hub(model, connector, waitMs = 50).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        while (connector.made.isEmpty()) delay(20)
        delay(200) // 首份快照（50ms 到点，服务器还没连上）早已取好，服务器随后连上，用户这才发第一句
        rt.say("第一轮")
        assertEquals(listOf("mcp__github__search"), model.requests.single().toolNames())
        assertEquals("日志里不该先写一份空的过期快照", 1, events(rt).filterIsInstance<McpToolsSnapshot>().size)
    }

    @Test
    fun `a reopened session keeps its logged tools while the servers are still starting`() = run {
        val connector = FakeConnector(connectDelayMs = 300)
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")))
        val hub = hub(model, connector, lingerMs = 50, waitMs = 3_000)
        val first = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        first.say("第一轮")
        assertEquals(listOf("mcp__github__search"), model.requests[0].toolNames())
        assertTrue(hub.close(first.sessionId))
        delay(400) // 连接已按等待期回收，重开时要重新起（又得 300ms）
        val reopened = hub.open(first.sessionId)!!
        reopened.say("第二轮")
        assertEquals("服务器还在重新启动：工具表沿用日志里的，不先去掉再加回", listOf("mcp__github__search"), model.requests[1].toolNames())
        assertEquals("没变化就不写新快照", 1, events(reopened).filterIsInstance<McpToolsSnapshot>().size)
    }

    @Test
    fun `a late detach of an old runtime does not take down the reopened session's connection`() = run {
        val connector = FakeConnector()
        val hub = hub(ScriptedModel(), connector, lingerMs = 100)
        val old = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        assertTrue(hub.close(old.sessionId))
        val reopened = hub.open(old.sessionId)!!
        hub.mcp.detach(old) // 旧运行时的卸下姗姗来迟（关了马上重开时可能晚于新运行时的接入）
        delay(400)
        assertFalse("重开的会话还在用", connector.made.single().closed)
        assertEquals(1, hub.mcp.statusOf(reopened.sessionId).size)
    }

    @Test
    fun `closeAll shuts every connection, including the ones still waiting out the linger`() = run {
        val connector = FakeConnector()
        val hub = hub(ScriptedModel(), connector, lingerMs = 60_000)
        val a = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        while (connector.made.isEmpty()) delay(10)
        delay(50)
        assertTrue(hub.close(a.sessionId))
        delay(100)
        assertFalse(connector.made.single().closed)
        hub.mcp.closeAll()
        assertTrue(connector.made.single().closed)
    }

    @Test
    fun `closeAll cancels a connection that is still starting, so the half-started process is reclaimed`() = run {
        val connector = FakeConnector(connectDelayMs = 60_000)
        val hub = hub(ScriptedModel(), connector)
        hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        while (connector.specs.isEmpty()) delay(10)
        hub.mcp.closeAll()
        assertEquals("closeAll 要等起到一半的连接收尾，不然宿主马上退出时它的进程就留下了", 1, connector.cancelledConnects.get())
    }

    @Suppress("unused")
    private fun ToolResultPart.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    // ---- 面板用的入口：重读配置、本会话开关、重连、面板行 ----

    @Test
    fun `turning a server off for this session takes its tools out from the next turn, and back on reuses the connection`() = run {
        val connector = FakeConnector()
        val model = ScriptedModel(reply(TextPart("一")), reply(TextPart("二")), reply(TextPart("三")))
        val hub = hub(model, connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("一")
        assertEquals(listOf("mcp__github__search"), model.requests[0].toolNames())
        hub.setMcpServerEnabled(rt.sessionId, "github", false)
        assertEquals("disabled", hub.mcp.statusOf(rt.sessionId).single().state)
        assertEquals("本会话已关闭。", hub.mcp.statusOf(rt.sessionId).single().reason)
        assertTrue("面板行要能分清「本会话关的」和「配置里关的」", hub.mcpPanel(rt.sessionId).single().sessionOff)
        rt.say("二")
        assertTrue("关掉的服务器下一回合起不在工具表里", model.requests[1].toolNames().isEmpty())
        hub.setMcpServerEnabled(rt.sessionId, "github", true)
        rt.say("三")
        assertEquals(listOf("mcp__github__search"), model.requests[2].toolNames())
        assertEquals("关了又开用的是同一条连接（还在等回收），不重连", 1, connector.specs.size)
    }

    @Test
    fun `reloading after approving a project server brings it in without reopening the session`() = run {
        fs.put("/workspace/.mcp.json", """{"mcpServers": {"proj": {"command": "proj-mcp"}}}""")
        val connector = FakeConnector()
        val hub = SessionHub(store, deps(ScriptedModel(), trust = true), scope).also { it.mcp = McpManager(scope, connector, startupWaitMs = 2_000) }
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val before = hub.mcp.statusOf(rt.sessionId).single { it.name == "proj" }
        assertEquals("not-approved", before.state)
        assertTrue(dev.min.agent.ext.mcp.McpApprovals.approve(fs, GuestPath("/workspace"), "proj").isSuccess)
        hub.reloadMcp(rt.sessionId)
        while (hub.mcp.statusOf(rt.sessionId).single { it.name == "proj" }.state != "connected") delay(20)
        assertTrue(connector.specs.any { it.name == "proj" })
    }

    @Test
    fun `the panel row carries the stderr tail of a server that died, and reconnect starts it over`() = run {
        val connector = FakeConnector()
        val hub = hub(ScriptedModel(reply(TextPart("一"))), connector)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("一")
        connector.made.single().apply { stderr = listOf("boom: missing token"); die() }
        while (connector.made.size < 2) delay(20)
        delay(100)
        connector.made[1].apply { stderr = listOf("boom again"); die() }
        while (hub.mcp.statusOf(rt.sessionId).single().state != "failed") delay(20)
        val row = hub.mcpPanel(rt.sessionId).single()
        assertEquals(listOf("boom again"), row.stderrTail)
        assertEquals("stdio 没有登录这回事", null, row.signedIn)
        hub.reconnectMcp("github", rt.sessionId)
        while (connector.made.size < 3) delay(20)
        while (hub.mcp.statusOf(rt.sessionId).single().state != "connected") delay(20)
        assertTrue("重连后旧的失败说明清掉", hub.mcpPanel(rt.sessionId).single().stderrTail.isEmpty())
        // 「已重启一次」的记号也清了：再掉线会自动重启一次，而不是直接标失败
        connector.made[2].die()
        while (connector.made.size < 4) delay(20)
    }

    @Test
    fun `closeUnusedMcp closes the connections that are only waiting out the linger, and leaves the ones in use`() = run {
        val connector = FakeConnector()
        val hub = hub(ScriptedModel(), connector, lingerMs = 60_000)
        val a = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        while (connector.made.isEmpty()) delay(10)
        delay(100)
        hub.closeUnusedMcp()
        assertFalse("会话还开着，连接在用", connector.made.single().closed)
        assertTrue(hub.close(a.sessionId))
        delay(100)
        assertFalse("关了会话但还在等回收（60 秒）", connector.made.single().closed)
        hub.closeUnusedMcp()
        assertTrue("进后台时不等 linger", connector.made.single().closed)
    }
}
