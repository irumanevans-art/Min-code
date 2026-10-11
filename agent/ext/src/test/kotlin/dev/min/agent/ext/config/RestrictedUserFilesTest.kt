package dev.min.agent.ext.config

import dev.min.agent.ext.mcp.McpConfigLoader
import dev.min.agent.ext.styles.OutputStyleFiles
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--restricted` 的用户层收口（Min 的解释，CC 原文没逐项列）：用户目录（`~/.claude` 与 `~/.claude.json`）下的
 * skills、命令、agents、输出样式、MCP 服务器一律不读——那是本机用户的东西；项目层（仓库里的）与托管层照常读。
 * MCP 只连托管层与 `--settings` 里声明的服务器。每条都配对照组（不开 restricted）。
 */
class RestrictedUserFilesTest {
    private val managed = "/etc/claude-code/managed-settings.json"

    private fun seed(fs: InMemoryFs) {
        // 用户层（~/.claude 与 ~/.claude.json）
        fs.file("/root/.claude/skills/us/SKILL.md", "---\ndescription: user skill\n---\nU")
        fs.file("/root/.claude/commands/uc.md", "---\ndescription: user command\n---\nU")
        fs.file("/root/.claude/agents/ua.md", "---\nname: ua\ndescription: user agent\n---\nU")
        fs.file("/root/.claude/output-styles/us.md", "---\ndescription: user style\n---\nU")
        fs.file("/root/.claude.json", """{"mcpServers":{"usersrv":{"command":"u"}},""" +
            """"projects":{"/workspace":{"mcpServers":{"localsrv":{"command":"l"}}}}}""")
        // 项目层（仓库里的）
        fs.file("/workspace/.claude/skills/ps/SKILL.md", "---\ndescription: project skill\n---\nP")
        fs.file("/workspace/.claude/commands/pc.md", "---\ndescription: project command\n---\nP")
        fs.file("/workspace/.claude/agents/pa.md", "---\nname: pa\ndescription: project agent\n---\nP")
        fs.file("/workspace/.claude/output-styles/ps.md", "---\ndescription: project style\n---\nP")
        fs.file("/workspace/.mcp.json", """{"mcpServers":{"projsrv":{"command":"p"}}}""")
        // 托管层
        fs.file("/etc/claude-code/.claude/skills/ms/SKILL.md", "---\ndescription: managed skill\n---\nM")
    }

    private val orgCli = """{"managedMcpServers":{"orgsrv":{"type":"http","url":"https://mcp.example.com/x"}}}"""

    private fun config(fs: InMemoryFs, restricted: Boolean, cli: String? = null): ClaudeConfig = runBlocking {
        val inputs = SettingsInputs(cli = cli?.let { CliSettings("--settings", it) }, restricted = restricted)
        ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"), GuestPath(managed), inputs)
    }

    @Test fun `对照组：不开 restricted，用户层的 skills、命令、agents 都读到`() {
        val fs = InMemoryFs().also { seed(it) }
        val c = config(fs, restricted = false)
        assertTrue(c.skills.any { it.name == "us" })
        assertTrue(c.commands.any { it.name == "uc" })
        assertTrue(c.agents.any { it.name == "ua" })
    }

    @Test fun `restricted：用户层的 skills、命令、agents 不读，项目与托管层照读`() {
        val fs = InMemoryFs().also { seed(it) }
        val c = config(fs, restricted = true)
        assertFalse("用户 skill 不该读", c.skills.any { it.name == "us" })
        assertFalse("用户命令不该读", c.commands.any { it.name == "uc" })
        assertFalse("用户 agent 不该读", c.agents.any { it.name == "ua" })
        assertTrue("项目 skill 照读", c.skills.any { it.name == "ps" })
        assertTrue("项目命令照读", c.commands.any { it.name == "pc" })
        assertTrue("项目 agent 照读", c.agents.any { it.name == "pa" })
        assertTrue("托管 skill 照读", c.skills.any { it.name == "ms" })
    }

    @Test fun `对照组：不开 restricted，用户与项目的输出样式都读到`() {
        val fs = InMemoryFs().also { seed(it) }
        val styles = runBlocking { OutputStyleFiles.load(fs, GuestPath("/workspace"), GuestPath("/root")) }.styles.keys
        assertTrue(styles.containsAll(listOf("us", "ps")))
    }

    @Test fun `restricted：输出样式的用户目录不读，项目目录照读`() {
        val fs = InMemoryFs().also { seed(it) }
        val styles = runBlocking {
            OutputStyleFiles.load(fs, GuestPath("/workspace"), GuestPath("/root"), includeUser = false)
        }.styles.keys
        assertFalse("用户样式不该读：$styles", "us" in styles)
        assertTrue("项目样式照读：$styles", "ps" in styles)
    }

    @Test fun `对照组：不开 restricted，MCP 的用户层、本项目 local、项目 mcp 配置 都在`() {
        val fs = InMemoryFs().also { seed(it) }
        val names = mcpNames(fs, restricted = false)
        assertTrue(names.containsAll(listOf("usersrv", "localsrv", "projsrv")))
    }

    @Test fun `restricted：MCP 不连用户层、本项目 local 与项目 mcp 配置，只连托管与 --settings 声明的`() {
        val fs = InMemoryFs().also { seed(it) }
        val names = mcpNames(fs, restricted = true, cli = orgCli)
        assertEquals(listOf("orgsrv"), names)
    }

    @Test fun `restricted：没有 --settings 声明的服务器时一台 MCP 都不连`() {
        val fs = InMemoryFs().also { seed(it) }
        assertEquals(emptyList<String>(), mcpNames(fs, restricted = true))
    }


    private fun mcpNames(fs: InMemoryFs, restricted: Boolean, cli: String? = null): List<String> = runBlocking {
        val inputs = SettingsInputs(cli = cli?.let { CliSettings("--settings", it) }, restricted = restricted)
        val settings = SettingsLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"), GuestPath(managed), inputs)
        McpConfigLoader.load(
            fs, GuestPath("/root"), GuestPath("/workspace"), settings.merged, trustProject = true,
            env = { null }, restricted = restricted,
        ).servers.map { it.spec.name }.sorted()
    }
}
