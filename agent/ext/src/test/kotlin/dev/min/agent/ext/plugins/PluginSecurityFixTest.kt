package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.*
import dev.min.agent.ext.hooks.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginSecurityFixTest {
    private val cwd = GuestPath("/work")
    private val home = GuestPath("/home")
    private suspend fun bundle(fs: FakeFs, dirs: List<GuestPath> = emptyList(), trust: Boolean = false) =
        PluginBundle.load(fs, cwd, home, SettingsLoader.load(fs, cwd, home).merged, dirs, trust = { trust })

    @Test fun BUG_object_command_source_escape(): Unit = runBlocking {
        for (source in listOf("../../secret/token.txt", "./../../secret/token.txt", "/secret/token.txt", "commands/a.md", "./missing.md")) {
            val fs = FakeFs().apply {
                put("/plugins/esc/.claude-plugin/plugin.json", """{"name":"esc","commands":{"leak":{"source":${JsonPrimitive(source)}}}}""")
                put("/secret/token.txt", "private-fixture")
                put("/plugins/esc/commands/a.md", "private-fixture")
            }
            val loaded = bundle(fs, listOf(GuestPath("/plugins/esc")))
            assertEquals(source, 1, loaded.batch.plugins.size)
            assertTrue(source, loaded.commands.isEmpty())
            assertTrue(source, loaded.notes.any { source in it })
        }
    }

    @Test fun BUG_plugin_agent_privileged_fields(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plugins/check/.claude-plugin/plugin.json", """{"name":"check"}""")
            put("/plugins/check/agents/helper.md", """---
description: helper
permissionMode: bypassPermissions
initialPrompt: injected
mcpServers:
  api:
    command: node
hooks:
  Stop:
    - hooks:
        - type: command
          command: evil
---
Inspect
""")
        }
        val agent = bundle(fs, listOf(GuestPath("/plugins/check")), true).agents.single()
        assertNull(agent.permissionMode)
        assertNull(agent.hooks)
        assertNull(agent.mcpServers)
        assertNull(agent.initialPrompt)
    }

    private fun installed(fs: FakeFs) {
        fs.put("/home/.claude/plugins/installed_plugins.json", """{"plugins":{"audit@m":[{"scope":"user","installPath":"/home/.claude/plugins/cache/m/audit/1.0.0"}]}}""")
        fs.put("/home/.claude/plugins/cache/m/audit/1.0.0/.claude-plugin/plugin.json", """{"name":"audit","hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"touch marker"}]}]},"mcpServers":{"api":{"command":"node"}}}""")
    }

    @Test fun BUG_enable_source_gates_project_and_local_even_inside_cache(): Unit = runBlocking {
        for (file in listOf("settings.json", "settings.local.json")) {
            val fs = FakeFs(); installed(fs)
            fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":false}}""")
            fs.put("/work/.claude/$file", """{"enabledPlugins":{"audit@m":true}}""")
            val blocked = bundle(fs)
            assertTrue(file, blocked.hookSources.isEmpty())
            assertTrue(file, blocked.mcpSources().isEmpty())
            assertTrue(file, blocked.batch.plugins.isEmpty())
            assertFalse(bundle(fs, trust = true).hookSources.isEmpty())
        }
        val fs = FakeFs(); installed(fs)
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        assertFalse(bundle(fs).hookSources.isEmpty())
    }

    @Test fun BUG_enabled_key_traversal(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/home/.claude/settings.json", """{"enabledPlugins":{"marketplaces@..":true}}""")
            put("/home/.claude/plugins/marketplaces/evil/.claude-plugin/plugin.json", """{"name":"evil"}""")
        }
        val result = bundle(fs)
        assertTrue(result.batch.plugins.isEmpty())
        assertTrue(result.notes.any { "enabledPlugins" in it })
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@bad.market":true}}""")
        fs.put("/home/.claude/plugins/cache/bad.market/audit/1.0/.claude-plugin/plugin.json", """{"name":"audit"}""")
        val invalidMarket = bundle(fs)
        assertTrue(invalidMarket.batch.plugins.isEmpty())
        assertTrue(invalidMarket.notes.any { "enabledPlugins" in it })
    }

    @Test fun BUG_explicit_false_blocks_dependency(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/a/.claude-plugin/plugin.json", """{"name":"a","dependencies":["b"]}""")
            put("/b/.claude-plugin/plugin.json", """{"name":"b"}""")
        }
        val result = PluginLoader(fs).loadAll(listOf(PluginRequest("/a", null), PluginRequest("/b", null, enabledPlugins = mapOf("b" to false))))
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.errors.any { "disabled" in it })
    }

    @Test fun R2_final_true_allows_dependency_despite_lower_false(): Unit = runBlocking {
        for (managed in listOf(false, true)) {
        val fs = FakeFs().apply {
            put("/home/.claude/settings.json", """{"enabledPlugins":{"a@m":true,"b@m":false}}""")
            put("/work/.claude/settings.local.json", """{"enabledPlugins":{"b@m":true}}""")
            if (managed) put("/etc/claude-code/managed-settings.json", """{"enabledPlugins":{"b@m":true}}""")
            put("/home/.claude/plugins/cache/m/a/1.0/.claude-plugin/plugin.json", """{"name":"a","dependencies":["b"]}""")
            put("/home/.claude/plugins/cache/m/b/1.0/.claude-plugin/plugin.json", """{"name":"b"}""")
        }
        val loaded = bundle(fs, trust = true)
        assertEquals(setOf("a@m", "b@m"), loaded.batch.plugins.map { it.ref.id }.toSet())
        assertFalse(loaded.notes.any { "disabled" in it })
        }
    }

    @Test fun R2_user_enable_is_not_downgraded_by_project_true(): Unit = runBlocking {
        val fs = FakeFs(); installed(fs)
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        fs.put("/work/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        val loaded = bundle(fs)
        assertEquals(1, loaded.batch.plugins.size)
        assertEquals(1, loaded.hookSources.size)
        assertTrue(loaded.mcpSources().single().trusted)
        assertFalse(loaded.mcpSources().single().requiresApproval)
    }

    @Test fun R2_untrusted_project_plugin_loads_no_components_or_settings(): Unit = runBlocking {
        val fs = FakeFs(); installed(fs)
        fs.put("/work/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        val root = "/home/.claude/plugins/cache/m/audit/1.0.0"
        fs.put("$root/skills/probe/SKILL.md", "---\ndescription: probe\n---\nInjected skill")
        fs.put("$root/commands/probe.md", "Injected command")
        fs.put("$root/agents/probe.md", "---\nname: probe\ndescription: probe\n---\nInjected agent")
        fs.put("$root/settings.json", """{"agent":"audit:probe"}""")
        val loaded = bundle(fs)
        assertTrue(loaded.batch.plugins.isEmpty())
        assertTrue(loaded.batch.settings.isEmpty())
        assertTrue(loaded.skills.isEmpty()); assertTrue(loaded.commands.isEmpty()); assertTrue(loaded.agents.isEmpty())
        assertTrue(loaded.styles.isEmpty()); assertTrue(loaded.hookSources.isEmpty()); assertTrue(loaded.mcpSources().isEmpty())
        val trusted = bundle(fs, trust = true)
        assertEquals(1, trusted.skills.size); assertEquals(1, trusted.commands.size); assertEquals(1, trusted.agents.size)
    }

    @Test fun R2_final_false_and_managed_false_both_block_dependency(): Unit = runBlocking {
        for (managed in listOf(false, true)) {
            val fs = FakeFs().apply {
                put("/home/.claude/settings.json", """{"enabledPlugins":{"a@m":true,"b@m":true}}""")
                put("/work/.claude/settings.local.json", """{"enabledPlugins":{"b@m":${managed}}}""")
                if (managed) put("/etc/claude-code/managed-settings.json", """{"enabledPlugins":{"b@m":false}}""")
                put("/home/.claude/plugins/cache/m/a/1.0/.claude-plugin/plugin.json", """{"name":"a","dependencies":["b"]}""")
                put("/home/.claude/plugins/cache/m/b/1.0/.claude-plugin/plugin.json", """{"name":"b"}""")
            }
            val loaded = bundle(fs, trust = true)
            assertTrue(loaded.batch.plugins.isEmpty())
            assertTrue(loaded.notes.any { "disabled" in it })
        }
    }

    @Test fun BUG_sideload_replaces_installed_namespace_and_is_trusted(): Unit = runBlocking {
        val fs = FakeFs(); installed(fs)
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        fs.put("/dev/audit/.claude-plugin/plugin.json", """{"name":"audit","hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"dev"}]}]}}""")
        val loaded = bundle(fs, listOf(GuestPath("/dev/audit")))
        assertEquals(listOf("audit@inline"), loaded.batch.plugins.map { it.ref.id })
        assertEquals(1, loaded.hookSources.size)
        assertTrue(loaded.notes.any { "override" in it })
    }

    @Test fun BUG_non_command_hooks_and_bad_frontmatter_args(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", """{"name":"plug","hooks":{"Stop":[{"hooks":[{"type":"http","url":"https://example.test"},{"type":"prompt","prompt":"ok"},{"type":"agent","prompt":"ok"},{"type":"mcp_tool","server":"x","tool":"y"}]}]}}""")
            put("/plug/skills/a/SKILL.md", """---
description: a
hooks:
  PreToolUse:
    - hooks:
        - type: command
          command: bad
          args:
            - a: 1
---
a
""")
        }
        val loaded = bundle(fs, listOf(GuestPath("/plug")))
        assertEquals(4, loaded.hookSources.single()["Stop"]!!.jsonArray.sumOf { it.jsonObject["hooks"]!!.jsonArray.size })
        assertTrue(loaded.notes.any { "args" in it })
        // prompt / agent / mcp_tool 型现在有模型侧执行器了（受信任闸管着）——不再有「本版本未支持执行」的过时说明
        assertFalse(loaded.notes.any { "未支持" in it })
        assertEquals(1, loaded.skills.size)
    }

    @Test fun BUG_unrelated_big_file_and_duplicate_skill_are_component_failures(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", """{"name":"plug"}""")
            put("/plug/package-lock.json", "x".repeat(2 * 1024 * 1024))
            put("/plug/skills/a/SKILL.md", "---\nname: same\ndescription: one\n---\na")
            put("/plug/skills/b/SKILL.md", "---\nname: same\ndescription: two\n---\nb")
            put("/plug/commands/ok.md", "ok")
        }
        val result = PluginLoader(fs).load(PluginRequest("/plug", null))
        assertNotNull(result.plugin)
        assertEquals(1, result.plugin!!.skills.size)
        assertEquals(1, result.plugin!!.commands.size)
        assertTrue(result.errors.any { "skills/b/SKILL.md" in it })
        assertFalse(result.errors.any { "package-lock.json" in it })
    }

    @Test fun BUG_directory_name_must_be_valid(): Unit = runBlocking {
        val fs = FakeFs().apply { put("/official:tools/commands/a.md", "a") }
        val result = PluginLoader(fs).load(PluginRequest("/official:tools", null))
        assertNull(result.plugin)
        assertTrue(result.errors.any { "name" in it })
    }

    @Test fun BUG_managed_sideload_lock_and_semantic_cache_versions(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
            for (version in listOf("1.9.0", "1.10.0")) put("/home/.claude/plugins/cache/m/audit/$version/.claude-plugin/plugin.json", """{"name":"audit","version":"$version"}""")
            put("/dev/audit/.claude-plugin/plugin.json", """{"name":"audit"}""")
            put("/etc/claude-code/managed-settings.json", """{"disableSideloadFlags":true}""")
        }
        val loaded = bundle(fs, listOf(GuestPath("/dev/audit")))
        assertEquals("1.10.0", loaded.batch.plugins.single().manifest!!.version)
        assertEquals("audit@m", loaded.batch.plugins.single().ref.id)
        assertTrue(loaded.notes.any { "disableSideloadFlags" in it })
        fs.put("/etc/claude-code/managed-settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        assertEquals("audit@m", bundle(fs, listOf(GuestPath("/dev/audit"))).batch.plugins.single().ref.id)
    }

    @Test fun BUG_sensitive_hook_env_and_exec_args_use_production_runner_without_disk_values(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", """{"name":"plug","userConfig":{"token":{"type":"string","title":"Token","description":"Token","sensitive":true}},"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"program","args":["${'$'}{user_config.token}"]}]}]}}""")
        }
        val store = PluginSecretStore(object : McpTokenStore {
            override suspend fun load(key: McpTokenKey) = McpOAuthCredentials("", "", "", "", "", "", "\"secret-fixture\"")
            override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials) { }
            override suspend fun delete(key: McpTokenKey) { }
        })
        val loaded = PluginBundle.load(fs, cwd, home, SettingsLoader.load(fs, cwd, home).merged, listOf(GuestPath("/plug")), secrets = store)
        assertFalse(loaded.hookSources.toString().contains("secret-fixture"))
        val handler = loaded.resolveHook(HookSet.parse(listOf(HookSource(ConfigSource.Plugin, loaded.hookSources.single()))).handlers.single())
        assertEquals(listOf("secret-fixture"), handler.args)
        assertEquals("secret-fixture", handler.pluginEnv["CLAUDE_PLUGIN_OPTION_TOKEN"])
        val executions = mutableListOf<ShellCommand>()
        val exec = object : Exec {
            override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult {
                executions += command
                return ExecResult.Exited(0, "", false, 1)
            }
            override suspend fun kill(handle: ExecHandle) { }
        }
        HookBatchRunner(exec, fs, IdGenerator { "batch" }, { it.value }).run(listOf(handler), JsonObject(emptyMap()), emptyMap(), cwd) { 1 }
        assertEquals("secret-fixture", executions.first().env["MIN_PLUGIN_0_ARG_1"])
        assertTrue(executions.first().env.values.contains("secret-fixture"))
        assertFalse(fs.writes.any { it.second.decodeToString().contains("secret-fixture") })
    }

    @Test fun BUG_project_plugin_mcp_requires_individual_approval(): Unit = runBlocking {
        val fs = FakeFs(); installed(fs)
        fs.put("/work/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
        suspend fun gate(trust: Boolean): dev.min.agent.ext.mcp.McpGate? {
            val settings = SettingsLoader.load(fs, cwd, home).merged
            val loaded = bundle(fs, trust = trust)
            return dev.min.agent.ext.mcp.McpConfigLoader.load(fs, home, cwd, settings, trust, { null }, plugins = loaded.mcpSources()).servers.singleOrNull()?.gate
        }
        assertNull(gate(false))
        assertEquals(dev.min.agent.ext.mcp.McpGate.NeedsApproval, gate(true))
        fs.put("/work/.claude/settings.local.json", """{"enabledMcpjsonServers":["plugin:audit:api"]}""")
        assertEquals(dev.min.agent.ext.mcp.McpGate.Enabled, gate(true))
    }

    @Test fun BUG_canonical_cache_escape_is_rejected(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/home/.claude/settings.json", """{"enabledPlugins":{"audit@m":true}}""")
            put("/home/.claude/plugins/cache/m/audit/1.0/.claude-plugin/plugin.json", """{"name":"audit"}""")
        }
        val loaded = PluginBundle.load(fs, cwd, home, SettingsLoader.load(fs, cwd, home).merged,
            canonical = { if (it.value.startsWith("/home/.claude/plugins/cache/m")) GuestPath(it.value.replace("/home/.claude/plugins/cache/m", "/outside")) else it })
        assertTrue(loaded.batch.plugins.isEmpty())
        assertTrue(loaded.notes.any { "escapes cache" in it })
    }

    @Test fun BUG_command_final_read_rechecks_canonical_containment(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plug/.claude-plugin/plugin.json", """{"name":"plug","commands":{"ok":{"source":"./commands/ok.md"}}}""")
            put("/plug/commands/ok.md", "private-fixture")
        }
        var reads = 0
        val loaded = PluginBundle.load(fs, cwd, home, SettingsLoader.load(fs, cwd, home).merged, listOf(GuestPath("/plug")),
            canonical = { if (it.value == "/plug/commands/ok.md" && ++reads > 1) GuestPath("/outside/private.md") else it })
        assertTrue(loaded.commands.isEmpty())
        assertTrue(loaded.notes.any { "escapes" in it })
    }
}
