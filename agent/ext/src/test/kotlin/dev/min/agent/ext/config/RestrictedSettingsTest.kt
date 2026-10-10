package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--restricted`（CC cli-reference :123）：只加载托管设置与 `--settings`；用户层、项目层、local 层都不读。
 * 同一份文件系统，开与不开 restricted 各读一次，对照出差别。
 */
class RestrictedSettingsTest {
    private val managed = "/etc/claude-code/managed-settings.json"

    private fun seed(fs: InMemoryFs) {
        fs.file("/root/.claude/settings.json", """{"permissions":{"allow":["Bash(user-rule)"]}}""")
        fs.file("/workspace/.claude/settings.json", """{"permissions":{"allow":["Bash(project-rule)"]}}""")
        fs.file("/workspace/.claude/settings.local.json", """{"permissions":{"allow":["Bash(local-rule)"]}}""")
        fs.file("/repo/.git", "gitdir: /elsewhere")
        fs.file("/repo/.claude/settings.local.json", """{"permissions":{"allow":["Bash(repo-local-rule)"]}}""")
        fs.file(managed, """{"permissions":{"deny":["Bash(managed-rule)"]}}""")
    }

    private fun load(fs: InMemoryFs, cwd: String, inputs: SettingsInputs) =
        runBlocking { SettingsLoader.load(fs, GuestPath(cwd), GuestPath("/root"), GuestPath(managed), inputs) }

    private fun SettingsSnapshot.texts() = merged.ruleSpecs().map { it.text }.toSet()

    @Test
    fun `对照组：不开 restricted，用户、项目、local 与托管都读`() {
        val fs = InMemoryFs().also { seed(it) }
        assertEquals(setOf("Bash(user-rule)", "Bash(project-rule)", "Bash(local-rule)", "Bash(managed-rule)"),
            load(fs, "/workspace", SettingsInputs()).texts().filter { it != "Bash(repo-local-rule)" }.toSet())
    }

    @Test
    fun `restricted：用户、项目、local 都不读，托管照读`() {
        val fs = InMemoryFs().also { seed(it) }
        val texts = load(fs, "/workspace", SettingsInputs(restricted = true)).texts()
        assertEquals(setOf("Bash(managed-rule)"), texts)
    }

    @Test
    fun `restricted：从子目录启动时，git 根上的 local 也不读`() {
        val fs = InMemoryFs().also { seed(it) }
        val texts = load(fs, "/repo/sub", SettingsInputs(restricted = true)).texts()
        assertTrue("仓库根的 local 不该读：$texts", "Bash(repo-local-rule)" !in texts)
        assertEquals(setOf("Bash(managed-rule)"), texts)
    }

    @Test
    fun `restricted：--settings 照常读，并参与合并`() {
        val fs = InMemoryFs().also { seed(it) }
        val cli = SettingsInputs(cli = CliSettings("--settings", """{"permissions":{"deny":["Bash(cli-rule)"]}}"""), restricted = true)
        val snapshot = load(fs, "/workspace", cli)
        assertEquals(setOf("Bash(managed-rule)", "Bash(cli-rule)"), snapshot.texts())
        assertTrue(snapshot.merged.ruleSpecs().any { it.text == "Bash(cli-rule)" && it.source == ConfigSource.Cli.label })
    }

    @Test
    fun `restricted：项目 hooks 与 env 也不进来`() {
        val fs = InMemoryFs().also { seed(it) }
        fs.file("/workspace/.claude/settings.json", """{"env":{"PROJECT_ONLY":"1"},"hooks":{"Stop":[{"hooks":[{"type":"command","command":"echo project"}]}]}}""")
        val s = load(fs, "/workspace", SettingsInputs(restricted = true))
        assertEquals(emptyMap<String, String>(), s.merged.env)
        assertTrue(s.merged.hooks.none { it.source == ConfigSource.Project })
    }
}
