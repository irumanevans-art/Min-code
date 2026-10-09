package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoHandbackTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun decision(value: String) = reply(TextPart("""{"decision":"$value","reason":"report verdict","hard_deny_matches":[]}"""))
    private fun run(mode: PermissionMode = PermissionMode.Auto, fork: Boolean = false, workflow: Boolean = false,
                    mainScript: ScriptedModel? = null, reviewScript: ScriptedModel? = null,
                    hook: HttpHookClient = HttpHookClient { HttpHookResult.Responded(200, "{}") },
                    block: suspend (SessionRuntime, ScriptedModel, ScriptedModel, FileSessionStore) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val main = mainScript ?: ScriptedModel(
            reply(ToolCallPart(CallId("bad"), "SubagentHandback", args("message" to "REJECTED_REPORT_RAW")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("good"), "SubagentHandback", args("message" to "safe report")), stop = StopReason.ToolUse),
            reply(TextPart("UNREVIEWED_LAST_MESSAGE")))
        val reviewer = reviewScript ?: ScriptedModel(decision("deny"), decision("allow"))
        val ref = ModelRef("review", "small")
        val base = testDeps(main, emptyList())
        val deps = KernelDeps(base.clock, base.ids, models = { model -> ResolvedModel(TEST_PROFILE.copy(ref = model), if (model == ref) reviewer else main) },
            tools = emptyList(), host = HostPorts(base.host.fs, base.host.search, base.host.exec, base.host.artifacts, httpHooks = hook), platform = "test", zone = base.zone)
        val store = FileSessionStore(tmp.newFolder().toPath(), { 0 })
        try {
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("child"), 0),
                SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode), deps, scope,
                initial = listOf(SubagentOrigin(SessionId("parent"), SessionId("parent"), CallId("agent"), "general-purpose", fork, 1, cacheKey = "child", tools = emptyList(), workflow = workflow),
                    AutoReviewSettingsLoaded(reviewer = AutoModeReviewer(ref))))
            withTimeout(8000) { block(rt, main, reviewer, store) }
        } finally { scope.cancel() }
    }
    @Test fun rejectedHandbackCanBeRevisedAndOnlyApprovedBodyIsDelivered() = run { rt, main, reviewer, store ->
        rt.receive(ClientMsgId("parent"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
        val ended = rt.state.first { it.turn == null && it.lastTurn != null }.lastTurn!!
        val report = Reports.render(rt.sessionId, null, "general-purpose", false, ended, rt.state.value)
        assertTrue(report.contains("safe report")); assertFalse(report.contains("REJECTED_REPORT_RAW")); assertFalse(report.contains("UNREVIEWED_LAST_MESSAGE"))
        assertTrue(rt.state.value.toolResults.getValue(CallId("bad")).isError)
        assertFalse(rt.state.value.toolResults.getValue(CallId("good")).isError)
        assertEquals(2, reviewer.requests.size); assertEquals(2, main.requests.size)
        assertTrue(report.contains("review=\"approved\""))
        val log = store.open(rt.sessionId)!!
        val replayed = fold(log.header, log.read().toList())
        assertEquals(report, Reports.render(rt.sessionId, null, "general-purpose", false, ended, replayed))
    }
    @Test fun toolOnlyAppearsForOrdinaryAutoChildren() {
        for ((mode, fork, workflow) in listOf(Triple(PermissionMode.Default, false, false), Triple(PermissionMode.Auto, true, false), Triple(PermissionMode.Auto, false, true))) {
            run(mode, fork, workflow) { rt, _, _, _ -> assertFalse(availableTools(rt.state.value, rt.deps, runtime = rt).any { it.name == "SubagentHandback" }) }
        }
        run { rt, _, _, _ -> assertTrue(availableTools(rt.state.value, rt.deps, runtime = rt).any { it.name == "SubagentHandback" }) }
        run { rt, _, _, _ ->
            val team = rt.state.value.copy(origin = rt.state.value.origin!!.copy(teamMember = true))
            assertFalse(availableTools(team, rt.deps, runtime = rt).any { it.name == "SubagentHandback" })
        }
    }
    @Test fun toolInputMatchesDocumentedMessageField() = run { rt, _, _, _ ->
        val tool = SubagentHandbackTool(rt)
        assertEquals(listOf("message"), tool.inputSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tool.parse(args("message" to "final report")) is ToolInput.Valid)
        assertTrue(tool.parse(args("report" to "final report")) is ToolInput.Invalid)
    }
    @Test fun fallbackReviewsLastMessageAndWithholdsRejectedText() {
        for (verdict in listOf("deny", "allow")) {
            run(mainScript = ScriptedModel(reply(TextPart("FALLBACK_BODY"))), reviewScript = ScriptedModel(decision(verdict))) { rt, _, reviewer, _ ->
                rt.receive(ClientMsgId("parent"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
                val ended = rt.state.first { it.turn == null && it.lastTurn != null }.lastTurn!!
                val report = Reports.render(rt.sessionId, null, "general-purpose", false, ended, rt.state.value)
                assertEquals(1, reviewer.requests.size)
                assertEquals(verdict == "allow", report.contains("FALLBACK_BODY"))
                assertTrue(report.contains("handback=\"false\""))
            }
        }
    }
    @Test fun stopHookReceivesShortHandbackNoteInsteadOfReport() {
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        run(hook = HttpHookClient { bodies += it.body; HttpHookResult.Responded(200, "{}") }) { rt, _, _, _ ->
            rt.commit(listOf(HooksLoaded(listOf(HookSourceData("user", Json.parseToJsonElement("""{"SubagentStop":[{"hooks":[{"type":"http","url":"https://hooks.test/stop"}]}]}""").jsonObject)))), Durability.Sync)
            rt.receive(ClientMsgId("parent"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
            rt.state.first { it.turn == null && it.lastTurn != null }
            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals(HANDBACK_NOTE, body.getValue("last_assistant_message").jsonPrimitive.content)
            assertEquals(HANDBACK_NOTE, body.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            assertFalse(bodies.single().contains("safe report"))
        }
    }
    @Test fun interruptedAutoChildCannotDeliverUnreviewedLastText() = run { rt, _, _, _ ->
        val state = rt.state.value.copy(history = listOf(HistoryEntry.Assistant(MessageId("partial"), listOf(TextPart("UNREVIEWED_PARTIAL")))))
        val ended = EndedTurn(TurnId("aborted"), TurnTrigger.User, TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), Usage(), 0, 0)
        val report = Reports.render(rt.sessionId, null, "general-purpose", false, ended, state)
        assertFalse(report.contains("UNREVIEWED_PARTIAL")); assertTrue(Reports.isError(ended.outcome, state))
    }
    @Test fun failedAutoChildCannotDeliverUnreviewedErrorText() = run { rt, _, _, _ ->
        val ended = EndedTurn(TurnId("failed"), TurnTrigger.User, TurnOutcome.Failed("UNREVIEWED_ERROR_BODY"), Usage(), 0, 0)
        val report = Reports.render(rt.sessionId, null, "general-purpose", false, ended, rt.state.value)
        assertFalse("错误详情也不能旁路终报审查", report.contains("UNREVIEWED_ERROR_BODY"))
    }
    @Test fun autoCreatedChildStillReviewsReportAfterSwitchingToPlan() {
        for (verdict in listOf("deny", "allow")) {
            run(mainScript = ScriptedModel(reply(TextPart("PLAN_REPORT_BODY"))), reviewScript = ScriptedModel(decision(verdict))) { rt, _, reviewer, _ ->
                rt.changeSettings(mode = PermissionMode.Plan)
                rt.receive(ClientMsgId("parent"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
                val ended = rt.state.first { it.turn == null && it.lastTurn != null }.lastTurn!!
                val report = Reports.render(rt.sessionId, null, "general-purpose", false, ended, rt.state.value)
                assertEquals(verdict == "allow", report.contains("PLAN_REPORT_BODY"))
                assertEquals(1, reviewer.requests.size)
            }
        }
    }
}
