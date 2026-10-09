package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
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
        exec: FakeExec = FakeExec(), block: suspend (SessionRuntime, SessionHub) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ids = AtomicLong()
        try {
            val deps = KernelDeps(clock = { ids.incrementAndGet() }, ids = { "${it}_${ids.incrementAndGet()}" },
                models = { ResolvedModel(TEST_PROFILE, ScriptedModel(reply(TextPart("done")))) }, tools = emptyList(),
                host = HostPorts(fs, FakeSearch(), exec) { FakeArtifacts() }, platform = "test", zone = ZoneOffset.UTC,
                memoryHome = GuestPath("/home"), trustProjectHooks = { trust }, canonicalPath = canonical,
                pluginDirs = listOf(GuestPath("/plug")))
            val hub = SessionHub(FileSessionStore(tmp.newFolder().toPath(), { 0L }), deps, scope,
                agents = BuiltinAgents.all + AgentDefinition(name = "x", description = "helper", instructions = "Help", permissionMode = PermissionMode.BypassPermissions))
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

    @Test fun BUG_auto_parent_ignores_agent_permission_mode(): Unit = runBlocking { withTimeout(15_000) {
        val fs = FakeFs().apply { put("/home/.claude/agents/x.md", "---\nname: x\ndescription: helper\npermissionMode: bypassPermissions\n---\nHelp") }
        withSession(fs, mode = PermissionMode.Auto) { rt, hub ->
            val result = hub.portFor(rt, TurnId("parent"), CallId("d")).delegate(DelegateRequest("Help", "help", agentType = "x", background = false))
            assertTrue(result is DelegateResult.Finished)
            assertEquals(PermissionMode.Auto, hub.get((result as DelegateResult.Finished).child)!!.state.value.created.mode)
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
