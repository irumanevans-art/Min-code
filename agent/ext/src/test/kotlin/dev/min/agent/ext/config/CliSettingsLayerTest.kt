package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--settings`（CC cli-reference :128）：会话级的一份设置，值盖过三个 settings.json，managed 仍盖过它。
 * 它是用户自己在命令行给的，所以它的 hooks 不受「工作区未信任」约束（与 user 层同等）。
 */
class CliSettingsLayerTest {
    private val fs = InMemoryFs()
    private val managed = "/etc/claude-code/managed-settings.json"

    private fun load(cli: String?, managedText: String? = null, cwd: String = "/workspace"): SettingsSnapshot {
        managedText?.let { fs.file(managed, it) }
        val inputs = SettingsInputs(cli = cli?.let { CliSettings("--settings", it) })
        return runBlocking { SettingsLoader.load(fs, GuestPath(cwd), GuestPath("/root"), GuestPath(managed), inputs) }
    }

    @Test fun `cli 层的 deny 规则参与合并，来源标为 cli`() {
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"allow":["Bash(git status)"]}}""")
        val s = load("""{"permissions":{"deny":["Bash(rm *)"]}}""")
        val deny = s.merged.ruleSpecs().single { it.kind == RuleSpecKind.Deny }
        assertEquals("Bash(rm *)", deny.text)
        assertEquals("cli", deny.source)
        // 项目层的规则照常在（合并不是替换）
        assertTrue(s.merged.ruleSpecs().any { it.text == "Bash(git status)" && it.source == "project" })
    }

    @Test fun `cli 层盖过项目层与 local 层，但 managed 仍盖过它`() {
        fs.file("/workspace/.claude/settings.json", """{"model":"project-model"}""")
        fs.file("/workspace/.claude/settings.local.json", """{"model":"local-model"}""")
        assertEquals("cli-model", load("""{"model":"cli-model"}""").merged.model)
        assertEquals("managed-model", load("""{"model":"cli-model"}""", managedText = """{"model":"managed-model"}""").merged.model)
    }

    @Test fun `cli 层排在 local 之上、managed 之下`() {
        val s = load("""{"model":"cli-model"}""", managedText = """{"model":"managed-model"}""")
        val order = s.layers.map { it.source }
        assertTrue(order.indexOf(ConfigSource.Cli) >= 0 && order.indexOf(ConfigSource.Managed) >= 0)
        assertTrue(order.indexOf(ConfigSource.Cli) < order.indexOf(ConfigSource.Managed))
    }

    @Test fun `cli 层的 hooks 在未信任的工作区也生效`() {
        val hooks = """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"true"}]}]}}"""
        val snapshot = load(hooks).merged.hookSnapshot(trustProject = false)
        assertTrue("cli 的 hooks 该在快照里：${snapshot.sources.map { it.source }}", snapshot.sources.any { it.source == ConfigSource.Cli })
    }

    @Test fun `没给 --settings 时没有 cli 层`() {
        assertNull(load(null).layers.firstOrNull { it.source == ConfigSource.Cli })
    }

    @Test fun `cli 层的坏 JSON 不抛异常，记一条说明跳过（CLI 启动时已先报错）`() {
        val s = load("{not json")
        assertNull(s.layers.firstOrNull { it.source == ConfigSource.Cli })
        assertTrue(s.notes.any { "JSON" in it })
        assertNotNull(s)
    }
}
