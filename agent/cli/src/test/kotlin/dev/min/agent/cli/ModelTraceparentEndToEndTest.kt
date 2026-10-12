package dev.min.agent.cli

import com.sun.net.httpserver.HttpServer
import dev.min.agent.host.JvmSearch
import dev.min.agent.host.UrlConnectionTransport
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.createModelClient
import dev.min.agent.otel.OtelConfig
import dev.min.agent.otel.OtelExporter
import dev.min.agent.otel.OtelStoreSessions
import dev.min.agent.otel.OtelTail
import dev.min.agent.otel.OtelTraceContext
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.HttpRequest
import dev.min.agent.spi.HttpResponse
import dev.min.agent.spi.HttpStreamingResponse
import dev.min.agent.spi.HttpTransport
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SseEvent
import dev.min.agent.spi.TraceContextProvider
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeSecrets
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 模型请求的出站 `traceparent` 与响应 `traceresponse` 的 span link（OTel 第四段，原文 monitoring-usage：
 * 直连 Anthropic API 时每个模型请求带 llm_request span 的 traceparent，API 回的 traceresponse 记成 span link；
 * 第三方不带，自定义代理要显式 `CLAUDE_CODE_PROPAGATE_TRACEPARENT=1`）。
 *
 * 真 UrlConnectionTransport、真 Anthropic 编解码器、真内核与真会话日志；对端是本机的假 Anthropic 服务器（记下请求头、回 SSE 与响应头）。
 * 官方主机名（api.anthropic.com）由一个改写地址的传输接到本机服务器——判定「是不是 Anthropic 本家」看的是供应商配置里的地址，不是实际连哪。
 */
class ModelTraceparentEndToEndTest {
    private val root: Path = Files.createTempDirectory("min model traceparent")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val localFile = root.resolve("telemetry.jsonl")
    private val serverTrace = "0af7651916cd43dd8448eb211c80319c"
    private val serverSpan = "00f067aa0ba902b7"

    /** 假服务器：每个请求记一份头；[failFirst] 个请求先回 529（带 request-id），之后回正常的 SSE */
    private class FakeAnthropic(val serverTrace: String, val serverSpan: String, failFirst: Int = 0) {
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        private val remaining = AtomicInteger(failFirst)
        private val counter = AtomicInteger(0)
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/v1/messages") { ex ->
                ex.requestBody.readBytes()
                seen += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                val n = counter.incrementAndGet()
                ex.responseHeaders.add("request-id", "req_fake_$n")
                if (remaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    val body = """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""".toByteArray()
                    ex.responseHeaders.add("content-type", "application/json")
                    ex.sendResponseHeaders(529, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                } else {
                    ex.responseHeaders.add("content-type", "text/event-stream")
                    ex.responseHeaders.add("traceresponse", "00-$serverTrace-$serverSpan-01")
                    ex.sendResponseHeaders(200, 0)
                    ex.responseBody.use { it.write(SSE.toByteArray()) }
                }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(90_000) { block() } }

    /** 把配置里的地址（官方或第三方主机名）改写到本机假服务器；请求头、请求体、响应头回调原样 */
    private class Rewriting(private val delegate: HttpTransport, private val base: String) : HttpTransport {
        val urls = CopyOnWriteArrayList<String>()
        private fun rewrite(r: HttpRequest): HttpRequest {
            urls += r.url
            return HttpRequest(r.method, r.url.replace(Regex("^https://[^/]+"), base), r.headers, r.body, r.timeoutMs, r.onResponseHeaders)
        }
        override suspend fun send(request: HttpRequest): HttpResponse = delegate.send(rewrite(request))
        override fun stream(request: HttpRequest): Flow<SseEvent> = delegate.stream(rewrite(request))
        override suspend fun open(request: HttpRequest): HttpStreamingResponse = delegate.open(rewrite(request))
    }

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

    private class Setup(val rt: SessionRuntime, val store: FileSessionStore, val config: OtelConfig?)

    private suspend fun session(fake: FakeAnthropic, baseUrl: String, telemetry: Map<String, String>?): Setup {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val config = telemetry?.let { env -> OtelConfig.fromEnv { env[it] } }
        val provider = ProviderDescriptor("anthropic", baseUrl, Wire.AnthropicMessages, AuthScheme.XApiKey)
        val client = createModelClient(provider, Rewriting(UrlConnectionTransport(), fake.base), FakeSecrets())
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, client) },
            tools = emptyList(),
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, Files.createDirectories(root.resolve("logs")), paths.guestOfHost(root.resolve("logs")))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = null,
            retry = dev.min.agent.kernel.RetryPolicy(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 5),
            traceContext = if (config == null) TraceContextProvider.None else OtelTraceContext({ config }) { id -> store.peek(SessionId(id)) },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val rt = SessionHub(store, deps, scope).create(paths.guestOfHost(work), TEST_MODEL, PermissionMode.BypassPermissions)
        return Setup(rt, store, config)
    }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun exportedSpans(s: Setup): List<JsonObject> {
        val tail = OtelTail(OtelStoreSessions.descendantsOf(s.store, s.rt.sessionId), s.config!!, OtelExporter(s.config, NoTransport))
        tail.pump()
        return Files.readAllLines(localFile).map { Json.parseToJsonElement(it).jsonObject }.filter { it["kind"]!!.jsonPrimitive.content == "span" }
    }

    /** 本机 JSONL 里属性值是 OTLP 的 {stringValue / intValue / ...} 对象 */
    private fun JsonObject.attr(k: String) = this["attributes"]!!.jsonObject[k]?.jsonObject?.values?.first()?.jsonPrimitive?.content
    private val traceparent = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-01")

    // ---- 直连官方端点 ----

    @Test
    fun `the official endpoint gets a traceparent pointing at the exported llm_request span, and the traceresponse becomes its link`() = run {
        val fake = FakeAnthropic(serverTrace, serverSpan)
        val s = session(fake, "https://api.anthropic.com", telemetryEnv())
        s.rt.say("你好")
        val headers = fake.seen.single()
        val tp = traceparent.matchEntire(headers["traceparent"].orEmpty())
        assertNotNull("官方端点的请求应带 traceparent：$headers", tp)
        val spans = exportedSpans(s)
        val llm = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.llm_request" }
        val interaction = spans.single { it["name"]!!.jsonPrimitive.content == "claude_code.interaction" }
        assertEquals("请求头里的 trace id 就是导出的 trace", llm["trace_id"]!!.jsonPrimitive.content, tp!!.groupValues[1])
        assertEquals(interaction["trace_id"]!!.jsonPrimitive.content, tp.groupValues[1])
        assertEquals("请求头里的 span id 就是这次 llm_request 的 span", llm["span_id"]!!.jsonPrimitive.content, tp.groupValues[2])
        // 响应头 → span link 与属性
        val link = llm["links"]!!.jsonArray.single().jsonObject
        assertEquals(serverTrace, link["trace_id"]!!.jsonPrimitive.content)
        assertEquals(serverSpan, link["span_id"]!!.jsonPrimitive.content)
        assertEquals("req_fake_1", llm.attr("gen_ai.response.id"))
        assertEquals("req_fake_1", llm.attr("request_id"))
        // 客户端请求 id：请求头、日志、span 三处同一个
        val clientId = headers["x-client-request-id"]
        assertFalse(clientId.isNullOrBlank())
        val started = s.store.peek(s.rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<ModelRequestStarted>().single()
        assertEquals(clientId, started.clientRequestId)
        assertEquals(clientId, llm.attr("client_request_id"))
    }

    @Test
    fun `retries keep one span id on the wire, with a fresh client request id per attempt`() = run {
        val fake = FakeAnthropic(serverTrace, serverSpan, failFirst = 1)
        val s = session(fake, "https://api.anthropic.com", telemetryEnv())
        s.rt.say("你好")
        assertEquals(2, fake.seen.size)
        assertEquals("同一个 llm_request span 的两次尝试带同一个 traceparent", fake.seen[0]["traceparent"], fake.seen[1]["traceparent"])
        assertNotEquals(fake.seen[0]["x-client-request-id"], fake.seen[1]["x-client-request-id"])
        val llm = exportedSpans(s).single { it["name"]!!.jsonPrimitive.content == "claude_code.llm_request" }
        assertEquals("2", llm.attr("attempt"))
        assertEquals(fake.seen[1]["x-client-request-id"], llm.attr("client_request_id"))
        assertEquals(traceparent.matchEntire(fake.seen[0]["traceparent"]!!)!!.groupValues[2], llm["span_id"]!!.jsonPrimitive.content)
        val attempt = llm["events"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "gen_ai.request.attempt" }.jsonObject
        assertEquals(fake.seen[1]["x-client-request-id"], attempt["attributes"]!!.jsonObject["client_request_id"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tracing off or no telemetry wired means no traceparent even on the official endpoint`() = run {
        val off = FakeAnthropic(serverTrace, serverSpan)
        session(off, "https://api.anthropic.com", telemetryEnv("CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "")).rt.say("你好")
        assertNull(off.seen.single()["traceparent"])
        val none = FakeAnthropic(serverTrace, serverSpan)
        session(none, "https://api.anthropic.com", telemetry = null).rt.say("你好")
        assertNull(none.seen.single()["traceparent"])
        // 客户端请求 id 与遥测无关：官方端点照发
        assertFalse(none.seen.single()["x-client-request-id"].isNullOrBlank())
    }

    // ---- 第三方端点 ----

    @Test
    fun `a third party endpoint gets neither the traceparent nor the client request id`() = run {
        val fake = FakeAnthropic(serverTrace, serverSpan)
        val s = session(fake, "https://relay.example.com", telemetryEnv())
        s.rt.say("你好")
        val headers = fake.seen.single()
        assertNull("第三方中转收不到 trace id", headers["traceparent"])
        assertNull(headers["x-client-request-id"])
        val started = s.store.peek(s.rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<ModelRequestStarted>().single()
        assertNull("没发出去的 id 不落盘", started.clientRequestId)
        // 响应头照读：中转回了 request-id 与 traceresponse 就照记（只是我们没发 traceparent，它多半不会回）
        val llm = exportedSpans(s).single { it["name"]!!.jsonPrimitive.content == "claude_code.llm_request" }
        assertNull(llm.attr("client_request_id"))
        assertEquals("req_fake_1", llm.attr("gen_ai.response.id"))
    }

    @Test
    fun `explicit consent lets a custom Anthropic endpoint get the traceparent but still no client request id`() = run {
        val fake = FakeAnthropic(serverTrace, serverSpan)
        val s = session(fake, "https://relay.example.com", telemetryEnv("CLAUDE_CODE_PROPAGATE_TRACEPARENT" to "1"))
        s.rt.say("你好")
        val headers = fake.seen.single()
        val tp = traceparent.matchEntire(headers["traceparent"].orEmpty())
        assertNotNull(headers.toString(), tp)
        val llm = exportedSpans(s).single { it["name"]!!.jsonPrimitive.content == "claude_code.llm_request" }
        assertEquals(llm["span_id"]!!.jsonPrimitive.content, tp!!.groupValues[2])
        assertNull(headers["x-client-request-id"])
    }

    @Test
    fun `a custom ANTHROPIC_BASE_URL in the environment is not consent for model requests`() = run {
        val fake = FakeAnthropic(serverTrace, serverSpan)
        session(fake, "https://relay.example.com", telemetryEnv("ANTHROPIC_BASE_URL" to "https://relay.example.com")).rt.say("你好")
        assertNull(fake.seen.single()["traceparent"])
    }

    companion object {
        private const val SSE = """event: message_start
data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":3,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

event: message_stop
data: {"type":"message_stop"}

"""
    }
}
