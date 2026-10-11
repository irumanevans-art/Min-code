package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.otel.OtelConfig
import dev.min.agent.otel.OtelExporter
import dev.min.agent.otel.OtelStoreSessions
import dev.min.agent.otel.OtelTail
import dev.min.agent.otel.OtelTraceContext
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.SseEvent
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.tools.BashTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * TRACEPARENT 透传（OTel 第三段，原文 monitoring-usage：tracing 开着时 Bash 子进程继承当前工具执行 span 的 TRACEPARENT）。
 *
 * 真文件系统、真 bash、真 LocalExec、真日志：Bash 命令与 hook 脚本是**真子进程**，把自己环境里的 TRACEPARENT 打出来；
 * 然后用同一份会话日志跑一遍导出，断言子进程看到的 trace id / span id 就是导出出去的那个 span——
 * 这才是「后端能把子进程里的 span 接到这条 trace 上」。
 */
class TraceparentEndToEndTest {
    private val root: Path = Files.createTempDirectory("min traceparent")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val localFile = root.resolve("telemetry.jsonl")

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(90_000) { block() } }

    private fun shellPath(p: Path) = paths.guestOfHost(p).value

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    /** 不会被用到的传输：本地文件导出不发请求 */
    private object NoTransport : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse = error("不该发请求")
        override fun stream(request: HttpRequest): Flow<SseEvent> = error("不该发请求")
        override suspend fun open(request: HttpRequest): HttpStreamingResponse = error("不该发请求")
    }

    private fun telemetryEnv(vararg extra: Pair<String, String>): Map<String, String> = mapOf(
        "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
        "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
        "MIN_TELEMETRY_FILE" to localFile.toString(),
    ) + extra.toMap()

    /** [telemetry] 为 null：宿主没接遥测（默认不传播） */
    private suspend fun session(
        model: ModelClient,
        telemetry: Map<String, String>?,
    ): Triple<SessionRuntime, FileSessionStore, OtelConfig?> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logDir = Files.createDirectories(root.resolve("logs"))
        val config = telemetry?.let { env -> OtelConfig.fromEnv { env[it] } }
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(BashTool()),
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = paths.guestOfHost(home),
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { false },
            traceContext = if (config == null) dev.min.agent.spi.TraceContextProvider.None
            else OtelTraceContext({ config }) { id -> store.peek(SessionId(id)) },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return Triple(SessionHub(store, deps, scope).create(paths.guestOfHost(work), TEST_MODEL, PermissionMode.BypassPermissions), store, config)
    }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private fun bashThenDone(command: String) = RoutingModel { req ->
        if (req.messages.size == 1) reply(ToolCallPart(CallId("c1"), "Bash", args("command" to command)), stop = StopReason.ToolUse)
        else reply(TextPart("好"))
    }

    private fun RoutingModel.bashOutput(): String = requests.last().messages.last().parts.filterIsInstance<ToolResultPart>()
        .flatMap { it.content.filterIsInstance<TextPart>() }.joinToString("") { it.text }

    /** 用同一份日志导出到本机文件，返回其中的 span（按 name 取） */
    private suspend fun exportedSpans(store: FileSessionStore, rt: SessionRuntime, config: OtelConfig): List<JsonObject> {
        val tail = OtelTail(OtelStoreSessions.descendantsOf(store, rt.sessionId), config, OtelExporter(config, NoTransport))
        tail.pump()
        return Files.readAllLines(localFile).map { Json.parseToJsonElement(it).jsonObject }.filter { it["kind"]!!.jsonPrimitive.content == "span" }
    }

    private val traceparent = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-01")

    // ---- Bash ----

    @Test
    fun `a real bash child sees TRACEPARENT pointing at this call's execution span, in the same trace`() = run {
        val model = bashThenDone("""printf 'TP=%s' "${'$'}TRACEPARENT"""")
        val (rt, store, config) = session(model, telemetryEnv())
        rt.say("跑")
        val seen = traceparent.find(model.bashOutput())
        assertNotNull("子进程环境里应有 TRACEPARENT：${model.bashOutput()}", seen)
        val spans = exportedSpans(store, rt, config!!)
        val execution = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.tool.execution" }
        val tool = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.tool" }
        assertEquals("子进程看到的 trace id 就是导出的 trace", execution["trace_id"]!!.jsonPrimitive.content, seen!!.groupValues[1])
        assertEquals("子进程看到的 span id 就是这次执行的 span", execution["span_id"]!!.jsonPrimitive.content, seen.groupValues[2])
        assertEquals("执行 span 是 tool span 的子", tool["span_id"]!!.jsonPrimitive.content, execution["parent_span_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `with no telemetry wired the child gets no TRACEPARENT`() = run {
        val model = bashThenDone("""printf 'TP=[%s]' "${'$'}TRACEPARENT"""")
        val (rt, _, _) = session(model, telemetry = null)
        rt.say("跑")
        assertTrue(model.bashOutput(), model.bashOutput().contains("TP=[]"))
    }

    @Test
    fun `tracing off (no beta switch) means no TRACEPARENT even though telemetry is on`() = run {
        val model = bashThenDone("""printf 'TP=[%s]' "${'$'}TRACEPARENT"""")
        val (rt, _, _) = session(model, telemetryEnv("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to ""))
        rt.say("跑")
        assertTrue(model.bashOutput(), model.bashOutput().contains("TP=[]"))
    }

    @Test
    fun `a custom ANTHROPIC_BASE_URL proxy does not get the trace id unless CLAUDE_CODE_PROPAGATE_TRACEPARENT is set`() = run {
        val proxy = "ANTHROPIC_BASE_URL" to "https://llm-proxy.example.com"
        val closed = bashThenDone("""printf 'TP=[%s]' "${'$'}TRACEPARENT"""")
        session(closed, telemetryEnv(proxy)).first.say("跑")
        assertTrue(closed.bashOutput(), closed.bashOutput().contains("TP=[]"))

        val open = bashThenDone("""printf 'TP=%s' "${'$'}TRACEPARENT"""")
        session(open, telemetryEnv(proxy, "CLAUDE_CODE_PROPAGATE_TRACEPARENT" to "1")).first.say("跑")
        assertNotNull(traceparent.find(open.bashOutput()))
    }

    @Test
    fun `an inbound TRACEPARENT makes the child share the inbound trace id`() = run {
        val inbound = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val model = bashThenDone("""printf 'TP=%s' "${'$'}TRACEPARENT"""")
        val env = telemetryEnv("TRACEPARENT" to inbound)
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val config = OtelConfig.fromEnv({ env[it] }, acceptInbound = true)
        val logDir = Files.createDirectories(root.resolve("logs"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(BashTool()),
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test", zone = ZoneOffset.UTC, memoryHome = paths.guestOfHost(home),
            traceContext = OtelTraceContext({ config }) { id -> store.peek(SessionId(id)) },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val rt = SessionHub(store, deps, scope).create(paths.guestOfHost(work), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("跑")
        val seen = traceparent.find(model.bashOutput())!!
        assertEquals("0af7651916cd43dd8448eb211c80319c", seen.groupValues[1])
        val spans = exportedSpans(store, rt, config)
        val interaction = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.interaction" }
        assertEquals("b7ad6b7169203331", interaction["parent_span_id"]!!.jsonPrimitive.content)
        val execution = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.tool.execution" }
        assertEquals(execution["span_id"]!!.jsonPrimitive.content, seen.groupValues[2])
    }

    // ---- hook ----

    private fun preToolUseHook(outFile: Path) {
        val script = Files.createDirectories(home.resolve(".claude/hooks")).resolve("tp.sh")
        Files.writeString(script, "#!/bin/bash\ncat > /dev/null\nprintf '%s' \"${'$'}TRACEPARENT\" > '${shellPath(outFile)}'\n")
        val command = "bash '${shellPath(script)}'"
        val dir = Files.createDirectories(home.resolve(".claude"))
        Files.writeString(
            dir.resolve("settings.json"),
            """{"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [{"type": "command", "command": ${kotlinx.serialization.json.JsonPrimitive(command)}}]}]}}""",
        )
    }

    @Test
    fun `a real hook child sees the tool span when detailed tracing is off`() = run {
        val out = root.resolve("hook-tp.txt")
        preToolUseHook(out)
        val model = bashThenDone("true")
        val (rt, store, config) = session(model, telemetryEnv())
        rt.say("跑")
        val seen = traceparent.find(Files.readString(out))
        assertNotNull(Files.readString(out), seen)
        val spans = exportedSpans(store, rt, config!!)
        val tool = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.tool" }
        assertTrue("没开详细追踪就没有 hook span", spans.none { it["name"]!!.jsonPrimitive.content == "claude_code.hook" })
        assertEquals(tool["trace_id"]!!.jsonPrimitive.content, seen!!.groupValues[1])
        assertEquals("PreToolUse 在工具调用上：指向那次调用的 tool span", tool["span_id"]!!.jsonPrimitive.content, seen.groupValues[2])
    }

    @Test
    fun `a real hook child sees its own hook span when detailed tracing is on`() = run {
        val out = root.resolve("hook-tp.txt")
        preToolUseHook(out)
        val model = bashThenDone("true")
        val detailed = telemetryEnv("ENABLE_BETA_TRACING_DETAILED" to "1", "BETA_TRACING_ENDPOINT" to "https://beta.example.com")
        val (rt, store, config) = session(model, detailed)
        rt.say("跑")
        val seen = traceparent.find(Files.readString(out))
        assertNotNull(Files.readString(out), seen)
        val spans = exportedSpans(store, rt, config!!)
        val hook = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.hook" }
        assertEquals("hook 子进程的 TRACEPARENT 指向这批 hook 自己的 span", hook["span_id"]!!.jsonPrimitive.content, seen!!.groupValues[2])
        assertEquals(hook["trace_id"]!!.jsonPrimitive.content, seen.groupValues[1])
        val interaction = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.interaction" }
        assertEquals("hook span 挂在 interaction 下", interaction["span_id"]!!.jsonPrimitive.content, hook["parent_span_id"]!!.jsonPrimitive.content)
        assertFalse(Files.readString(localFile).contains("beta.example.com"))
    }
}
