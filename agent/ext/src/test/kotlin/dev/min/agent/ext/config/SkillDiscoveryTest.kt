package dev.min.agent.ext.config

import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库祖先、嵌套目录懒加载（含重名的限定名与「查过不再查」）、`--add-dir` 的 skills。
 * 文件系统用 [InMemoryFs]（目录隐式存在）。
 */
class SkillDiscoveryTest {
    private fun skillText(description: String) = "---\ndescription: $description\n---\nbody of $description"

    @Test
    fun `ancestors up to the repository root are the project skill dirs, nearest first`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/repo/.git", "gitdir: somewhere")
        fs.file("/repo/apps/web/README.md", "x")
        val dirs = SkillDiscovery.ancestorSkillDirs(fs, GuestPath("/repo/apps/web"))
        assertEquals(listOf("/repo/apps/web/.claude/skills", "/repo/apps/.claude/skills", "/repo/.claude/skills"), dirs)
    }

    @Test
    fun `outside a repository only the working directory counts`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/home/someone/.claude/skills/x/SKILL.md", skillText("x"))
        fs.file("/work/a/b.txt", "x")
        assertEquals(listOf("/work/a/.claude/skills"), SkillDiscovery.ancestorSkillDirs(fs, GuestPath("/work/a")))
    }

    @Test
    fun `a nested skill loads when a file in its directory is touched and a same-named one is qualified`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/workspace/apps/web/.claude/skills/deploy/SKILL.md", skillText("web deploy"))
        fs.file("/workspace/apps/.claude/skills/lint/SKILL.md", skillText("apps lint"))
        fs.file("/workspace/apps/web/src/a.ts", "x")
        // 根上已经有 deploy：嵌套的那个要用限定名
        val found = SkillDiscovery.nestedSkills(fs, GuestPath("/workspace"), listOf(GuestPath("/workspace/apps/web/src/a.ts")), emptySet(), setOf("deploy"))
        assertEquals(listOf("lint", "apps/web:deploy"), found.skills.map { it.name })
        // 文件所在目录（src）也算一层：文档说的是「读写那个子目录里的文件」；三层都查过（有没有 skill 都记）
        assertEquals(setOf("/workspace/apps/.claude/skills", "/workspace/apps/web/.claude/skills", "/workspace/apps/web/src/.claude/skills"), found.checked.toSet())
        assertEquals("/workspace/apps/web/.claude/skills/deploy/SKILL.md", found.skills.last().path.value)
    }

    @Test
    fun `a directory already checked is not read again and a file outside the working directory is ignored`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/workspace/apps/web/.claude/skills/deploy/SKILL.md", skillText("web deploy"))
        val first = SkillDiscovery.nestedSkills(fs, GuestPath("/workspace"), listOf(GuestPath("/workspace/apps/web/a.ts")), emptySet(), emptySet())
        assertEquals(1, first.skills.size)
        val again = SkillDiscovery.nestedSkills(fs, GuestPath("/workspace"), listOf(GuestPath("/workspace/apps/web/a.ts")), first.checked.toSet(), setOf("deploy"))
        assertTrue(again.skills.isEmpty())
        val outside = SkillDiscovery.nestedSkills(fs, GuestPath("/workspace"), listOf(GuestPath("/elsewhere/x.ts")), emptySet(), emptySet())
        assertTrue(outside.skills.isEmpty() && outside.checked.isEmpty())
    }

    @Test
    fun `add-dir skills load with their own source and the config loader includes them`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/extra/lib/.claude/skills/tool/SKILL.md", skillText("tool"))
        val direct = addDirSkills(fs, listOf(GuestPath("/extra/lib")))
        assertEquals(listOf("tool"), direct.skills.map { it.name })
        assertEquals(ConfigSource.AddDir, direct.skills.single().source)

        val config = ClaudeConfigLoader.load(fs, GuestPath("/workspace"), GuestPath("/root"), inputs = SettingsInputs(addDirs = listOf(GuestPath("/extra/lib"))))
        assertEquals(listOf("tool"), config.skills.filter { it.source == ConfigSource.AddDir }.map { it.name })
    }

    @Test
    fun `the config loader reads ancestor project skills at lower priority than the working directory`() = runBlocking {
        val fs = InMemoryFs()
        fs.file("/repo/.git", "gitdir: x")
        fs.file("/repo/.claude/skills/shared/SKILL.md", skillText("root shared"))
        fs.file("/repo/apps/web/.claude/skills/shared/SKILL.md", skillText("web shared"))
        fs.file("/repo/apps/web/.claude/skills/own/SKILL.md", skillText("own"))
        val config = ClaudeConfigLoader.load(fs, GuestPath("/repo/apps/web"), GuestPath("/root"))
        val shared = config.skills.single { it.name == "shared" }
        assertEquals("web shared", shared.description)
        assertEquals(listOf("own", "shared"), config.skills.map { it.name }.sorted())
    }
}
