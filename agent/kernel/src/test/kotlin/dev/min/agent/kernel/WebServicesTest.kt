package dev.min.agent.kernel

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import dev.min.agent.tools.WebFetchTool
import dev.min.agent.tools.WebSearchTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class WebServicesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fastRef = ModelRef("test", "fast")
    private val artifacts = FakeArtifacts()
    @After fun stop() = scope.cancel()
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(15_000) { block() } }
    private fun fetchCall() = ToolCallPart(CallId("fetch"), "WebFetch", args("url" to "https://example.com", "prompt" to "question"))

    private fun deps(
        model: ModelClient, fast: ModelClient? = null, backend: SearchBackend? = null, cap: Int = 200, tools: List<Tool<*>> = listOf(WebFetchTool, WebSearchTool),
        providerSmall: ModelRef? = null,
    ): KernelDeps {
        val base = testDeps(model, tools, artifacts)
        return KernelDeps(base.clock, base.ids, ModelResolver { ref ->
            if (ref == fastRef) ResolvedModel(TEST_PROFILE.copy(ref = fastRef), requireNotNull(fast)) else base.models.resolve(ref)
        }, tools, HostPorts(base.host.fs, base.host.search, base.host.exec, base.host.artifacts,
            WebPort { WebResponse.Page(it, PageContent(null, "PAGE PRIVATE SOURCE")) }, backend),
            base.platform, base.zone, base.retry, fastModel = if (fast == null || providerSmall != null) null else fastRef, maxWebSearches = cap,
            providerFastModel = { id -> providerSmall?.takeIf { id == TEST_MODEL.providerId } })
    }
    private suspend fun create(deps: KernelDeps) = SessionHub(store, deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
    private suspend fun execute(rt: SessionRuntime) {
        rt.send(ClientMsgId("u1"), listOf(TextPart("user history secret")))
        rt.awaitIdle()
    }

    @Test fun `fast extraction is isolated and usage belongs to current turn and replays`() = run {
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(reply(TextPart("answer"), usage = Usage(100, 20, 30, 40)))
        val rt = create(deps(main, fast))
        execute(rt)
        val request = fast.requests.single()
        assertEquals(fastRef, request.profile.ref)
        assertTrue(request.tools.isEmpty())
        assertEquals(ToolChoice.None, request.toolChoice)
        assertFalse(request.messages.toString().contains("user history secret"))
        assertTrue(request.messages.toString().contains("PAGE PRIVATE SOURCE"))
        assertTrue(request.messages.toString().contains("question"))
        assertEquals(Usage(120, 30, 30, 40), rt.state.value.lastTurn!!.usage)
        assertEquals(2, main.requests.size)
        assertFalse(main.requests.last().messages.toString().contains("PAGE PRIVATE SOURCE"))
        val log = store.peek(rt.sessionId)!!
        val records = log.read().toList()
        assertEquals(1, records.count { it.event is WebExtractionCompleted })
        assertEquals(rt.state.value, fold(log.header, records))
    }

    @Test fun `without an explicit extraction model the provider's small catalog model extracts, as CC uses Haiku`() = run {
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(reply(TextPart("answer")))
        val rt = create(deps(main, fast, providerSmall = fastRef))
        execute(rt)
        assertEquals(fastRef, fast.requests.single().profile.ref)
        assertEquals(2, main.requests.size)
        assertEquals(fastRef, store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<WebExtractionCompleted>().single().model)
    }

    @Test fun `a provider small model this host cannot resolve falls back to the session model`() = run {
        val model = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("answer")), reply(TextPart("done")))
        val rt = create(deps(model, providerSmall = ModelRef(TEST_MODEL.providerId, "not-in-this-catalog")))
        execute(rt)
        assertEquals(TEST_MODEL, model.requests[1].profile.ref)
    }

    @Test fun `unset fast model uses current session model but separate request`() = run {
        val model = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("answer")), reply(TextPart("done")))
        val rt = create(deps(model))
        execute(rt)
        assertEquals(3, model.requests.size)
        assertEquals(TEST_MODEL, model.requests[1].profile.ref)
        assertTrue(model.requests[1].tools.isEmpty())
        assertEquals(Usage(30, 15), rt.state.value.lastTurn!!.usage)
        assertEquals(3, rt.state.value.history.size)
    }

    @Test fun `overloaded extraction retries then fails without returning api error text`() = run {
        val failure = ModelException(ModelFailureKind.Overloaded, "UPSTREAM PRIVATE ERROR", beforeFirstByte = true)
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(failing(failure), failing(failure), failing(failure))
        val rt = create(deps(main, fast))
        execute(rt)
        assertEquals(3, fast.requests.size)
        val result = rt.state.value.toolResults.getValue(CallId("fetch"))
        assertTrue(result.isError)
        assertFalse(result.text.contains("UPSTREAM PRIVATE ERROR"))
        assertNotNull(result.artifact)
        assertEquals(Usage(20, 10), rt.state.value.lastTurn!!.usage)
    }

    @Test fun `overloaded retry success is charged once`() = run {
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(failing(ModelException(ModelFailureKind.Overloaded, "busy", beforeFirstByte = true)), reply(TextPart("answer")))
        val rt = create(deps(main, fast))
        execute(rt)
        assertFalse(rt.state.value.toolResults.getValue(CallId("fetch")).isError)
        assertEquals(2, fast.requests.size)
        assertEquals(Usage(30, 15), rt.state.value.lastTurn!!.usage)
    }

    @Test fun `incomplete extraction is error but completed usage is charged`() = run {
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(reply(TextPart("partial"), truncated = true, stop = StopReason.MaxTokens))
        val rt = create(deps(main, fast))
        execute(rt)
        assertTrue(rt.state.value.toolResults.getValue(CallId("fetch")).isError)
        assertEquals(Usage(30, 15), rt.state.value.lastTurn!!.usage)
    }

    @Test fun `cancel extraction cancels model and does not fabricate page text`() = run {
        val entered = CompletableDeferred<Unit>()
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse))
        val fast = ModelClient { flow { entered.complete(Unit); awaitCancellation() } }
        val rt = create(deps(main, fast))
        rt.send(ClientMsgId("u1"), listOf(TextPart("fetch")))
        entered.await()
        rt.interrupt(); rt.awaitIdle()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), rt.state.value.lastTurn!!.outcome)
        assertTrue(rt.state.value.toolResults.values.none { it.text.contains("按你的提问提取") })
    }

    @Test fun `non retryable extraction does not retry or expose api text`() = run {
        val main = ScriptedModel(reply(fetchCall(), stop = StopReason.ToolUse), reply(TextPart("done")))
        val fast = ScriptedModel(failing(ModelException(ModelFailureKind.Auth, "PRIVATE KEY ECHO", beforeFirstByte = true)))
        val rt = create(deps(main, fast))
        execute(rt)
        assertEquals(1, fast.requests.size)
        assertTrue(rt.state.value.toolResults.getValue(CallId("fetch")).isError)
        assertFalse(rt.state.value.toolResults.getValue(CallId("fetch")).text.contains("PRIVATE KEY ECHO"))
    }

    @Test fun `concurrent root and nested child searches share durable cap across reopening`() = run {
        val count = AtomicInteger()
        val deps = deps(ScriptedModel(), backend = SearchBackend { _, _, _, _ -> count.incrementAndGet(); emptyList() }, cap = 3)
        val hub = SessionHub(store, deps, scope)
        val root = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
        suspend fun child(id: String, parent: SessionId, depth: Int): SessionRuntime {
            val log = store.create(LogHeader(LOG_FORMAT_VERSION, SessionId(id), 0, parent))
            log.append(listOf(SessionCreated(GuestPath("/work"), TEST_MODEL, PermissionMode.Default),
                SubagentOrigin(parent, root.sessionId, CallId(id), "Explore", false, depth, cacheKey = id)), Durability.Sync)
            return hub.open(SessionId(id))!!
        }
        val child = child("child", root.sessionId, 1)
        val nested = child("nested", child.sessionId, 2)
        val results = coroutineScope {
            (1..20).map { i -> async { searchWeb(listOf(root, child, nested)[i % 3], CallId("c$i"), "x", emptyList(), emptyList(), 10) } }.awaitAll()
        }
        assertEquals(3, count.get())
        assertEquals(3, results.count { it is SearchResponse.Hits })
        assertEquals(17, results.count { it == SearchResponse.LimitReached })
        assertEquals(3, root.state.value.webSearches)
        assertEquals(0, child.state.value.webSearches)
        assertEquals(0, nested.state.value.webSearches)
        assertTrue(hub.close(root.sessionId))
        val reopenedHub = SessionHub(store, deps, scope)
        val reopenedChild = reopenedHub.open(nested.sessionId)!!
        assertEquals(SearchResponse.LimitReached, searchWeb(reopenedChild, CallId("reopen"), "x", emptyList(), emptyList(), 10))
        assertEquals(3, count.get())
        val log = store.peek(root.sessionId)!!
        assertEquals(3, log.read().toList().count { it.event is WebSearchUsed })
    }

    @Test fun `failed backend consumes reservation and different roots have own caps`() = run {
        val deps = deps(ScriptedModel(), backend = SearchBackend { _, _, _, _ -> error("backend fails") }, cap = 1)
        val hub = SessionHub(store, deps, scope)
        val root = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
        try { searchWeb(root, CallId("a"), "x", emptyList(), emptyList(), 10); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(1, root.state.value.webSearches)
        assertEquals(SearchResponse.LimitReached, searchWeb(root, CallId("b"), "x", emptyList(), emptyList(), 10))
        val other = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
        try { searchWeb(other, CallId("c"), "x", emptyList(), emptyList(), 10); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(1, other.state.value.webSearches)
    }

    @Test fun `tree stop cancels child search keeps root quota and does not wake parent after report or reopen`() = run {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val model = ModelClient { request -> flow {
            val last = request.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            val step = when {
                last == "slow search" -> reply(ToolCallPart(CallId("search"), "WebSearch", args("query" to "x")), stop = StopReason.ToolUse)
                request.messages.size == 1 -> reply(ToolCallPart(CallId("delegate"), "Agent",
                    args("description" to "search", "prompt" to "slow search", "run_in_background" to true)), stop = StopReason.ToolUse)
                else -> reply(TextPart("waiting"))
            }
            emitAll(step(request))
        } }
        val deps = deps(model, backend = SearchBackend { _, _, _, _ ->
            count.incrementAndGet()
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, cap = 1, tools = listOf(dev.min.agent.tools.AgentTool, WebSearchTool))
        val hub = SessionHub(store, deps, scope)
        val root = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        root.send(ClientMsgId("start"), listOf(TextPart("delegate search")))
        entered.await()
        root.state.first { it.turn == null && it.lastTurn != null }
        val child = hub.get(root.state.value.children.keys.single())!!
        hub.stopTree(root.sessionId)
        cancelled.await()
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), child.state.value.lastTurn!!.outcome)
        val reported = root.state.first { it.inbox.any { input -> input.origin is InputOrigin.Agent } }
        // Report receipt and notification startup are separate operations; let the watcher finish both.
        delay(300)
        assertNull(reported.turn)
        assertNull(root.state.value.turn)
        assertEquals(1, reported.webSearches)
        assertEquals(0, child.state.value.webSearches)
        assertEquals(1, store.peek(root.sessionId)!!.read().toList().count { it.event is TurnStarted })
        assertEquals(1, store.peek(root.sessionId)!!.read().toList().count { it.event is WebSearchUsed })

        assertTrue(hub.shutdown(root.sessionId))
        val reopened = SessionHub(store, deps, scope).open(root.sessionId)!!
        assertEquals(1, reopened.state.value.webSearches)
        assertEquals(1, reopened.state.value.inbox.size)
        assertNull(reopened.state.value.turn)
        assertEquals(SearchResponse.LimitReached, searchWeb(reopened, CallId("after-stop"), "x", emptyList(), emptyList(), 10))
        assertEquals(1, count.get())
    }

    @Test fun `search tool uses injected backend and caps calls without error`() = run {
        fun call(id: String) = ToolCallPart(CallId(id), "WebSearch", args("query" to "kotlin"))
        val model = ScriptedModel(reply(call("a"), call("b"), stop = StopReason.ToolUse), reply(TextPart("done")))
        val count = AtomicInteger()
        val rt = create(deps(model, backend = SearchBackend { q, _, _, limit ->
            assertEquals("kotlin", q); assertEquals(10, limit); count.incrementAndGet()
            listOf(SearchHit("Title", "https://example.com", "Snippet"))
        }, cap = 1))
        execute(rt)
        assertEquals(1, count.get())
        assertTrue(rt.state.value.toolResults.values.any { it.text.contains("Title — https://example.com — Snippet") })
        assertTrue(rt.state.value.toolResults.values.any { it.text == WebSearchTool.LIMIT_NOTICE && !it.isError })
    }

    @Test fun `standalone runtime reserves atomically and unavailable backend does not count`() = run {
        val deps = deps(ScriptedModel(), backend = SearchBackend { _, _, _, _ -> emptyList() }, cap = 1)
        val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("single"), 0),
            SessionCreated(GuestPath("/work"), TEST_MODEL, PermissionMode.Default), deps, scope)
        val results = coroutineScope { (1..10).map { async { searchWeb(rt, CallId("c$it"), "x", emptyList(), emptyList(), 10) } }.awaitAll() }
        assertEquals(1, results.count { it is SearchResponse.Hits })
        val missing = create(deps(ScriptedModel()))
        assertEquals(SearchResponse.Unavailable, searchWeb(missing, CallId("none"), "x", emptyList(), emptyList(), 10))
        assertEquals(0, missing.state.value.webSearches)
    }
}
