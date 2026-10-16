package dev.min.agent.kernel

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.RejectedToolRef
import dev.min.agent.llm.ToolSpec
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.McpServerInfo
import dev.min.agent.spi.McpServersLoaded
import dev.min.agent.spi.McpToolEntry
import dev.min.agent.spi.McpToolSchemaRejected
import dev.min.agent.spi.McpToolsSnapshot
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeTool
import dev.min.agent.testkit.READ_ONLY
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.failing
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * API 拒收 MCP 工具 schema 后降级并重发（CC 2.1.300）。假模型第一次回点名某 MCP 工具的 400，
 * 内核落盘事件、把该工具改成空 object 再发；用户看不到一次失败的回合。
 */
class McpSchemaRejectedSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s-schema"), createdAtMs = 0)
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val builtin = FakeTool("Probe", READ_ONLY) { dev.min.agent.spi.ToolResult(listOf(TextPart("ok"))) }
    private val servers = McpServersLoaded(listOf(McpServerInfo("github", "user", "http", "connected", toolCount = 2)))

    private fun schema(vararg props: String) = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(props.associateWith { JsonObject(mapOf("type" to JsonPrimitive("string"))) }),
        ),
    )

    private fun entry(tool: String, vararg props: String) = McpToolEntry(
        name = "mcp__github__$tool", server = "github", tool = tool, description = "github 的 $tool",
        inputSchema = schema(*props),
    )

    private fun badRequest(vararg refs: RejectedToolRef) = ModelException(
        ModelFailureKind.BadRequest, "HTTP 400：schema rejected", httpStatus = 400, beforeFirstByte = true,
        rejectedTools = refs.toList(),
    )

    private suspend fun session(model: ScriptedModel): SessionRuntime =
        SessionRuntime.create(store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions), testDeps(model, listOf(builtin)), scope)

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(): List<SessionEvent> = store.peek(SessionId("s-schema"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private fun ToolSpec.isDegraded(): Boolean =
        inputSchema == EMPTY_MCP_INPUT_SCHEMA && description.endsWith(MCP_SCHEMA_REJECTED_NOTE)

    private fun blankState(
        mcpTools: McpToolsSnapshot? = null,
        rejectedMcpSchemas: Map<String, String> = emptyMap(),
    ) = SessionState(
        header = header,
        created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
        lastSeq = 0,
        settings = SessionSettings(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions, effort = null),
        inbox = emptyList(),
        seenInputs = emptySet(),
        history = emptyList(),
        toolResults = emptyMap(),
        turn = null,
        usage = Usage(),
        mcpTools = mcpTools,
        rejectedMcpSchemas = rejectedMcpSchemas,
    )

    @Test
    fun `Anthropic index 400 degrades the named MCP tool, resends, and later turns stay degraded`() = run {
        val model = ScriptedModel(
            failing(badRequest(RejectedToolRef.Index(1))),
            reply(TextPart("好")),
            reply(TextPart("仍好")),
        )
        val rt = session(model)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"), entry("create", "title"))))
        rt.say("搜一下")

        assertEquals(2, model.requests.size)
        assertEquals(listOf("Probe", "mcp__github__search", "mcp__github__create"), model.requests[0].tools.map { it.name })
        assertEquals(schema("q"), model.requests[0].tools[1].inputSchema)
        assertTrue("第一次失败后立刻重发，且该工具已是空 schema", model.requests[1].tools[1].isDegraded())
        assertEquals(schema("title"), model.requests[1].tools[2].inputSchema)
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn?.outcome)

        val log = events()
        assertEquals(listOf("mcp__github__search"), log.filterIsInstance<McpToolSchemaRejected>().map { it.name })
        val failed = log.filterIsInstance<ModelRequestFailed>().single()
        assertTrue(failed.willRetry)
        assertEquals(0L, failed.retryDelayMs)
        assertTrue(log.filterIsInstance<TurnEnded>().single().outcome is TurnOutcome.Completed)

        rt.say("再来")
        assertEquals(3, model.requests.size)
        assertTrue("之后的回合仍是降级后的 schema", model.requests[2].tools[1].isDegraded())
        assertEquals(schema("title"), model.requests[2].tools[2].inputSchema)
        assertLogIsTruth(rt)
    }

    @Test
    fun `reopening from the log rebuilds the degraded request`() = run {
        val first = ScriptedModel(failing(badRequest(RejectedToolRef.Index(1))), reply(TextPart("好")))
        val rt = session(first)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"))))
        rt.say("搜一下")
        val degraded = first.requests[1]

        val second = ScriptedModel(reply(TextPart("重开")))
        val reopened = SessionRuntime.open(store, SessionId("s-schema"), testDeps(second, listOf(builtin)), scope)!!
        reopened.say("继续")
        assertEquals(degraded.tools, second.requests.single().tools)
        assertTrue(second.requests.single().tools[1].isDegraded())
        assertLogIsTruth(reopened)
    }

    @Test
    fun `naming a built-in tool does not degrade and the turn fails`() = run {
        val model = ScriptedModel(failing(badRequest(RejectedToolRef.Index(0))))
        val rt = session(model)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"))))
        rt.say("搜一下")
        assertEquals(1, model.requests.size)
        assertEquals(schema("q"), model.requests.single().tools[1].inputSchema)
        assertTrue(rt.state.value.lastTurn?.outcome is TurnOutcome.Failed)
        assertTrue(events().filterIsInstance<McpToolSchemaRejected>().isEmpty())
        assertLogIsTruth(rt)
    }

    @Test
    fun `a 400 that names no tool fails as before`() = run {
        val model = ScriptedModel(failing(badRequest()))
        val rt = session(model)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"))))
        rt.say("搜一下")
        assertEquals(1, model.requests.size)
        assertTrue(rt.state.value.lastTurn?.outcome is TurnOutcome.Failed)
        assertTrue(events().filterIsInstance<McpToolSchemaRejected>().isEmpty())
        assertLogIsTruth(rt)
    }

    @Test
    fun `one 400 naming two MCP tools degrades both`() = run {
        val model = ScriptedModel(
            failing(badRequest(RejectedToolRef.Index(1), RejectedToolRef.Index(2))),
            reply(TextPart("好")),
        )
        val rt = session(model)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"), entry("create", "title"))))
        rt.say("搜一下")
        assertEquals(2, model.requests.size)
        assertTrue(model.requests[1].tools[1].isDegraded())
        assertTrue(model.requests[1].tools[2].isDegraded())
        assertEquals(listOf("mcp__github__search", "mcp__github__create"), events().filterIsInstance<McpToolSchemaRejected>().map { it.name })
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn?.outcome)
        assertLogIsTruth(rt)
    }

    @Test
    fun `OpenAI-style name maps onto this request's tools`() = run {
        val model = ScriptedModel(
            failing(badRequest(RejectedToolRef.Name("mcp__github__create"))),
            reply(TextPart("好")),
        )
        val rt = session(model)
        rt.loadMcp(servers, McpToolsSnapshot(listOf(entry("search", "q"), entry("create", "title"))))
        rt.say("建一个")
        assertTrue(model.requests[1].tools[2].isDegraded())
        assertEquals(schema("q"), model.requests[1].tools[1].inputSchema)
        assertEquals(listOf("mcp__github__create"), events().filterIsInstance<McpToolSchemaRejected>().map { it.name })
        assertLogIsTruth(rt)
    }

    @Test
    fun `index maps this request not the snapshot order`() {
        val request = ModelRequest(
            profile = dev.min.agent.testkit.TEST_PROFILE,
            system = emptyList(),
            messages = emptyList(),
            tools = listOf(
                ToolSpec("Probe", "p", schema()),
                ToolSpec("mcp__github__search", "s", schema("q")),
            ),
        )
        val state = blankState(mcpTools = McpToolsSnapshot(listOf(entry("search", "q"), entry("create", "title"))))
        assertEquals(
            listOf("mcp__github__search"),
            mcpToolsNamedInRejection(badRequest(RejectedToolRef.Index(1)), request, state),
        )
        assertEquals(
            emptyList<String>(),
            mcpToolsNamedInRejection(badRequest(RejectedToolRef.Index(0)), request, state),
        )
        assertEquals(
            emptyList<String>(),
            mcpToolsNamedInRejection(badRequest(RejectedToolRef.Index(2)), request, state),
        )
    }

    @Test
    fun `the same tool is not degraded twice`() {
        val request = ModelRequest(
            profile = dev.min.agent.testkit.TEST_PROFILE,
            system = emptyList(),
            messages = emptyList(),
            tools = listOf(ToolSpec("mcp__github__search", "s", EMPTY_MCP_INPUT_SCHEMA)),
        )
        val state = blankState(
            mcpTools = McpToolsSnapshot(listOf(entry("search", "q"))),
            rejectedMcpSchemas = mapOf("mcp__github__search" to "already"),
        )
        assertEquals(emptyList<String>(), mcpToolsNamedInRejection(badRequest(RejectedToolRef.Name("mcp__github__search")), request, state))
    }
}
