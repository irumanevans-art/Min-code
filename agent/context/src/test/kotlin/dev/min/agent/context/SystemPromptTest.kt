package dev.min.agent.context

import dev.min.agent.protocol.ModelRef
import dev.min.agent.llm.ModelProfile
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.SkillEntry
import dev.min.agent.spi.SkillKind
import dev.min.agent.spi.SkillsLoaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示里「项目说明」一段的快照（docs/agent/07-工程规范.md 第 9 条：改提示必须更新请求快照）。
 * 期望文本逐字手写：谁改了措辞、顺序或小节格式，这里立刻红，并提醒在提交说明里写 token 代价与缓存影响。
 */
class SystemPromptTest {
    private val profile = ModelProfile(ref = ModelRef("test", "m"), family = ModelFamily.Other, contextWindow = 100_000, maxOutputTokens = 4_000)
    private val env = Environment(platform = "test", cwd = "/workspace", date = "2026-10-01")

    private val memory = listOf(
        LoadedMemoryFile(GuestPath("/root/.claude/CLAUDE.md"), MemorySource.User, "Reply in Chinese.\n"),
        LoadedMemoryFile(GuestPath("/workspace/AGENTS.md"), MemorySource.Import, "\n\nRun ./gradlew check.\n\n"),
        LoadedMemoryFile(GuestPath("/workspace/CLAUDE.md"), MemorySource.Project, "@AGENTS.md"),
        LoadedMemoryFile(GuestPath("/workspace/.claude/rules/style.md"), MemorySource.Rule, "Use tabs."),
        LoadedMemoryFile(GuestPath("/workspace/CLAUDE.local.md"), MemorySource.Local, "  My sandbox is box-3."),
    )

    @Test
    fun `without memory the prompt keeps its two blocks`() {
        assertEquals(2, SystemPrompt.build(profile, env).size)
        assertEquals(SystemPrompt.build(profile, env), SystemPrompt.build(profile, env, emptyList()))
    }

    @Test
    fun `memory is a third block after the unchanged first two`() {
        val withMemory = SystemPrompt.build(profile, env, memory)
        assertEquals(3, withMemory.size)
        // 前两段不受记忆影响，逐字不变
        assertEquals(SystemPrompt.build(profile, env), withMemory.take(2))
        assertEquals(
            """
            Project instructions:
            The user and the project's maintainers wrote these in memory files (CLAUDE.md, AGENTS.md, .claude/rules).
            Follow them; they take precedence over the general guidance above. Files go from the broadest scope to the most
            specific, so when two disagree, follow the later one.

            Contents of /root/.claude/CLAUDE.md (user's instructions for all projects):

            Reply in Chinese.

            Contents of /workspace/AGENTS.md (imported by a memory file):

            Run ./gradlew check.

            Contents of /workspace/CLAUDE.md (project instructions, checked into the codebase):

            @AGENTS.md

            Contents of /workspace/.claude/rules/style.md (rule):

            Use tabs.

            Contents of /workspace/CLAUDE.local.md (user's private instructions for this project, not checked in):

              My sandbox is box-3.
            """.trimIndent(),
            withMemory[2],
        )
    }

    private val skills = SkillsLoaded(
        entries = listOf(
            SkillEntry("deploy", "Ship the app", GuestPath("/workspace/.claude/skills/deploy/SKILL.md"), whenToUse = "when asked to release"),
            SkillEntry("notes", "Take notes", GuestPath("/workspace/.claude/commands/notes.md"), kind = SkillKind.Command),
            SkillEntry("only-user", "hidden from the model", GuestPath("/workspace/.claude/skills/only-user/SKILL.md"), disableModelInvocation = true),
        ),
        notes = listOf("只给人看"),
    )

    @Test
    fun `skills are a block after memory and leave the earlier blocks unchanged`() {
        val withSkills = SystemPrompt.build(profile, env, memory, skills = skills)
        assertEquals(4, withSkills.size)
        assertEquals(SystemPrompt.build(profile, env, memory), withSkills.take(3))
        assertEquals(SystemPrompt.build(profile, env), SystemPrompt.build(profile, env, skills = skills).take(2))
        assertEquals(
            """
            The following skills are available for use with the Skill tool:

            - deploy: Ship the app - when asked to release
            - notes: Take notes
            """.trimIndent(),
            withSkills[3],
        )
    }

    @Test
    fun `the subagent role block stays last and a snapshot with nothing listable adds no block`() {
        val sub = SystemPrompt.build(profile, env, memory, agentInstructions = "Explore.", skills = skills)
        assertEquals(5, sub.size)
        assertEquals(SystemPrompt.build(profile, env, memory, skills = skills), sub.take(4))
        assertTrue(sub.last().endsWith("Explore."))
        val hiddenOnly = skills.copy(entries = skills.entries.filter { it.disableModelInvocation })
        assertEquals(SystemPrompt.build(profile, env), SystemPrompt.build(profile, env, skills = hiddenOnly))
        assertEquals(SystemPrompt.build(profile, env), SystemPrompt.build(profile, env, skills = null))
    }

    @Test
    fun `the listing budget follows the model window and the frozen settings`() {
        val many = SkillsLoaded(entries = (1..30).map { SkillEntry("skill-$it", "d".repeat(200), GuestPath("/s/$it/SKILL.md")) })
        val small = ModelProfile(ref = ModelRef("test", "m"), family = ModelFamily.Other, contextWindow = 20_000, maxOutputTokens = 4_000)
        val tight = SystemPrompt.build(small, env, skills = many)[2]
        val roomy = SystemPrompt.build(profile, env, skills = many)[2]
        assertTrue(tight.length < roomy.length)
        // 名字一个不少
        assertEquals(30, tight.lines().count { it.startsWith("- skill-") })
        val raised = SystemPrompt.build(small, env, skills = many.copy(listingBudgetFraction = 0.1))[2]
        assertTrue(raised.length > tight.length)
    }

    @Test
    fun `the assembler passes skills into the system prompt`() {
        val request = RequestAssembler.assemble(profile, env, emptyList(), emptyList(), effort = null, cacheKey = "s1", skills = skills)
        assertEquals(SystemPrompt.build(profile, env, skills = skills), request.system)
    }

    @Test
    fun `the assembler passes memory into the system prompt`() {
        val request = RequestAssembler.assemble(profile, env, emptyList(), emptyList(), effort = null, cacheKey = "s1", memory = memory)
        assertEquals(SystemPrompt.build(profile, env, memory), request.system)
    }
}
