package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--add-dir` 目录的 `.claude/agents`（CC sub-agents 文档：「When you add a directory with --add-dir or /add-dir,
 * Claude Code also loads its .claude/agents/ folder, alongside your project subagents」；优先级表里没有它，
 * Min 取与项目同档、排在项目之后、用户之前；同文档：不监视，改了要重启）。
 */
class AddDirAgentsTest {
    private fun agent(name: String, description: String) = "---\nname: $name\ndescription: $description\n---\nprompt of $name"
    private val inputs = SettingsInputs(addDirs = listOf(GuestPath("/extra/lib")))

    @Test
    fun `an add-dir agent loads alongside project agents with its own source`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/workspace/.claude/agents/mine.md", agent("mine", "project one"))
        fs.file("/extra/lib/.claude/agents/theirs.md", agent("theirs", "added one"))
        val config = ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"), inputs = inputs)
        assertEquals(listOf("mine", "theirs"), config.agents.map { it.name }.sorted())
        assertEquals(ConfigSource.AddDir, config.agents.single { it.name == "theirs" }.source)
        assertEquals(ConfigSource.Project, config.agents.single { it.name == "mine" }.source)
    }

    @Test
    fun `a project agent beats an add-dir one and an add-dir one beats a user one`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/workspace/.claude/agents/a.md", agent("a", "project a"))
        fs.file("/extra/lib/.claude/agents/a.md", agent("a", "added a"))
        fs.file("/extra/lib/.claude/agents/b.md", agent("b", "added b"))
        fs.file("/root/.claude/agents/b.md", agent("b", "user b"))
        val config = ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"), inputs = inputs)
        assertEquals("project a", config.agents.single { it.name == "a" }.description)
        assertEquals("added b", config.agents.single { it.name == "b" }.description)
        assertTrue(config.notes.any { "`a`" in it })
    }

    @Test
    fun `without add-dir nothing from that directory is read`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/extra/lib/.claude/agents/theirs.md", agent("theirs", "added one"))
        val config = ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"))
        assertTrue(config.agents.isEmpty())
    }
}
