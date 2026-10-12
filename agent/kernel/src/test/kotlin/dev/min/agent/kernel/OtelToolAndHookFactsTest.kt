package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

/**
 * OTel 第四段：工具失败的类别（`Error:ENOENT` 一类）随 tool_outcome 落盘；回合被中断、hook 整批被取消时补一条 hooks_ran（`num_cancelled`）。
 */
class OtelToolAndHookFactsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    // ---- 工具失败类别 ----

    private class ThrowingTool(private val make: () -> Exception) : Tool<JsonObject> {
        override val name = "Boom"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用：抛异常"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { throw make() }
    }

    private suspend fun outcomeAfter(make: () -> Exception): ToolOutcome {
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("c1"), "Boom", JsonObject(emptyMap())), stop = StopReason.ToolUse),
            reply(TextPart("好")),
        )
        val rt = SessionHub(store, testDeps(model, listOf(ThrowingTool(make))), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u${++counter}"), listOf(TextPart("go")))
        rt.awaitIdle()
        return store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<ToolOutcome>().single()
    }

    @Test
    fun `a missing file exception is recorded as ENOENT`() = run {
        val o = outcomeAfter { java.nio.file.NoSuchFileException("/x") }
        assertTrue(o.result.isError)
        assertEquals("Error:ENOENT", o.result.errorKind)
    }

    @Test
    fun `an access denied exception is recorded as EACCES`() = run {
        assertEquals("Error:EACCES", outcomeAfter { java.nio.file.AccessDeniedException("/x") }.result.errorKind)
    }

    @Test
    fun `any other exception is recorded by its class name only, never its message`() = run {
        val o = outcomeAfter { IllegalStateException("secret-token-123") }
        assertEquals("IllegalStateException", o.result.errorKind)
        assertTrue("类别里不能带异常消息", "secret-token-123" !in o.result.errorKind.orEmpty())
    }

    // ---- hook 被取消 ----

    private class BlockingHttp(val started: CompletableDeferred<Unit>) : HttpHookClient {
        override suspend fun send(request: HttpHookRequest): HttpHookResult {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    private fun promptHook() = JsonObject(
        mapOf(
            "UserPromptSubmit" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "hooks" to JsonArray(
                                listOf(JsonObject(mapOf("type" to JsonPrimitive("http"), "url" to JsonPrimitive("https://hooks.example/x")))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `interrupting a turn while hooks are running records the cancelled batch`() = run {
        val started = CompletableDeferred<Unit>()
        val model = ScriptedModel(reply(TextPart("好")))
        val deps = testDeps(model, emptyList()).copy(
            host = HostPorts(FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() }, httpHooks = BlockingHttp(started)),
            zone = ZoneOffset.UTC,
        )
        val rt = SessionHub(store, deps, newScope()).create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", promptHook())))),
        )
        rt.send(ClientMsgId("u${++counter}"), listOf(TextPart("干活")))
        started.await()
        rt.interrupt()
        rt.awaitIdle()
        val ran = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().filter { it.event == "UserPromptSubmit" }
        assertEquals("被取消的批留一条记录：$ran", 1, ran.size)
        assertEquals(1, ran.single().handlers)
        assertEquals(1, ran.single().cancelled)
        assertEquals("cancelled", ran.single().decision)
        assertTrue(ran.single().problems.isNotEmpty())
    }

    @Test
    fun `a hook batch that finishes normally does not carry a cancelled count`() = run {
        val model = ScriptedModel(reply(TextPart("好")))
        val deps = testDeps(model, emptyList()).copy(
            host = HostPorts(
                FakeFs(), FakeSearch(), FakeExec(), { FakeArtifacts() },
                httpHooks = object : HttpHookClient {
                    override suspend fun send(request: HttpHookRequest): HttpHookResult = HttpHookResult.Responded(200, "{}")
                },
            ),
        )
        val rt = SessionHub(store, deps, newScope()).create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", promptHook())))),
        )
        rt.send(ClientMsgId("u${++counter}"), listOf(TextPart("干活")))
        rt.awaitIdle()
        val ran = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<HooksRan>().single { it.event == "UserPromptSubmit" }
        assertNull(ran.cancelled)
        assertEquals(1, ran.succeeded)
    }
}
