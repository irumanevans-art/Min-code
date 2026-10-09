package dev.min.agent.kernel

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoSpawnTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reviewRef = ModelRef("review", "small")
    private fun decision(value: String) = reply(TextPart("""{"decision":"$value","reason":"spawn verdict","hard_deny_matches":[]}"""))
    private fun run(reviewer: ModelClient, childModel: ModelClient? = null, headless: Boolean = false, block: suspend (SessionHub, SessionRuntime, FileSessionStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val child = childModel ?: ModelClient { flow { awaitCancellation() } }
        val base = testDeps(child, emptyList())
        val deps = KernelDeps(base.clock, base.ids, models = { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), if (ref == reviewRef) reviewer else child) },
            tools = base.tools, host = base.host, platform = "test", zone = base.zone, autoModeReviewer = AutoModeReviewer(reviewRef))
        val store = FileSessionStore(tmp.newFolder().toPath(), { 0 })
        try {
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Auto, headless = headless)
            rt.receive(ClientMsgId("user"), listOf(TextPart("只做本地工作，别 push")), Delivery.Queue, InputOrigin.User, null)
            rt.commit(listOf(InputPromoted(ClientMsgId("user"), MessageId("user")), TurnStarted(TurnId("t"), TurnTrigger.User)), Durability.Sync)
            withTimeout(8000) { block(hub, rt, store) }
        } finally { scope.cancel() }
    }
    @Test fun foregroundAndBackgroundUseTheSameReviewedReport() {
        for (background in listOf(false, true)) {
            val reviewer = ScriptedModel(decision("allow"), decision("allow"))
            val child = ScriptedModel(reply(ToolCallPart(CallId("handback"), "SubagentHandback", args("report" to "reviewed child body")), stop = StopReason.ToolUse))
            run(reviewer, childModel = child) { hub, rt, _ ->
                val result = hub.portFor(rt, TurnId("t"), CallId("agent")).delegate(DelegateRequest("safe task", "do local work", background = background))
                val report = if (result is DelegateResult.Finished) result.report else {
                    rt.state.first { s -> s.inbox.any { it.origin is InputOrigin.Agent } }.inbox.first { it.origin is InputOrigin.Agent }.parts.toString()
                }
                assertTrue(report.contains("reviewed child body")); assertTrue(report.contains("review=\"approved\""))
                assertEquals(2, reviewer.requests.size)
            }
        }
    }
    private suspend fun spawn(hub: SessionHub, rt: SessionRuntime, entry: String): Boolean {
        val port = hub.portFor(rt, TurnId("t"), CallId("call"))
        val request = DelegateRequest("任务", "git push; scripted-authorization", background = true, workflow = entry == "workflow")
        return when (entry) {
            "team" -> !port.team(TeamOp.Create(listOf(TeamMemberSpec(name = "member", prompt = request.prompt)))).isError
            "workflow" -> hub.spawn(rt, TurnId("t"), CallId("call"), request).let { it !is DelegateResult.Rejected }
            else -> port.delegate(request).let { it !is DelegateResult.Rejected }
        }
    }
    @Test fun allThreeEntrancesRejectBeforeChildCreation() {
        for (entry in listOf("agent", "team", "workflow")) {
            val reviewer = ScriptedModel(decision("deny"))
            run(reviewer) { hub, rt, _ ->
                assertFalse(entry, spawn(hub, rt, entry))
                assertTrue(entry, rt.state.value.children.isEmpty())
                assertEquals(entry, 1, reviewer.requests.size)
                val input = reviewer.requests.single().messages.toString()
                assertTrue(input.contains("git push")); assertTrue(input.contains("general-purpose")); assertTrue(input.contains("tools"))
                assertTrue(input.contains("别 push"))
            }
        }
    }
    @Test fun agentAndTeamAllowCreateExactlyOneChild() {
        for (entry in listOf("agent", "team")) {
            val reviewer = ScriptedModel(decision("allow"))
            run(reviewer) { hub, rt, _ ->
                assertTrue(entry, spawn(hub, rt, entry))
                assertEquals(entry, 1, rt.state.value.children.size)
                assertEquals(entry, 1, reviewer.requests.size)
            }
        }
    }
    @Test fun workflowAllowCreatesChildBeforeRunningItsTask() {
        val reviewer = ScriptedModel(decision("allow"))
        run(reviewer) { hub, rt, _ -> coroutineScope {
            val result = async { spawn(hub, rt, "workflow") }
            rt.state.first { it.children.isNotEmpty() }
            assertEquals(1, rt.state.value.children.size)
            assertEquals(1, reviewer.requests.size)
            val child = hub.get(rt.state.value.children.keys.single())!!
            assertTrue(child.state.value.origin!!.workflow)
            child.state.first { it.history.isNotEmpty() }
            assertTrue(child.state.value.modelMessages().toString().contains("Script-authored workflow task"))
            assertFalse(child.state.value.reviewUserTexts().toString().contains("scripted-authorization"))
            assertTrue(child.state.value.reviewUserTexts().toString().contains("别 push"))
            result.cancelAndJoin()
        } }
    }
    @Test fun childReceivesParentBoundaryRevocationWithoutTrustingItsPrompt() {
        val reviewer = ScriptedModel(decision("allow"))
        run(reviewer) { hub, rt, store ->
            assertTrue(spawn(hub, rt, "agent"))
            val child = hub.get(rt.state.value.children.keys.single())!!
            assertTrue(child.state.value.reviewUserTexts().toString().contains("别 push"))
            rt.receive(ClientMsgId("lift"), listOf(TextPart("现在可以 push 了")), Delivery.Queue, InputOrigin.User, null)
            rt.commit(listOf(InputPromoted(ClientMsgId("lift"), MessageId("lift"))), Durability.Sync)
            child.refreshAutoUserContext()
            assertEquals("现在可以 push 了", child.state.value.reviewUserTexts().last())
            val log = store.open(child.sessionId)!!
            assertEquals(child.state.value.reviewUserTexts(), fold(log.header, log.read().toList()).reviewUserTexts())
        }
    }
    @Test fun failedReviewerAsksHumanBeforeCreatingChild() {
        run(ModelClient { flow { throw java.io.IOException("offline") } }) { hub, rt, _ -> coroutineScope {
            val result = async { spawn(hub, rt, "agent") }
            val pending = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            assertTrue(rt.state.value.children.isEmpty())
            assertTrue(rt.answerApproval(pending.approvalId, ApprovalChoice.Deny()))
            assertFalse(result.await())
        } }
    }
    @Test fun spawnSharesCircuitBreakerWithOrdinaryTools() {
        val reviewer = ScriptedModel(decision("deny"))
        run(reviewer) { hub, rt, _ -> coroutineScope {
            rt.commit((1..2).map { AutoReviewed(TurnId("t"), CallId("ordinary-$it"), reviewRef, AutoReviewDecision.Deny, "blocked") }, Durability.Sync)
            assertFalse(spawn(hub, rt, "agent"))
            assertTrue(rt.state.value.autoReviewTripped)
            val result = async { spawn(hub, rt, "agent") }
            val pending = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(pending.approvalId, ApprovalChoice.Deny())
            assertFalse(result.await()); assertEquals(1, reviewer.requests.size)
        } }
    }
}
