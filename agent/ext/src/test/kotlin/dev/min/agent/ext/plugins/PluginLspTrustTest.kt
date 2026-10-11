package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.SettingsLoader
import dev.min.agent.spi.*
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginLspTrustTest {
    @Test fun `project enable source cannot start LSP even in user cache`(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/home/.claude/plugins/installed_plugins.json", """{"plugins":{"check@m":[{"scope":"user","installPath":"/home/.claude/plugins/cache/m/check/1"}]}}""")
            put("/home/.claude/plugins/cache/m/check/1/.claude-plugin/plugin.json", """{"name":"check","lspServers":{"test":{"command":"fake","extensionToLanguage":{".t":"test"}}}}""")
            put("/home/.claude/settings.json", """{"enabledPlugins":{"check@m":false}}""")
            put("/work/.claude/settings.json", """{"enabledPlugins":{"check@m":true}}""")
        }
        suspend fun bundle(trust: Boolean) = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"),
            SettingsLoader.load(fs, GuestPath("/work"), GuestPath("/home")).merged, trust = { trust })
        val blocked = bundle(false)
        assertTrue(blocked.lspSources().isEmpty())
        assertTrue(blocked.batch.plugins.isEmpty())
        assertEquals(1, bundle(true).lspSources().size)
        fs.put("/home/.claude/settings.json", """{"enabledPlugins":{"check@m":true}}""")
        assertEquals(1, bundle(false).lspSources().size)
    }

    @Test fun `default then declared configs override names in order and export resolved variables`(): Unit = runBlocking {
        val fs = FakeFs().apply {
            put("/plugins/check/.claude-plugin/plugin.json", """{"name":"check","lspServers":["./first.json",{"test":{"command":"final","extensionToLanguage":{".t":"test"},"env":{"ROOT":"${'$'}{CLAUDE_PLUGIN_ROOT}"}}}]}""")
            put("/plugins/check/.lsp.json", """{"test":{"command":"default","extensionToLanguage":{".t":"test"}}}""")
            put("/plugins/check/first.json", """{"test":{"command":"first","extensionToLanguage":{".t":"test"}}}""")
        }
        val bundle = PluginBundle.load(fs, GuestPath("/work"), GuestPath("/home"), SettingsLoader.load(fs, GuestPath("/work"), GuestPath("/home")).merged,
            dirs = listOf(GuestPath("/plugins/check")))
        val spec = bundle.lspSources().single()
        assertEquals("final", spec.command)
        assertEquals("/plugins/check", spec.env["ROOT"])
        assertEquals("/work", spec.env["CLAUDE_PROJECT_DIR"])
        assertEquals("/plugins/check", spec.env["CLAUDE_PLUGIN_ROOT"])
    }
}
