package dev.min.agent.mcp

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class ScriptHttp : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var script: suspend (HttpRequest) -> HttpStreamingResponse = { error("unexpected request") }
    override suspend fun open(request: HttpRequest): HttpStreamingResponse { requests += request; return script(request) }
    override suspend fun send(request: HttpRequest): HttpResponse = error("raw only")
    override fun stream(request: HttpRequest): Flow<SseEvent> = error("raw only")
}
internal fun response(status: Int = 200, body: String = "", type: String = "application/json", session: String? = null) =
    HttpStreamingResponse(status, listOfNotNull("Content-Type" to type, session?.let { "Mcp-Session-Id" to it }), flowOf(body.toByteArray()))
internal fun HttpRequest.message(): JsonObject = json(body!!.decodeToString())

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFixesTest {
    private fun TestScope.client(t: McpTransport, options: McpClientOptions = McpClientOptions("1", requestTimeoutMs = 100)) = McpClient(t, backgroundScope, options)
    private fun transport(h: ScriptHttp, get: Boolean = false, headers: suspend () -> List<Pair<String, String>> = { emptyList() }, limit: Int = 4096, debug: (String) -> Unit = {}) =
        StreamableHttpMcpTransport("https://example.test/mcp", headers, h, maxMessageBytes = limit, enableGetStream = get, retryDelaysMs = emptyList(), onDebug = debug)
    private fun ScriptHttp.normal() {
        script = { r ->
            if (r.method != "POST") response(405)
            else { val m = r.message(); when (m.text("method")) {
                "initialize" -> response(body = result(m.getValue("id"), initializeResult()).toString(), session = "s${requests.size}")
                "notifications/initialized", "notifications/cancelled", null -> response(202)
                "tools/list" -> response(body = result(m.getValue("id"), json("""{"tools":[]}""")).toString())
                "tools/call" -> response(body = result(m.getValue("id"), json("""{"content":[]}""")).toString())
                else -> response(body = result(m.getValue("id"), emptyObject).toString())
            } }
        }
    }
    private suspend fun failure(block: suspend () -> Unit): Exception = try { block(); fail("expected failure"); error("unreachable") } catch (e: Exception) { e }

    @Test fun r01_initialized_404_retries_whole_handshake_and_is_bounded() = runTest {
        for (always in listOf(false, true)) {
            val h = ScriptHttp(); h.normal(); val normal = h.script; var initialized = 0; var initializes = 0
            h.script = { r ->
                if (r.method == "POST" && r.message().text("method") == "initialize") initializes++
                if (r.method == "POST" && r.message().text("method") == "notifications/initialized" && (++initialized == 1 || always)) response(404) else normal(r)
            }
            val c = client(transport(h))
            val e = try { withTimeout(500) { c.connect() }; null } catch (e: Exception) { e }
            assertFalse("handshake reentered its own mutex", e is TimeoutCancellationException)
            assertEquals(2, initializes)
            if (always) { assertTrue(e is McpHttpException); assertEquals(404, (e as McpHttpException).status) } else assertNull(e)
            c.close()
        }
    }
    @Test fun r01_recovery_initialized_404_also_retries_without_deadlock() = runTest {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var initializations = 0; var notifications = 0
        h.script = { r -> if (r.method != "POST") normal(r) else when (r.message().text("method")) {
            "initialize" -> { initializations++; normal(r) }
            "notifications/initialized" -> if (++notifications == 2) response(404) else normal(r)
            "tools/call" -> if (initializations == 1) response(404) else normal(r)
            else -> normal(r)
        } }
        val c = client(transport(h)); c.connect()
        val e = try { withTimeout(500) { c.callTool("x", emptyObject, idleTimeoutMs = 1000) }; null } catch (e: Exception) { e }
        assertNull("recovery handshake must finish", e); assertEquals(3, initializations); c.close()
    }
    @Test fun r02_optional_get_failures_never_close_or_renew_main_session() = runTest {
        for (status in listOf(400, 404, 406, 501, 200)) {
            val h = ScriptHttp(); h.normal(); val normal = h.script; var initializes = 0
            h.script = { r -> if (r.method == "GET") response(status, "not SSE", "text/plain") else { if (r.method == "POST" && r.message().text("method") == "initialize") initializes++; normal(r) } }
            val c = client(transport(h, get = true)); c.connect(); runCurrent(); advanceTimeBy(20_000); runCurrent()
            assertTrue("GET $status killed main connection: ${c.state.value}", c.state.value is McpConnectionState.Ready)
            assertEquals(1, initializes); c.ping(); c.close()
        }
    }
    @Test fun r02_get_eof_heartbeats_and_idle_timeout_do_not_consume_failure_budget() = runTest {
        for (idle in listOf(false, true)) {
            val h = ScriptHttp(); h.normal(); val normal = h.script; var gets = 0
            h.script = { r -> if (r.method != "GET") normal(r) else {
                gets++
                HttpStreamingResponse(200, listOf("Content-Type" to "text/event-stream"), flow {
                    emit("retry: 1\n\n: heartbeat\n\n".toByteArray())
                    if (idle) throw SocketTimeoutException("idle")
                })
            } }
            val logs = mutableListOf<String>()
            val c = client(transport(h, get = true, debug = logs::add)); c.connect(); advanceTimeBy(20); runCurrent()
            assertTrue("GET stopped after healthy EOF or idle", gets > 6)
            assertTrue("GET end needs a diagnostic", logs.isNotEmpty())
            assertTrue(c.state.value is McpConnectionState.Ready); c.ping(); c.close()
        }
    }
    @Test fun r02_debug_observer_failure_cannot_close_main_connection() = runTest {
        for (status in listOf(400, 200)) {
            val h = ScriptHttp(); h.normal(); val normal = h.script
            h.script = { r -> if (r.method == "GET") response(status, "not SSE", "text/plain") else normal(r) }
            val c = client(transport(h, get = true, debug = { throw IllegalStateException("logger failed") }))
            c.connect(); runCurrent(); assertTrue(c.state.value is McpConnectionState.Ready); c.ping(); c.close()
        }
    }
    @Test fun r03_post_uses_hard_deadline_or_long_default_not_idle_timeout() = runTest {
        val h = ScriptHttp(); h.normal(); val c = client(transport(h)); c.connect()
        c.callTool("x", emptyObject, idleTimeoutMs = 100, hardTimeoutMs = 420_000)
        val hard = h.requests.last { it.method == "POST" && it.message().text("method") == "tools/call" }
        assertEquals(420_000, hard.timeoutMs)
        c.callTool("x", emptyObject, idleTimeoutMs = 100)
        assertTrue(h.requests.last().timeoutMs > 300_000); c.close()
    }
    @Test fun r04_redaction_keeps_diagnostics_and_removes_known_credentials() {
        for (line in listOf("Error: Cannot find module 'foo'", "ModuleNotFoundError: No module named 'mcp'")) assertEquals(line, safeLog(line))
        for (line in listOf("{\"authorization\":\"Bearer x\"}", "Proxy-Authorization: Basic abc", "Bearer top-secret", "password=hunter2", "secret=value", "private_key=abc", "https://user:pass@host/x?q=secret", "postgres://user:pass@host/db?q=secret")) {
            val redacted = safeLog(line)
            assertTrue(line, redacted != line)
            assertFalse(redacted, listOf("Bearer x", "Basic abc", "top-secret", "hunter2", "=value", "=abc", "user:pass", "q=secret").any { it in redacted })
        }
    }
    @Test fun r05_single_bad_request_does_not_close_other_requests() = runTest {
        for (kind in listOf("size", "headers", "response")) {
            val h = ScriptHttp(); h.normal(); val normal = h.script; var badHeader = false; var badResponse = false
            h.script = { r -> if (badResponse && r.method == "POST" && r.message().text("method") == "tools/call") response(body = "not-json") else normal(r) }
            val c = client(transport(h, headers = { if (badHeader) throw IOException("Authorization: credential") else emptyList() }, limit = 1024)); c.connect()
            badHeader = kind == "headers"; badResponse = kind == "response"
            failure { c.callTool("x", if (kind == "size") buildJsonObject { put("big", "x".repeat(1500)) } else emptyObject, idleTimeoutMs = 100) }
            runCurrent(); assertTrue(kind, c.state.value is McpConnectionState.Ready)
            badHeader = false; badResponse = false; c.ping(); c.listTools(); c.close()
        }
    }
    @Test fun r05_notifications_and_server_responses_ignore_all_2xx_bodies() = runTest {
        for (body in listOf("", "not-json")) {
            val h = ScriptHttp(); h.normal(); val normal = h.script
            h.script = { r -> if (r.method == "POST" && r.message()["id"] == null) response(body = body, type = "text/plain") else normal(r) }
            val c = client(transport(h)); assertNull(failureOrNull { c.connect() }); c.ping(); c.close()
        }
        val h = ScriptHttp(); h.normal(); val t = transport(h); val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { try { t.incoming.collect() } catch (_: McpTransportClosed) {} }
        h.script = { response(body = "not-json", type = "text/plain") }
        assertNull(failureOrNull { t.send(result(JsonPrimitive("server"), emptyObject)) }); t.close(); collector.cancel()
    }
    private suspend fun failureOrNull(block: suspend () -> Unit): Exception? = try { block(); null } catch (e: Exception) { e }
    @Test fun r06_rpc_preserves_server_message_and_data() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect(); val msg = "Invalid params: expected integer for count"
        t.respond = { m -> t.received.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", m.getValue("id")); putJsonObject("error") { put("code", -32602); put("message", msg); putJsonObject("data") { put("field", "count") } } }) }
        val e = failure { c.callTool("x", emptyObject, idleTimeoutMs = 100) } as McpRpcException
        assertTrue(e.message!!, msg in e.message!!)
        assertEquals(msg, e.javaClass.methods.firstOrNull { it.name == "getServerMessage" }?.invoke(e))
        assertEquals(json("""{"field":"count"}"""), e.data); c.close()
    }
    @Test fun r07_initialize_timeout_and_cancel_never_send_cancelled() = runTest {
        for (cancel in listOf(false, true)) {
            val t = FakeTransport(); t.respond = {}; val c = client(t)
            val connect = async { failureOrNull { c.connect() } }; runCurrent()
            if (cancel) connect.cancelAndJoin() else { advanceTimeBy(101); runCurrent(); assertTrue(connect.await() is McpTimeoutException) }
            assertTrue(t.closed); assertFalse(t.sent.any { it.text("method") == "notifications/cancelled" }); c.close()
        }
    }
    @Test fun r08_list_changed_has_persistent_version_and_slow_subscriber_cannot_block_reader() = runTest {
        val t = FakeTransport(); val c = client(t); c.connect()
        val sub = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.notifications.collect { awaitCancellation() } }
        backgroundScope.launch { repeat(140) { t.received.send(rpcNotification("notifications/tools/list_changed")) } }; runCurrent()
        val ping = async { failureOrNull { c.ping() } }; runCurrent(); advanceTimeBy(101); runCurrent()
        assertNull("notification subscriber blocked response", ping.await())
        val version = c.javaClass.methods.firstOrNull { it.name == "getToolsListVersion" }?.invoke(c) as? StateFlow<*>
        assertEquals(140L, version?.value); sub.cancel(); c.close()
    }
    @Test fun r08_progress_callback_is_dispatched_off_reader() = runTest {
        val reading = ThreadLocal.withInitial { false }
        val fresh = FakeTransport()
        val decorated = object : McpTransport by fresh { override val incoming = flow { fresh.incoming.collect { reading.set(true); try { emit(it) } finally { reading.set(false) } } } }
        val d = client(decorated); d.connect(); fresh.respond = {}; val onReader = CompletableDeferred<Boolean>()
        val call = async { d.callTool("x", emptyObject, { onReader.complete(reading.get()) }, 100) }; runCurrent(); val id = fresh.sent.last().getValue("id")
        fresh.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", id); put("progress", 1) })); runCurrent()
        fresh.received.send(result(id, json("""{"content":[]}"""))); call.await()
        assertEquals(false, onReader.await()); d.close()
    }
    @Test fun r08_blocked_progress_callback_keeps_responses_live_and_queue_bounded() = runBlocking {
        val t = FakeTransport(); val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = McpClient(t, owner, McpClientOptions("1")); val release = CountDownLatch(1)
        val started = CompletableDeferred<Unit>(); val latest = CompletableDeferred<Unit>(); val id = CompletableDeferred<JsonElement>(); val calls = AtomicInteger()
        try {
            c.connect(); t.respond = { m -> if (m.text("method") == "tools/call") {
                id.complete(m.getValue("id"))
                t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", m.getValue("id")); put("progress", 0) }))
            } }
            val call = async { c.callTool("x", emptyObject, { p ->
                calls.incrementAndGet()
                if (p.progress == 0.0) { started.complete(Unit); release.await(3, TimeUnit.SECONDS) }
                if (p.progress == 60.0) latest.complete(Unit)
            }, idleTimeoutMs = 3000) }
            withTimeout(1000) { started.await() }
            repeat(60) { n -> t.received.send(rpcNotification("notifications/progress", buildJsonObject { put("progressToken", id.await()); put("progress", n + 1) })) }
            t.received.send(result(id.await(), json("""{"content":[]}""")))
            withTimeout(500) { call.await() }
            release.countDown(); withTimeout(1000) { latest.await() }
            assertTrue("progress queue grew without a bound: ${calls.get()}", calls.get() <= 17)
            assertTrue(c.state.value is McpConnectionState.Ready)
        } finally { release.countDown(); c.close(); owner.cancel() }
    }
    @Test fun r09_sse_eof_discards_incomplete_event_and_stdio_keeps_residual_line() = runTest {
        for (tail in listOf("data: tail", "data: tail\n", "id: unfinished\ndata: tail\n", "id: unfinished\ndata: tail\n\r")) {
            val events = flowOf(("id: done\ndata: complete\n\n" + tail).toByteArray()).sse(1024).toList()
            if (tail.endsWith("\r")) assertEquals(2, events.size) else assertEquals(listOf("complete"), events.map { it.data })
        }
        assertEquals(listOf("tail"), flowOf("tail".toByteArray()).lines(1024).toList())
    }
    @Test fun r09_partial_post_response_is_resumed_from_last_completed_id() = runTest {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var callId: JsonElement = JsonPrimitive(0); var resumes = 0
        h.script = { r -> when {
            r.method == "GET" -> {
                resumes++; assertEquals("complete", r.headers.first { it.first == "Last-Event-ID" }.second)
                response(body = "data: ${result(callId, json("""{"content":[]}"""))}\n\n", type = "text/event-stream")
            }
            r.method == "POST" && r.message().text("method") == "tools/call" -> {
                callId = r.message().getValue("id")
                response(body = "id: complete\nretry: 1\n\nid: partial\ndata: ${result(callId, json("""{"content":[]}"""))}\n", type = "text/event-stream")
            }
            else -> normal(r)
        } }
        val c = client(transport(h)); c.connect(); c.callTool("x", emptyObject, idleTimeoutMs = 1000)
        assertEquals(1, resumes); c.close()
    }
    @Test fun r12_recovery_wait_timeout_never_sends_waiting_request() = runTest {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var inits = 0; val entered = CompletableDeferred<Unit>()
        h.script = { r -> if (r.method != "POST") normal(r) else when (r.message().text("method")) {
            "initialize" -> { if (++inits > 1) { entered.complete(Unit); awaitCancellation() }; normal(r) }
            "tools/call" -> response(404)
            else -> normal(r)
        } }
        val c = client(transport(h)); c.connect()
        val first = async { failure { c.callTool("x", emptyObject, idleTimeoutMs = 1000) } }; entered.await()
        val waiting = async { failure { c.listTools() } }; advanceTimeBy(101); runCurrent()
        assertTrue(waiting.await() is McpException); assertTrue(first.await() is McpException)
        assertFalse(h.requests.any { it.method == "POST" && it.message().text("method") == "tools/list" }); c.close()
    }
    @Test fun r12_recovery_wait_is_included_in_tool_hard_deadline() = runTest {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var inits = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.script = { r -> if (r.method != "POST") normal(r) else when (r.message().text("method")) {
            "initialize" -> { if (++inits > 1) { entered.complete(Unit); release.await() }; normal(r) }
            "tools/call" -> if (inits == 1) response(404) else normal(r)
            else -> normal(r)
        } }
        val c = client(transport(h), McpClientOptions("1", requestTimeoutMs = 1000)); c.connect()
        val first = async { c.callTool("x", emptyObject, idleTimeoutMs = 5000) }; entered.await()
        val waiting = async { failure { c.callTool("waiting", emptyObject, idleTimeoutMs = 3000, hardTimeoutMs = 50) } }; runCurrent()
        advanceTimeBy(51); runCurrent(); assertTrue("hard deadline restarted after readiness", waiting.isCompleted)
        assertEquals(McpTimeoutException.Kind.Hard, (waiting.await() as McpTimeoutException).kind)
        assertFalse(h.requests.any { it.method == "POST" && it.message().obj("params")?.text("name") == "waiting" })
        release.complete(Unit); first.await(); c.close()
    }
    @Test fun r10_public_calls_normalize_raw_failures_to_sealed_hierarchy() = runTest {
        for (api in listOf("connect", "list", "call")) {
            for (raw in listOf(IOException("Authorization: secret"), IllegalStateException("password=secret"))) {
                val t = FakeTransport(); val c = client(t); if (api != "connect") c.connect()
                t.respond = { throw raw }
                val e = failure { when (api) { "connect" -> c.connect(); "list" -> c.listTools(); else -> c.callTool("x", emptyObject, idleTimeoutMs = 100) } }
                assertEquals("McpException", e.javaClass.superclass.simpleName)
                assertFalse(e.toString().contains("secret")); c.close()
            }
        }
    }
    @Test fun r11_malformed_tool_is_skipped_with_debug_and_good_tool_survives() = runTest {
        val t = FakeTransport(); val logs = mutableListOf<String>(); val c = client(t, McpClientOptions("1", onDebug = logs::add)); c.connect()
        t.respond = { m -> t.received.send(result(m.getValue("id"), json("""{"tools":[{"name":"bad"},{"name":"good","inputSchema":{}},42]}"""))) }
        assertEquals(listOf("good"), c.listTools().map { it.name }); assertEquals(2, logs.count { "跳过" in it }); c.close()
    }
    @Test fun r12_calls_wait_for_recovery_and_have_bounded_wait() = runTest {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var inits = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.script = { r -> if (r.method != "POST") normal(r) else when (r.message().text("method")) {
            "initialize" -> { if (++inits > 1) { entered.complete(Unit); release.await() }; normal(r) }
            "tools/call" -> if (inits == 1) response(404) else normal(r)
            else -> normal(r)
        } }
        val c = client(transport(h)); c.connect()
        val first = async { c.callTool("x", emptyObject, idleTimeoutMs = 1000) }; entered.await()
        val waiting = async { failureOrNull { c.listTools() } }; runCurrent()
        assertFalse("call failed instead of waiting", waiting.isCompleted)
        release.complete(Unit); assertNull(waiting.await()); first.await(); c.close()
    }
    @Test fun r13_transport_decorator_forwards_negotiation_capability() = runTest {
        val h = ScriptHttp(); h.normal(); val inner = transport(h)
        val c = client(object : McpTransport by inner {}); c.connect(); c.ping()
        assertEquals("2025-11-25", h.requests.last().headers.firstOrNull { it.first == "MCP-Protocol-Version" }?.second); c.close()
    }
    @Test fun r14_legacy_default_message_and_inherited_userinfo_are_supported() = runTest {
        for (userinfo in listOf("", "user:pass@")) {
            val h = ScriptHttp(); val frames = Channel<ByteArray>(16)
            h.script = { r -> if (r.method == "GET") {
                frames.send("event: endpoint\ndata: /post\n\n".toByteArray())
                HttpStreamingResponse(200, listOf("Content-Type" to "text/event-stream"), frames.receiveAsFlow())
            } else { val m = r.message(); if (m.text("method") == "initialize") frames.send("data: ${result(m.getValue("id"), initializeResult())}\n\n".toByteArray()); response(202) } }
            val c = client(LegacySseMcpTransport("https://${userinfo}example.test/sse", { emptyList() }, h, retryDelaysMs = emptyList()))
            assertNull(failureOrNull { c.connect() }); assertTrue(c.state.value is McpConnectionState.Ready); c.close(); frames.close()
        }
    }
    @Test fun r03_legacy_idle_and_eof_reconnect_with_last_event_id() = runTest {
        for (idle in listOf(false, true)) {
            val h = ScriptHttp(); var gets = 0; val frames = Channel<ByteArray>(16)
            h.script = { r -> if (r.method == "GET") {
                gets++; HttpStreamingResponse(200, listOf("Content-Type" to "text/event-stream"), if (gets == 1) flow {
                    emit("event: endpoint\ndata: /post\n\nid: cursor\nretry: 1\n\n".toByteArray()); if (idle) throw SocketTimeoutException("idle")
                } else frames.receiveAsFlow())
            } else { val m = r.message(); if (m.text("method") == "initialize") frames.send("event: message\ndata: ${result(m.getValue("id"), initializeResult())}\n\n".toByteArray()); response(202) } }
            val c = client(LegacySseMcpTransport("https://example.test/sse", { emptyList() }, h, retryDelaysMs = emptyList()))
            assertNull("legacy did not reconnect", failureOrNull { c.connect() }); assertEquals(2, gets)
            assertEquals("cursor", h.requests.last { it.method == "GET" }.headers.firstOrNull { it.first == "Last-Event-ID" }?.second)
            c.close(); frames.close()
        }
    }
    @Test fun r03_legacy_initial_failure_has_only_three_retries() = runTest {
        for (io in listOf(false, true)) {
            val h = ScriptHttp(); h.script = { if (io) throw IOException("network") else response(503) }
            val c = client(LegacySseMcpTransport("https://example.test/sse", { emptyList() }, h, retryDelaysMs = listOf(1, 2, 4)), McpClientOptions("1", requestTimeoutMs = 10_000))
            failure { c.connect() }; assertEquals(4, h.requests.count { it.method == "GET" }); c.close()
        }
    }
    @Test fun r15_server_handler_rpc_error_preserves_code_message_and_data() = runTest {
        val t = FakeTransport(); val c = client(t, McpClientOptions("1", requestHandler = { _, _ -> throw McpRpcException(-32602, "Invalid params: roots missing", json("""{"retry":false}""")) })); c.connect()
        t.received.send(json("""{"jsonrpc":"2.0","id":"s","method":"roots/list"}""")); runCurrent()
        val e = t.sent.last().obj("error")!!; assertEquals(-32602, e["code"]!!.jsonPrimitive.int)
        assertEquals("Invalid params: roots missing", e.text("message")); assertEquals(json("""{"retry":false}"""), e["data"]); c.close()
    }
    @Test fun r16_failed_recovery_preserves_protocol_reason_in_closed_state() = runTest {
        for (rpc in listOf(false, true)) {
        val h = ScriptHttp(); h.normal(); val normal = h.script; var inits = 0
        h.script = { r -> if (r.method != "POST") normal(r) else when (r.message().text("method")) {
            "initialize" -> if (++inits == 1) normal(r) else response(body = if (!rpc) result(r.message().getValue("id"), initializeResult("2099-01-01")).toString() else buildJsonObject {
                put("jsonrpc", "2.0"); put("id", r.message().getValue("id")); putJsonObject("error") { put("code", -32602); put("message", "协议版本不支持") }
            }.toString())
            "tools/call" -> response(404)
            else -> normal(r)
        } }
        val c = client(transport(h)); c.connect(); failure { c.callTool("x", emptyObject, idleTimeoutMs = 1000) }
        val state = c.state.value as McpConnectionState.Closed
        assertTrue(state.reason, "不支持" in state.reason); c.close()
        }
    }
}
