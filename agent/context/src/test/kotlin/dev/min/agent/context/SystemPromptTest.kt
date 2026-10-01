package dev.min.agent.context

import dev.min.agent.protocol.ModelRef
import dev.min.agent.llm.ModelProfile
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.ModelFamily
import org.junit.Assert.assertEquals
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

    @Test
    fun `the assembler passes memory into the system prompt`() {
        val request = RequestAssembler.assemble(profile, env, emptyList(), emptyList(), effort = null, cacheKey = "s1", memory = memory)
        assertEquals(SystemPrompt.build(profile, env, memory), request.system)
    }
}
