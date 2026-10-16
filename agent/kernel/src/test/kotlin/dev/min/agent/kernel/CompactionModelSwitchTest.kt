package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Note
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.KillableStore
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 切到窗口更小的模型时，放不下的对话由切换前的模型先压缩（CC 2.1.301）。两个模型各有自己的脚本客户端，
 * 谁收到了摘要请求一目了然；窗口：[BIG] 20 万、[SMALL] 2 万（遮蔽线约 1.2 万、摘要线约 1.5 万）。
 * 时机选在下一次请求前（[Compactor.beforeRequest]）而不是 changeSettings 里，见 [Compactor.previousModelFor]。
 */
class CompactionModelSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private val big = ModelRef("test", "big")
    private val small = ModelRef("test", "small")

    /** 窗口与小窗口模型相同、输出预留不同的两个型号：摘要线一个约 1.62 万、一个约 1.33 万，用来验证「窗口不变就不让旧模型摘要」 */
    private val sameA = ModelRef("test", "same-a")
    private val sameB = ModelRef("test", "same-b")

    private val profiles: Map<ModelRef, ModelProfile> = mapOf(
        big to TEST_PROFILE.copy(ref = big, contextWindow = 200_000, maxOutputTokens = 2_000),
        small to TEST_PROFILE.copy(ref = small, contextWindow = 20_000, maxOutputTokens = 2_000),
        sameA to TEST_PROFILE.copy(ref = sameA, contextWindow = 20_000, maxOutputTokens = 500),
        sameB to TEST_PROFILE.copy(ref = sameB, contextWindow = 20_000, maxOutputTokens = 4_000),
    )

    private class RecordingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }

        val summaries get() = requests.filter { it.isSummary() }
        val normal get() = requests.filterNot { it.isSummary() }
    }

    /** 像真的供应商那样把请求的估算当输入用量回报：「上次请求的用量」才有意义 */
    private fun answer(text: String): Step = { req ->
        flowOf(
            ModelStreamEvent.Started(null),
            ModelStreamEvent.Completed(ModelMessage(Role.Assistant, listOf(TextPart(text))), Usage(inputTokens = Tokens.of(req.messages), outputTokens = 10), StopReason.EndTurn, false),
        )
    }

    private fun overloaded() = ModelException(ModelFailureKind.Overloaded, "529 overloaded", httpStatus = 529, beforeFirstByte = true)

    /** 摘要请求回摘要，其余回「好」；[summary] / [normal] 可覆盖 */
    private fun client(summary: Step = answer("## 目标\n用户发了两段长文。"), normal: Step = answer("好")): RecordingModel =
        RecordingModel { req -> if (req.isSummary()) summary else normal }

    /** 解析不了的型号（模拟那个供应商没了）：解析时抛 */
    private val gone = java.util.concurrent.ConcurrentHashMap.newKeySet<ModelRef>()

    private fun deps(clients: Map<ModelRef, ModelClient>, chain: List<ModelRef> = emptyList()): KernelDeps =
        testDeps(clients.values.first(), emptyList()).let { base ->
            KernelDeps(
                clock = base.clock, ids = base.ids,
                models = { ref ->
                    check(ref !in gone) { "供应商不可用：${ref.modelId}" }
                    ResolvedModel(profiles.getValue(ref), checkNotNull(clients[ref]) { "没有配置模型 ${ref.modelId}" })
                },
                tools = emptyList(), host = base.host, platform = "test", zone = base.zone, retry = base.retry,
                fallbackModels = chain,
            )
        }

    private suspend fun session(d: KernelDeps, model: ModelRef, s: dev.min.agent.spi.SessionStore = store, scope: CoroutineScope = newScope()) =
        SessionHub(s, d, scope).create(GuestPath("/workspace"), model, PermissionMode.BypassPermissions)

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

    /** 两段各约 8.5k token 的长文：加起来过小窗口的摘要线（约 1.5 万），远不到大窗口的 */
    private val long = "A".repeat(34_000)

    // ---- 主路径 ----

    @Test
    fun `a conversation too long for the smaller window is summarized by the previous model, then continues on the new one`() = run {
        val bigClient = client()
        val smallClient = client()
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), big)
        rt.say(long)
        rt.say(long)
        assertTrue("大窗口里放得下：切换前不压缩", bigClient.summaries.isEmpty())

        assertNull(rt.changeSettings(model = small))
        assertEquals("切模型本身不压缩（时机在下一次请求前）", 0, events(rt).filterIsInstance<ContextCompacted>().size)
        rt.say("接着做")

        val summary = bigClient.summaries.single()
        assertEquals("摘要请求发给切换前的模型", big, summary.profile.ref)
        assertTrue("新模型没有被叫去做摘要", smallClient.summaries.isEmpty())
        // 这次摘要的账记在做摘要的模型头上（日志里看得见是谁做的）
        val charged = events(rt).filterIsInstance<ModelCharged>()
        assertEquals("两次回复 + 一次摘要", 3, charged.count { it.model == big })
        assertEquals("新模型只回了一次", 1, charged.count { it.model == small })

        // 之后的请求用新模型，视图已被摘要取代
        assertEquals(small, smallClient.normal.single().profile.ref)
        val first = (smallClient.normal.single().messages.first().parts.single() as TextPart).text
        assertTrue(first, first.startsWith("[较早的对话已压缩") && first.contains("用户发了两段长文"))
        val compacted = events(rt).filterIsInstance<ContextCompacted>().single()
        assertEquals(dev.min.agent.spi.CompactionTrigger.Auto, compacted.trigger)
        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        assertEquals("压缩之后会话记的是新模型在撑着", small, rt.state.value.lastReplyModel)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a conversation that still fits the smaller window is not compacted`() = run {
        val bigClient = client()
        val smallClient = client()
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), big)
        rt.say("你好")
        rt.changeSettings(model = small)
        rt.say("再来一句")
        assertTrue(bigClient.summaries.isEmpty() && smallClient.summaries.isEmpty())
        assertTrue(events(rt).none { it is ContextCompacted || it is CompactionFailed })
        assertEquals(small, smallClient.normal.single().profile.ref)
        assertLogIsTruth(rt)
    }

    @Test
    fun `switching to a larger window never compacts`() = run {
        val bigClient = client()
        val smallClient = client()
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), small)
        rt.say(long)
        rt.changeSettings(model = big)
        rt.say(long)
        assertTrue(bigClient.summaries.isEmpty() && smallClient.summaries.isEmpty())
        assertTrue(events(rt).none { it is ContextCompacted })
        assertEquals(big, bigClient.normal.single().profile.ref)
    }

    @Test
    fun `an equal window keeps the old behaviour - the current model summarizes`() = run {
        val aClient = client()
        val bClient = client()
        val rt = session(deps(mapOf(sameA to aClient, sameB to bClient)), sameA)
        // 15k token：低于 sameA 的摘要线、高于 sameB 的，所以只有换成 sameB 之后才该压缩
        rt.say("A".repeat(60_000))
        assertTrue(aClient.summaries.isEmpty())
        rt.changeSettings(model = sameB)
        rt.say("好的")
        assertTrue("窗口一样大：不让旧模型摘要", aClient.summaries.isEmpty())
        assertEquals(sameB, bClient.summaries.single().profile.ref)
        assertEquals(1, events(rt).filterIsInstance<ContextCompacted>().size)
    }

    // ---- 退路 ----

    @Test
    fun `when the previous model fails the same compaction falls back to the new model and the session carries on`() = run {
        val bigClient = client(summary = { throw overloaded() })
        val smallClient = client()
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), big)
        rt.say(long)
        rt.say(long)
        rt.changeSettings(model = small)
        val done = rt.say("接着做")

        assertEquals(1, bigClient.summaries.size)
        assertEquals("退回新模型做同一次压缩", small, smallClient.summaries.single().profile.ref)
        val log = events(rt)
        assertEquals("只有一次压缩、一次结局（不重复开始）", 1, log.filterIsInstance<ContextCompacted>().size)
        assertTrue(log.none { it is CompactionFailed })
        val note = log.filterIsInstance<Note>().single { it.text.contains("切换前的模型") }
        assertTrue(note.text, note.text.contains("big") && note.text.contains("small"))
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals(0, done.compactionFailures)
        assertLogIsTruth(rt)
    }

    @Test
    fun `when the previous model cannot even be resolved the new model summarizes`() = run {
        val bigClient = client()
        val smallClient = client()
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), big)
        rt.say(long)
        rt.say(long)
        rt.changeSettings(model = small)
        gone += big
        val done = rt.say("接着做")

        assertTrue(bigClient.summaries.isEmpty())
        assertEquals(small, smallClient.summaries.single().profile.ref)
        assertTrue(events(rt).filterIsInstance<Note>().any { it.text.contains("解析不了") })
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals(1, events(rt).filterIsInstance<ContextCompacted>().size)
    }

    @Test
    fun `when both models fail the compaction fails once, the request still goes out and the session is not stuck`() = run {
        val bigClient = client(summary = { throw overloaded() })
        val smallClient = client(summary = { throw overloaded() })
        val rt = session(deps(mapOf(big to bigClient, small to smallClient)), big)
        rt.say(long)
        rt.say(long)
        rt.changeSettings(model = small)
        val done = rt.say("接着做")

        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals("两个模型各试一次", 1, bigClient.summaries.size)
        assertEquals(1, smallClient.summaries.size)
        assertEquals("失败只记一次（旧模型那次不单独计入熔断）", 1, events(rt).filterIsInstance<CompactionFailed>().size)
        assertEquals(1, done.compactionFailures)
        assertNull(done.openCompaction)
        // 请求照发，用的是新模型
        assertEquals(small, smallClient.normal.single().profile.ref)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a fallback-chain switch inside a turn does not send the summary back to the model that just failed`() = run {
        // 大模型前两句正常回复，第三句起一直过载 → 重试用尽后回合内换到回退链上的小模型
        val served = java.util.concurrent.atomic.AtomicInteger(0)
        val overloadedStep: Step = { flow { throw overloaded() } }
        val flaky = RecordingModel { req ->
            when {
                req.isSummary() -> answer("## 目标\n大模型的摘要")
                served.getAndIncrement() < 2 -> answer("好")
                else -> overloadedStep
            }
        }
        val smallClient = client()
        val rt = session(deps(mapOf(big to flaky, small to smallClient), chain = listOf(small)), big)
        rt.say(long)
        rt.say(long)
        val done = rt.say("接着做")

        assertEquals("回退是因为原模型倒了，摘要不再叫它", 0, flaky.summaries.size)
        assertEquals(small, smallClient.summaries.single().profile.ref)
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertLogIsTruth(rt)
    }

    // ---- 恢复 ----

    @Test
    fun `after a kill the rebuilt request matches the one sent after the compaction`() = run {
        val bigClient = client()
        val smallClient = client()
        val d = deps(mapOf(big to bigClient, small to smallClient))
        val rt = session(d, big)
        rt.say(long)
        rt.say(long)
        rt.changeSettings(model = small)
        rt.say("接着做")
        val sent = smallClient.normal.single()

        // 进程「被杀」后只凭日志重开
        val reopened = SessionHub(store, d, newScope()).open(rt.sessionId)!!
        assertEquals(rt.state.value.modelMessages(), reopened.state.value.modelMessages())
        assertEquals(small, reopened.state.value.lastReplyModel)
        reopened.say("再来一句")
        val rebuilt = smallClient.normal.last()
        assertEquals("重建出的请求前缀与压缩后实际发出的逐字一致", sent.messages, rebuilt.messages.take(sent.messages.size))
        assertEquals(sent.system, rebuilt.system)
        assertEquals(prefixFingerprint(sent), prefixFingerprint(rebuilt))
        assertEquals("重开后不会再压缩一遍", 1, events(reopened).filterIsInstance<ContextCompacted>().size)
    }

    @Test
    fun `killed in the middle of the previous model's summary, the reopened session re-derives the previous model from the log`() = run {
        val hang: Step = { flow { awaitCancellation() } }
        val bigClient = client(summary = hang)
        val smallClient = client()
        val killable = KillableStore(store)
        val scope = newScope()
        val d = deps(mapOf(big to bigClient, small to smallClient))
        val rt = session(d, big, killable, scope)
        rt.say(long)
        rt.say(long)
        rt.changeSettings(model = small)
        scope.launch { rt.say("接着做") }
        rt.state.first { it.openCompaction != null }
        // CompactionStarted 先落盘、之后才发请求：等请求真的挂在旧模型上再杀
        while (bigClient.summaries.isEmpty()) kotlinx.coroutines.delay(10)
        assertEquals("摘要请求挂在切换前的模型上", 1, bigClient.summaries.size)
        killable.kill()
        scope.cancel()

        val bigAfter = client()
        val reopened = SessionHub(store, deps(mapOf(big to bigAfter, small to smallClient)), newScope()).open(rt.sessionId)!!
        assertTrue("恢复补了一条压缩失败", events(reopened).any { it is CompactionFailed })
        assertNull(reopened.state.value.openCompaction)
        assertEquals("撑着这段对话的是谁，只凭日志就知道", big, reopened.state.value.lastReplyModel)
        reopened.say("继续")
        assertEquals("重开后仍由切换前的模型做这次摘要", big, bigAfter.summaries.single().profile.ref)
        assertTrue(smallClient.summaries.isEmpty())
        assertFalse(events(reopened).filterIsInstance<ContextCompacted>().isEmpty())
    }
}

private fun ModelRequest.isSummary() = messages.lastOrNull()?.parts?.any { it is TextPart && it.text.startsWith("上下文快满了") } == true
