package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

class WorkflowApprovalTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun run(block: suspend (SessionHub, SessionRuntime) -> Unit, mode: PermissionMode, approvals: WorkflowApprovals = WorkflowApprovals.SessionOnly, configure: (FakeFs) -> Unit = {}, hook: HttpHookClient = HttpHookClient { HttpHookResult.Responded(200,"{}") }, reviewer: dev.min.agent.llm.ModelClient? = null, env: Map<String,String> = emptyMap(), enabled: Boolean = true) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        val fs = FakeFs().apply { put("/work/.claude/workflows/review.js", "export const meta={name:'review',description:'Review'}; return 1;") }
        configure(fs)
        val deps = KernelDeps(clock={1000}, ids={"${it}_${ids.incrementAndGet()}"}, models={ ref -> ResolvedModel(TEST_PROFILE, if(ref.providerId=="reviewer") requireNotNull(reviewer) else ScriptedModel()) },
            tools=emptyList(), host=HostPorts(fs,FakeSearch(),FakeExec(),{FakeArtifacts()}, httpHooks=hook), platform="test", zone=ZoneOffset.UTC,
            autoModeReviewer=reviewer?.let { AutoModeReviewer(ModelRef("reviewer","small")) }, memoryHome=GuestPath("/home"),workflowApprovals=approvals,env={ env[it] },workflowsEnabled={ enabled },workflowEngine=WorkflowEngine { _,_,_ -> JsonPrimitive(1) })
        try {
            val hub=SessionHub(FileSessionStore(tmp.newFolder().toPath(),{1000}),deps,scope)
            withTimeout(5000) { block(hub,hub.create(GuestPath("/work"),TEST_MODEL,mode)) }
        } finally { scope.cancel() }
    }
    @Test fun bypassRunsWithoutPrompt() = run({ hub,rt ->
        rt.send(ClientMsgId("one"),listOf(TextPart("/review")))
        assertNotNull("bypass must finish without consent",withTimeoutOrNull(2000) { rt.state.first { it.lastTurn != null && it.turn == null } })
        assertTrue(hub.pending.value.isEmpty()); assertEquals(1,rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().size)
    },PermissionMode.BypassPermissions)
    @Test fun manualOnceDoesNotApproveTheNextRun() = run({ hub,rt ->
        rt.send(ClientMsgId("one"),listOf(TextPart("/review")))
        val ask=hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
        rt.answerApproval(ask.approvalId,ApprovalChoice.Once)
        rt.state.first { it.lastTurn != null && it.turn == null }
        rt.send(ClientMsgId("two"),listOf(TextPart("/review")))
        val pending=withTimeoutOrNull(2000) { hub.pending.first { it.isNotEmpty() } }
        assertNotNull("Manual Once must not authorize the next run",pending)
        val second=requireNotNull(pending).single().what as HubPending.What.Approval
        assertNotEquals(ask.approvalId,second.approvalId)
        rt.answerApproval(second.approvalId,ApprovalChoice.Once)
    },PermissionMode.Default)

    private class MemoryApprovals : WorkflowApprovals {
        val remembered=mutableSetOf<Pair<GuestPath,String>>()
        var auto=false
        override fun contains(cwd:GuestPath,hash:String)=cwd to hash in remembered
        override suspend fun approve(cwd:GuestPath,hash:String) { remembered+=cwd to hash }
        override suspend fun revoke(cwd:GuestPath,hash:String) { remembered-=cwd to hash }
        override fun autoConsented()=auto
        override suspend fun consentAuto() { auto=true }
        override suspend fun revokeAuto() { auto=false }
    }
    @Test fun acceptEditsStillAsksOnEachRun() = run({ hub,rt ->
        repeat(2) { i ->
            val before=rt.state.value.lastTurn?.turnId
            rt.send(ClientMsgId("run$i"),listOf(TextPart("/review")))
            val ask=hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(ask.approvalId,ApprovalChoice.Once)
            rt.state.first { it.turn==null && it.lastTurn?.turnId!=before }
        }
    },PermissionMode.AcceptEdits)
    @Test fun projectRememberIsPrivateAndCanBeRevoked() {
        val approvals=MemoryApprovals()
        run({ hub,rt ->
            rt.send(ClientMsgId("one"),listOf(TextPart("/review")))
            val ask=hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            assertEquals("Workflow(review)",ask.suggestedRule)
            rt.answerApproval(ask.approvalId,ApprovalChoice.Always("Workflow(review)"))
            rt.state.first { it.lastTurn!=null && it.turn==null }
            assertTrue(rt.state.value.sessionRules.isEmpty()); assertEquals(1,approvals.remembered.size)
            val before=rt.state.value.lastTurn!!.turnId
            rt.send(ClientMsgId("two"),listOf(TextPart("/review")))
            rt.state.first { it.turn==null && it.lastTurn?.turnId!=before }
            assertTrue(hub.pending.value.isEmpty())
            rt.revokeWorkflowApproval("export const meta={name:'review',description:'Review'}; return 1;")
            rt.send(ClientMsgId("three"),listOf(TextPart("/review")))
            val third=hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(third.approvalId,ApprovalChoice.Once)
        },PermissionMode.Default,approvals)
    }
    @Test fun autoConsentOnceAndRevocationUsesCurrentStore() {
        val approvals=MemoryApprovals()
        run({ hub,rt ->
            repeat(2) { i ->
                val before=rt.state.value.lastTurn?.turnId
                rt.send(ClientMsgId("auto$i"),listOf(TextPart("/review")))
                if(i==0) {
                    val ask=hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
                    rt.answerApproval(ask.approvalId,ApprovalChoice.Once)
                }
                rt.state.first { it.turn==null && it.lastTurn?.turnId!=before }
                assertTrue(hub.pending.value.isEmpty()); assertTrue(approvals.auto)
            }
            approvals.revokeAuto()
            assertFalse(rt.workflowApproved("0".repeat(64)))
        },PermissionMode.Auto,approvals)
    }
    @Test fun namedAllowRulePreauthorizesWorkflow() = run({ hub,rt ->
        rt.send(ClientMsgId("rule"),listOf(TextPart("/review")))
        rt.state.first { it.lastTurn!=null && it.turn==null }
        assertTrue(hub.pending.value.isEmpty()); assertEquals(1,rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().size)
    },PermissionMode.Default,configure={ it.put("/home/.claude/settings.json","""{"permissions":{"allow":["Workflow(review)"]}}""") })
    @Test fun disabledSettingSurvivesReopen() = run({ hub,rt ->
        assertTrue(rt.savedWorkflows().isEmpty())
        assertTrue(hub.close(rt.sessionId))
        val reopened=hub.open(rt.sessionId)!!
        assertTrue(reopened.state.value.workflowSettingsDisabled)
    },PermissionMode.BypassPermissions,configure={ it.put("/home/.claude/settings.json","""{"disableWorkflows":true}""") })

    @Test fun preToolUseAllowPreauthorizesInManualAndAuto() {
        for(mode in listOf(PermissionMode.Default,PermissionMode.Auto)) run({ hub,rt ->
            rt.send(ClientMsgId("hook"),listOf(TextPart("/review")))
            rt.state.first { it.turn==null && it.lastTurn!=null }
            assertTrue(hub.pending.value.isEmpty())
            assertEquals(1,rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().size)
        },mode,configure={ it.put("/home/.claude/settings.json","""{"hooks":{"PreToolUse":[{"matcher":"Workflow","hooks":[{"type":"http","url":"https://hooks.invalid/allow"}]}]}}""") },
            hook=HttpHookClient { HttpHookResult.Responded(200,"""{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}""") })
    }
    @Test fun autoClassifierCanPreauthorizeWorkflow() {
        val model=ScriptedModel(reply(TextPart("""{"decision":"allow","reason":"workflow review","hard_deny_matches":[]}""")))
        run({ hub,rt ->
            rt.send(ClientMsgId("classifier"),listOf(TextPart("/review")))
            rt.state.first { it.turn==null && it.lastTurn!=null }
            assertTrue(hub.pending.value.isEmpty()); assertEquals(1,model.requests.size)
            assertEquals(1,rt.state.value.workflowEvents.filterIsInstance<WorkflowRunCompleted>().size)
        },PermissionMode.Auto,reviewer=model)
    }

    @Test fun hostEnvironmentAndAppSwitchHideNewRuns() {
        for(disabledByEnv in listOf(true,false)) run({ _,rt ->
            assertTrue(rt.savedWorkflows().isEmpty())
            assertTrue(availableTools(rt.state.value,rt.deps,runtime=rt).none { it.name=="Workflow" })
        },PermissionMode.BypassPermissions,env=if(disabledByEnv) mapOf("CLAUDE_CODE_DISABLE_WORKFLOWS" to "1") else emptyMap(),enabled=disabledByEnv)
    }
}
