package dev.min.agent.kernel

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.last
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdvisorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val advisor = ModelRef("other", "oracle")
    private val price = ModelPricing(currency = "USD", inputPerMTok = 1.0, outputPerMTok = 2.0, asOf = "test")

    @Test fun `settings freeze advisor and resume branch preserve off without network`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs(); fs.mkdir("/work"); fs.put("/root/.claude/settings.json", """{"advisorModel":"other/oracle"}""")
            val model = ScriptedModel()
            val base = testDeps(model, emptyList(), fs = fs)
            val deps = KernelDeps(base.clock, base.ids, { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), model) }, base.tools, base.host, base.platform, base.zone, memoryHome = GuestPath("/root"))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L }); val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            assertEquals(advisor, rt.state.value.advisor.model)
            assertEquals(advisor, hub.branch(rt.sessionId).state.value.advisor.model)
            rt.changeAdvisor(null)
            assertNull(hub.branch(rt.sessionId).state.value.advisor.model)
            val reopened = SessionRuntime.open(store, rt.sessionId, deps, scope)!!
            assertNull(reopened.state.value.advisor.model)
            assertTrue(model.requests.isEmpty())
            val configured: SessionEvent = AdvisorConfigured(advisor)
            assertEquals(configured, AgentJson.decodeFromString(SessionEvent.serializer(), AgentJson.encodeToString(SessionEvent.serializer(), configured)))
            assertEquals(AdvisorConfigured(), AgentJson.decodeFromString(SessionEvent.serializer(), """{"type":"advisor_configured"}"""))
        } finally { scope.cancel() }
    }

    @Test fun `unpriced advisor cannot bypass USD budget and cancellation escapes`() = runBlocking {
        for (budget in listOf<Double?>(0.1, null)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val oracle = ScriptedModel(failing(CancellationException("cancel advisor")))
                val base = testDeps(ScriptedModel(), emptyList())
                val deps = KernelDeps(base.clock, base.ids, { ResolvedModel(TEST_PROFILE.copy(ref = advisor), oracle) }, base.tools, base.host, base.platform, base.zone, maxBudgetUsd = budget)
                val rt = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
                rt.changeAdvisor(advisor)
                try {
                    AdvisorTool(rt).run(args("question" to "check"), TestToolContext()).toList()
                    fail("budget/cancellation must escape")
                } catch (e: BudgetExceeded) { assertNotNull(budget) }
                catch (e: CancellationException) { assertNull(budget) }
                assertEquals(if (budget == null) 1 else 0, oracle.requests.size)
            } finally { scope.cancel() }
        }
    }

    @Test fun `advisor retains full tool text marks long transcript and empty advice is an error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val oracle = ScriptedModel(reply(TextPart("")))
            val base = testDeps(ScriptedModel(), emptyList())
            val deps = KernelDeps(base.clock, base.ids, { ResolvedModel(TEST_PROFILE.copy(ref = advisor), oracle) }, base.tools, base.host, base.platform, base.zone)
            val rt = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope).create(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
            rt.changeAdvisor(advisor)
            val result = (AdvisorTool(rt).run(args("question" to "check"), TestToolContext()).last() as ToolProgress.Done).result
            assertTrue(result.isError); assertTrue(result.text.contains("declined"))
            val messages = listOf(ModelMessage(Role.User, listOf(TextPart("FIRST"))),
                ModelMessage(Role.Assistant, listOf(ToolCallPart(CallId("read"), "Read", args("content" to "a".repeat(600) + "INPUT_END")))),
                ModelMessage(Role.User, listOf(ToolResultPart(CallId("read"), listOf(TextPart("b".repeat(1000) + "RESULT_END"))))))
            val text = goalTranscript(messages, 5000, Int.MAX_VALUE, Int.MAX_VALUE, markTruncation = true)
            assertTrue(text.contains("INPUT_END")); assertTrue(text.contains("RESULT_END"))
            val cut = goalTranscript(listOf(ModelMessage(Role.User, listOf(TextPart("FIRST" + "x".repeat(3000) + "LAST")))), 500, Int.MAX_VALUE, Int.MAX_VALUE, markTruncation = true)
            assertTrue(cut.startsWith("[用户]\nFIRST")); assertTrue(cut.endsWith("LAST")); assertTrue(cut.contains("省略")); assertTrue(cut.length <= 500)
        } finally { scope.cancel() }
    }

    @Test fun `only configured advisor is exposed guidance returns to model and is billed`() = runBlocking {
        val main = ScriptedModel(
            reply(ToolCallPart(CallId("oracle"), "advisor", args("question" to "Check plan")), stop = StopReason.ToolUse),
            reply(TextPart("I applied the advice")),
        )
        val oracle = ScriptedModel(reply(TextPart("Use the transaction")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(main, emptyList())
            val deps = KernelDeps(base.clock, base.ids, { ref -> if (ref == advisor) ResolvedModel(TEST_PROFILE.copy(ref = advisor, pricing = price), oracle) else ResolvedModel(TEST_PROFILE.copy(pricing = price), main) }, base.tools, base.host, base.platform, base.zone, maxBudgetUsd = 1.0)
            val store = FileSessionStore(tmp.root.toPath(), { 0L }); val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            assertFalse(availableTools(rt.state.value, deps, runtime = rt).any { it.name == "advisor" })
            rt.changeAdvisor(advisor)
            assertTrue(availableTools(rt.state.value, deps, runtime = rt).any { it.name == "advisor" })
            rt.send(ClientMsgId("u"), listOf(TextPart("implement transaction")))
            withTimeout(20_000) { rt.state.first { it.turn == null && it.lastTurn != null } }
            assertEquals("Use the transaction", rt.state.value.toolResults.getValue(CallId("oracle")).text)
            assertTrue(main.requests.last().messages.toString().contains("Use the transaction"))
            assertTrue(oracle.requests.single().messages.toString().contains("implement transaction"))
            assertTrue(oracle.requests.single().messages.toString().contains("Check plan"))
            assertTrue(oracle.requests.single().messages.toString().contains("本次没有结果"))
            assertTrue(oracle.requests.single().tools.isEmpty())
            val events = store.peek(rt.sessionId)!!.read().toList()
            val charged = events.map { it.event }.filterIsInstance<ModelCharged>()
            assertEquals(3, charged.size)
            assertEquals(advisor, charged[1].model)
            assertEquals(charged.sumOf { it.amount!! }, hub.runCostOf(rt.sessionId).byCurrency.getValue("USD"), 0.00000001)
            assertTrue(events.map { it.event }.filterIsInstance<Note>().any { it.text.contains("other") && it.text.contains("对话") })
            assertEquals(fold(store.peek(rt.sessionId)!!.header, events), rt.state.value)
            rt.changeAdvisor(null)
            assertNull(rt.state.value.advisor.model)
            assertFalse(availableTools(rt.state.value, deps, runtime = rt).any { it.name == "advisor" })
        } finally { scope.cancel() }
    }

    @Test fun `advisor charge trips shared tree budget before next main request`() = runBlocking {
        val main = ScriptedModel(reply(ToolCallPart(CallId("oracle"), "advisor", args("question" to "check")), stop = StopReason.ToolUse))
        val oracle = ScriptedModel(reply(TextPart("expensive advice"), usage = Usage(1_000_000, 0)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(main, emptyList())
            val deps = KernelDeps(base.clock, base.ids, { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref, pricing = price), if (ref == advisor) oracle else main) }, base.tools, base.host, base.platform, base.zone, maxBudgetUsd = 0.1)
            val hub = SessionHub(FileSessionStore(tmp.root.toPath(), { 0L }), deps, scope)
            val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.changeAdvisor(advisor); rt.send(ClientMsgId("u"), listOf(TextPart("test")))
            withTimeout(20_000) { rt.state.first { it.turn == null && it.lastTurn != null } }
            assertEquals(1, main.requests.size); assertEquals(1, oracle.requests.size)
            assertTrue(hub.runCostOf(rt.sessionId).byCurrency.getValue("USD") >= 1.0)
            assertTrue(rt.state.value.toolResults.getValue(CallId("oracle")).text.contains("expensive advice"))
        } finally { scope.cancel() }
    }
}
