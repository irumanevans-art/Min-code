package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.RecapTrigger
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionRecap
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.failing
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** 会话回顾（session recap）：生成、不进前缀、失败不坏会话、自动回顾的闸。每个会话测试末尾做「日志即真相」 */
class RecapSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0
    private var sessions = 0
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val fast = ModelRef("test", "fast")

    private class Recording(private val model: ModelClient) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> {
            requests += request
            return model.stream(request)
        }
    }

    private fun usage(out: Long) = Usage(inputTokens = 100, outputTokens = out)

    private suspend fun session(
        main: ModelClient,
        fastClient: ModelClient? = null,
        useFast: Boolean = fastClient != null,
        env: (String) -> String? = { null },
    ): SessionRuntime {
        val base = testDeps(main, emptyList())
        val deps = KernelDeps(
            clock = base.clock, ids = base.ids,
            models = { ref ->
                when (ref) {
                    TEST_MODEL -> ResolvedModel(TEST_PROFILE, main)
                    fast -> ResolvedModel(TEST_PROFILE.copy(ref = fast), checkNotNull(fastClient) { "没配提取模型" })
                    else -> error("未知模型 $ref")
                }
            },
            tools = emptyList(), host = base.host, platform = "test", zone = base.zone, retry = base.retry,
            fastModel = if (useFast) fast else null, env = env,
        )
        return SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("recap-${++sessions}"), createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions), deps, newScope(),
        )
    }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private fun chat(vararg answers: String) = ScriptedModel(*answers.map { reply(TextPart(it)) }.toTypedArray())

    @Test
    fun `a manual recap uses the extraction model, lands in the log and costs usage`() = run {
        val extractor = Recording(ScriptedModel(reply(TextPart("在修登录页的空指针；已定位到 AuthView，下一步补测试。"), usage = usage(30))))
        val rt = session(chat("好的，我来看看"), extractor)
        rt.say("登录页闪退，帮我修一下")
        val before = rt.state.value.usage

        val result = rt.recap()

        assertEquals(RecapResult.Generated("在修登录页的空指针；已定位到 AuthView，下一步补测试。"), result)
        val event = events(rt).filterIsInstance<SessionRecap>().single()
        assertEquals(RecapTrigger.Manual, event.trigger)
        assertEquals("提取模型生成的", fast, event.model)
        assertEquals(30, event.usage.outputTokens)
        assertEquals(before + event.usage, rt.state.value.usage)
        // 请求形状：没有工具、近况里有用户说的话、要求一行
        val request = extractor.requests.single()
        assertTrue(request.tools.isEmpty())
        val prompt = (request.messages.single().parts.single() as TextPart).text
        assertTrue(prompt, prompt.contains("登录页闪退，帮我修一下") && prompt.contains("不超过 400"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `without a fast model the session model writes the recap`() = run {
        val main = Recording(ScriptedModel(reply(TextPart("答复")), reply(TextPart("一行回顾"))))
        val rt = session(main)
        rt.say("问题")
        assertEquals(RecapResult.Generated("一行回顾"), rt.recap())
        assertEquals(TEST_MODEL, events(rt).filterIsInstance<SessionRecap>().single().model)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a recap never reaches the model's view or changes the prefix`() = run {
        val main = Recording(ScriptedModel(reply(TextPart("第一答")), reply(TextPart("第二答"))))
        val extractor = Recording(ScriptedModel(reply(TextPart("RECAP-LINE-不该出现在前缀里"))))
        val rt = session(main, extractor)
        rt.say("第一问")
        val viewBefore = rt.state.value.modelMessages()
        rt.recap()
        assertEquals("折叠后模型视图一字不差", viewBefore, rt.state.value.modelMessages())
        rt.say("第二问")

        val second = main.requests.last()
        assertFalse(second.messages.flatMap { it.parts }.any { it is TextPart && "RECAP-LINE" in it.text })
        assertEquals("system 与工具表（稳定前缀）不变", main.requests.first().system, second.system)
        assertEquals(main.requests.first().tools, second.tools)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a failing extraction model leaves the session fine and writes nothing`() = run {
        val extractor = Recording(ScriptedModel(failing(ModelException(ModelFailureKind.Overloaded, "529", beforeFirstByte = true))))
        val rt = session(chat("答 1", "答 2"), extractor)
        rt.say("问 1")
        val seqBefore = rt.state.value.lastSeq

        val result = rt.recap()

        assertTrue(result.toString(), result is RecapResult.Failed)
        assertEquals("失败不留痕", seqBefore, rt.state.value.lastSeq)
        assertEquals(0, events(rt).count { it is SessionRecap })
        // 会话照常能用
        val next = rt.say("问 2")
        assertEquals(dev.min.agent.spi.TurnOutcome.Completed, next.lastTurn?.outcome)
        assertLogIsTruth(rt)
    }

    @Test
    fun `an unresolvable model and an empty answer are failures too`() = run {
        val blank = session(chat("答"), Recording(ScriptedModel(reply(TextPart("   \n")))))
        blank.say("问")
        assertEquals(RecapResult.Failed("模型没有给出回顾"), blank.recap())

        val unresolvable = session(chat("答"), fastClient = null, useFast = true)
        unresolvable.say("问")
        assertTrue(unresolvable.recap() is RecapResult.Failed)
        assertEquals(0, events(unresolvable).count { it is SessionRecap })
    }

    @Test
    fun `a busy or empty session is skipped, not failed`() = run {
        val rt = session(chat("答"), Recording(ScriptedModel(reply(TextPart("x")))))
        assertTrue("还没有对话", rt.recap() is RecapResult.Skipped)

        val gate = CompletableDeferred<Unit>()
        val slow = ScriptedModel({ flow { gate.await(); emit(ModelStreamEvent.Started(null)) } })
        val busy = session(slow, Recording(ScriptedModel(reply(TextPart("x")))))
        busy.send(ClientMsgId("busy"), listOf(TextPart("在忙")))
        busy.state.first { it.turn != null }
        assertTrue("回合在跑", busy.recap() is RecapResult.Skipped)
        busy.interrupt()
        busy.awaitIdle()
    }

    @Test
    fun `the recap is one line capped at 400 characters`() {
        assertEquals("a b c", Recapper.tidy("  a\n\n b \n c  \n"))
        val long = Recapper.tidy("字".repeat(1_000))
        assertEquals(Recapper.MAX_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("刚好四百", Recapper.tidy("刚好四百"))
    }

    @Test
    fun `the transcript keeps the newest entries within its budget`() = run {
        val rt = session(chat("答 1", "答 2", "答 3", "答 4", "答 5", "答 6"))
        // 单条最多留 TRANSCRIPT_CHARS/4 个字，四条长问题就把 12000 的预算占满了
        rt.say("最早的问题 ${"x".repeat(5_000)}")
        repeat(3) { rt.say("填充 $it ${"y".repeat(5_000)}") }
        rt.say("中间的问题")
        rt.say("最近的问题")
        val text = Recapper.transcript(rt.state.value)
        assertTrue(text.contains("最近的问题"))
        assertTrue(text.contains("中间的问题"))
        assertFalse("装不下的最早一条被丢掉", text.contains("最早的问题"))
        assertTrue("没有一条超出预算太多", text.length < Recapper.TRANSCRIPT_CHARS + 3_500)
    }

    // ---- 自动回顾的闸 ----

    @Test
    fun `an away recap needs three turns and never runs twice in a row`() = run {
        val extractor = Recording(ScriptedModel(reply(TextPart("回顾一")), reply(TextPart("回顾二")), reply(TextPart("回顾三"))))
        val rt = session(chat("a", "b", "c", "d"), extractor)
        rt.say("1")
        rt.say("2")
        assertTrue("不到三轮", rt.recap(RecapTrigger.Away) is RecapResult.Skipped)
        rt.say("3")

        assertEquals(RecapResult.Generated("回顾一"), rt.recap(RecapTrigger.Away))
        assertEquals(RecapTrigger.Away, events(rt).filterIsInstance<SessionRecap>().single().trigger)
        assertTrue("没有新回合就不再自动来一次", rt.recap(RecapTrigger.Away) is RecapResult.Skipped)
        // 手动不受这条限制
        assertEquals(RecapResult.Generated("回顾二"), rt.recap(RecapTrigger.Manual))
        rt.say("4")
        assertEquals("新回合之后又可以自动回顾", RecapResult.Generated("回顾三"), rt.recap(RecapTrigger.Away))
        assertLogIsTruth(rt)
    }

    @Test
    fun `the environment variable overrides the setting in both directions`() {
        val off = { name: String -> if (name == Recapper.AWAY_ENV) "0" else null }
        val on = { name: String -> if (name == Recapper.AWAY_ENV) "1" else null }
        val none = { _: String -> null }
        assertTrue(awayRecapEnabled(null, none))
        assertFalse(awayRecapEnabled(false, none))
        assertTrue(awayRecapEnabled(true, none))
        assertFalse("0 强制关，盖过 settings 的 true", awayRecapEnabled(true, off))
        assertTrue("1 强制开，盖过 settings 的 false", awayRecapEnabled(false, on))
        assertFalse(awayRecapEnabled(null, { if (it == Recapper.AWAY_ENV) "false" else null }))
        // 写错的取值当没设
        assertFalse(awayRecapEnabled(false, { if (it == Recapper.AWAY_ENV) "maybe" else null }))
    }

    @Test
    fun `the environment switch stops an away recap but not a manual one`() = run {
        val extractor = Recording(ScriptedModel(reply(TextPart("手动的回顾"))))
        val rt = session(chat("a", "b", "c"), extractor, env = { if (it == Recapper.AWAY_ENV) "0" else null })
        rt.say("1"); rt.say("2"); rt.say("3")
        assertTrue(rt.recap(RecapTrigger.Away) is RecapResult.Skipped)
        assertEquals(0, extractor.requests.size)
        assertEquals(RecapResult.Generated("手动的回顾"), rt.recap(RecapTrigger.Manual))
    }
}
