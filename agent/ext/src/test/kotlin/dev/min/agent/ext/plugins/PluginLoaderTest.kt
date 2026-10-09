package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.ext.mcp.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginLoaderTest {
    private fun FakeFs.plugin(name: String, fields: String = "") {
        put("/plugins/$name/.claude-plugin/plugin.json", """{"name":"$name"$fields}""")
    }
    private fun request(name: String, market: String? = null) = PluginRequest("/plugins/$name", PluginRef(name, market))

    @Test fun `invalid installation path isolates its record and dotted plugin name reaches MCP`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.put("/home/.claude/plugins/installed_plugins.json", """{"plugins":{"bad@market":[{"scope":"user","installPath":"invalid"}]}}""")
        fs.plugin("dotted.name", """, "mcpServers":{"api":{"command":"node"}}""")
        val settings = SettingsLoader.load(fs, GuestPath("/work"), GuestPath("/home"))
        val bundle = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"), settings.merged,
            dirs = listOf(GuestPath("/plugins/dotted.name")), trust = { true },
            installedPath = { throw IllegalArgumentException("fake sensitive message") })
        assertEquals(listOf("dotted.name"), bundle.batch.plugins.map { it.namespace })
        assertTrue(bundle.notes.any { "安装记录路径无效" in it })
        assertFalse(bundle.notes.toString().contains("fake sensitive message"))
        val mcp = McpConfigLoader.load(fs, GuestPath("/home"), GuestPath("/work"), settings.merged, true, { null }, plugins = bundle.mcpSources())
        assertEquals("plugin:dotted.name:api", mcp.servers.single().spec.name)
        assertEquals(McpGate.Enabled, mcp.servers.single().gate)
    }

    @Test fun `later settings override and dependency default disabled is lifted`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.plugin("base", """, "defaultEnabled":false, "settings":{"agent":"first"}""")
        fs.plugin("top", """, "dependencies":["base"], "settings":{"agent":"last"}""")
        val batch = PluginLoader(fs).loadAll(listOf(request("base", "team"), request("top", "team")))
        assertEquals(listOf("base@team", "top@team"), batch.plugins.map { it.ref.id })
        assertTrue(batch.plugins.all { it.enabled })
        assertEquals(JsonPrimitive("last"), batch.settings["agent"])
        assertEquals(listOf("top@team overrides setting agent"), batch.overrides)
    }

    @Test fun `cycles and missing dependencies isolate the failed plugins`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.plugin("a", """, "dependencies":["b"]""")
        fs.plugin("b", """, "dependencies":[{"name":"a"}]""")
        fs.plugin("c", """, "dependencies":["missing@other"]""")
        fs.plugin("ok")
        val batch = PluginLoader(fs).loadAll(listOf("a", "b", "c", "ok").map { request(it) })
        assertEquals(listOf("ok"), batch.plugins.map { it.namespace })
        assertTrue(batch.errors.any { "循环依赖" in it })
        assertTrue(batch.errors.any { "missing@other" in it })
    }

    @Test fun `last local candidate with the same identity wins`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.put("/one/.claude-plugin/plugin.json", """{"name":"same", "settings":{"agent":"first"}}""")
        fs.put("/two/.claude-plugin/plugin.json", """{"name":"same", "settings":{"agent":"last"}}""")
        val batch = PluginLoader(fs).loadAll(listOf(PluginRequest("/one", null), PluginRequest("/two", null)))
        assertEquals("/two", batch.plugins.single().root)
        assertEquals(JsonPrimitive("last"), batch.settings["agent"])
    }

    @Test fun `all three path failures reject only the bad plugin`(): Unit = runBlocking {
        for (raw in listOf("commands/a.md", "./../outside/a.md", "./commands/missing.md")) {
            val fs = FakeFs()
            fs.plugin("bad", """, "commands":${JsonPrimitive(raw)}""")
            fs.put("/plugins/bad/commands/a.md", "body")
            fs.put("/plugins/outside/a.md", "outside")
            fs.plugin("ok")
            val batch = PluginLoader(fs).loadAll(listOf(request("bad"), request("ok")))
            assertEquals(raw, listOf("bad", "ok"), batch.plugins.map { it.namespace })
            assertTrue(batch.plugins.first().commands.isEmpty())
            assertTrue(batch.errors.isNotEmpty())
        }
    }

    @Test fun `canonical component containment rejects links outside the plugin`(): Unit = runBlocking {
        val fs = FakeFs()
        // 清单保持合法，真正的失败必须发生在宿主路径校验上。
        fs.put("/plugins/bad/.claude-plugin/plugin.json", """{"name":"bad","commands":"./commands/a.md"}""")
        fs.put("/plugins/bad/commands/a.md", "body")
        val result = PluginLoader(fs) { path -> if (path.value.endsWith("/commands/a.md")) GuestPath("/outside/a.md") else path }.load(request("bad"))
        assertNotNull(result.plugin)
        assertTrue(result.plugin!!.commands.isEmpty())
        assertTrue(result.errors.any { "escapes plugin directory" in it })
        assertEquals("同一组件只报告实际路径错误，不能另报不存在", 1, result.errors.size)
    }

    @Test fun `bad JSON and field types do not escape and cancellation always does`(): Unit = runBlocking {
        val fs = FakeFs()
        for (text in listOf("[1]", "{broken", """{"name":1}""", """{"name":"bad","defaultEnabled":"true"}""")) {
            fs.put("/bad/.claude-plugin/plugin.json", text)
            val result = PluginLoader(fs).load(PluginRequest("/bad", null))
            assertNull(result.plugin)
            assertTrue(result.errors.isNotEmpty())
        }
        val cancelled = object : WorkspaceFs by fs { override suspend fun stat(path: GuestPath): FileStat? = throw CancellationException("cancel") }
        try { PluginLoader(cancelled).load(PluginRequest("/bad", null)); fail("取消被吞掉") }
        catch (_: CancellationException) { }
    }

    @Test fun `official source cannot bypass directory trust and project pluginConfigs cannot supply values`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.put("/home/.claude/plugins/installed_plugins.json", """{"plugins":{"official@claude-plugins-official":[{"scope":"project","projectPath":"/work","installPath":"/work/plugin"}]}}""")
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"official@claude-plugins-official":true},"pluginConfigs":{"official@claude-plugins-official":{"options":{"endpoint":"user"}}}}""")
        fs.put("/work/.claude/settings.json", """{"pluginConfigs":{"official@claude-plugins-official":{"options":{"endpoint":"project"}}}}""")
        fs.put("/work/plugin/.claude-plugin/plugin.json", """{"name":"official","userConfig":{"endpoint":{"type":"string","title":"Endpoint","description":"Endpoint"}},"mcpServers":{"api":{"command":"node","env":{"ENDPOINT":"${'$'}{user_config.endpoint}"}}},"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"bad"}]}]}}""")
        val settings = SettingsLoader.load(fs, GuestPath("/work"), GuestPath("/home"))
        val bundle = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"), settings.merged)
        assertTrue(bundle.hookSources.isEmpty())
        val blocked = McpConfigLoader.load(fs, GuestPath("/home"), GuestPath("/work"), settings.merged, false, { null }, plugins = bundle.mcpSources())
        assertEquals(McpScope.Plugin, blocked.servers.single().spec.scope)
        assertEquals(McpGate.Untrusted, blocked.servers.single().gate)
        val trusted = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"), settings.merged, trust = { true })
        val loaded = McpConfigLoader.load(fs, GuestPath("/home"), GuestPath("/work"), settings.merged, true, { null }, plugins = trusted.mcpSources())
        assertEquals("user", (loaded.servers.single().spec as McpServerSpec.Stdio).env["ENDPOINT"])
    }

    @Test fun `MCP secrets go to process env and never into settings or body`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.plugin("private", """, "userConfig":{"token":{"type":"string","title":"Token","description":"Token","sensitive":true},"endpoint":{"type":"string","title":"Endpoint","description":"Endpoint"}}""")
        val plugin = PluginLoader(fs).load(request("private")).plugin!!
        val records = mutableMapOf<McpTokenKey, McpOAuthCredentials>()
        val store = PluginSecretStore(object : McpTokenStore {
            override suspend fun load(key: McpTokenKey) = records[key]
            override suspend fun save(key: McpTokenKey, credentials: McpOAuthCredentials) { records[key] = credentials }
            override suspend fun delete(key: McpTokenKey) { records.remove(key) }
        })
        val secret = "fake-plugin-credential-6789"
        val saved = savePluginOptions(plugin, JsonObject(mapOf("token" to JsonPrimitive(secret), "endpoint" to JsonPrimitive("https://example.test"))), store)
        fs.put("/home/.claude/settings.json", saved.toString())
        assertFalse(fs.read(GuestPath("/home/.claude/settings.json")).decodeToString().contains(secret))
        val values = PluginValues.read(plugin, saved["options"]!!.jsonObject, store)
        val vars = PluginVars("/plugins/private", "/home/data", "/work")
        val raw = "${'$'}{user_config.token} ${'$'}{user_config.endpoint} ${'$'}{CLAUDE_PLUGIN_ROOT}"
        assertTrue(resolvePluginMcp(raw, vars, values).contains(secret))
        val body = resolvePluginBody(raw, vars, values, setOf("token"))
        assertFalse(body.contains(secret))
        assertTrue(body.contains("${'$'}{user_config.token}"))
        assertTrue(body.contains("https://example.test"))
        assertFalse(values.toString().contains(secret))
        // settings 中已有敏感值也不能被读取路径默默采纳。
        val empty = PluginValues.read(plugin, JsonObject(mapOf("token" to JsonPrimitive(secret))), PluginSecretStore(McpTokenStore.Unavailable))
        assertNull(empty.value("token"))
    }

    @Test fun `frontmatter hooks receive plugin env and shell user config references are rejected`(): Unit = runBlocking {
        val fs = FakeFs()
        fs.put("/home/.claude/settings.json", """{"pluginConfigs":{"env":{"options":{"mode":"review"}}}}""")
        fs.put("/plugins/env/.claude-plugin/plugin.json", """{"name":"env","userConfig":{"mode":{"type":"string","title":"Mode","description":"Mode"}},"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"echo ${'$'}{user_config.mode}"}]}]},"mcpServers":{"api":{"type":"http","url":"https://example.test/mcp","headersHelper":"echo ${'$'}{user_config.mode}"}}}""")
        fs.put("/plugins/env/skills/run/SKILL.md", """---
description: Inspect
hooks:
  PreToolUse:
    - matcher: Read
      hooks:
        - type: command
          command: inspect
          args:
            - ${'$'}{user_config.mode}
---
Inspect
""")
        val settings = SettingsLoader.load(fs, GuestPath("/work"), GuestPath("/home"))
        val bundle = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"), settings.merged,
            dirs = listOf(GuestPath("/plugins/env")), trust = { true })
        assertTrue(bundle.hookSources.isEmpty())
        assertTrue(bundle.notes.any { "shell hook" in it })
        val handlers = dev.min.agent.ext.hooks.HookSet.parse(listOf(dev.min.agent.ext.config.HookSource(
            dev.min.agent.ext.config.ConfigSource.Plugin, bundle.skills.single().hooks!!,
        ))).handlers
        assertEquals(listOf("review"), handlers.single().args)
        assertEquals("/plugins/env", handlers.single().pluginEnv["CLAUDE_PLUGIN_ROOT"])
        assertEquals("review", handlers.single().pluginEnv["CLAUDE_PLUGIN_OPTION_MODE"])
        assertTrue(bundle.mcpSources().single().error!!.contains("headersHelper"))
    }
}
