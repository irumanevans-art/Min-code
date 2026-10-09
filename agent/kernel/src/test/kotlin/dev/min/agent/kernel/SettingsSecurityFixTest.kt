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

class SettingsSecurityFixTest {
    @get:Rule val tmp = TemporaryFolder()
    private suspend fun withSession(fs: FakeFs, trust: Boolean = false, mode: PermissionMode = PermissionMode.Default,
        canonical: (GuestPath) -> GuestPath = { it }, cwd: GuestPath = GuestPath("/work"), headless: Boolean = false,
        exec: FakeExec = FakeExec(), workflowEngine: WorkflowEngine? = null, env: Map<String, String> = emptyMap(),
        block: suspend (SessionRuntime, SessionHub) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        try {
            val deps = KernelDeps(clock = { ids.incrementAndGet() }, ids = { "${it}_${ids.incrementAndGet()}" },
                models = { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("done")))) }, tools = emptyList(),
                host = HostPorts(fs, FakeSearch(), exec) { FakeArtifacts() }, platform = "test", zone = ZoneOffset.UTC,
                memoryHome = GuestPath("/home"), trustProjectHooks = { trust }, canonicalPath = canonical,
                pluginDirs = listOf(GuestPath("/plug")), workflowEngine = workflowEngine, env = env::get)
            val definitions = dev.min.agent.ext.config.ClaudeConfigLoader.load(fs, cwd, GuestPath("/home")).agents
            val catalog = AgentCatalog.of(definitions)
            val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope, agents = catalog.definitions)
            block(hub.create(cwd, TEST_MODEL, mode, headless = headless), hub)
        } finally { scope.cancel() }
    }

    @Test fun BUG_project_and_unchecked_local_capabilities_require_trust_but_restrictions_do_not(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/work/.claude/settings.json", """{"permissions":{"allow":["Bash(*)"],"ask":["Bash(git push *)"],"deny":["Bash(rm *)"],"additionalDirectories":["/external"]}}""")
            put("/work/.claude/settings.local.json", """{"permissions":{"allow":["Bash(npm test)"],"additionalDirectories":["/local"]}}""")
        }
        withSession(fs) { rt, _ ->
            assertFalse(rt.state.value.rules.any { it.kind == RuleKindData.Allow && it.text == "Bash(*)" })
            assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Ask })
            assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Deny })
            assertFalse(rt.state.value.rules.any { it.source == "local" && it.kind == RuleKindData.Allow })
            assertTrue(rt.state.value.additionalDirectories.isEmpty())
        }
        withSession(fs, trust = true) { rt, _ ->
            assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Allow && it.text == "Bash(*)" })
            assertTrue(GuestPath("/external") in rt.state.value.additionalDirectories)
        }
    }

    @Test fun R2_local_trust_matrix_and_git_acceptance(): Unit = runBlocking {
        for (tracked in listOf(false, true)) for (headless in listOf(false, true)) {
            val fs = FakeFs().apply {
                mkdir("/work/.git")
                put("/work/.claude/settings.local.json", """{"permissions":{"allow":["Bash(curl *)"],"ask":["Bash(git *)"],"deny":["Bash(rm *)"],"additionalDirectories":["/external"]}}""")
            }
            val commands = mutableListOf<String>()
            val exec = FakeExec { command, _ ->
                commands += command.script
                ExecResult.Exited(if (tracked) 0 else 1, "", false, 1)
            }
            withSession(fs, headless = headless, exec = exec) { rt, _ ->
                val enabled = headless && !tracked
                assertEquals("tracked=$tracked headless=$headless", enabled, rt.state.value.rules.any { it.kind == RuleKindData.Allow })
                assertEquals(enabled, GuestPath("/external") in rt.state.value.additionalDirectories)
                assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Ask })
                assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Deny })
                assertEquals(headless, commands.any { "ls-files" in it })
                assertEquals(!enabled, rt.state.value.permissionNotes.any { "this workspace has not been trusted" in it })
            }
        }
        val fs = FakeFs().apply { put("/home/.claude/settings.local.json", """{"permissions":{"allow":["Bash(curl *)"],"additionalDirectories":["/external"]}}""") }
        val exec = FakeExec { _, _ -> error("配置主目录不运行 git") }
        withSession(fs, cwd = GuestPath("/home"), exec = exec) { rt, _ ->
            assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Allow })
            assertTrue(GuestPath("/external") in rt.state.value.additionalDirectories)
        }
    }

    @Test fun R2_linked_claude_local_is_held_in_headless(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/work/.claude/settings.local.json", """{"permissions":{"allow":["Bash(curl *)"],"additionalDirectories":["/external"]}}""") }
        withSession(fs, headless = true, canonical = { if (it.value.startsWith("/work/.claude")) GuestPath(it.value.replace("/work/.claude", "/repo-config")) else it }) { rt, _ ->
            assertFalse(rt.state.value.rules.any { it.kind == RuleKindData.Allow })
            assertTrue(rt.state.value.additionalDirectories.isEmpty())
        }
    }

    @Test fun R2_config_home_inside_repo_exempts_own_file_but_holds_relocated_root(): Unit = runBlocking {
        val settings = """{"permissions":{"allow":["Bash(curl *)"],"additionalDirectories":["/external"]}}"""
        val fs = FakeFs().apply {
            mkdir("/repo/.git")
            put("/repo/config/.claude/settings.local.json", settings)
            put("/repo/.claude/settings.local.json", settings)
        }
        withSession(fs, cwd = GuestPath("/repo/config"), env = mapOf("CLAUDE_CONFIG_DIR" to "/repo/config/.claude"),
            exec = FakeExec { _, _ -> error("接受工作区前不能启动 git") }) { rt, _ ->
            assertEquals(1, rt.state.value.rules.count { it.kind == RuleKindData.Allow })
            assertTrue(GuestPath("/external") in rt.state.value.additionalDirectories)
            assertTrue(rt.state.value.permissionNotes.any { "this workspace has not been trusted" in it })
        }
        val root = FakeFs().apply { mkdir("/home/.git"); put("/home/.claude/settings.local.json", settings) }
        withSession(root, cwd = GuestPath("/home")) { rt, _ -> assertTrue(rt.state.value.rules.isEmpty()) }
    }

    @Test fun R2_headless_local_no_repo_or_failed_git_and_cancellation(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/work/.claude/settings.local.json", """{"permissions":{"allow":["Bash(curl *)"]}}""") }
        withSession(fs, headless = true, exec = FakeExec { _, _ -> error("无仓库不启动 git") }) { rt, _ ->
            assertTrue(rt.state.value.rules.any { it.kind == RuleKindData.Allow })
        }
        fs.mkdir("/work/.git")
        withSession(fs, headless = true, exec = FakeExec { _, _ -> throw IllegalStateException("git unavailable") }) { rt, _ ->
            assertFalse(rt.state.value.rules.any { it.kind == RuleKindData.Allow })
        }
        try {
            withSession(fs, headless = true, exec = FakeExec { _, _ -> throw CancellationException("cancel") }) { _, _ -> fail("取消必须传播") }
            fail("取消必须传播")
        } catch (_: CancellationException) { }
    }

    @Test fun R2_inherited_managed_locks_apply_without_loading_settings(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (mode in listOf(PermissionMode.Auto, PermissionMode.BypassPermissions)) {
                val deps = testDeps(ScriptedModel(), emptyList())
                val rt = SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), { 0L }),
                    LogHeader(dev.min.agent.store.LOG_FORMAT_VERSION, SessionId(mode.name), 0),
                    SessionCreated(GuestPath("/work"), TEST_MODEL, mode), deps, scope,
                    initial = listOf(AutoReviewSettingsLoaded(disableAutoMode = true, disableBypassPermissionsMode = true)),
                    loadSettings = false, loadMemory = false, loadSkills = false)
                assertEquals(PermissionMode.Default, rt.state.value.settings.mode)
                try { rt.changeSettings(mode = mode); fail("继承锁必须保留") } catch (_: IllegalArgumentException) { }
            }
        } finally { scope.cancel() }
    }

    @Test fun BUG_auto_parent_ignores_agent_permission_mode(): Unit = runBlocking { withTimeout(15_000) {
        val fs = FakeFs().apply { put("/home/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: bypassPermissions\n---\nHelp") }
        withSession(fs, mode = PermissionMode.Auto) { rt, hub ->
            val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false))
            assertTrue(result is DelegateResult.Finished)
            assertEquals(PermissionMode.Auto, hub.get((result as DelegateResult.Finished).child)!!.state.value.created.mode)
        }
    } }

    @Test fun R2_child_modes_follow_parent_and_managed_locks(): Unit = runBlocking { withTimeout(30_000) {
        for (parent in PermissionMode.entries) for (requested in listOf("bypassPermissions", "auto", "acceptEdits")) {
            val fs = FakeFs().apply { put("/home/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: $requested\n---\nHelp") }
            withSession(fs, mode = parent) { rt, hub ->
                val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false)) as DelegateResult.Finished
                val expected = when {
                    parent in setOf(PermissionMode.Auto, PermissionMode.BypassPermissions, PermissionMode.AcceptEdits) -> parent
                    requested == "bypassPermissions" -> parent
                    requested == "auto" -> PermissionMode.Auto
                    else -> PermissionMode.AcceptEdits
                }
                assertEquals("$parent/$requested", expected, hub.get(result.child)!!.state.value.settings.mode)
            }
        }
        val fs = FakeFs().apply {
            put("/home/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: auto\n---\nHelp")
            put("/etc/claude-code/managed-settings.json", """{"permissions":{"disableAutoMode":"disable","disableBypassPermissionsMode":"disable"}}""")
        }
        withSession(fs, mode = PermissionMode.Plan) { rt, hub ->
            val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false)) as DelegateResult.Finished
            val child = hub.get(result.child)!!
            assertEquals(PermissionMode.Plan, child.state.value.settings.mode)
            assertTrue(child.state.value.autoReviewSettings.disableAutoMode)
            assertTrue(child.state.value.autoReviewSettings.disableBypassPermissionsMode)
            try { child.changeSettings(mode = PermissionMode.BypassPermissions); fail("子会话托管锁") } catch (_: IllegalArgumentException) { }
        }
    } }

    @Test fun R2_project_agent_mode_requires_workspace_trust(): Unit = runBlocking { withTimeout(15_000) {
        val fs = FakeFs().apply { put("/work/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: acceptEdits\n---\nHelp") }
        for (trust in listOf(false, true)) withSession(fs, trust = trust) { rt, hub ->
            val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false)) as DelegateResult.Finished
            assertEquals(if (trust) PermissionMode.AcceptEdits else PermissionMode.Default, hub.get(result.child)!!.state.value.settings.mode)
        }
    } }

    @Test fun R2_TeamCreate_malicious_agentType_cannot_escalate(): Unit = runBlocking { withTimeout(15_000) {
        val fs = FakeFs().apply { put("/work/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: bypassPermissions\n---\nHelp") }
        withSession(fs) { rt, hub ->
            val result = hub.portFor(rt, TurnId("parent"), CallId("team")).team(TeamOp.Create(listOf(TeamMemberSpec("evil", "x", "help"))))
            assertFalse(result.isError)
            val member = rt.state.first { it.team?.member("evil")?.session != null }.team!!.member("evil")!!.session!!
            assertEquals(PermissionMode.Default, hub.get(member)!!.state.value.settings.mode)
        }
    } }

    @Test fun R2_workflow_agentType_uses_guarded_child_creation(): Unit = runBlocking { withTimeout(15_000) {
        val fs = FakeFs().apply {
            put("/work/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: bypassPermissions\n---\nHelp")
            put("/work/.claude/workflows/review.js", "export const meta={name:'review',description:'review'};")
        }
        val engine = WorkflowEngine { _, _, host -> host.agent(0, "help", buildJsonObject { put("agentType", "x") }) }
        withSession(fs, workflowEngine = engine) { rt, hub ->
            rt.send(ClientMsgId("workflow"), listOf(TextPart("/review")))
            val approval = hub.pending.first { it.isNotEmpty() }.single().what as HubPending.What.Approval
            rt.answerApproval(approval.approvalId, ApprovalChoice.Once)
            rt.state.first { it.turn == null && it.lastTurn != null }
            assertEquals(1, rt.state.value.children.size)
            val child = hub.get(rt.state.value.children.keys.single())!!
            assertTrue(child.state.value.origin!!.workflow)
            assertEquals(PermissionMode.Default, child.state.value.settings.mode)
            rt.awaitIdle()
        }
    } }

    @Test fun BUG_cwd_change_must_reapply_project_trust(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/other/.claude/settings.json", """{"permissions":{"allow":["Bash(*)"],"additionalDirectories":["/external"]}}""") }
        withSession(fs) { rt, _ ->
            rt.changeCwd(GuestPath("/other"), null)
            assertFalse(rt.state.value.rules.any { it.kind == RuleKindData.Allow && it.text == "Bash(*)" })
            assertFalse(GuestPath("/external") in rt.state.value.additionalDirectories)
        }
    }

    @Test fun BUG_malformed_auto_config_does_not_prevent_session_creation(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/home/.claude/settings.json", """{"autoMode":"oops"}""") }
        withSession(fs) { rt, _ -> assertTrue(rt.state.value.autoReviewSettings.unavailable) }
    }

    @Test fun BUG_managed_mode_locks_apply_at_creation_and_switch(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/etc/claude-code/managed-settings.json", """{"permissions":{"disableAutoMode":"disable","disableBypassPermissionsMode":"disable"}}""") }
        for (mode in listOf(PermissionMode.Auto, PermissionMode.BypassPermissions)) withSession(fs, mode = mode) { rt, _ ->
            assertEquals(PermissionMode.Default, rt.state.value.settings.mode)
            try { rt.changeSettings(mode = mode); fail("托管锁") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun BUG_plugin_load_failure_falls_back_but_cancellation_propagates(): Unit = runBlocking {
        withSession(FakeFs(), canonical = { throw IllegalStateException("private failure") }) { rt, _ ->
            assertTrue(rt.state.value.skills?.entries.orEmpty().isEmpty())
        }
        try { withSession(FakeFs(), canonical = { throw CancellationException("cancel") }) { _, _ -> }; fail("取消") }
        catch (_: CancellationException) { }
    }
}
