package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.tools.BashTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkSandboxTest {
    @Test fun brokenManagedSettingsDoNotBlockLocalShell() = run(local = true) { _, rt, fs, _, _, _ ->
        fs.put("/etc/claude-code/managed-settings.json", "broken")
        fs.put("/root/.claude/settings.json", """{"sandbox":{"enabled":false},"permissions":{"allow":["Bash"]}}""")
        rt.send(ClientMsgId("one"), listOf(TextPart("local")))
        rt.state.first { it.turn == null && it.lastTurn != null }
        assertTrue(rt.state.value.lastTurn!!.outcome is TurnOutcome.Completed)
        assertFalse(rt.state.value.toolResults.getValue(CallId("c")).isError)
        assertTrue(rt.state.value.networkDecisions.isEmpty())
    }
    @Test fun brokenManagedSettingsKeepLocalTurnsButCloseNetwork() = run { _, rt, fs, _, _, _ ->
        fs.put("/etc/claude-code/managed-settings.json", "broken")
        fs.put("/root/.claude/settings.json", """{"sandbox":{"enabled":false}}""")
        assertTrue("损坏的托管策略必须强制关闭网络", rt.refreshNetworkSettings().enabled)
        rt.send(ClientMsgId("one"), listOf(TextPart("fetch")))
        rt.state.first { it.turn == null && it.lastTurn != null }
        assertTrue("网络拒绝不能令整个回合失败", rt.state.value.lastTurn!!.outcome is TurnOutcome.Completed)
        assertEquals(NetworkOutcome.Deny, rt.state.value.networkDecisions.single().outcome)
        assertTrue(rt.state.value.toolResults.getValue(CallId("c")).isError)
        assertTrue(rt.refreshNetworkSettings().enabled)
    }
    @get:Rule val tmp = TemporaryFolder()
    private class Proxy : NetworkProxy {
        lateinit var decide: suspend (NetworkTarget) -> NetworkVerdict
        var closed = 0
        override fun open(session: SessionId, decide: suspend (NetworkTarget) -> NetworkVerdict): NetworkLease {
            this.decide = decide
            return object : NetworkLease {
                override val env = networkEnvironment("http://test-credential@127.0.0.1:8001")
                override fun close() { closed++ }
            }
        }
        override fun closeSession(session: SessionId) { closed++ }
    }
    private fun run(mode: PermissionMode = PermissionMode.Default, timeout: Long = 1000,
                    reviewer: ScriptedModel? = null, hosts: List<String> = emptyList(), rule: String = "Bash", local: Boolean = false,
                    block: suspend (SessionHub, SessionRuntime, FakeFs, FileSessionStore, Proxy, ScriptedModel) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fs = FakeFs().apply { put("/root/.claude/settings.json", """{"sandbox":{"enabled":true},"permissions":{"allow":["$rule"]}}""") }
        val proxy = Proxy()
        val raw = buildJsonObject { put("command", if (local) "printf local" else "curl https://example.test"); if (hosts.isNotEmpty()) put("allowed_domains", JsonArray(hosts.map(::JsonPrimitive))) }
        val model = ScriptedModel(reply(ToolCallPart(CallId("c"), "Bash", raw), stop = StopReason.ToolUse), reply(TextPart("finished")))
        val base = testDeps(model, listOf(BashTool()))
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                assertEquals("http://test-credential@127.0.0.1:8001", command.env["HTTPS_PROXY"])
                if (local) return ExecResult.Exited(0, "local", false, 0)
                val verdict = proxy.decide(NetworkTarget("example.test", 443))
                return ExecResult.Exited(if (verdict.outcome == NetworkOutcome.Allow) 0 else 1, verdict.reason, false, 0)
            }
            override suspend fun kill(handle: ExecHandle) { }
        }
        val deps = KernelDeps(base.clock, base.ids, models = { ref -> ResolvedModel(TEST_PROFILE.copy(ref = ref), if (ref.providerId == "review") requireNotNull(reviewer) else model) }, tools = listOf(BashTool()),
            host = HostPorts(fs, FakeSearch(), exec, { FakeArtifacts() }), platform = "test", zone = base.zone,
            memoryHome = GuestPath("/root"), trustProjectHooks = { true }, networkProxy = proxy, networkApprovalTimeoutMs = timeout,
            autoModeReviewer = reviewer?.let { AutoModeReviewer(ModelRef("review", "small")) })
        val store = FileSessionStore(tmp.newFolder().toPath(), { 1000 })
        val hub = SessionHub(store, deps, scope)
        try { withTimeout(8000) { block(hub, hub.create(GuestPath("/work"), TEST_MODEL, mode), fs, store, proxy, model) } }
        finally { scope.cancel() }
    }
    @Test fun shellInjectsProxyAndHumanDecisionIsLoggedAndReplayed() = run { hub, rt, _, store, proxy, model ->
        rt.send(ClientMsgId("one"), listOf(TextPart("fetch")))
        val pending = hub.pending.first { list -> list.any { it.what is HubPending.What.Network } }.single().what as HubPending.What.Network
        assertEquals("example.test", pending.request.domain); assertEquals("curl https://example.test", pending.request.commandSummary)
        assertTrue(rt.answerApproval(pending.request.approvalId, ApprovalChoice.Once))
        rt.state.first { it.turn == null && it.lastTurn != null }
        val decision = rt.state.value.networkDecisions.single()
        assertEquals(NetworkOutcome.Allow, decision.outcome); assertEquals(NetworkSource.User, decision.source)
        val log = requireNotNull(store.peek(rt.sessionId))
        assertEquals(rt.state.value.networkDecisions, fold(log.header, log.read().toList()).networkDecisions)
        assertFalse(rt.state.value.toolResults.getValue(CallId("c")).isError)
        assertTrue(model.requests.first().tools.first { it.name == "Bash" }.description.contains("不是硬隔离"))
        assertTrue(hub.pending.value.isEmpty()); assertEquals(1, proxy.closed)
    }
    @Test fun alwaysWritesUserSettingsAndRevocationAsksAgain() = run { hub, rt, fs, _, _, _ ->
        suspend fun askAndAnswer(choice: ApprovalChoice): NetworkVerdict = coroutineScope {
            val request = async { rt.decideNetwork(CallId("c"), "curl", NetworkTarget("example.test", 443), emptyList()) }
            val card = hub.pending.first { it.any { p -> p.what is HubPending.What.Network } }.first { it.what is HubPending.What.Network }.what as HubPending.What.Network
            assertTrue(rt.answerApproval(card.request.approvalId, choice)); request.await()
        }
        assertEquals(NetworkOutcome.Allow, askAndAnswer(ApprovalChoice.Always("ignored" )).outcome)
        val text = fs.read(GuestPath("/root/.claude/settings.json")).decodeToString()
        assertTrue(text.contains("example.test:443")); assertTrue(text.contains("permissions"))
        assertEquals(NetworkSource.Rule, rt.decideNetwork(CallId("c2"), "curl", NetworkTarget("example.test", 443), emptyList()).source)
        fs.put("/root/.claude/settings.json", """{"sandbox":{"enabled":true}}""")
        assertEquals(NetworkOutcome.Deny, askAndAnswer(ApprovalChoice.Deny()).outcome)
        assertEquals(3, rt.state.value.networkDecisions.size)
    }
    @Test fun timeoutIsDeniedLoggedAndLateAnswerIsStale() = run(timeout = 30) { _, rt, _, _, _, _ ->
        val verdict = rt.decideNetwork(CallId("c"), "curl", NetworkTarget("example.test", 443), emptyList())
        assertEquals(NetworkOutcome.Deny, verdict.outcome); assertEquals(NetworkSource.Timeout, verdict.source)
        assertTrue(rt.state.value.networkPending.isEmpty())
        assertFalse(rt.answerApproval(requireNotNull(rt.state.value.networkDecisions.single().approvalId), ApprovalChoice.Once))
    }
    @Test fun denyAddedWhileWaitingAndManagedLockPreventHumanOverride() = run { hub, rt, fs, _, _, _ -> coroutineScope {
        val request = async { rt.decideNetwork(CallId("c"), "curl", NetworkTarget("example.test", 443), emptyList()) }
        val card = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Network
        fs.put("/etc/claude-code/managed-settings.json", """{"sandbox":{"enabled":true,"network":{"allowManagedDomainsOnly":true}}}""")
        assertTrue(rt.answerApproval(card.request.approvalId, ApprovalChoice.Always("example.test")))
        assertEquals(NetworkOutcome.Deny, request.await().outcome)
        assertEquals(NetworkSource.ManagedLock, rt.state.value.networkDecisions.single().source)
        assertFalse(fs.read(GuestPath("/root/.claude/settings.json")).decodeToString().contains("example.test"))
    } }
    @Test fun autoDomainsForceClassifierEvenWithBashAllowRule() {
        val reviewer = ScriptedModel(reply(TextPart("""{"decision":"allow","reason":"ok","hard_deny_matches":[]}""")))
        run(PermissionMode.Auto, reviewer = reviewer, hosts = listOf("example.test"), rule = "Bash(curl *)") { hub, rt, _, _, _, _ ->
            rt.send(ClientMsgId("one"), listOf(TextPart("fetch")))
            rt.state.first { it.turn == null && it.lastTurn != null }
            assertEquals(1, reviewer.requests.size)
            assertTrue(reviewer.requests.single().messages.toString().contains("allowed_domains"))
            assertEquals(NetworkSource.Classifier, rt.state.value.networkDecisions.single().source)
            assertEquals(NetworkOutcome.Allow, rt.state.value.networkDecisions.single().outcome)
            assertTrue(hub.pending.value.isEmpty())
        }
    }
    @Test fun autoUnlistedHostIsDeniedWithoutPrompt() = run(PermissionMode.Auto,
        reviewer = ScriptedModel(reply(TextPart("""{"decision":"allow","reason":"ok","hard_deny_matches":[]}""")))) { hub, rt, _, _, _, _ ->
        rt.send(ClientMsgId("one"), listOf(TextPart("fetch")))
        rt.state.first { it.turn == null && it.lastTurn != null }
        assertEquals(NetworkOutcome.Deny, rt.state.value.networkDecisions.single().outcome)
        assertTrue(rt.state.value.toolResults.getValue(CallId("c")).isError); assertTrue(hub.pending.value.isEmpty())
    }
    @Test fun processRestartClosesOrphanNetworkApproval() = run { _, rt, _, _, _, _ ->
        val event = NetworkApprovalRequested(ApprovalId("dead"), CallId("c"), "example.test", 443, "curl", 0)
        rt.commit(listOf(event), Durability.Sync)
        val recovery = planRecovery(rt.state.value).filterIsInstance<NetworkDecision>().single()
        assertEquals(NetworkOutcome.Deny, recovery.outcome); assertEquals(NetworkSource.Timeout, recovery.source)
    }
}
