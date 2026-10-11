package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.failing
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * StopFailure 接进内核（hooks.md :2625–:2653）：回合因 API 错误结束时代替 Stop 触发，matcher 对错误类别。
 * 无决策控制——输出与退出码全被丢弃（引擎丢弃表里是 ALL_OUTPUT），这里钉「发了、字段对、结局不受影响」。
 * hook 用 http（FakeHttp 记请求体），与 HooksH3EventsTest 同一条路。
 */
class StopFailureHookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private class FakeHttp(var reply: (dev.min.agent.spi.HttpHookRequest) -> dev.min.agent.spi.HttpHookResult) : dev.min.agent.spi.HttpHookClient {
        val sent = CopyOnWriteArrayList<dev.min.agent.spi.HttpHookRequest>()
        override suspend fun send(request: dev.min.agent.spi.HttpHookRequest): dev.min.agent.spi.HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        override fun stream(request: ModelRequest): Flow<dev.min.agent.llm.ModelStreamEvent> = flow {
            emitAll(route(request)(request))
        }
    }

    private fun hook(event: String, matcher: String? = null) = JsonObject(
        mapOf(
            event to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        buildMap {
                            matcher?.let { put("matcher", JsonPrimitive(it)) }
                            put("hooks", kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf(
                                "type" to JsonPrimitive("http"),
                                "url" to JsonPrimitive("https://hooks.example/$event"),
                            )))))
                        },
                    ),
                ),
            ),
        ),
    )

    private fun hub(model: ModelClient, http: FakeHttp): SessionHub = SessionHub(
        store,
        KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = emptyList(),
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            trustProjectHooks = { true },
            retry = dev.min.agent.kernel.RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 5),
        ),
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
    )

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private fun rateLimited() = ModelException(ModelFailureKind.RateLimited, "429 too many requests", beforeFirstByte = true)

    @Test
    fun `turn ending on an api error fires StopFailure with the mapped error kind instead of Stop`() = run {
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { _ -> failing(rateLimited()) }
        val hooks = dev.min.agent.spi.HooksLoaded(
            listOf(
                HookSourceData("user", hook("StopFailure", "rate_limit")),
                // 原文：StopFailure 是「代替 Stop」——API 错误收尾的回合不发 Stop
                HookSourceData("user", hook("Stop")),
            ),
        )
        val hub = hub(model, http)
        val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions, initial = listOf(hooks))
        rt.say("干活")

        assertEquals("只有 StopFailure 发了", 1, http.sent.size)
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"hook_event_name\":\"StopFailure\""))
        assertTrue("ModelFailureKind.RateLimited 映射到原文的 rate_limit", body.contains("\"error\":\"rate_limit\""))
        assertTrue("error_details 带错误原文", body.contains("429 too many requests"))
        assertTrue("last_assistant_message 是收尾时渲染的错误文本（含错误原文）",
            body.contains("\"last_assistant_message\":\"模型请求失败（RateLimited）：429 too many requests\""))
        assertTrue("同一回合没有发 Stop", events(rt).none { it is HooksRan && it.event == "Stop" })
        assertEquals(TurnOutcome.Failed::class, rt.state.value.lastTurn!!.outcome::class)
    }

    @Test
    fun `outputs are discarded - the failed turn is not revived by continue false`() = run {
        val http = FakeHttp {
            dev.min.agent.spi.HttpHookResult.Responded(
                200,
                """{"decision":"block","reason":"no","continue":false,"stopReason":"stopped","systemMessage":"api failed"}""",
            )
        }
        val model = RoutingModel { _ -> failing(rateLimited()) }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(dev.min.agent.spi.HooksLoaded(listOf(HookSourceData("user", hook("StopFailure"))))),
        )
        rt.say("干活")

        assertEquals(1, http.sent.size)
        val log = events(rt)
        assertEquals(1, log.filterIsInstance<HooksRan>().size)
        assertTrue("无决策控制：不给任何说明", log.filterIsInstance<dev.min.agent.spi.Note>().isEmpty())
        assertEquals("回合仍以 Failed 收尾", TurnOutcome.Failed::class, rt.state.value.lastTurn!!.outcome::class)
    }

    @Test
    fun `non-api failures do not fire StopFailure`() = run {
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        // 一直交工具调用：跑到 MAX_REQUESTS_PER_TURN（200，rootMaxTurns 不设）上限收尾——
        // TurnAbort 无 kind：TurnOutcome.Failed 但不是 API 错误。
        // 每轮给新的调用 id：内核把重复的调用 id 当内部错误，那不是这里要造的失败
        val round = java.util.concurrent.atomic.AtomicInteger(0)
        val model = RoutingModel { _ ->
            dev.min.agent.testkit.reply(
                dev.min.agent.protocol.ToolCallPart(dev.min.agent.protocol.CallId("c${round.incrementAndGet()}"), "noop", dev.min.agent.testkit.args()),
                stop = dev.min.agent.protocol.StopReason.ToolUse,
            )
        }
        val noop = object : dev.min.agent.spi.Tool<JsonObject> {
            override val name = "noop"
            override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
            override val traits = dev.min.agent.spi.ToolTraits(readOnly = true, concurrencySafe = true)
            override fun description(profile: dev.min.agent.spi.HarnessProfile) = "什么也不做"
            override fun parse(raw: JsonObject) = dev.min.agent.spi.ToolInput.Valid(raw)
            override fun permissionAsk(input: JsonObject, ctx: dev.min.agent.spi.ToolContext) =
                dev.min.agent.spi.PermissionAsk(name, "什么也不做")
            override fun run(input: JsonObject, ctx: dev.min.agent.spi.ToolContext): Flow<dev.min.agent.spi.ToolProgress> = flow {
                emit(dev.min.agent.spi.ToolProgress.Done(dev.min.agent.spi.ToolResult(listOf(TextPart("ok")))))
            }
        }
        val depsWithTool = KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(noop),
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            trustProjectHooks = { true },
            retry = dev.min.agent.kernel.RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 5),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub2 = SessionHub(store, depsWithTool, scope)
        val rt = hub2.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(dev.min.agent.spi.HooksLoaded(listOf(HookSourceData("user", hook("StopFailure"))))),
        )
        rt.say("干活")

        assertTrue("请求上限收尾也是 Failed", rt.state.value.lastTurn!!.outcome is TurnOutcome.Failed)
        assertEquals("不是 API 错误，不发 StopFailure", 0, http.sent.size)
        assertTrue(events(rt).none { it is HooksRan })
    }
}
