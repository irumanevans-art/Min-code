package dev.min.agent.kernel

import dev.min.agent.testkit.*
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ForkSeeded
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.MaskedOutput
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolOutputsMasked
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** 用真实日志验证遮蔽种子，单独放以免继续扩张 SubagentTest。 */
class ForkMaskTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private var counter = 0

    @After fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }
    private fun text(value: String) = listOf(TextPart(value))
    private fun msg() = ClientMsgId("u${++counter}")
    private fun agentCall(id: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(id), "Agent", args(*pairs))
    private fun hub(model: ModelClient, tools: List<Tool<*>>) = SessionHub(store, testDeps(model, tools), scope)
    private suspend fun parentOf(h: SessionHub) = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
    private suspend fun events(id: SessionId) = store.peek(id)!!.read().toList().map { it.event }
    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.peek(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { part ->
        when (part) {
            is TextPart -> part.text
            is ToolResultPart -> part.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private class PortTool(override val name: String, private val body: suspend (JsonObject, ToolContext) -> ToolResult) : Tool<JsonObject> {
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 $name"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body(input, ctx))) }
    }

    @Test
    fun `a masked fork keeps the exact request prefix without a summary`() = maskedFork()

    @Test
    fun `a masked then compacted fork inherits only masks after the summary boundary`() = maskedFork(compact = true)

    @Test
    fun `a fork never masks synthetic delegation or sibling results`() = maskedFork(sibling = true)

    private fun maskedFork(compact: Boolean = false, sibling: Boolean = false) = run {
        lateinit var parent: SessionRuntime
        val probe = PortTool("Probe") { _, _ -> ToolResult(text("原始大块输出".repeat(300))) }
        val delegate = PortTool("Agent") { _, ctx ->
            if (sibling) {
                parent.commit(listOf(ToolOutputsMasked(parent.state.value.turn!!.turnId, listOf(
                    MaskedOutput(CallId("c1"), "/parent/delegate.txt"),
                    MaskedOutput(CallId("sibling"), "/parent/sibling.txt"),
                ))), Durability.Sync)
            }
            when (val result = ctx.agents.delegate(DelegateRequest(
                description = "检查", prompt = "检查", agentType = "fork", background = false,
            ))) {
                is DelegateResult.Finished -> ToolResult(text(result.report), isError = result.isError)
                else -> error("前台分身应交回报告：$result")
            }
        }
        val model = RoutingModel { req ->
            when (val last = req.last()) {
                "old", "kept" -> reply(ToolCallPart(CallId(last), "Probe", args()), stop = StopReason.ToolUse)
                "去分身" -> reply(
                    *buildList {
                        add(agentCall("c1", "subagent_type" to "fork", "prompt" to "检查", "run_in_background" to false))
                        if (sibling) add(ToolCallPart(CallId("sibling"), "Probe", args()))
                    }.toTypedArray(),
                    stop = StopReason.ToolUse,
                )
                else -> if ("You are a fork" in last) reply(TextPart("分身完成")) else reply(TextPart("好"))
            }
        }
        val h = hub(model, listOf(delegate, probe))
        parent = parentOf(h)
        suspend fun say(value: String) {
            val before = parent.state.value.lastTurn?.turnId
            parent.send(msg(), text(value))
            parent.state.first { it.turn == null && it.lastTurn != null && it.lastTurn.turnId != before }
        }
        say("old")
        val boundary = parent.state.value.history.last().messageId
        say("kept")
        val masks = listOf(MaskedOutput(CallId("old"), null), MaskedOutput(CallId("kept"), "/parent/compactions/kept.txt"))
        val turn = TurnId("mask-turn")
        // 直接落盘压缩事件，避免把继承边界测试绑在自动压缩的 token 阈值上。
        parent.commit(buildList {
            add(TurnStarted(turn, TurnTrigger.Compact))
            add(ToolOutputsMasked(turn, masks))
            if (compact) {
                add(CompactionStarted(turn, "cmp", CompactionTrigger.Manual))
                add(ContextCompacted(turn, "cmp", CompactionTrigger.Manual, "较早的对话摘要", boundary))
            }
            add(TurnEnded(turn, TurnOutcome.Completed, dev.min.agent.protocol.Usage(), 0))
        }, Durability.Sync)
        say("去分身")
        val child = h.get(parent.state.value.children.keys.single())!!
        val fork = model.requests.single { "You are a fork" in it.last() }
        val parentAfter = model.requests.last()
        assertEquals(parentAfter.system, fork.system)
        assertEquals(parentAfter.tools, fork.tools)
        assertEquals(parentAfter.cache, fork.cache)
        assertEquals("到委派消息为止的请求逐字相同", parentAfter.messages.dropLast(1), fork.messages.dropLast(1))
        val results = fork.messages.flatMap { it.parts }.filterIsInstance<ToolResultPart>()
        val kept = results.single { it.callId == CallId("kept") }
        assertTrue(kept.content.toString(), kept.content.toString().contains("/parent/compactions/kept.txt"))
        val seed = events(child.sessionId).filterIsInstance<ForkSeeded>().single()
        assertEquals(if (compact) masks.drop(1) else masks, seed.masked)
        assertEquals(seed.masked.associate { it.callId to it.path }, child.state.value.masked)
        assertTrue(events(child.sessionId).none { it is ToolOutputsMasked })
        if (compact) {
            assertTrue(results.none { it.callId == CallId("old") })
            assertEquals("较早的对话摘要", child.state.value.compaction!!.summary)
        } else {
            assertNull(child.state.value.compaction)
            assertFalse(results.single { it.callId == CallId("old") }.content.toString().contains("原始大块输出"))
        }
        if (sibling) {
            assertEquals(text(SIBLING_CALL_RESULT), results.single { it.callId == CallId("sibling") }.content)
            assertEquals(text(forkDirective("检查")), results.single { it.callId == CallId("c1") }.content)
        }
        assertLogIsTruth(parent)
        assertLogIsTruth(child)
    }

}
