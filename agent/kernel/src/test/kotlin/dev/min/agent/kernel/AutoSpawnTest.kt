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
    private fun run(reviewer: ModelClient, childModel: ModelClient? = null, headless: Boolean = false,
                    tools: List<Tool<*>> = emptyList(), hook: HttpHookClient? = null,
                    workflowEngine: WorkflowEngine? = null,
                    block: suspend (SessionHub, SessionRuntime, FileSessionStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val child = childModel ?: ModelClient { flow { awaitCancellation() } }
        val base = testDeps(child, tools)
        val deps = KernelDeps(base.clock, base.ids, models = { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), if (ref == reviewRef) reviewer else child) },
            tools = base.tools, host = hook?.let { HostPorts(base.host.fs, base.host.search, base.host.exec, base.host.artifacts, httpHooks = it) } ?: base.host,
            platform = "test", zone = base.zone, autoModeReviewer = AutoModeReviewer(reviewRef), workflowEngine = workflowEngine)
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
            val child = ScriptedModel(reply(ToolCallPart(CallId("handback"), "SubagentHandback", args("message" to "reviewed child body")), stop = StopReason.ToolUse))
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
    @Test fun failedReviewerManualApprovalCreatesChild() {
        run(ModelClient { flow { throw java.io.IOException("offline") } }) { hub, rt, _ -> coroutineScope {
            val result = async { spawn(hub, rt, "agent") }
            val pending = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            assertTrue(rt.state.value.children.isEmpty())
            rt.answerApproval(pending.approvalId, ApprovalChoice.Once)
            assertTrue(result.await()); assertEquals(1, rt.state.value.children.size)
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
    @Test fun headlessSafetyRefusalAndContextOverflowHaveOriginalErrorText() {
        val cases = listOf(
            ScriptedModel(reply(TextPart("refused"), stop = StopReason.Refusal)) to "Agent aborted: auto mode classifier request refused by the safety safeguard in headless mode",
            ScriptedModel(failing(ModelException(ModelFailureKind.ContextOverflow, "large", beforeFirstByte = true))) to "Agent aborted: auto mode classifier transcript exceeded context window in headless mode",
        )
        for ((reviewer, expected) in cases) {
            run(reviewer, headless = true) { hub, rt, _ ->
                val result = hub.portFor(rt, TurnId("t"), CallId("call")).delegate(DelegateRequest("task", "task", background = true))
                assertTrue(result is DelegateResult.Rejected)
                assertEquals(expected, (result as DelegateResult.Rejected).messageForModel)
                assertTrue(rt.state.value.children.isEmpty()); assertEquals(0, rt.state.value.autoReviewDenials)
                assertTrue(hub.pending.value.isEmpty())
            }
        }
    }
    @Test fun permissionModeChangedDuringSpawnReviewCannotUseOldAutoAllow() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reviewer = ModelClient { request -> flow { entered.complete(Unit); release.await(); emitAll(decision("allow")(request)) } }
        run(reviewer) { hub, rt, _ -> coroutineScope {
            val result = async { spawn(hub, rt, "agent") }
            entered.await()
            rt.changeSettings(mode = PermissionMode.DontAsk)
            release.complete(Unit)
            assertFalse(result.await()); assertTrue(rt.state.value.children.isEmpty())
        } }
    }
    @Test fun modeChangeDuringReportReviewCannotReplaceClassifierDenialWithReadOnlyAllow() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reviewer = ModelClient { request -> flow { entered.complete(Unit); release.await(); emitAll(decision("deny")(request)) } }
        run(reviewer) { _, rt, _ -> coroutineScope {
            val result = async { rt.reviewReport(TurnId("t"), "PRIVATE_REPORT_BODY") }
            entered.await()
            rt.changeSettings(mode = PermissionMode.Plan)
            release.complete(Unit)
            assertTrue("改模式不能把终报拒绝改成只读免批", result.await() is dev.min.agent.policy.Verdict.Deny)
        } }
    }
    @Test fun workflowStructuredReportIsReviewedAndRejectedValueIsNotDelivered() {
        for (verdict in listOf("deny", "allow")) {
            val reviewer = ScriptedModel(decision("allow"), decision(verdict))
            val schema = Json.parseToJsonElement("""{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""").jsonObject
            val value = args("value" to "SCHEMA_REPORT_BODY")
            val child = ScriptedModel(reply(ToolCallPart(CallId("structured"), "StructuredOutput", value), stop = StopReason.ToolUse))
            run(reviewer, childModel = child) { hub, rt, _ ->
                val result = hub.spawn(rt, TurnId("t"), CallId("call"), DelegateRequest("json task", "deliver json", workflow = true, schema = schema)) as DelegateResult.Finished
                assertEquals(verdict == "deny", result.isError)
                assertEquals(verdict == "allow", result.report.contains("SCHEMA_REPORT_BODY"))
                assertEquals(2, reviewer.requests.size)
                assertTrue(reviewer.requests.last().messages.toString().contains("SCHEMA_REPORT_BODY"))
            }
        }
    }
    @Test fun foregroundAgentPostToolUseGetsHandbackNoteObject() {
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reviewer = ScriptedModel(decision("allow"), decision("allow"), decision("allow"))
        val child = ScriptedModel(reply(ToolCallPart(CallId("handback"), "SubagentHandback", args("message" to "FULL_REPORT_BODY")), stop = StopReason.ToolUse))
        run(reviewer, childModel = child, tools = listOf(dev.min.agent.tools.AgentTool),
            hook = HttpHookClient { bodies += it.body; HttpHookResult.Responded(200, "{}") }) { _, rt, _ ->
            rt.commit(listOf(HooksLoaded(listOf(HookSourceData("user", Json.parseToJsonElement("""{"PostToolUse":[{"matcher":"Agent","hooks":[{"type":"http","url":"https://hooks.test/post"}]}]}""").jsonObject)))), Durability.Sync)
            val call = ToolCallPart(CallId("agent-tool"), "Agent", buildJsonObject { put("description", "task"); put("prompt", "task"); put("run_in_background", false) })
            ToolExecutor(rt).executeAll(TurnId("t"), listOf(call))
            val encoded = Json.parseToJsonElement(bodies.single()).jsonObject.getValue("tool_response")
            assertTrue("handback 的 Agent PostToolUse 必须保留对象载荷", encoded is JsonObject)
            val response = encoded.jsonObject
            assertEquals("completed", response.getValue("status").jsonPrimitive.content)
            assertEquals(HANDBACK_NOTE, response.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            assertFalse(bodies.single().contains("FULL_REPORT_BODY"))
            assertTrue(rt.state.value.toolResults.getValue(call.callId).content.toString().contains("FULL_REPORT_BODY"))
        }
    }
    @Test fun headlessBackgroundHandbackRefusalAbortsWithoutRawReport() {
        val reviewer = ScriptedModel(decision("allow"), reply(TextPart("refused"), stop = StopReason.Refusal))
        val child = ScriptedModel(reply(ToolCallPart(CallId("handback"), "SubagentHandback", args("message" to "PRIVATE_REPORT_BODY")), stop = StopReason.ToolUse))
        run(reviewer, childModel = child, headless = true) { hub, rt, _ ->
            val result = hub.portFor(rt, TurnId("t"), CallId("call")).delegate(DelegateRequest("task", "task", background = true)) as DelegateResult.Started
            val ended = hub.get(result.child)!!.state.first { it.lastTurn != null && it.turn == null }.lastTurn!!
            assertEquals("Agent aborted: auto mode classifier request refused by the safety safeguard in headless mode", (ended.outcome as TurnOutcome.Failed).message)
            val report = rt.state.first { it.inbox.any { input -> input.origin is InputOrigin.Agent } }.inbox.first { it.origin is InputOrigin.Agent }
            assertFalse(report.parts.toString().contains("PRIVATE_REPORT_BODY"))
        }
    }
    @Test fun workflowExecutionReachesCommonSpawnReviewBeforeNodeModel() {
        for (verdict in listOf("deny", "allow")) {
            val reviewer = ScriptedModel(decision(verdict), decision("allow"))
            val child = ScriptedModel(reply(TextPart("NODE_RESULT")))
            val engine = WorkflowEngine { _, _, host -> host.agent(0, "git push; script task", JsonObject(emptyMap())) }
            run(reviewer, childModel = child, workflowEngine = engine) { _, rt, _ ->
                val result = rt.executeWorkflow(TurnId("t"), CallId("workflow"), WorkflowInput("node", "export const meta={name:'node',description:'task'};", null))
                val node = rt.state.value.workflowEvents.filterIsInstance<WorkflowNodeCompleted>().single()
                assertEquals(verdict == "deny", node.isError)
                assertEquals(if (verdict == "deny") 0 else 1, rt.state.value.children.size)
                assertEquals(if (verdict == "deny") 0 else 1, child.requests.size)
                assertEquals(if (verdict == "deny") JsonNull else JsonPrimitive("NODE_RESULT"), node.result)
                assertFalse(result.isError)
            }
        }
    }
}
