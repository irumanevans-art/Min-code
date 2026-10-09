package dev.min.agent.kernel

import dev.min.agent.llm.ModelPricing
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Clock
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.testDeps
import dev.min.agent.llm.ModelStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong

/**
 * 「从摘要续接」：重开会话时按空闲时长与上下文大小给建议（照 CC：> 1 小时且 > 100,000 token），选摘要就跑一次现有的压缩。
 * 时钟由测试拨，会话先关、过一段时间再重开。
 */
class ResumeAdviceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { now.get() }) }
    private val now = AtomicLong(1_000)
    private var counter = 0

    /** 重开会话会新建 deps：id 计数器得跨 deps 共用，否则新回合又叫 turn_1，和旧的撞了（真宿主用随机 id） */
    private val ids = AtomicLong(0)
    private var sessions = 0
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private val hour = 60 * 60 * 1000L

    /** 回复里报告的输入用量就是请求的估算值——像真的供应商那样，让「上次请求的用量」有意义 */
    private fun answer(text: String): Step = { req ->
        flowOf(
            ModelStreamEvent.Started(null),
            ModelStreamEvent.Completed(
                ModelMessage(Role.Assistant, listOf(TextPart(text))),
                Usage(inputTokens = Tokens.of(req.messages), outputTokens = 10),
                StopReason.EndTurn, false,
            ),
        )
    }

    private fun deps(model: ScriptedModel, pricing: ModelPricing? = null, thresholds: ResumeThresholds = ResumeThresholds()): KernelDeps {
        val base = testDeps(model, emptyList(), FakeArtifacts())
        return KernelDeps(
            clock = Clock { now.get() }, ids = dev.min.agent.spi.IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            // 窗口开大：这里测的是续接建议，不该被「上下文超过窗口」的自动压缩抢了脚本
            models = { ResolvedModel(TEST_PROFILE.copy(pricing = pricing, contextWindow = 1_000_000, maxOutputTokens = 8_000), model) },
            tools = emptyList(), host = base.host, platform = "test", zone = base.zone, retry = base.retry,
            resumeThresholds = thresholds,
        )
    }

    private suspend fun fresh(model: ScriptedModel, pricing: ModelPricing? = null, thresholds: ResumeThresholds = ResumeThresholds()) =
        SessionRuntime.create(
            store, LogHeader(LOG_FORMAT_VERSION, SessionId("resume-${++sessions}"), createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(model, pricing, thresholds), newScope(),
        )

    private suspend fun reopen(rt: SessionRuntime, model: ScriptedModel, pricing: ModelPricing? = null, thresholds: ResumeThresholds = ResumeThresholds()): SessionRuntime =
        checkNotNull(SessionRuntime.open(store, rt.sessionId, deps(model, pricing, thresholds), newScope()))

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    /** 一个已经聊到 [tokens] 上下文的会话，在 [idleMs] 之后重开 */
    private suspend fun bigSessionReopened(
        tokens: Int,
        idleMs: Long,
        pricing: ModelPricing? = null,
        thresholds: ResumeThresholds = ResumeThresholds(),
        followUp: ScriptedModel = ScriptedModel(answer("摘要正文")),
    ): SessionRuntime {
        val first = fresh(ScriptedModel(answer("好")), pricing, thresholds)
        first.say("字".repeat(tokens))
        now.addAndGet(idleMs)
        return reopen(first, followUp, pricing, thresholds)
    }

    @Test
    fun `advice needs both a long idle and a big context`() = run {
        assertNotNull("两个条件都满足", bigSessionReopened(110_000, 2 * hour).resumeAdvice())
        assertNull("隔得久但对话不大", bigSessionReopened(1_000, 5 * hour).resumeAdvice())
        assertNull("对话很大但刚离开", bigSessionReopened(110_000, 30 * 60 * 1000L).resumeAdvice())
    }

    @Test
    fun `thresholds are strictly greater and come from KernelDeps`() = run {
        val small = ResumeThresholds(idleMs = 1_000, contextTokens = 500)
        assertNull("空闲正好等于阈值不算", bigSessionReopened(2_000, 1_000, thresholds = small).resumeAdvice())
        assertNotNull(bigSessionReopened(2_000, 1_001, thresholds = small).resumeAdvice())
        // token 也是严格大于：把阈值设成估算值本身
        val probe = bigSessionReopened(2_000, 5_000, thresholds = small)
        val tokens = probe.resumeAdvice()!!.contextTokens
        val exact = ResumeThresholds(idleMs = 1_000, contextTokens = tokens)
        assertNull(bigSessionReopened(2_000, 5_000, thresholds = exact).resumeAdvice())
    }

    @Test
    fun `the advice reports idle time, tokens and an estimated reread cost only when a price is known`() = run {
        val pricing = ModelPricing(currency = "USD", inputPerMTok = 3.0, outputPerMTok = 15.0, cacheWritePerMTok = 3.75, cacheReadPerMTok = 0.3, asOf = "2026-10-01")
        val priced = bigSessionReopened(110_000, 2 * hour, pricing).resumeAdvice()!!
        assertTrue(priced.idleMs >= 2 * hour)
        assertTrue(priced.contextTokens > 100_000)
        val cost = priced.rereadCost!!
        assertEquals("USD", cost.currency)
        assertEquals("按缓存写价估", priced.contextTokens / 1_000_000.0 * 3.75, cost.amount, 1e-9)

        assertNull("没有价格就不报花费", bigSessionReopened(110_000, 2 * hour).resumeAdvice()!!.rereadCost)
    }

    @Test
    fun `only a reopened and still untouched session gets advice`() = run {
        val created = fresh(ScriptedModel(answer("好"), answer("好")))
        created.say("字".repeat(110_000))
        now.addAndGet(3 * hour)
        assertNull("新建的会话没有重开基线", created.resumeAdvice())

        val reopened = bigSessionReopened(110_000, 3 * hour, followUp = ScriptedModel(answer("接着答")))
        assertNotNull(reopened.resumeAdvice())
        reopened.say("继续")
        assertNull("有了新回合，建议就过期了", reopened.resumeAdvice())
    }

    @Test
    fun `recovery events written on reopen do not reset the idle clock`() = run {
        // 进程在回合中被杀：重开会写一条补上的收尾事件，它的时间是「现在」，不能算成用户刚来过
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val hang: Step = { kotlinx.coroutines.flow.flow { gate.await() } }
        val killable = dev.min.agent.testkit.KillableStore(store)
        val killed = SessionRuntime.create(
            killable, LogHeader(LOG_FORMAT_VERSION, SessionId("resume-killed"), createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(ScriptedModel(answer("好"), hang)), newScope(),
        )
        killed.say("字".repeat(110_000))
        killed.send(ClientMsgId("u${++counter}"), listOf(TextPart("再来")))
        killed.state.first { it.turn?.openRequest != null }
        killable.kill()
        now.addAndGet(3 * hour)
        val reopened = reopen(killed, ScriptedModel(answer("摘要")))
        assertTrue(reopened.state.value.lastTurn != null)
        val advice = reopened.resumeAdvice()
        assertNotNull(advice)
        assertTrue("空闲从被杀前最后一条事件算起", advice!!.idleMs >= 3 * hour)
        gate.complete(Unit)
    }

    @Test
    fun `choosing the summary compacts through the existing path and the log agrees`() = run {
        val followUp = ScriptedModel(answer("摘要正文：目标、进度、下一步"), answer("接着做"))
        val rt = bigSessionReopened(110_000, 2 * hour, followUp = followUp)
        assertNotNull(rt.resumeAdvice())

        assertTrue(rt.continueFromSummary())

        val state = rt.state.value
        assertNotNull(state.compaction)
        assertEquals(TurnTrigger.Compact, state.lastTurn?.trigger)
        val compacted = events(rt).filterIsInstance<ContextCompacted>().single()
        assertEquals(state.compaction?.summary, compacted.summary)
        assertTrue("摘要请求发给了模型", followUp.requests.first().messages.last().parts.any { it is TextPart && it.text.startsWith("上下文快满了") })
        assertNull("压缩之后没有再要建议的理由", rt.resumeAdvice())
        assertLogIsTruth(rt)
        // 压缩后继续对话：请求里是摘要而不是那 11 万字
        rt.say("继续")
        val sent = followUp.requests.last().messages.flatMap { it.parts }.filterIsInstance<TextPart>()
        assertTrue(sent.any { it.text.contains("摘要正文") })
        assertTrue("原文不再发出去", sent.none { it.text.length > 100_000 })
    }

    @Test
    fun `a full resume leaves the log alone`() = run {
        val rt = bigSessionReopened(110_000, 2 * hour)
        val seq = rt.state.value.lastSeq
        assertNotNull(rt.resumeAdvice())
        // 选「完整续接」就是什么都不做
        assertEquals(seq, rt.state.value.lastSeq)
        assertFalse(events(rt).any { it is ContextCompacted })
    }

    @Test
    fun `summary resume that cannot compact reports false and changes nothing`() = run {
        // 压缩请求失败（模型报错）：续接照常，状态里没有摘要
        val failing: Step = { kotlinx.coroutines.flow.flow { throw dev.min.agent.llm.ModelException(dev.min.agent.spi.ModelFailureKind.Auth, "401", beforeFirstByte = true) } }
        val rt = bigSessionReopened(110_000, 2 * hour, followUp = ScriptedModel(failing))
        assertFalse(rt.continueFromSummary())
        assertNull(rt.state.value.compaction)
        assertLogIsTruth(rt)
    }
}
