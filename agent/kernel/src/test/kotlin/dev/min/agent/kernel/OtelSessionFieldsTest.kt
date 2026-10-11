package dev.min.agent.kernel

import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookSourceData
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.HttpHookClient
import dev.min.agent.spi.HttpHookRequest
import dev.min.agent.spi.HttpHookResult
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * 遥测要用、事后又从日志推不出来的两类事实（OTel 第三段）：
 * 流式耗时（`ttft_ms` / `first_content_ms`）随助手回复落盘；一批 hook 里各处理器的结局计数随 hooks_ran 落盘。
 */
class OtelSessionFieldsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private var counter = 0
    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(rt: SessionRuntime) = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    // ---- 流式耗时 ----

    /** 先给 Started，再给一段文本增量，最后 Completed */
    private val streamed: Step = {
        flow {
            emit(ModelStreamEvent.Started(null))
            emit(ModelStreamEvent.TextDelta("你"))
            emit(ModelStreamEvent.TextDelta("好"))
            emit(
                ModelStreamEvent.Completed(
                    ModelMessage(Role.Assistant, listOf(TextPart("你好"))), Usage(inputTokens = 1, outputTokens = 1), StopReason.EndTurn, false,
                ),
            )
        }
    }

    @Test
    fun `a streamed reply records time to first event and time to first content`() = run {
        val rt = SessionHub(store, testDeps(ScriptedModel(streamed), emptyList()), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val done = events(rt).filterIsInstance<AssistantMessageCompleted>().single()
        // 测试时钟每读一次走 10 毫秒：Started 先到、内容后到，所以首内容不早于首事件，两个都有
        assertNotNull(done.ttftMs)
        assertNotNull(done.firstContentMs)
        assertTrue("首内容不应早于首事件：${done.ttftMs} / ${done.firstContentMs}", done.firstContentMs!! >= done.ttftMs!!)
        assertTrue(done.ttftMs!! >= 0)
    }

    @Test
    fun `a reply without any content delta has a first event time but no first content time`() = run {
        // reply() 只给 Started + Completed：没有增量
        val rt = SessionHub(store, testDeps(ScriptedModel(reply(TextPart("好"))), emptyList()), newScope())
            .create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("hi")
        val done = events(rt).filterIsInstance<AssistantMessageCompleted>().single()
        assertNotNull(done.ttftMs)
        assertNull("没有内容增量就不编造首内容时间", done.firstContentMs)
    }

    // ---- hook 结局计数 ----

    private class FakeHttp(var reply: (HttpHookRequest) -> HttpHookResult) : HttpHookClient {
        override suspend fun send(request: HttpHookRequest): HttpHookResult = reply(request)
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

    private suspend fun ranFor(answer: (HttpHookRequest) -> HttpHookResult): HooksRan {
        val http = FakeHttp(answer)
        val model = ScriptedModel(reply(TextPart("好")))
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
        val rt = SessionHub(store, deps, newScope()).create(
            GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions,
            initial = listOf(HooksLoaded(listOf(HookSourceData("user", promptHook())))),
        )
        rt.say("干活")
        return events(rt).filterIsInstance<HooksRan>().single { it.event == "UserPromptSubmit" }
    }

    @Test
    fun `a hook that answers neutral counts as succeeded`() = run {
        val ran = ranFor { HttpHookResult.Responded(200, "{}") }
        assertEquals(1, ran.succeeded)
        assertEquals(0, ran.blocking)
        assertEquals(0, ran.nonBlockingErrors)
    }

    @Test
    fun `a hook that blocks counts as blocking, not as an error`() = run {
        val ran = ranFor { HttpHookResult.Responded(200, """{"decision":"block","reason":"不行"}""") }
        assertEquals(0, ran.succeeded)
        assertEquals(1, ran.blocking)
        assertEquals(0, ran.nonBlockingErrors)
    }

    @Test
    fun `a hook that cannot be reached counts as a non-blocking error`() = run {
        val ran = ranFor { HttpHookResult.Failed("连不上") }
        assertEquals(0, ran.succeeded)
        assertEquals(0, ran.blocking)
        assertEquals(1, ran.nonBlockingErrors)
    }
}
