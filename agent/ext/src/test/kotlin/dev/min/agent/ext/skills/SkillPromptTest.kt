package dev.min.agent.ext.skills

import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 渲染：目录头、变量代入的顺序（在参数替换之后）、没有值的变量原样保留；以及读正文 */
class SkillPromptTest {
    private val vars = SkillVars(sessionId = "sess-1", projectDir = "/workspace", effort = "high")

    @Test
    fun `a skill gets a base directory header and a command does not`() {
        assertEquals("Base directory for this skill: /d\n\nbody", SkillPrompt.render("body", null, emptyList(), "/d"))
        assertEquals("body", SkillPrompt.render("body", null, emptyList(), null))
    }

    @Test
    fun `claude variables are substituted after the arguments so argument text is covered too`() {
        val body = "run \${CLAUDE_SKILL_DIR}/x.sh \$ARGUMENTS in \${CLAUDE_PROJECT_DIR} (\${CLAUDE_SESSION_ID}, \${CLAUDE_EFFORT})"
        val out = SkillPrompt.render(body, "\${CLAUDE_SKILL_DIR}/y", emptyList(), "/d", vars)
        assertEquals("Base directory for this skill: /d\n\nrun /d/x.sh /d/y in /workspace (sess-1, high)", out)
    }

    @Test
    fun `variables without a value stay as written`() {
        val body = "\${CLAUDE_SESSION_ID} \${CLAUDE_SKILL_DIR}"
        assertEquals(body, SkillPrompt.render(body, null, emptyList(), null))
    }

    @Test
    fun `readBody gives the body without frontmatter and null for a missing file`() {
        val fs = InMemoryFs()
        fs.file("/root/.claude/skills/a/SKILL.md", "---\ndescription: d\n---\nreal body")
        runBlocking {
            assertEquals("real body", SkillFiles.readBody(fs, GuestPath("/root/.claude/skills/a/SKILL.md")))
            assertNull(SkillFiles.readBody(fs, GuestPath("/root/.claude/skills/gone/SKILL.md")))
        }
    }
}
