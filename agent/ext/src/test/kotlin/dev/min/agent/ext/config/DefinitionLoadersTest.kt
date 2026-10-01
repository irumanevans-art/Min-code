package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * skills / commands / agents 的位置、命名、字段与覆盖。期望对照 CC 文档：
 * skills.md（位置、同名优先级 managed > user > project、skill 胜过 command、`dir:file` 命名、保留名、description 缺省取首个非空行）、
 * sub-agents.md（frontmatter 字段、project 高于 user、未知字段静默忽略、必填 name 与 description）。
 */
class DefinitionLoadersTest {
    private val fs = InMemoryFs()

    private fun load(cwd: String = "/workspace", home: String = "/root") =
        runBlocking { ClaudeConfigLoader.load(fs, GuestPath(cwd), GuestPath(home)) }

    private fun md(description: String, body: String = "body") = "---\ndescription: $description\n---\n$body"

    // ---- skills ----

    @Test
    fun `skills are found in user and project skill directories and named after their directory`() {
        fs.file("/root/.claude/skills/deploy/SKILL.md", md("Deploy it", "Run the deploy."))
        fs.file("/workspace/.claude/skills/review/SKILL.md", md("Review code"))
        fs.file("/workspace/.claude/skills/review/reference.md", "supporting file, not a skill")
        fs.file("/workspace/.claude/skills/review/scripts/run.sh", "echo")
        fs.file("/workspace/.claude/skills/empty/notes.txt", "no SKILL.md here")
        val c = load()
        assertEquals(setOf("deploy", "review"), c.skills.map { it.name }.toSet())
        val deploy = c.skills.single { it.name == "deploy" }
        assertEquals(ConfigSource.User, deploy.source)
        assertEquals("/root/.claude/skills/deploy/SKILL.md", deploy.path.value)
        assertEquals("Deploy it", deploy.description)
        assertEquals("Run the deploy.", deploy.body)
        assertEquals(ConfigSource.Project, c.skills.single { it.name == "review" }.source)
        assertTrue(c.notes.isEmpty())
    }

    @Test
    fun `frontmatter name overrides the directory name and a missing description falls back to the first non-empty body line`() {
        fs.file("/workspace/.claude/skills/dir-name/SKILL.md", "---\nname: real-name\n---\n\n\n  First line here\nsecond")
        val skill = load().skills.single()
        assertEquals("real-name", skill.name)
        assertEquals("First line here", skill.description)
    }

    @Test
    fun `the description fallback skips blank lines made of spaces`() {
        fs.file("/workspace/.claude/skills/s/SKILL.md", "   \n\t\nReal first line\nnext")
        assertEquals("Real first line", load().skills.single().description)
    }

    @Test
    fun `a skill without frontmatter loads with defaults`() {
        fs.file("/workspace/.claude/skills/plain/SKILL.md", "Just instructions.\nMore.")
        val skill = load().skills.single()
        assertEquals("plain", skill.name)
        assertEquals("Just instructions.", skill.description)
        assertEquals("Just instructions.\nMore.", skill.body)
        assertFalse(skill.disableModelInvocation)
        assertTrue(skill.userInvocable)
        assertTrue(skill.background)
    }

    @Test
    fun `every documented skill field is parsed`() {
        fs.file(
            "/workspace/.claude/skills/full/SKILL.md",
            """
            ---
            name: full
            description: >
              Does the whole thing.
              Really.
            when_to_use: when asked to do everything
            argument-hint: [issue-number]
            arguments: issue branch
            disable-model-invocation: yes
            user-invocable: off
            allowed-tools: Bash(git add *) Bash(git commit *), Read
            disallowed-tools:
              - AskUserQuestion
              - "Bash(rm *)"
            model: opus
            effort: high
            context: fork
            agent: Explore
            background: 0
            hooks:
              PreToolUse:
                - matcher: Bash
                  hooks:
                    - type: command
                      command: ./lint.sh
            paths: src/**/*.{ts,tsx}, docs/*.md
            shell: powershell
            metadata:
              team: core
              level: 3
            license: MIT
            compatibility: needs git
            unknown-field: ignored
            ---
            # Body
            """.trimIndent(),
        )
        val c = load()
        val s = c.skills.single()
        assertEquals("Does the whole thing. Really.", s.description) // `>` 折叠后去掉首尾空白
        assertEquals("when asked to do everything", s.whenToUse)
        assertEquals("[issue-number]", s.argumentHint) // 不带引号的 [x] 在 YAML 里是列表，按原写法还原
        assertEquals(listOf("issue", "branch"), s.arguments)
        assertTrue(s.disableModelInvocation)
        assertFalse(s.userInvocable)
        assertEquals(listOf("Bash(git add *)", "Bash(git commit *)", "Read"), s.allowedTools)
        assertEquals(listOf("AskUserQuestion", "Bash(rm *)"), s.disallowedTools)
        assertEquals("opus", s.model)
        assertEquals("high", s.effort)
        assertEquals("fork", s.context)
        assertEquals("Explore", s.agent)
        assertFalse(s.background)
        assertEquals(
            Json.parseToJsonElement("""{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"./lint.sh"}]}]}"""),
            s.hooks,
        )
        assertEquals(listOf("src/**/*.{ts,tsx}", "docs/*.md"), s.paths)
        assertEquals("powershell", s.shell)
        assertEquals(Json.parseToJsonElement("""{"team":"core","level":3}"""), s.metadata)
        assertEquals("MIT", s.license)
        assertEquals("needs git", s.compatibility)
        assertEquals("# Body", s.body)
        assertTrue(c.notes.toString(), c.notes.isEmpty())
    }

    @Test
    fun `same name resolves managed over user over project and the loser is noted`() {
        fs.file("/workspace/.claude/skills/deploy/SKILL.md", md("project"))
        fs.file("/root/.claude/skills/deploy/SKILL.md", md("user"))
        var c = load()
        assertEquals("user", c.skills.single().description)
        assertEquals(1, c.notes.size)
        assertTrue(c.notes.single().contains("/workspace/.claude/skills/deploy/SKILL.md"))
        assertTrue(c.notes.single().contains("被 /root/.claude/skills/deploy/SKILL.md"))

        fs.file("/etc/claude-code/.claude/skills/deploy/SKILL.md", md("managed"))
        c = load()
        assertEquals("managed", c.skills.single().description)
        assertEquals(ConfigSource.Managed, c.skills.single().source)
        assertEquals(2, c.notes.size)
    }

    @Test
    fun `identity is the effective name, so a renamed skill collides with a directory of that name`() {
        fs.file("/root/.claude/skills/a/SKILL.md", "---\nname: shared\ndescription: from a\n---\n")
        fs.file("/workspace/.claude/skills/shared/SKILL.md", md("from project dir"))
        val skill = load().skills.single()
        assertEquals("from a", skill.description)
    }

    @Test
    fun `reserved names are skipped with a note`() {
        fs.file("/root/.claude/skills/synced/SKILL.md", md("x"))
        fs.file("/root/.claude/skills/SYNCED/SKILL.md", md("x"))
        fs.file("/root/.claude/skills/anthropic-skills/SKILL.md", md("x"))
        fs.file("/root/.claude/skills/ok/SKILL.md", "---\nname: anthropic-skills:foo\n---\n")
        fs.file("/root/.claude/skills/fine/SKILL.md", md("fine"))
        val c = load()
        assertEquals(listOf("fine"), c.skills.map { it.name })
        assertEquals(4, c.notes.size)
    }

    @Test
    fun `invalid YAML in a skill still loads it with no fields and says why`() {
        fs.file("/workspace/.claude/skills/oops/SKILL.md", "---\nname: renamed\ndescription: Use when: needed\n---\nStill the body")
        val c = load()
        val skill = c.skills.single()
        assertEquals("oops", skill.name) // name 字段也被丢了
        assertEquals("Still the body", skill.description)
        assertEquals("Still the body", skill.body)
        assertEquals(1, c.notes.size)
        assertTrue(c.notes.single().contains("/workspace/.claude/skills/oops/SKILL.md"))
    }

    @Test
    fun `a wrong typed skill field falls back to its default and is reported`() {
        fs.file("/workspace/.claude/skills/s/SKILL.md", "---\ndescription: d\ndisable-model-invocation: maybe\nmetadata: just text\nallowed-tools:\n  k: v\n---\nb")
        val c = load()
        val s = c.skills.single()
        assertFalse(s.disableModelInvocation)
        assertNull(s.metadata)
        assertTrue(s.allowedTools.isEmpty())
        assertEquals(3, c.notes.size)
        assertTrue(c.notes.any { it.contains("`disable-model-invocation`") })
        assertTrue(c.notes.any { it.contains("`metadata`") })
        assertTrue(c.notes.any { it.contains("`allowed-tools`") })
    }

    @Test
    fun `a symlinked skill directory is followed`() {
        fs.file("/shared/skills/linked/SKILL.md", md("via link"))
        fs.link("/root/.claude/skills/linked", "/shared/skills/linked")
        val skill = load().skills.single()
        assertEquals("linked", skill.name)
        assertEquals("via link", skill.description)
    }

    @Test
    fun `project definitions come from cwd only`() {
        fs.file("/workspace/.claude/skills/up/SKILL.md", md("parent"))
        fs.file("/workspace/.claude/commands/up.md", "parent")
        fs.file("/workspace/.claude/agents/up.md", "---\nname: up\ndescription: parent\n---\n")
        val c = load(cwd = "/workspace/app")
        assertTrue(c.skills.isEmpty() && c.commands.isEmpty() && c.agents.isEmpty())
    }

    @Test
    fun `when cwd is home the project directory is the user directory and is read once`() {
        fs.file("/root/.claude/skills/a/SKILL.md", md("a"))
        fs.file("/root/.claude/commands/c.md", "c")
        fs.file("/root/.claude/agents/g.md", "---\nname: g\ndescription: g\n---\n")
        val c = load(cwd = "/root")
        assertEquals(listOf(ConfigSource.User), c.skills.map { it.source })
        assertEquals(listOf(ConfigSource.User), c.commands.map { it.source })
        assertEquals(listOf(ConfigSource.User), c.agents.map { it.source })
        assertTrue(c.notes.isEmpty())
    }

    // ---- commands ----

    @Test
    fun `commands are named after the file and subdirectories become colon prefixes`() {
        fs.file("/workspace/.claude/commands/deploy.md", "Deploy now\nmore")
        fs.file("/workspace/.claude/commands/frontend/component.md", md("Make a component"))
        fs.file("/workspace/.claude/commands/frontend/ui/button.md", "Button")
        fs.file("/workspace/.claude/commands/readme.txt", "not a command")
        fs.file("/workspace/.claude/commands/.md", "nameless")
        val c = load()
        assertEquals(setOf("deploy", "frontend:component", "frontend:ui:button"), c.commands.map { it.name }.toSet())
        val deploy = c.commands.single { it.name == "deploy" }
        assertEquals("Deploy now", deploy.description) // 缺省取首个非空行
        assertEquals("Deploy now\nmore", deploy.body)
        assertEquals("Make a component", c.commands.single { it.name == "frontend:component" }.description)
        assertEquals("/workspace/.claude/commands/frontend/ui/button.md", c.commands.single { it.name == "frontend:ui:button" }.path.value)
    }

    @Test
    fun `command frontmatter takes the skill fields but ignores name and paths`() {
        fs.file(
            "/workspace/.claude/commands/fix.md",
            "---\nname: other\npaths: src/**\ndescription: Fix it\nargument-hint: <id>\nallowed-tools: Bash(git *)\nmodel: sonnet\nuser-invocable: false\n---\nFix \$ARGUMENTS",
        )
        val cmd = load().commands.single()
        assertEquals("fix", cmd.name)
        assertEquals("Fix it", cmd.description)
        assertEquals("<id>", cmd.argumentHint)
        assertEquals(listOf("Bash(git *)"), cmd.allowedTools)
        assertEquals("sonnet", cmd.model)
        assertFalse(cmd.userInvocable)
        assertEquals("Fix \$ARGUMENTS", cmd.body)
    }

    @Test
    fun `a skill beats a command of the same name`() {
        fs.file("/workspace/.claude/skills/deploy/SKILL.md", md("skill"))
        fs.file("/workspace/.claude/commands/deploy.md", "command")
        fs.file("/workspace/.claude/commands/other.md", "other")
        val c = load()
        assertEquals(listOf("other"), c.commands.map { it.name })
        assertEquals("skill", c.skills.single().description)
        assertTrue(c.notes.single().contains("被同名的 skill 覆盖"))
    }

    @Test
    fun `a user command shadows a project command of the same name`() {
        fs.file("/root/.claude/commands/x.md", "user")
        fs.file("/workspace/.claude/commands/x.md", "project")
        val c = load()
        assertEquals("user", c.commands.single().body)
        assertEquals(1, c.notes.size)
    }

    // ---- agents ----

    @Test
    fun `agent frontmatter fields are parsed and unknown fields are ignored silently`() {
        fs.file(
            "/workspace/.claude/agents/reviewer.md",
            """
            ---
            name: code-reviewer
            description: Reviews code after changes
            tools: Read, Grep, Bash(git diff *)
            disallowedTools:
              - Write
            model: sonnet
            permissionMode: acceptEdits
            maxTurns: 12
            skills: [lint, "format"]
            mcpServers:
              - github
              - local:
                  command: ./srv
            hooks:
              Stop:
                - hooks:
                    - type: command
                      command: ./done.sh
            memory: project
            background: true
            omitClaudeMd: yes
            effort: xhigh
            isolation: worktree
            color: cyan
            initialPrompt: "Start by reading the diff."
            experimental:
              cacheTtl: 1h
            some-future-field: whatever
            ---
            You are a careful reviewer.
            """.trimIndent(),
        )
        val c = load()
        val a = c.agents.single()
        assertEquals("code-reviewer", a.name) // 身份看 name，不看文件名
        assertEquals("Reviews code after changes", a.description)
        assertEquals("You are a careful reviewer.", a.prompt)
        assertEquals(ConfigSource.Project, a.source)
        assertEquals(listOf("Read", "Grep", "Bash(git diff *)"), a.tools)
        assertEquals(listOf("Write"), a.disallowedTools)
        assertEquals("sonnet", a.model)
        assertEquals("acceptEdits", a.permissionMode)
        assertEquals(12, a.maxTurns)
        assertEquals(listOf("lint", "format"), a.skills)
        assertEquals(Json.parseToJsonElement("""["github",{"local":{"command":"./srv"}}]"""), a.mcpServers)
        assertEquals(Json.parseToJsonElement("""{"Stop":[{"hooks":[{"type":"command","command":"./done.sh"}]}]}"""), a.hooks)
        assertEquals("project", a.memory)
        assertEquals(true, a.background)
        assertTrue(a.omitClaudeMd)
        assertEquals("xhigh", a.effort)
        assertEquals("worktree", a.isolation)
        assertEquals("cyan", a.color)
        assertEquals("Start by reading the diff.", a.initialPrompt)
        assertEquals(Json.parseToJsonElement("""{"cacheTtl":"1h"}"""), a.experimental)
        assertTrue(c.notes.toString(), c.notes.isEmpty())
    }

    @Test
    fun `omitted tools means inherit everything and differs from an empty list`() {
        fs.file("/workspace/.claude/agents/a.md", "---\nname: a\ndescription: d\n---\n")
        fs.file("/workspace/.claude/agents/b.md", "---\nname: b\ndescription: d\ntools: []\n---\n")
        val agents = load().agents.associateBy { it.name }
        assertNull(agents.getValue("a").tools)
        assertEquals(emptyList<String>(), agents.getValue("b").tools)
    }

    @Test
    fun `an agent without name or description or with an illegal name is skipped and explained`() {
        fs.file("/workspace/.claude/agents/no-name.md", "---\ndescription: d\n---\nbody")
        fs.file("/workspace/.claude/agents/no-desc.md", "---\nname: x\n---\nbody")
        fs.file("/workspace/.claude/agents/dash.md", "---\nname: -x\ndescription: d\n---\n")
        fs.file("/workspace/.claude/agents/colon.md", "---\nname: plugin:agent\ndescription: d\n---\n")
        fs.file("/workspace/.claude/agents/typed.md", "---\nname: [a]\ndescription: d\n---\n")
        fs.file("/workspace/.claude/agents/plain.md", "no frontmatter at all")
        fs.file("/workspace/.claude/agents/ok.md", "---\nname: ok\ndescription: d\n---\n")
        val c = load()
        assertEquals(listOf("ok"), c.agents.map { it.name })
        assertEquals(7, c.notes.size) // 6 个文件被跳过，其中类型不对的 name 另有一条字段说明
        assertTrue(c.notes.any { it.contains("no-name.md") && it.contains("`name`") })
        assertTrue(c.notes.any { it.contains("no-desc.md") && it.contains("`description`") })
        assertTrue(c.notes.any { it.contains("dash.md") })
        assertTrue(c.notes.any { it.contains("colon.md") })
        assertTrue(c.notes.any { it.contains("typed.md") && it.contains("期望一个字符串") })
        assertTrue(c.notes.any { it.contains("plain.md") })
    }

    @Test
    fun `agent priority is managed over project over user, the opposite of skills`() {
        fs.file("/root/.claude/agents/r.md", "---\nname: r\ndescription: user\n---\n")
        fs.file("/workspace/.claude/agents/r.md", "---\nname: r\ndescription: project\n---\n")
        var c = load()
        assertEquals("project", c.agents.single().description)
        assertEquals(1, c.notes.size)

        fs.file("/etc/claude-code/.claude/agents/r.md", "---\nname: r\ndescription: managed\n---\n")
        c = load()
        assertEquals("managed", c.agents.single().description)
        assertEquals(2, c.notes.size)
    }

    @Test
    fun `agents are scanned recursively and a duplicate name inside one tree keeps the first path`() {
        fs.file("/workspace/.claude/agents/team/b.md", "---\nname: dup\ndescription: second\n---\n")
        fs.file("/workspace/.claude/agents/a.md", "---\nname: dup\ndescription: first\n---\n")
        fs.file("/workspace/.claude/agents/team/deep/c.md", "---\nname: deep\ndescription: nested\n---\n")
        val c = load()
        assertEquals(setOf("dup", "deep"), c.agents.map { it.name }.toSet())
        assertEquals("first", c.agents.single { it.name == "dup" }.description)
        assertTrue(c.notes.single().contains("同一来源里先到先得"))
    }

    @Test
    fun `an unreadable definition file is reported and does not stop the others`() {
        fs.fail("/workspace/.claude/agents/locked.md")
        fs.file("/workspace/.claude/agents/ok.md", "---\nname: ok\ndescription: d\n---\n")
        fs.fail("/workspace/.claude/commands/locked.md")
        fs.file("/workspace/.claude/skills/locked/SKILL.md", "x")
        fs.fail("/workspace/.claude/skills/locked/SKILL.md")
        val c = load()
        assertEquals(listOf("ok"), c.agents.map { it.name })
        assertEquals(3, c.notes.count { it.contains("读取") && it.contains("locked") })
    }

    // ---- 统一入口 ----

    @Test
    fun `the facade merges settings and definition notes`() {
        fs.file("/workspace/.claude/settings.json", "{oops")
        fs.file("/root/.claude/skills/a/SKILL.md", md("a"))
        fs.file("/workspace/.claude/skills/a/SKILL.md", md("a2"))
        val c = load()
        assertEquals(2, c.notes.size)
        assertTrue(c.notes.first().contains("settings.json"))
        assertTrue(c.settings.merged.ruleSpecs().isEmpty())
    }
}
