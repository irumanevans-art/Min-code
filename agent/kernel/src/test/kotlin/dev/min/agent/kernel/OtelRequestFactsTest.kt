package dev.min.agent.kernel

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.OutboundTrace
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Role
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TraceContextProvider
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.failing
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * OTel 第四段：遥测要用、事后推不出来的请求侧事实随日志落盘——
 * 供应商响应 id 与 traceresponse、客户端请求 id、超时、出站 traceparent 的 span 起点、工作流运行归属。
 */
class OtelRequestFactsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(id: SessionId) = store.peek(id)!!.read().toList()

    private fun completed(text: String = "好", started: ModelStreamEvent.Started): Step = {
        flow {
            emit(started)
            emit(
                ModelStreamEvent.Completed(
                    ModelMessage(Role.Assistant, listOf(TextPart(text))), Usage(inputTokens = 1, outputTokens = 1), StopReason.EndTurn, false,
                ),
            )
        }
    }

    // ---- 响应 id 与 traceresponse ----

    @Test
    fun `the reply records the provider response id and traceresponse from the response headers`() = run {
        val trace = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val model = ScriptedModel(completed(started = ModelStreamEvent.Started("msg_1", responseId = "req_abc", traceResponse = trace)))
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val done = events(rt.sessionId).map { it.event }.filterIsInstance<AssistantMessageCompleted>().single()
        assertEquals("响应头里的请求 id 优先于消息 id", "req_abc", done.responseId)
        assertEquals(trace, done.traceResponse)
    }

    @Test
    fun `without a request id header the response id falls back to the stream message id`() = run {
        val model = ScriptedModel(completed(started = ModelStreamEvent.Started("msg_9")))
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val done = events(rt.sessionId).map { it.event }.filterIsInstance<AssistantMessageCompleted>().single()
        assertEquals("msg_9", done.responseId)
        assertNull(done.traceResponse)
    }

    @Test
    fun `a failed request records the response headers and the timeout flag carried by the exception`() = run {
        val boom = ModelException(
            ModelFailureKind.Network, "读取超时", beforeFirstByte = true, timedOut = true,
            responseId = "req_failed", traceResponse = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        )
        val model = ScriptedModel(failing(boom), failing(boom), failing(boom))
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val failed = events(rt.sessionId).map { it.event }.filterIsInstance<ModelRequestFailed>()
        assertTrue(failed.isNotEmpty())
        assertTrue("每次失败都标超时", failed.all { it.timedOut })
        assertTrue(failed.all { it.responseId == "req_failed" })
        assertNotNull(failed.last().traceResponse)
    }

    @Test
    fun `an ordinary network failure is not marked as a timeout`() = run {
        val boom = ModelException(ModelFailureKind.Network, "reset", beforeFirstByte = true)
        val model = ScriptedModel(failing(boom), failing(boom), failing(boom))
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val failed = events(rt.sessionId).map { it.event }.filterIsInstance<ModelRequestFailed>()
        assertTrue(failed.none { it.timedOut })
        assertTrue(failed.all { it.responseId == null })
    }

    // ---- 客户端请求 id ----

    @Test
    fun `each attempt gets its own client request id, logged and sent when the codec sends it`() = run {
        val boom = ModelException(ModelFailureKind.Network, "reset", beforeFirstByte = true)
        val model = ScriptedModel(failing(boom), reply(TextPart("好"))).also { it.sendsId = true }
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val started = events(rt.sessionId).map { it.event }.filterIsInstance<ModelRequestStarted>()
        assertEquals(2, started.size)
        val logged = started.map { it.clientRequestId }
        assertTrue("两次尝试都有 id", logged.all { !it.isNullOrBlank() })
        assertNotEquals("每次尝试一个新的 id", logged[0], logged[1])
        assertEquals("发出去的与落盘的是同一个", logged, model.requests.map { it.clientRequestId })
    }

    @Test
    fun `a codec that does not send the header gets no client request id and nothing is logged`() = run {
        val model = ScriptedModel(reply(TextPart("好")))
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val started = events(rt.sessionId).map { it.event }.filterIsInstance<ModelRequestStarted>().single()
        assertNull(started.clientRequestId)
        assertNull(model.requests.single().clientRequestId)
    }

    // ---- 出站 traceparent ----

    private class RecordingTrace(val allowCustom: Boolean = false) : TraceContextProvider {
        val calls = CopyOnWriteArrayList<Triple<TurnId, RequestId, Long>>()
        override suspend fun toolExecution(session: SessionId, turn: TurnId, call: CallId): String? = null
        override suspend fun hook(session: SessionId, turn: TurnId, call: CallId?, runId: String?): String? = null
        override suspend fun modelRequest(session: SessionId, turn: TurnId, request: RequestId, startSeq: Long): OutboundTrace? {
            calls += Triple(turn, request, startSeq)
            return OutboundTrace("00-${"a".repeat(32)}-${"b".repeat(16)}-01", allowCustom)
        }
    }

    @Test
    fun `the outbound traceparent is handed to a codec that propagates it, with the first attempt seq as the span start`() = run {
        val boom = ModelException(ModelFailureKind.Network, "reset", beforeFirstByte = true)
        val model = ScriptedModel(failing(boom), reply(TextPart("好"))).also { it.propagates = { true } }
        val trace = RecordingTrace()
        val rt = SessionHub(store, testDeps(model, emptyList()).copy(traceContext = trace), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val started = events(rt.sessionId).filter { it.event is ModelRequestStarted }
        assertEquals(2, started.size)
        assertEquals("两次尝试都带 trace 上下文", 2, model.requests.count { it.trace != null })
        assertEquals("宿主拿到的 span 起点是第一次尝试的 seq，重试沿用它", started[0].seq, trace.calls.first().third)
        assertTrue(trace.calls.all { it.third == started[0].seq })
    }

    @Test
    fun `a codec that does not propagate gets no trace even when the host provides one`() = run {
        val model = ScriptedModel(reply(TextPart("好"))) // propagates 默认恒 false：第三方端点
        val rt = SessionHub(store, testDeps(model, emptyList()).copy(traceContext = RecordingTrace()), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        assertNull("第三方端点收不到 traceparent", model.requests.single().trace)
    }

    @Test
    fun `the explicit custom endpoint consent is passed to the codec policy`() = run {
        val seen = CopyOnWriteArrayList<Boolean>()
        val model = ScriptedModel(reply(TextPart("好"))).also { it.propagates = { allow -> seen += allow; allow } }
        val rt = SessionHub(store, testDeps(model, emptyList()).copy(traceContext = RecordingTrace(allowCustom = true)), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        assertEquals(listOf(true), seen)
        assertNotNull(model.requests.single().trace)
    }

    @Test
    fun `no host trace context means no trace on the request`() = run {
        val model = ScriptedModel(reply(TextPart("好"))).also { it.propagates = { true } }
        val rt = SessionHub(store, testDeps(model, emptyList()), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        assertNull(model.requests.single().trace)
    }

    // ---- 工作流运行归属 ----

    private class SpawnTool(private val onDone: (DelegateResult) -> Unit = {}) : Tool<JsonObject> {
        override val name = "Spawn"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用：委派一个子 agent"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val workflow = input["wf"]?.jsonPrimitive?.booleanOrNull == true
            val result = ctx.agents.delegate(
                DelegateRequest(
                    description = "子任务", prompt = input["prompt"]!!.jsonPrimitive.content, background = false,
                    workflow = workflow,
                    workflowRunId = if (workflow) "workflow_run_1" else null, workflowName = if (workflow) "deploy" else null,
                ),
            )
            onDone(result)
            val text = (result as? DelegateResult.Finished)?.report ?: "no"
            emit(ToolProgress.Done(ToolResult(listOf(TextPart(text)))))
        }
    }

    private fun spawnCall(id: String, prompt: String, wf: Boolean) =
        ToolCallPart(CallId(id), "Spawn", JsonObject(mapOf("prompt" to JsonPrimitive(prompt), "wf" to JsonPrimitive(wf))))

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { part ->
        when (part) {
            is TextPart -> part.text
            is ToolResultPart -> part.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    @Test
    fun `a sub agent spawned by a workflow node records the run id and name, and its descendants inherit them`() = run {
        val route = object : dev.min.agent.llm.ModelClient {
            override fun stream(request: ModelRequest): Flow<ModelStreamEvent> {
                val last = request.lastText()
                val step: Step = when {
                    last == "start" -> reply(spawnCall("c1", "child", wf = true), stop = StopReason.ToolUse)
                    last.contains("child") -> reply(spawnCall("c2", "grand", wf = false), stop = StopReason.ToolUse)
                    else -> reply(TextPart("完成"))
                }
                return step(request)
            }
        }
        val hub = SessionHub(store, testDeps(route, listOf(SpawnTool())), newScope())
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("start")
        val origins = store.list().filter { it.parent != null }.mapNotNull { header ->
            events(header.sessionId).map { it.event }.filterIsInstance<SubagentOrigin>().firstOrNull()
        }
        assertEquals("子与孙各一个来历：$origins", 2, origins.size)
        assertTrue("子 agent 与孙 agent 都带运行 id 与脚本名", origins.all { it.workflowRunId == "workflow_run_1" && it.workflowName == "deploy" })
        // 根会话自己不是工作流的子 agent
        assertNull(events(rt.sessionId).map { it.event }.filterIsInstance<SubagentOrigin>().firstOrNull())
    }

    @Test
    fun `a sub agent that is not spawned by a workflow has no run id`() = run {
        val route = object : dev.min.agent.llm.ModelClient {
            override fun stream(request: ModelRequest): Flow<ModelStreamEvent> {
                val step: Step = if (request.lastText() == "start") reply(spawnCall("c1", "child", wf = false), stop = StopReason.ToolUse) else reply(TextPart("完成"))
                return step(request)
            }
        }
        val rt = SessionHub(store, testDeps(route, listOf(SpawnTool())), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("start")
        val origin = store.list().filter { it.parent != null }.mapNotNull { h ->
            events(h.sessionId).map { it.event }.filterIsInstance<SubagentOrigin>().firstOrNull()
        }.single()
        assertNull(origin.workflowRunId)
        assertNull(origin.workflowName)
    }
}
