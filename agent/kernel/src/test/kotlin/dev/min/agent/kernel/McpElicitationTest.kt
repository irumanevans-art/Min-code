package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class McpElicitationTest {
    @Test fun `manager associates reverse elicitation with its actual executing MCP call`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs().apply { put("/root/.claude.json", """{"mcpServers":{"s":{"command":"unused"}}}""") }
            val model = ScriptedModel(reply(ToolCallPart(CallId("invoke"), "mcp__s__ask", args()), stop=StopReason.ToolUse), reply(TextPart("done")))
            val base = testDeps(model, emptyList())
            val deps = KernelDeps(base.clock, base.ids, base.models, emptyList(), HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts), base.platform, base.zone, memoryHome=GuestPath("/root"))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val connector = object : McpConnector {
                override suspend fun connect(spec: dev.min.agent.ext.mcp.McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection = error("必须接交互处理器")
                override suspend fun connectWithInteraction(spec: dev.min.agent.ext.mcp.McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>, elicit: suspend (JsonObject?) -> JsonObject) = object : McpConnection {
                    override val state = MutableStateFlow<dev.min.agent.mcp.McpConnectionState>(dev.min.agent.mcp.McpConnectionState.Ready(dev.min.agent.mcp.McpServerInfo("2025-11-25", args(), args("tools" to args(), "prompts" to args(), "resources" to args()), null)))
                    override val toolsChanged = emptyFlow<Unit>()
                    override val instructions: String? = null
                    override suspend fun listTools() = listOf(dev.min.agent.mcp.McpToolDef("ask", null, "ask", args("type" to "object"), null, null, null))
                    override suspend fun call(tool: String, arguments: JsonObject, onProgress: (dev.min.agent.mcp.McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?) = dev.min.agent.mcp.McpCallResult(listOf(dev.min.agent.mcp.McpContent.Text(elicit(form).toString())), null, false)
                    override suspend fun getPrompt(name: String, arguments: JsonObject) = listOf(dev.min.agent.mcp.McpPromptMessage("user", dev.min.agent.mcp.McpContent.Text(elicit(form).toString())))
                    override suspend fun readResource(uri: String) = dev.min.agent.mcp.McpCallResult(listOf(dev.min.agent.mcp.McpContent.Text(elicit(form).toString())), null, false)
                    override fun stderrTail() = emptyList<String>()
                    override suspend fun close() { state.value = dev.min.agent.mcp.McpConnectionState.Closed("closed") }
                }
            }
            hub.mcp = McpManager(scope, connector)
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("u"), listOf(TextPart("invoke")))
            val asked = withTimeout(5_000) { rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single() }
            assertEquals(CallId("invoke"), asked.callId); assertEquals(rt.state.value.turn!!.turnId, asked.mcpTurnId)
            rt.declineQuestion(asked.questionId); rt.awaitIdle()
            val log = store.open(rt.sessionId)!!; val records = log.read().toList()
            assertTrue(records.map { it.event }.filterIsInstance<ToolOutcome>().single().result.content.toString().contains("decline"))
            val second = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            assertTrue(rt.sessionId.value < second.sessionId.value)
            for (prompt in listOf(true, false)) {
                val request = async {
                    if (prompt) second.mcpCaller.getPrompt("s", "p", args())
                    else second.mcpCaller.readResource("s", "file:///item", deps.host.artifacts(second.sessionId), "before-input")
                }
                val inputQuestion = withTimeout(2_000) { second.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single() }
                assertTrue(rt.state.value.pendingMcpQuestions.isEmpty())
                assertNull(second.state.value.turn); assertNull(inputQuestion.mcpTurnId)
                second.declineQuestion(inputQuestion.questionId); request.await()
            }
            val secondLog = store.open(second.sessionId)!!
            assertEquals(second.state.value, fold(secondLog.header, secondLog.read().toList()))
            assertEquals(rt.state.value, fold(log.header, records))
            hub.close(rt.sessionId); hub.close(second.sessionId); hub.closeMcpConnections()
        } finally { scope.cancel() }
    }
    @get:Rule val tmp = TemporaryFolder()
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private val form = json("""{"message":"填写","requestedSchema":{"type":"object","properties":{"name":{"type":"string"},"count":{"type":"integer"},"ratio":{"type":"number"},"enabled":{"type":"boolean"},"color":{"type":"string","enum":["red","blue"]}},"required":["name","count","ratio","enabled","color"]}}""")
    private val url = json("""{"mode":"url","message":"登录","url":"https://example.com/auth?a=1#section","elicitationId":"flow"}""")

    private suspend fun runCase(canAnswer: Boolean = true, timeout: Long = 2_000, block: suspend (SessionRuntime, SessionHub, FileSessionStore) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(ScriptedModel(), emptyList())
            val deps = KernelDeps(base.clock, base.ids, base.models, emptyList(), base.host, base.platform, base.zone, canAnswerMcpElicitation = canAnswer, mcpElicitationTimeoutMs = timeout)
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            withTimeout(5_000) { block(rt, hub, store) }
            val log = store.open(rt.sessionId)!!
            assertEquals(rt.state.value, fold(log.header, log.read().toList()))
        } finally { scope.cancel() }
    }

    @Test fun `idle form becomes an answerable Hub question without starting a turn and returns typed content`() = runBlocking {
        runCase { rt, hub, _ -> coroutineScope {
            val request = async { rt.handleMcpElicitation("remote", form) }
            val asked = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
            assertNull(rt.state.value.turn)
            assertTrue(asked.questions.all { "remote" in it.question })
            assertEquals(listOf("string", "integer", "number", "boolean", "string"), asked.questions.map { it.inputType })
            withTimeout(1_000) { hub.pending.first { it.isNotEmpty() } }
            assertTrue(rt.answerQuestion(asked.questionId, listOf(QuestionAnswer(other="Ada"), QuestionAnswer(other="3"), QuestionAnswer(other="1.5"), QuestionAnswer(selected=listOf("true")), QuestionAnswer(selected=listOf("blue")))))
            val result = request.await()
            assertEquals("accept", result["action"]!!.jsonPrimitive.content)
            assertEquals(json("""{"name":"Ada","count":3,"ratio":1.5,"enabled":true,"color":"blue"}"""), result["content"])
        } }
    }

    @Test fun `unsupported schema and unattended hosts decline without pending interactions`() = runBlocking {
        runCase { rt, _, _ ->
            val unsupported = json("""{"message":"x","requestedSchema":{"type":"object","properties":{"nested":{"type":"object"}}}}""")
            assertEquals("decline", rt.handleMcpElicitation("s", unsupported)["action"]!!.jsonPrimitive.content)
            assertTrue(rt.state.value.mcpQuestions.isEmpty())
        }
        runCase(canAnswer=false) { rt, _, _ ->
            assertEquals("decline", rt.handleMcpElicitation("s", form)["action"]!!.jsonPrimitive.content)
            assertEquals("decline", rt.handleMcpElicitation("s", url)["action"]!!.jsonPrimitive.content)
            assertTrue(rt.state.value.mcpQuestions.isEmpty()); assertTrue(rt.state.value.mcpUrls.isEmpty())
        }
    }

    @Test fun `decline timeout shutdown and reverse request cancellation have distinct persisted outcomes`() = runBlocking {
        runCase { rt, _, _ -> coroutineScope {
            val request = async { rt.handleMcpElicitation("s", form) }
            val q = rt.state.first { it.pendingMcpQuestions.isNotEmpty() }.pendingMcpQuestions.single()
            rt.declineQuestion(q.questionId)
            assertEquals("decline", request.await()["action"]!!.jsonPrimitive.content)
        } }
        runCase(timeout=50) { rt, _, _ ->
            assertEquals("cancel", rt.handleMcpElicitation("s", form)["action"]!!.jsonPrimitive.content)
            assertTrue(rt.state.value.mcpQuestions.values.single().cancelled)
        }
        runCase { rt, hub, _ -> coroutineScope {
            val request = async { rt.handleMcpElicitation("s", form) }
            rt.state.first { it.pendingMcpQuestions.isNotEmpty() }
            hub.close(rt.sessionId)
            assertEquals("cancel", request.await()["action"]!!.jsonPrimitive.content)
        } }
        runCase { rt, _, _ -> coroutineScope {
            val request = async { rt.handleMcpElicitation("s", form) }
            rt.state.first { it.pendingMcpQuestions.isNotEmpty() }
            request.cancelAndJoin()
            assertTrue(rt.state.value.mcpQuestions.values.single().cancelled)
        } }
    }

    @Test fun `url records full URI domain and caller consent without running a model turn`() = runBlocking {
        runCase { rt, hub, _ -> coroutineScope {
            val request = async { rt.handleMcpElicitation("remote", url) }
            val pending = rt.state.first { it.pendingMcpUrls.isNotEmpty() }.pendingMcpUrls.single()
            assertEquals("https://example.com/auth?a=1#section", pending.request.url)
            assertEquals("example.com", pending.request.domain)
            assertNull(rt.state.value.turn)
            val visible = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Question
            assertEquals(pending.request, visible.mcpUrl)
            rt.resolveMcpUrl(pending.request.questionId, "accept")
            assertEquals("accept", request.await()["action"]!!.jsonPrimitive.content)
        } }
    }

    @Test fun `connector advertises both elicitation modes and dispatches server requests`() = runBlocking {
        var calls = 0
        val options = ClientConnector(ProcessPort.Unavailable, null, "1").clientOptions("s", { listOf(GuestPath("/w")) }) { calls++; json("""{"action":"decline"}""") }
        val capability = options.capabilities["elicitation"]!!.jsonObject
        assertEquals(setOf("form", "url"), capability.keys)
        assertEquals("decline", options.requestHandler("elicitation/create", form).jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
    }
}
