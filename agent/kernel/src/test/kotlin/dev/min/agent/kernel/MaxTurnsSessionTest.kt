package dev.min.agent.kernel

import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.FakeExec
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
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 根会话的模型请求次数上限（`--max-turns` / `CLAUDE_CODE_MAX_TURNS` → [KernelDeps.rootMaxTurns]）：
 * 到上限以 Interrupted(Limit) 收尾、可续；没给上限时只剩防呆闸。上限是运行时选项——
 * 它属于一次 CLI 运行而不是会话，日志里没有它的痕迹，重开会话后自然不带它。
 */
class MaxTurnsSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest, Int) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request, requests.size)(request))
        }
    }

    /** 无害的只读工具：模型每调它一次，回合就还要再发一次请求 */
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

    private fun callEcho(n: Int) = reply(ToolCallPart(CallId("call_$n"), "Echo", JsonObject(emptyMap())))

    private fun deps(model: ModelClient, rootMaxTurns: Int?): KernelDeps = KernelDeps(
        clock = { 1_000 },
        ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) },
        tools = listOf(echo),
        host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }),
        platform = "test",
        zone = ZoneOffset.UTC,
        rootMaxTurns = rootMaxTurns,
    )

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private suspend fun session(model: ModelClient, rootMaxTurns: Int?): SessionRuntime =
        SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s_${ids.incrementAndGet()}"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(model, rootMaxTurns), scope(),
        )

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    @Test
    fun `the root request limit ends the turn with Interrupted Limit and stays out of the log`() = run {
        val model = RoutingModel { _, n -> if (n >= 5) reply(TextPart("做完了")) else callEcho(n) }
        val rt = session(model, rootMaxTurns = 2)
        rt.say("一直调工具")
        assertEquals("到上限前恰好发两次请求", 2, model.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Limit), rt.state.value.lastTurn!!.outcome)
        // 日志即真相：这份日志重开（新进程没有 --max-turns）不带任何上限——模型说完 5 句话照常结束
        val reopened = SessionRuntime.open(store, rt.sessionId, deps(model, rootMaxTurns = null), scope())!!
        reopened.say("接着来")
        assertEquals("第 3、4 次调工具，第 5 次收尾", 5, model.requests.size)
        assertEquals(TurnOutcome.Completed, reopened.state.value.lastTurn!!.outcome)
    }

    @Test
    fun `without a limit the turn runs to its natural end`() = run {
        val model = RoutingModel { _, n -> if (n >= 4) reply(TextPart("做完了")) else callEcho(n) }
        val rt = session(model, rootMaxTurns = null)
        rt.say("做三步")
        assertEquals(4, model.requests.size)
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
    }

    @Test
    fun `the limit is per turn - a second run gets its own budget`() = run {
        val model = RoutingModel { _, n -> callEcho(n) }
        val rt = session(model, rootMaxTurns = 1)
        rt.say("第一轮")
        assertEquals(1, model.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Limit), rt.state.value.lastTurn!!.outcome)
        rt.say("第二轮")
        assertEquals("新一轮重新计数", 2, model.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Limit), rt.state.value.lastTurn!!.outcome)
    }

    @Test
    fun `a subagent keeps its own origin maxTurns and ignores the root limit`() = run {
        val model = RoutingModel { _, n -> callEcho(n) }
        val rt = SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s_child"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(model, rootMaxTurns = 5), scope(),
            initial = listOf(
                SubagentOrigin(
                    parent = SessionId("s_parent"), root = SessionId("s_parent"), parentCall = CallId("c1"),
                    agentType = "general-purpose", fork = false, depth = 1, cacheKey = "k", maxTurns = 1,
                ),
            ),
            loadMemory = false, loadSettings = false, loadSkills = false,
        )
        rt.startDelegated()
        rt.awaitIdle()
        assertEquals("子会话用来历里的 1，不用根会话的 5", 1, model.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Limit), rt.state.value.lastTurn!!.outcome)
    }
}
