package dev.min.agent.kernel

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SkillKind
import dev.min.agent.testkit.FakeFs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 skill 进会话清单的规则（会话创建时冻结的 [SkillSnapshots]）：
 * 出现在清单里、来源标「内置」；同名时用户 / 项目 / 插件的胜出（文档：你的 skill 替换内置的）；
 * 内置 skill 替换同名的内置命令；`disableBundledSkills` 关掉全部内置。
 */
class BundledSkillsSnapshotTest {
    private fun load(fs: FakeFs) = runBlocking { SkillSnapshots.load(fs, GuestPath("/workspace"), GuestPath("/root")) }

    @Test
    fun `an empty workspace still lists the bundled skills, labelled as built-in`() {
        val s = load(FakeFs())
        assertNotNull(s)
        val loop = s!!.entries.single { it.name == "loop" }
        assertEquals("内置", loop.source)
        assertEquals(SkillKind.Skill, loop.kind)
        assertTrue(loop.path.value.startsWith(dev.min.agent.ext.skills.BundledSkills.PATH_PREFIX))
        assertEquals(listOf("loop", "code-review", "simplify", "batch"), s.entries.map { it.name })
    }

    @Test
    fun `a user skill with the same name replaces the bundled one`() {
        val fs = FakeFs()
        fs.put("/root/.claude/skills/loop/SKILL.md", "---\ndescription: my loop\n---\nmine")
        val entries = load(fs)!!.entries.filter { it.name == "loop" }
        assertEquals(1, entries.size)
        assertEquals("user", entries.single().source)
        assertEquals("my loop", entries.single().description)
    }

    @Test
    fun `a project skill with the same name replaces the bundled one and the others stay`() {
        val fs = FakeFs()
        fs.put("/workspace/.claude/skills/simplify/SKILL.md", "---\ndescription: project simplify\n---\nx")
        val s = load(fs)!!
        assertEquals("project", s.entries.single { it.name == "simplify" }.source)
        assertEquals("内置", s.entries.single { it.name == "batch" }.source)
    }

    @Test
    fun `a project command named like a bundled skill is replaced by it, other commands stay`() {
        val fs = FakeFs()
        fs.put("/workspace/.claude/commands/batch.md", "---\ndescription: old batch\n---\nold")
        fs.put("/workspace/.claude/commands/mine.md", "---\ndescription: mine\n---\nmine")
        val s = load(fs)!!
        assertEquals(1, s.entries.count { it.name == "batch" })
        assertEquals("内置", s.entries.single { it.name == "batch" }.source)
        assertEquals("project", s.entries.single { it.name == "mine" }.source)
    }

    @Test
    fun `disableBundledSkills true in settings removes every bundled skill`() {
        val fs = FakeFs()
        fs.put("/workspace/.claude/settings.json", """{"disableBundledSkills": true}""")
        assertNull(load(fs))
    }

    @Test
    fun `a disk skill is listed next to the bundled ones`() {
        val fs = FakeFs()
        fs.put("/root/.claude/skills/deploy/SKILL.md", "---\ndescription: deploy\n---\nd")
        val names = load(fs)!!.entries.map { it.name }
        assertTrue(names.containsAll(listOf("deploy", "loop", "code-review", "simplify", "batch")))
    }
}
