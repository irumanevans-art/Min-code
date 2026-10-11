package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
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
 * InstructionsLoaded 接进内核（hooks.md :1278–:1313）：Min 里对应两条载入路——
 * 会话创建的记忆快照（eager，load_reason=session_start；@导入的文件 include）与工具碰到文件后的按需注入
 * （子目录 CLAUDE.md = nested_traversal、带 paths: 的规则 = path_glob_match）。
 * 输入字段按原文逐字段断言；无决策控制，输出（连 systemMessage）被丢弃也一并钉住。
 * hook 用 http（FakeHttp 记请求体），与 HooksH3EventsTest 同一条路。
 */
class InstructionLoadedHookTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()

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

    /** 只读工具：permissionAsk 声明的路径就是 ToolExecutor 记下的「碰到过的文件」（按需注入按它发现） */
    private val read = object : Tool<JsonObject> {
        override val name = "Read"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true)
        override fun description(profile: HarnessProfile) = "读文件"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(
            name, "读文件",
            paths = listOf(PathAccess(GuestPath((input["file_path"] as JsonPrimitive).content), AccessKind.Read, PathScope.Workspace)),
        )
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("read ok")))))
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
                                "url" to JsonPrimitive("https://hooks.example/x"),
                            )))))
                        },
                    ),
                ),
            ),
        ),
    )

    private fun hub(model: ModelClient, http: FakeHttp): SessionHub =
        SessionHub(store, deps(model, http), CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it })

    private fun deps(model: ModelClient, http: FakeHttp): KernelDeps = KernelDeps(
        clock = { 1_000 },
        ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) },
        tools = listOf(read),
        host = dev.min.agent.kernel.HostPorts(fs, FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = http),
        platform = "test",
        zone = ZoneOffset.UTC,
        memoryHome = GuestPath("/root"),
        trustProjectHooks = { true },
    )

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    @Test
    fun `session start fires per eagerly loaded memory file with file_path memory_type and load_reason`() = run {
        fs.put("/w/CLAUDE.md", "project md\n@./extra.md")
        fs.put("/w/extra.md", "imported marker")
        fs.put("/root/.claude/CLAUDE.md", "user md")
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { _ -> reply(TextPart("好")) }
        val hub = hub(model, http)
        hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("InstructionsLoaded"))))),
        )

        assertEquals("用户、项目、@导入 三个文件各一次", 3, http.sent.size)
        val bodies = http.sent.map { it.body }
        val user = bodies.single { it.contains("/root/.claude/CLAUDE.md") }
        assertTrue(user, user.contains("\"hook_event_name\":\"InstructionsLoaded\""))
        assertTrue(user, user.contains("\"memory_type\":\"User\""))
        assertTrue(user, user.contains("\"load_reason\":\"session_start\""))
        assertTrue(user, user.contains("\"session_id\":\""))
        val project = bodies.single { it.contains("\"file_path\":\"/w/CLAUDE.md\"") }
        assertTrue(project, project.contains("\"memory_type\":\"Project\""))
        assertTrue(project, project.contains("\"load_reason\":\"session_start\""))
        val imported = bodies.single { it.contains("\"file_path\":\"/w/extra.md\"") }
        assertTrue("导入的文件按原文记 include（:1300）", imported.contains("\"load_reason\":\"include\""))
    }

    @Test
    fun `outputs are discarded - no systemMessage no block`() = run {
        fs.put("/w/CLAUDE.md", "project md")
        val http = FakeHttp {
            dev.min.agent.spi.HttpHookResult.Responded(
                200,
                """{"decision":"block","reason":"no","continue":false,"systemMessage":"instructions note"}""",
            )
        }
        val model = RoutingModel { _ -> reply(TextPart("好")) }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("InstructionsLoaded"))))),
        )
        rt.say("干活")

        assertEquals("hook 跑了一次", 1, http.sent.size)
        val log = events(rt)
        assertEquals("事件照常记", 1, log.filterIsInstance<HooksRan>().size)
        assertTrue("无决策控制：不给任何说明", log.filterIsInstance<dev.min.agent.spi.Note>().isEmpty())
        assertEquals("回合照常完成", dev.min.agent.spi.TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
    }

    @Test
    fun `lazy nested memory fires with nested_traversal when a tool touches a file under it`() = run {
        fs.put("/w/sub/CLAUDE.md", "nested marker")
        fs.put("/w/sub/a.txt", "data")
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Read", dev.min.agent.testkit.args("file_path" to "/w/sub/a.txt")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("InstructionsLoaded"))))),
        )
        assertEquals("创建时没有 eager 文件", 0, http.sent.size)
        rt.say("读一下")

        assertEquals("按需注入的那次", 1, http.sent.size)
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"file_path\":\"/w/sub/CLAUDE.md\""))
        assertTrue(body, body.contains("\"load_reason\":\"nested_traversal\""))
        assertTrue(body, body.contains("\"memory_type\":\"Project\""))
    }

    @Test
    fun `lazy path rule fires with path_glob_match`() = run {
        fs.put("/w/.claude/rules/lazy.md", "---\npaths:\n  - \"*.csv\"\n---\ncsv rule")
        fs.put("/w/data.csv", "a,b")
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Read", dev.min.agent.testkit.args("file_path" to "/w/data.csv")), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val hub = hub(model, http)
        val rt = hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("InstructionsLoaded"))))),
        )
        rt.say("读一下")

        assertEquals(1, http.sent.size)
        val body = http.sent.single().body
        assertTrue(body, body.contains("\"file_path\":\"/w/.claude/rules/lazy.md\""))
        assertTrue(body, body.contains("\"load_reason\":\"path_glob_match\""))
    }

    @Test
    fun `matcher filters by load_reason`() = run {
        fs.put("/w/CLAUDE.md", "project md\n@./extra.md")
        fs.put("/w/extra.md", "imported marker")
        fs.put("/root/.claude/CLAUDE.md", "user md")
        val http = FakeHttp { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }
        val model = RoutingModel { _ -> reply(TextPart("好")) }
        val hub = hub(model, http)
        hub.create(
            GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook("InstructionsLoaded", "session_start"))))),
        )
        assertEquals("matcher = session_start，include 的导入文件不匹配", 2, http.sent.size)
    }
}
