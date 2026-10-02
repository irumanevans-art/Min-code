package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.CompactionFailed
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutputsMasked
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上下文压缩（docs/agent/detail/压缩设计.md）。用一个窗口很小的模型（2 万 token）让阈值真的被碰到：
 * 遮蔽线 ≈ 12.3k、摘要线 ≈ 15k、保护最近 4.4k、攒批门槛 1.76k。
 */
class CompactionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val small = TEST_PROFILE.copy(contextWindow = 20_000, maxOutputTokens = 2_000)
    private val budget = CompactionBudget.of(20_000, 2_000)
    private var counter = 0

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 回复里报告的输入用量就是请求的估算值——像真的供应商那样，让「上次请求的用量」有意义 */
    private fun answer(vararg parts: dev.min.agent.protocol.Part, stop: StopReason = StopReason.EndTurn): Step = { req ->
        flowOf(
            ModelStreamEvent.Started(null),
            ModelStreamEvent.Completed(
                ModelMessage(Role.Assistant, parts.toList()),
                Usage(inputTokens = Tokens.of(req.messages), outputTokens = 10),
                stop,
                false,
            ),
        )
    }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.isSummary() = messages.lastOrNull()?.parts?.any { it is TextPart && it.text.startsWith("上下文快满了") } == true

    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private val artifacts = FakeArtifacts()

    private fun deps(model: ModelClient, tools: List<dev.min.agent.spi.Tool<*>> = emptyList()) = testDeps(model, tools, artifacts).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids,
            models = { ResolvedModel(small, model) },
            tools = tools, host = base.host, platform = "test", zone = base.zone, retry = base.retry,
        )
    }

    private suspend fun session(model: ModelClient, tools: List<dev.min.agent.spi.Tool<*>> = emptyList(), s: dev.min.agent.spi.SessionStore = store) =
        SessionHub(s, deps(model, tools), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    // ---- 纯函数 ----

    @Test
    fun `token estimate counts wide characters one by one and the rest by four`() {
        assertEquals(3, Tokens.of("你好吗"))
        assertEquals(2, Tokens.of("abcdefgh"))
        assertEquals(1, Tokens.of("a"))
        assertEquals(1600, Tokens.of(dev.min.agent.protocol.ImagePart("image/png", dev.min.agent.protocol.ImageSource.Base64(""))))
    }

    @Test
    fun `budget lines follow the window minus output and margin`() {
        assertEquals(17_600, budget.usable)
        assertEquals(12_320, budget.maskLine)
        assertEquals(14_960, budget.summaryLine)
        assertEquals(4_400, budget.protectRecent)
        assertEquals(1_760, budget.minMaskBatch)
    }

    // ---- 遮蔽 ----

    @Test
    fun `old large tool outputs are masked in one batch while recent ones stay`() = run {
        val big = "x".repeat(8_000)
        val read = FakeTool("Read", READ_ONLY) { ToolResult(listOf(TextPart(big))) }
        val rounds = AtomicInteger(0)
        val model = RoutingModel { req ->
            if (rounds.incrementAndGet() <= 7) answer(ToolCallPart(CallId("r${rounds.get()}"), "Read", args("n" to rounds.get())), stop = StopReason.ToolUse)
            else answer(TextPart("读完了"))
        }
        val rt = session(model, listOf(read))
        val done = rt.say("把七个文件读一遍")

        val masked = events(rt).filterIsInstance<ToolOutputsMasked>()
        assertEquals("攒成一批遮一次", 1, masked.size)
        val ids = masked.single().outputs.map { it.callId }
        assertTrue("最新的结果不遮", CallId("r7") !in ids && CallId("r6") !in ids)
        assertTrue(CallId("r1") in ids)
        // 原文存成附件，占位里给路径；history 与工具结果一字不动
        ids.forEach { id -> assertEquals(big, artifacts.files.getValue("compactions/${id.value}.txt").decodeToString()) }
        assertEquals(big, (done.toolResults.getValue(CallId("r1")).content.single() as TextPart).text)
        val stub = model.requests.last().messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>().first { it.callId == CallId("r1") }
        assertTrue((stub.content.single() as TextPart).text.contains("artifacts/compactions/r1.txt"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `masking waits until a batch is worth rewriting the prefix`() {
        val state = stateWith(listOf("x".repeat(2_100), "x".repeat(20_000)))
        // 只有一条小一点的可遮，省不到攒批门槛，又没过摘要线：不动
        assertEquals(CompactionPlan.None, CompactionPlanner.plan(state, budget, budget.maskLine + 10))
    }

    // ---- 摘要 ----

    @Test
    fun `auto summary reuses the cached prefix and replaces older turns`() = run {
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n用户发了两段长文。"))
                else -> answer(TextPart("好${req.messages.size}"))
            }
        }
        val rt = session(model)
        rt.say("A".repeat(34_000))
        val after = rt.say("B".repeat(34_000))

        val summaryRequest = model.requests.single { it.isSummary() }
        val normalAfter = model.requests.last()
        assertEquals("摘要请求的前缀与正常请求相同（命中缓存）", prefixFingerprint(summaryRequest), prefixFingerprint(normalAfter))
        assertEquals(summaryRequest.cache, normalAfter.cache)
        assertEquals("摘要请求 = 原有对话 + 一条指令", 4, summaryRequest.messages.size)

        val compacted = events(rt).filterIsInstance<ContextCompacted>().single()
        assertEquals(CompactionTrigger.Auto, compacted.trigger)
        val firstAssistant = after.history.filterIsInstance<HistoryEntry.Assistant>().first()
        assertEquals("边界在最后一条人发的消息之前", firstAssistant.messageId, compacted.through)
        val first = (normalAfter.messages.first().parts.single() as TextPart).text
        assertTrue(first.startsWith("[较早的对话已压缩"))
        assertTrue(first.contains("用户发了两段长文"))
        assertTrue(first.contains(compacted.transcriptPath!!))
        assertEquals("B".repeat(34_000), (normalAfter.messages[1].parts.single() as TextPart).text)
        assertTrue(artifacts.files.getValue("compactions/${compacted.compactionId}.md").decodeToString().contains("## 用户"))
        assertEquals("history 一字不动", 4, after.history.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the summary carries the current task list`() = run {
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n做两件事。"))
                req.last().startsWith("A") -> answer(
                    ToolCallPart(CallId("t1"), "TaskCreate", args("subject" to "迁移数据库", "description" to "把表搬到新库")),
                    stop = StopReason.ToolUse,
                )
                else -> answer(TextPart("好"))
            }
        }
        val rt = session(model, listOf(dev.min.agent.tools.TaskCreateTool))
        rt.say("A".repeat(34_000))
        rt.say("B".repeat(34_000))
        val summary = events(rt).filterIsInstance<ContextCompacted>().single().summary
        assertTrue(summary, summary.contains("- #1 [Pending] 迁移数据库"))
    }

    @Test
    fun `the summary lists files changed by write tools from the log, not from memory`() = run {
        val write = FakeTool("Write", WRITES) { input ->
            val path = (input["path"] as kotlinx.serialization.json.JsonPrimitive).content
            ToolResult(listOf(TextPart("写好了")), touched = listOf(dev.min.agent.spi.FileStamp(GuestPath(path), 1, 1)))
        }
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n改两个文件。"))
                req.last().startsWith("A") -> answer(
                    ToolCallPart(CallId("w1"), "Write", args("path" to "/workspace/a.kt")),
                    ToolCallPart(CallId("w2"), "Write", args("path" to "/workspace/b.kt")),
                    stop = StopReason.ToolUse,
                )
                else -> answer(TextPart("好"))
            }
        }
        val rt = session(model, listOf(write))
        rt.say("A".repeat(34_000))
        rt.say("B".repeat(34_000))
        val summary = events(rt).filterIsInstance<ContextCompacted>().single().summary
        assertTrue(summary.contains("- /workspace/a.kt（Write）"))
        assertTrue(summary.contains("- /workspace/b.kt（Write）"))
    }

    @Test
    fun `an overflow is compacted once and retried once`() = run {
        val overflowed = AtomicInteger(0)
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n摘要。"))
                req.last() == "第二句" && overflowed.getAndIncrement() == 0 ->
                    failing(ModelException(ModelFailureKind.ContextOverflow, "prompt is too long", httpStatus = 400, beforeFirstByte = true))
                else -> answer(TextPart("好"))
            }
        }
        val rt = session(model)
        rt.say("第一句")
        val done = rt.say("第二句")
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue(events(rt).filterIsInstance<ModelRequestFailed>().single().willRetry)
        assertEquals(CompactionTrigger.Overflow, events(rt).filterIsInstance<ContextCompacted>().single().trigger)
    }

    @Test
    fun `a second overflow ends the turn instead of looping`() = run {
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n摘要。"))
                req.last() == "第一句" -> answer(TextPart("好"))
                else -> failing(ModelException(ModelFailureKind.ContextOverflow, "prompt is too long", httpStatus = 400, beforeFirstByte = true))
            }
        }
        val rt = session(model)
        rt.say("第一句")
        val done = rt.say("第二句")
        val outcome = done.lastTurn!!.outcome as TurnOutcome.Failed
        assertEquals(ModelFailureKind.ContextOverflow, outcome.kind)
        assertEquals("只压缩了一次", 1, model.requests.count { it.isSummary() })
    }

    @Test
    fun `manual compact runs as its own turn with the user's focus`() = run {
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("## 目标\n摘要。")) else answer(TextPart("好")) }
        val rt = session(model)
        rt.say("第一句")
        assertTrue(rt.compact("侧重数据库迁移"))
        val done = rt.state.first { it.lastTurn?.trigger == TurnTrigger.Compact }
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue(model.requests.single { it.isSummary() }.last().contains("用户希望摘要侧重：侧重数据库迁移"))
        assertEquals("侧重数据库迁移", events(rt).filterIsInstance<CompactionStarted>().single().instructions)
        assertEquals(CompactionTrigger.Manual, events(rt).filterIsInstance<ContextCompacted>().single().trigger)
        assertNotNull(done.compaction)
        assertLogIsTruth(rt)
    }

    @Test
    fun `failed summaries are recorded and three in a row stop automatic summarizing`() = run {
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("   ")) else answer(TextPart("好")) }
        val rt = session(model)
        rt.say("第一句")
        repeat(3) {
            assertTrue(rt.compact())
            rt.state.first { s -> s.turn == null && s.compactionFailures == it + 1 }
        }
        assertTrue(events(rt).filterIsInstance<CompactionFailed>().all { it.reason == "模型没有给出摘要" })
        val summariesBefore = model.requests.count { it.isSummary() }
        rt.say("A".repeat(34_000))
        rt.say("B".repeat(34_000))
        assertEquals("熔断后不再自动摘要", summariesBefore, model.requests.count { it.isSummary() })
    }

    @Test
    fun `a compaction killed midway is closed on reopen and the view stays valid`() = run {
        val model = RoutingModel { req -> if (req.isSummary()) { _ -> flow { awaitCancellation() } } else answer(TextPart("好")) }
        val killable = KillableStore(store)
        val rt = session(model, s = killable)
        rt.say("第一句")
        assertTrue(rt.compact())
        rt.state.first { it.openCompaction != null }
        killable.kill()
        scopes.first().cancel()

        val reopened = SessionHub(store, deps(model), newScope()).open(rt.sessionId)!!
        val log = events(reopened)
        assertTrue(log.dropLast(1).last() is CompactionFailed)
        assertTrue(log.last() is TurnEnded)
        assertNull(reopened.state.value.openCompaction)
        assertNull(reopened.state.value.compaction)
        assertEquals(2, reopened.state.value.modelMessages().size)
    }

    // ---- 边界 ----

    @Test
    fun `the summary boundary never splits a tool call from its result`() {
        val state = stateWith(listOf("x".repeat(100)))
        val through = CompactionPlanner.summarizeBoundary(state)!!
        val entry = state.history.single { it.messageId == through }
        assertTrue(entry is HistoryEntry.Assistant)
    }

    /** 一个用户消息后跟着若干次带工具调用的助手消息（每次一个结果），最后一条助手纯文字 */
    private fun stateWith(outputs: List<String>): SessionState {
        val base = fold(
            dev.min.agent.spi.LogHeader(1, dev.min.agent.protocol.SessionId("s"), 0),
            listOf(dev.min.agent.spi.EventRecord(1, 0, dev.min.agent.spi.SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default))),
        )
        val history = mutableListOf<HistoryEntry>(HistoryEntry.User(MessageId("u0"), listOf(TextPart("开始"))))
        val results = mutableMapOf<CallId, ToolResult>()
        outputs.forEachIndexed { i, out ->
            history += HistoryEntry.Assistant(MessageId("a$i"), listOf(ToolCallPart(CallId("c$i"), "Read", args())))
            results[CallId("c$i")] = ToolResult(listOf(TextPart(out)))
        }
        history += HistoryEntry.Assistant(MessageId("end"), listOf(TextPart("完")))
        return base.copy(history = history, toolResults = results)
    }
}
