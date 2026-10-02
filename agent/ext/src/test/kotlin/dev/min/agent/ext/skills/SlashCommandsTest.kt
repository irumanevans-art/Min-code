package dev.min.agent.ext.skills

import dev.min.agent.ext.config.CommandDef
import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SkillDef
import dev.min.agent.spi.GuestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/名字 参数` 的展开：skill 与 command 都能调，叠放规则与不可调的情形照 skills.md */
class SlashCommandsTest {
    private fun skill(
        name: String,
        body: String,
        userInvocable: Boolean = true,
        context: String? = null,
        arguments: List<String> = emptyList(),
        disableModel: Boolean = false,
    ) = SkillDef(
        name, "desc", GuestPath("/root/.claude/skills/$name/SKILL.md"), body, ConfigSource.User,
        arguments = arguments, userInvocable = userInvocable, context = context, disableModelInvocation = disableModel,
    )

    private fun command(name: String, body: String) =
        CommandDef(name, "desc", GuestPath("/workspace/.claude/commands/$name.md"), body, ConfigSource.Project)

    private fun expand(input: String, skills: List<SkillDef> = emptyList(), commands: List<CommandDef> = emptyList()) =
        SlashCommands.expand(input, skills, commands, SkillVars(sessionId = "S1"))

    private fun header(name: String) = "Base directory for this skill: /root/.claude/skills/$name\n\n"

    @Test
    fun `text that does not start with a slash is not a command`() {
        assertEquals(SlashResult.NotACommand, expand("fix the bug", listOf(skill("fix", "x"))))
        assertEquals(SlashResult.NotACommand, expand(""))
        assertEquals(SlashResult.NotACommand, expand("  /  "))
    }

    @Test
    fun `a path is not a command but a bare unknown word is reported`() {
        assertEquals(SlashResult.NotACommand, expand("/etc/hosts show me"))
        assertEquals(SlashResult.NotACommand, expand("/c/Users/me/a.txt"))
        assertEquals(SlashResult.Unknown("nope"), expand("/nope some args", listOf(skill("fix", "x"))))
    }

    @Test
    fun `a skill expands with a base directory header and its arguments substituted`() {
        val r = expand("/fix-issue 123", listOf(skill("fix-issue", "Fix issue \$ARGUMENTS.")))
        assertEquals(SlashResult.Expanded(header("fix-issue") + "Fix issue 123.", listOf("fix-issue")), r)
    }

    @Test
    fun `a command expands to its plain body without a header`() {
        assertEquals(
            SlashResult.Expanded("Review src hard", listOf("review")),
            expand("/review src", commands = listOf(command("review", "Review \$0 hard"))),
        )
        assertEquals(
            SlashResult.Expanded("Review  hard", listOf("review")),
            expand("/review", commands = listOf(command("review", "Review \$ARGUMENTS hard"))),
        )
    }

    @Test
    fun `with no arguments the placeholders become empty rather than staying literal`() {
        val r = expand("/hello", listOf(skill("hello", "Say hi to [\$ARGUMENTS] and [\$who]", arguments = listOf("who"))))
        assertEquals(header("hello") + "Say hi to [] and []", (r as SlashResult.Expanded).text)
    }

    @Test
    fun `named arguments and quoted arguments work through the slash path`() {
        val s = skill("mig", "from \$from to \$to", arguments = listOf("from", "to"))
        val r = expand("/mig \"src dir\" dst", listOf(s)) as SlashResult.Expanded
        assertEquals(header("mig") + "from src dir to dst", r.text)
    }

    @Test
    fun `a skill beats a command with the same name`() {
        val r = expand("/dup", listOf(skill("dup", "from skill")), listOf(command("dup", "from command"))) as SlashResult.Expanded
        assertTrue("from skill" in r.text && "from command" !in r.text)
    }

    @Test
    fun `user-invocable false is refused but disable-model-invocation does not matter to the user`() {
        assertEquals(SlashResult.NotInvocable("bg"), expand("/bg", listOf(skill("bg", "x", userInvocable = false))))
        val r = expand("/deploy now", listOf(skill("deploy", "go \$ARGUMENTS", disableModel = true)))
        assertTrue(r is SlashResult.Expanded)
    }

    @Test
    fun `colon names from command subdirectories are valid`() {
        val r = expand("/frontend:component Button", commands = listOf(command("frontend:component", "make \$0")))
        assertEquals(SlashResult.Expanded("make Button", listOf("frontend:component")), r)
    }

    // ---- 叠放 ----

    @Test
    fun `stacked skills all expand and share the trailing text as arguments`() {
        val r = expand("/write-tests /fix-issue 123", listOf(skill("write-tests", "T:\$ARGUMENTS"), skill("fix-issue", "F:\$ARGUMENTS")))
        r as SlashResult.Expanded
        assertEquals(listOf("write-tests", "fix-issue"), r.names)
        assertEquals(header("write-tests") + "T:123\n\n" + header("fix-issue") + "F:123", r.text)
    }

    @Test
    fun `stacking stops at an unknown word and that word starts the arguments`() {
        val r = expand("/a /nope 1", listOf(skill("a", "A:\$ARGUMENTS"))) as SlashResult.Expanded
        assertEquals(listOf("a"), r.names)
        assertEquals(header("a") + "A:/nope 1", r.text)
    }

    @Test
    fun `stacking stops at a forked skill and at a skill the user may not invoke`() {
        val a = skill("a", "A:\$ARGUMENTS")
        val forked = skill("forky", "F", context = "fork")
        val hidden = skill("hidden", "H", userInvocable = false)
        assertEquals(listOf("a"), (expand("/a /forky x", listOf(a, forked)) as SlashResult.Expanded).names)
        assertEquals(listOf("a"), (expand("/a /hidden x", listOf(a, hidden)) as SlashResult.Expanded).names)
        // 最前面的 fork skill 单独展开，后面的不叠
        assertEquals(listOf("forky"), (expand("/forky /a x", listOf(a, forked)) as SlashResult.Expanded).names)
    }

    @Test
    fun `at most five skills stack after the first`() {
        val all = (1..8).map { skill("s$it", "B$it") }
        val r = expand("/s1 /s2 /s3 /s4 /s5 /s6 /s7 /s8", all) as SlashResult.Expanded
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), r.names)
    }
}
