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
        override suspend fun listTools() = tools
        override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?): McpCallResult {
            onProgress(McpProgress(1.0, 2.0))
            return result(tool, arguments)
        }
        override fun stderrTail(): List<String> = emptyList()
        override suspend fun close() {
            closed = true
            state.value = McpConnectionState.Closed("客户端关闭")
        }
        fun die() {
            state.value = McpConnectionState.Closed("进程退出", exitCode = 1)
        }
    }

    private inner class FakeConnector(var connectDelayMs: Long = 0) : McpConnector {
        val made = CopyOnWriteArrayList<FakeConnection>()
        val specs = CopyOnWriteArrayList<McpServerSpec>()
        override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection {
            specs += spec
            delay(connectDelayMs)
            return FakeConnection(listOf(tool("search", readOnly = true))).also { made += it }
        }
    }

    private fun hub(model: dev.min.agent.llm.ModelClient, connector: FakeConnector, lingerMs: Long = 60_000, waitMs: Long = 2_000): SessionHub {
        val base = testDeps(model, emptyList())
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = emptyList(),
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry, memoryHome = GuestPath("/root"),
        )
        return SessionHub(store, deps, scope).also { it.mcp = McpManager(scope, connector, lingerMs = lingerMs, startupWaitMs = waitMs) }
    }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.open(rt.sessionId)!!.read().toList().map { it.event }

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

    @Suppress("unused")
    private fun ToolResultPart.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }
}
