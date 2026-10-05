package dev.min.agent.kernel

import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
import java.util.concurrent.atomic.AtomicLong

/**
 * `--json-schema` 的工具式结构化输出（[StructuredOutputPlan] → StructuredOutput 工具）：
 * 校验失败的调用拿到带路径的错误结果、模型重交；交卷成功回合立即收尾（不再请求模型）；
 * 模型想空手结束会被提醒（steer 输入，计入次数）；到 MAX_STRUCTURED_OUTPUT_RETRIES 回合失败。
 */
class StructuredOutputSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest, Int) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request, requests.size)(request))
        }
    }

    /** 一个无害的只读工具：用来证明「交卷后同一批里别的调用照常执行」 */
    private val echo = object : Tool<JsonObject> {
        override val name = "Echo"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "回显"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "回显一句话")

        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ok")))))
        }
    }

    private val schema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("name", JsonObject(mapOf("type" to JsonPrimitive("string"))))
        }
        putJsonArray("required") { add(JsonPrimitive("name")) }
    }

    private fun callStructured(n: Int, body: String) =
        reply(ToolCallPart(CallId("call_$n"), StructuredOutputTool.NAME, jsonOf(body)))

    private fun jsonOf(text: String): JsonObject =
        dev.min.agent.protocol.AgentJson.parseToJsonElement(text) as JsonObject

    private fun deps(model: ModelClient, plan: StructuredOutputPlan?, env: Map<String, String> = emptyMap()): KernelDeps =
        KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(echo),
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }),
            platform = "test",
            zone = ZoneOffset.UTC,
            structuredOutput = plan,
            env = { env[it] },
        )

    private fun plan() = StructuredOutputPlan(schema)

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun session(model: ModelClient, plan: StructuredOutputPlan?, env: Map<String, String> = emptyMap()): SessionRuntime =
        SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s_${ids.incrementAndGet()}"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(model, plan, env), scope(),
        )

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.open(rt.sessionId)!!.read().toList().map { it.event }

    @Test
    fun `a valid delivery is adopted, ends the turn without another request, and survives reopen`() = run {
        val model = RoutingModel { _, n ->
            when (n) {
                // 第一次：缺必填字段，校验失败
                1 -> callStructured(1, """{"wrong": true}""")
                // 第二次：合格的答案 + 同一批里还有个别的工具：都执行，然后回合收尾、没有第三次请求
                else -> reply(
                    ToolCallPart(CallId("call_2"), StructuredOutputTool.NAME, jsonOf("""{"name": "Min"}""")),
                    ToolCallPart(CallId("call_2b"), "Echo", JsonObject(emptyMap())),
                )
            }
        }
        val rt = session(model, plan())
        rt.say("给出名字")
        assertEquals(2, model.requests.size)
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        assertEquals(jsonOf("""{"name": "Min"}"""), rt.state.value.structuredOutput!!.value)
        // 第一次调用的错误结果把不合格路径讲清楚，模型看得懂才改得对
        val log = events(rt)
        val failed = log.filterIsInstance<ToolOutcome>().first()
        assertTrue("错误结果要带路径与原因：${failed.result.content}", failed.result.isError)
        assertTrue(
            failed.result.content.filterIsInstance<TextPart>().joinToString { it.text }.contains("缺少必填字段 name"),
        )
        // 同批的 Echo 照常执行
        assertTrue(log.filterIsInstance<ToolOutcome>().any { !it.result.isError && it.callId.value == "call_2b" })
        // 日志即真相：重开日志折叠出同一份最终答案
        val reopened = store.open(rt.sessionId)!!
        val folded = fold(reopened.header, reopened.read().toList())
        assertEquals(jsonOf("""{"name": "Min"}"""), folded.structuredOutput!!.value)
    }

    @Test
    fun `ending without calling it gets one reminder as a steer input that counts as an attempt`() = run {
        val model = RoutingModel { _, n ->
            when (n) {
                1 -> reply(TextPart("我觉得做完了"))
                else -> callStructured(2, """{"name": "Min"}""")
            }
        }
        val rt = session(model, plan())
        rt.say("给出名字")
        assertEquals("提醒之后第二次请求交卷", 2, model.requests.size)
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        // 提醒是进对话的 steer 输入，日志里看得到（重复跑批的人能解释第二次请求从哪来）
        val reminder = events(rt).filterIsInstance<dev.min.agent.spi.InputReceived>()
            .single { it.origin == InputOrigin.Hook("structured_output") }
        assertTrue(reminder.parts.filterIsInstance<TextPart>().joinToString { it.text }.contains("StructuredOutput"))
        // 提醒的话跟在第一个请求的对话后面，模型第二个请求看得到
        assertTrue(model.requests[1].messages.any { m -> m.parts.any { p -> (p as? TextPart)?.text?.contains("StructuredOutput") == true } })
    }

    @Test
    fun `exhausting the retries fails the turn - default five, env override two`() = run {
        val model = RoutingModel { _, n -> callStructured(n, """{"wrong": $n}""") }
        val rt = session(model, plan(), env = mapOf("MAX_STRUCTURED_OUTPUT_RETRIES" to "2"))
        rt.say("给出名字")
        assertEquals("两次尝试（首次 + 一次重试）就停", 2, model.requests.size)
        val outcome = rt.state.value.lastTurn!!.outcome
        assertTrue((outcome as? TurnOutcome.Failed)?.message?.contains("2 次") == true)
        assertNull(rt.state.value.structuredOutput)
    }

    @Test
    fun `the default cap is five attempts`() = run {
        val model = RoutingModel { _, n -> callStructured(n, """{"wrong": $n}""") }
        val rt = session(model, plan())
        rt.say("给出名字")
        assertEquals(5, model.requests.size)
        assertTrue((rt.state.value.lastTurn!!.outcome as? TurnOutcome.Failed)?.message?.contains("5 次") == true)
    }

    @Test
    fun `the tool is offered to root sessions only, with the user schema as its input schema`() = run {
        val model = RoutingModel { _, n -> reply(TextPart("x")) }
        val deps = deps(model, plan())
        val root = SessionState(
            header = LogHeader(LOG_FORMAT_VERSION, SessionId("s_r"), 0),
            created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            lastSeq = 0,
            settings = SessionSettings(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions, null),
            inbox = emptyList(), seenInputs = emptySet(), history = emptyList(), toolResults = emptyMap(), turn = null,
            usage = dev.min.agent.protocol.Usage(),
        )
        val rootTools = availableTools(root, deps)
        val tool = rootTools.single { it.name == StructuredOutputTool.NAME }
        assertEquals("工具的入参 schema 就是用户给的 schema", schema, tool.inputSchema)
        // 子 agent（origin 非空）的工具表里没有它：最终答案属于主对话
        val child = root.copy(
            origin = SubagentOrigin(
                parent = SessionId("s_r"), root = SessionId("s_r"), parentCall = CallId("c"),
                agentType = "general-purpose", fork = false, depth = 1, cacheKey = "k",
            ),
        )
        assertFalse(availableTools(child, deps).any { it.name == StructuredOutputTool.NAME })
        // 没开 --json-schema 的根会话也没有它
        assertFalse(availableTools(root, deps(model, null)).any { it.name == StructuredOutputTool.NAME })
    }
}
