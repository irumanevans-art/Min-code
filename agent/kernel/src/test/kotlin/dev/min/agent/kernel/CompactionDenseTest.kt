package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolOutputsMasked
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 密集文本、图片 / PDF 与撞墙之后的压缩（CC 2.1.307、2.1.310）：
 * base64 / SVG 这类文本按四字符一 token 会估少；撞墙压缩后留下的尾巴本身放不下时要遮蔽、再缩；
 * 只有图片或 PDF 的工具结果也要能遮；压缩腾不出空间时不在每个请求上空转。
 * 小窗口（2 万）下：遮蔽线 12,320、摘要线 14,960、保护最近 4,400。
 */
class CompactionDenseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val small = TEST_PROFILE.copy(contextWindow = 20_000, maxOutputTokens = 2_000)
    private val budget = CompactionBudget.of(20_000, 2_000)
    private val artifacts = FakeArtifacts()
    private var counter = 0

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    // ---- 估算 ----

    @Test
    fun `dense text like base64 is estimated well above one token per four characters`() {
        val bytes = ByteArray(30_000).also { java.util.Random(7).nextBytes(it) }
        val base64 = java.util.Base64.getMimeEncoder().encodeToString(bytes)
        // 真实分词器上 base64 大约两三个字符一个 token：至少按 2.5 个字符一个算（每 76 字符一个换行，换行本身不算）
        val payload = base64.count { !it.isWhitespace() }
        assertTrue("base64 估了 ${Tokens.of(base64)}", Tokens.of(base64) >= payload * 2 / 5)
        val svg = "<path d=\"" + (1..2_000).joinToString("") { "L${it}.5,${it * 3}.25" } + "\"/>"
        assertTrue("SVG 路径估了 ${Tokens.of(svg)}", Tokens.of(svg) >= svg.length * 2 / 5)
        val hex = (1..3_000).joinToString("") { "%08x".format(it * 2_654_435_761L and 0xffffffffL) }
        assertTrue("hex 估了 ${Tokens.of(hex)}", Tokens.of(hex) >= hex.length * 2 / 5)
    }

    @Test
    fun `ordinary prose, code and repeated characters keep the four characters per token estimate`() {
        val prose = "The quick brown fox jumps over the lazy dog. ".repeat(200)
        assertEquals((prose.length + 3L) / 4, Tokens.of(prose))
        val code = "    val result = repository.findAll().filter { it.isActive }\n".repeat(100)
        assertEquals((code.length + 3L) / 4, Tokens.of(code))
        assertEquals(8_500, Tokens.of("A".repeat(34_000)))
        assertEquals(3 + 2, Tokens.of("你好吗abcdefgh"))
        val path = "/workspace/app/src/main/kotlin/dev/min/code/ui/components/InkButton.kt\n".repeat(50)
        assertEquals((path.length + 3L) / 4, Tokens.of(path))
    }

    // ---- 图片 / PDF 也是遮蔽候选 ----

    @Test
    fun `image or document only tool results are mask candidates`() {
        val image = listOf<Part>(ImagePart("image/png", ImageSource.Base64("aGk=")))
        val pdf = listOf<Part>(DocumentPart("application/pdf", ImageSource.Base64("aGk=")))
        // 从新往旧：最新三张图（1,600 × 3 越过 4,400 的保护线前）受保护，更早的三个是候选
        val state = stateWith(listOf(pdf, image, image, image, image, image))
        val ids = CompactionPlanner.maskCandidates(state, budget).map { it.first }
        assertEquals(listOf(CallId("c0"), CallId("c1"), CallId("c2")), ids)
    }

    // ---- 撞墙压缩的切法 ----

    @Test
    fun `an overflow cut masks large tool results in the kept tail when the tail does not fit`() {
        val state = humanTurns(firstUser = "word ".repeat(10_000), secondUser = "读文件", result = "word ".repeat(8_000))
        val cut = CompactionPlanner.overflowCut(state, budget, fixedTokens = 0)!!
        assertEquals("用户当前的要求原样留着", MessageId("a0"), cut.through)
        assertEquals(listOf(CallId("c1")), cut.mask)
    }

    @Test
    fun `an overflow cut leaves the tail alone when it fits`() {
        val state = humanTurns(firstUser = "word ".repeat(10_000), secondUser = "读文件", result = "word ".repeat(400))
        val cut = CompactionPlanner.overflowCut(state, budget, fixedTokens = 0)!!
        assertEquals(MessageId("a0"), cut.through)
        assertEquals(emptyList<CallId>(), cut.mask)
    }

    @Test
    fun `an overflow cut shrinks the tail to after the last assistant message when masking cannot make it fit`() {
        val state = humanTurns(firstUser = "word ".repeat(10_000), secondUser = "word ".repeat(8_000), result = "ok")
        val cut = CompactionPlanner.overflowCut(state, budget, fixedTokens = 0)!!
        assertEquals(MessageId("a1"), cut.through)
        assertEquals(emptyList<CallId>(), cut.mask)
    }

    // ---- 端到端 ----

    @Test
    fun `after an overflow the retried request carries the large tail result masked, so it does not overflow again`() = run {
        val read = FakeTool("Read", READ_ONLY) { ToolResult(listOf(TextPart("word ".repeat(8_000)))) }
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> lowUsage(TextPart("## 目标\n读文件。"))
                // 供应商的真实分词比估算重：只要请求里还有 r1 的原文就超长
                req.messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>().any { r ->
                    r.callId == CallId("r1") && r.content.filterIsInstance<TextPart>().sumOf { it.text.length } > 1_000
                } -> failing(ModelException(ModelFailureKind.ContextOverflow, "prompt is too long", httpStatus = 400, beforeFirstByte = true))
                req.last() == "读文件" -> lowUsage(ToolCallPart(CallId("r1"), "Read", args()), stop = StopReason.ToolUse)
                req.last().startsWith("word") && req.messages.size == 1 -> lowUsage(TextPart("好"))
                else -> lowUsage(TextPart("读完了"))
            }
        }
        val rt = session(model, listOf(read))
        rt.say("word ".repeat(12_000))
        val done = rt.say("读文件")

        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        val log = events(rt)
        assertEquals(CompactionTrigger.Overflow, log.filterIsInstance<ContextCompacted>().single().trigger)
        assertTrue("尾巴里的大结果被遮蔽", log.filterIsInstance<ToolOutputsMasked>().flatMap { it.outputs }.any { it.callId == CallId("r1") })
        val retried = model.requests.last()
        assertTrue("用户当前的要求原样留着", retried.messages.any { m -> m.parts.any { it is TextPart && it.text == "读文件" } })
    }

    @Test
    fun `compaction that frees too little stops after three tries with a clear warning instead of running on every request`() = run {
        // 工具表本身就超过摘要线：每次摘要后估算仍在线上，旧逻辑每个请求都摘要一次
        val read = BigTool(FakeTool("Read", READ_ONLY) { ToolResult(listOf(TextPart("结果".repeat(50)))) }, "word ".repeat(14_000))
        val calls = AtomicInteger(0)
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> lowUsage(TextPart("## 目标\n读。"))
                // 供应商报告的输入用量与工具表一样大：每次请求前的估算都在摘要线上
                calls.incrementAndGet() <= 7 -> lowUsage(ToolCallPart(CallId("r${calls.get()}"), "Read", args("n" to calls.get())), stop = StopReason.ToolUse, input = 18_000)
                else -> lowUsage(TextPart("读完了"), input = 18_000)
            }
        }
        val rt = session(model, listOf(read))
        val done = rt.say("读七次")

        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals("连续三次腾不出空间就不再自动摘要", 3, model.requests.count { it.isSummary() })
        val warnings = events(rt).filterIsInstance<Note>().filter { it.level == NoteLevel.Warning && it.text.contains("自动压缩") }
        assertEquals(1, warnings.size)
    }

    // ---- 工具 ----

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    /** 工具描述放大到指定长度：让请求的固定部分（system + 工具表）本身就很大 */
    private class BigTool(private val inner: FakeTool, private val text: String) : Tool<JsonObject> by inner {
        override fun description(profile: HarnessProfile) = text
    }

    /** 回复报告很小的输入用量：下一次请求的估算以它为基准，自动压缩就不会抢在撞墙之前 */
    private fun lowUsage(vararg parts: Part, stop: StopReason = StopReason.EndTurn, input: Long = 10): Step = {
        flowOf(
            ModelStreamEvent.Started(null),
            ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts.toList()), Usage(inputTokens = input, outputTokens = 10), stop, false),
        )
    }

    private fun ModelRequest.isSummary() = messages.lastOrNull()?.parts?.any { it is TextPart && it.text.startsWith("上下文快满了") } == true

    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools, artifacts).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids,
            models = { ResolvedModel(small, model) },
            tools = tools, host = base.host, platform = "test", zone = base.zone, retry = base.retry,
        )
    }

    private suspend fun session(model: ModelClient, tools: List<Tool<*>>) =
        SessionHub(store, deps(model, tools), newScope()).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private fun baseState(): SessionState = fold(
        dev.min.agent.spi.LogHeader(1, dev.min.agent.protocol.SessionId("s"), 0),
        listOf(dev.min.agent.spi.EventRecord(1, 0, dev.min.agent.spi.SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.Default))),
    )

    /** 一个用户消息后跟着若干次带工具调用的助手消息（每次一个结果），最后一条助手纯文字 */
    private fun stateWith(outputs: List<List<Part>>): SessionState {
        val history = mutableListOf<HistoryEntry>(HistoryEntry.User(MessageId("u0"), listOf(TextPart("开始"))))
        val results = mutableMapOf<CallId, ToolResult>()
        outputs.forEachIndexed { i, out ->
            history += HistoryEntry.Assistant(MessageId("a$i"), listOf(ToolCallPart(CallId("c$i"), "Read", args())))
            results[CallId("c$i")] = ToolResult(out)
        }
        history += HistoryEntry.Assistant(MessageId("end"), listOf(TextPart("完")))
        return baseState().copy(history = history, toolResults = results)
    }

    /** 撞墙时的样子：人说一句、助手答；人再说一句、助手调一次工具（结果 [result]），这次请求超长 */
    private fun humanTurns(firstUser: String, secondUser: String, result: String): SessionState {
        val history = listOf(
            HistoryEntry.User(MessageId("u0"), listOf(TextPart(firstUser))),
            HistoryEntry.Assistant(MessageId("a0"), listOf(TextPart("好"))),
            HistoryEntry.User(MessageId("u1"), listOf(TextPart(secondUser))),
            HistoryEntry.Assistant(MessageId("a1"), listOf(ToolCallPart(CallId("c1"), "Read", args()))),
        )
        return baseState().copy(history = history, toolResults = mapOf(CallId("c1") to ToolResult(listOf(TextPart(result)))))
    }
}
