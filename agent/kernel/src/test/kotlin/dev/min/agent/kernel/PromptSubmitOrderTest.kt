package dev.min.agent.kernel

import dev.min.agent.ext.skills.PromptSubmitVerdict
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.InputBlocked
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.SessionEvent
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 内核入口 [SessionRuntime.promptSubmitHooks] 与 `send(submitScreened=…)`：斜杠展开前对原文跑一次 UserPromptSubmit，
 * 提升时不再重跑（CC 2.1.299）；拦下不进收件箱；普通消息仍由回合提升时跑。
 */
class PromptSubmitOrderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After fun tearDown() = scopes.forEach { it.cancel() }

    private class FakeHttp(val reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            sent += request
            return reply(request)
        }
    }

    private fun hook() = kotlinx.serialization.json.JsonObject(
        mapOf(
            "UserPromptSubmit" to kotlinx.serialization.json.JsonArray(
                listOf(
                    kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "hooks" to kotlinx.serialization.json.JsonArray(
                                listOf(kotlinx.serialization.json.JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive("http"), "url" to kotlinx.serialization.json.JsonPrimitive("https://hooks.test/submit")))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun promptOf(request: HttpHookRequest) = Json.parseToJsonElement(request.body).jsonObject["prompt"]?.jsonPrimitive?.content

    private suspend fun session(http: FakeHttp): SessionRuntime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val model = object : dev.min.agent.llm.ModelClient {
            override fun stream(request: dev.min.agent.llm.ModelRequest) = reply(TextPart("ok"))(request)
        }
        val deps = KernelDeps(
            clock = { 1_000 },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = emptyList(),
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = http),
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = null,
        )
        return SessionHub(store, deps, scope).create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", hook())))),
        )
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test fun `blocking promptSubmitHooks refuses before send, so the model never sees the line`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, """{"decision":"block","reason":"blocked by policy"}""") }
        val rt = session(http)
        val verdict = rt.promptSubmitHooks("/danger BLOCKME")
        assertTrue(verdict is PromptSubmitVerdict.Blocked)
        assertEquals("blocked by policy", (verdict as PromptSubmitVerdict.Blocked).reason)
        assertEquals(listOf("/danger BLOCKME"), http.sent.mapNotNull(::promptOf))
        assertTrue(events(rt).none { it is InputReceived || it is InputBlocked })
        assertTrue(events(rt).filterIsInstance<HooksRan>().any { it.event == "UserPromptSubmit" })
    }

    @Test fun `passing promptSubmitHooks plus send with submitScreened does not run the hook a second time`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"ctx"}}""") }
        val rt = session(http)
        val verdict = rt.promptSubmitHooks("/danger fine") as PromptSubmitVerdict.Pass
        assertEquals(listOf("ctx"), verdict.contexts)
        assertTrue(rt.send(ClientMsgId("m1"), listOf(TextPart("EXPANDED BODY")), submitScreened = verdict.contexts))
        rt.awaitIdle()
        assertEquals("同一条 prompt 只跑一次", listOf("/danger fine"), http.sent.mapNotNull(::promptOf))
        val received = events(rt).filterIsInstance<InputReceived>().single()
        assertEquals(listOf("ctx"), received.submitScreened)
        assertEquals("EXPANDED BODY", (received.parts.single() as TextPart).text)
    }

    @Test fun `a plain send without submitScreened still screens inside the turn`() = run {
        val http = FakeHttp { HttpHookResult.Responded(200, """{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"ctx"}}""") }
        val rt = session(http)
        assertTrue(rt.send(ClientMsgId("m1"), listOf(TextPart("hello there"))))
        rt.awaitIdle()
        assertEquals(listOf("hello there"), http.sent.mapNotNull(::promptOf))
        assertEquals(null, events(rt).filterIsInstance<InputReceived>().single().submitScreened)
    }
}
