package dev.min.agent.cli

import dev.min.agent.ext.config.ClaudeConfigLoader
import dev.min.agent.ext.skills.SkillVars
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** 桌面输入入口：真的 `.claude/skills`、`.claude/commands` 文件经桌面路径映射读出后，`/名字 参数` 展开成要发给模型的内容 */
class SlashInputTest {
    @Test fun `MCP prompt arguments reach the shared input action and missing arguments become notice`() {
        val p = dev.min.agent.ext.mcp.McpPromptCommand("mcp__s__p", "s", "p", listOf(dev.min.agent.ext.mcp.McpPromptArgument("arg", true)))
        assertTrue(interpretInput("/mcp__s__p \"two words\"", emptyList(), emptyList(), SkillVars(), listOf(p)) is InputAction.Prompt)
        assertTrue(interpretInput("/mcp__s__p", emptyList(), emptyList(), SkillVars(), listOf(p)) is InputAction.Notice)
    }
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(base: java.nio.file.Path, rel: String, text: String) {
        val file = base.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private val config by lazy {
        val home = tmp.newFolder("home").toPath()
        val work = tmp.newFolder("work").toPath()
        write(work, ".claude/skills/fix/SKILL.md", "---\ndescription: Fix\narguments: [issue]\n---\nFix issue \$issue now.")
        write(work, ".claude/skills/bg/SKILL.md", "---\nuser-invocable: false\n---\nbackground")
        write(work, ".claude/commands/frontend/component.md", "Make component \$0")
        val paths = DesktopPaths(workspace = work, scratchRoot = tmp.newFolder("root").toPath())
        runBlocking { ClaudeConfigLoader.load(paths.fs, paths.guestOfHost(work), paths.guestOfHost(home)) }
    }

    private fun input(line: String) = interpretInput(line, config.skills, config.commands, SkillVars(sessionId = "s1"))

    @Test
    fun `plain text is sent as is`() {
        assertEquals(InputAction.Send("fix the bug", humanInput=true), input("fix the bug"))
        assertEquals(InputAction.Send("/etc/hosts?", humanInput=true), input("/etc/hosts?"))
    }

    @Test
    fun `a skill and a nested command expand with their arguments`() {
        val skill = input("/fix 42") as InputAction.Send
        assertTrue(skill.text, skill.text.startsWith("Base directory for this skill: ") && skill.text.endsWith("Fix issue 42 now."))
        // 展开带上组件名：宿主据此登记 frontmatter hooks
        assertEquals(listOf("fix"), skill.names)
        val command = input("/frontend:component Button") as InputAction.Send
        assertEquals("Make component Button", command.text)
        assertEquals(listOf("frontend:component"), command.names)
        // 普通输入不带名字
        assertTrue((input("plain text") as InputAction.Send).names.isEmpty())
    }

    @Test
    fun `unknown and model-only skills are not sent`() {
        assertEquals(InputAction.Notice("没有名为 /nope 的 skill 或命令。"), input("/nope x"))
        assertTrue((input("/bg") as InputAction.Notice).message.contains("user-invocable: false"))
    }

    @Test
    fun `the Skill tool follows tasks and web tools append after the stable prefix`() {
        val names = desktopTools().map { it.name }
        assertEquals(listOf("Skill", "WebFetch", "EnterWorktree", "ExitWorktree", "Monitor", "TaskOutput", "TeamCreate", "TeamDisband", "CronCreate", "CronDelete", "CronList", "ScheduleWakeup", "EndConversation", "PushNotification", "ListAgents", "SendUserFile"), names.takeLast(16))
        assertEquals(
            listOf("Read", "Write", "Edit", "NotebookEdit", "Glob", "Grep", "Bash", "LSP", "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "Agent", "SendMessage", "TaskStop",
                "TaskCreate", "TaskGet", "TaskList", "TaskUpdate"),
            names.dropLast(16),
        )
    }
}
