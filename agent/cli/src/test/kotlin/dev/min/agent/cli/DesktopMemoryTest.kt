package dev.min.agent.cli

import dev.min.agent.context.MemoryLoader
import dev.min.agent.spi.MemorySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 记忆加载走真实的桌面路径映射（Windows 上是 `/c/…` 这种按盘挂载的 guest 路径）。
 * 临时目录的祖先里可能本来就有 CLAUDE.md（比如仓库根），所以只断言我们放的文件、相对顺序与没有读失败。
 */
class DesktopMemoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `memory loads through the desktop path mapping`() {
        val home = tmp.newFolder("home")
        val work = tmp.newFolder("work")
        val cwd = Files.createDirectories(work.toPath().resolve("app"))
        Files.createDirectories(home.toPath().resolve(".claude/rules"))
        Files.writeString(home.toPath().resolve(".claude/CLAUDE.md"), "user")
        Files.writeString(home.toPath().resolve(".claude/rules/r.md"), "user rule")
        Files.writeString(work.toPath().resolve("CLAUDE.md"), "project @docs/a.md")
        Files.createDirectories(work.toPath().resolve("docs"))
        Files.writeString(work.toPath().resolve("docs/a.md"), "imported")
        Files.writeString(cwd.resolve("CLAUDE.local.md"), "local")

        val paths = DesktopPaths(workspace = work.toPath(), scratchRoot = tmp.newFolder("root").toPath())
        val memory = runBlocking {
            MemoryLoader.load(paths.fs, paths.guestOfHost(cwd), paths.guestOfHost(home.toPath()))
        }

        val ours = memory.files.filter { it.path.value.startsWith(paths.guestOfHost(tmp.root.toPath()).value + "/") }
        assertEquals(
            listOf(
                "home/.claude/CLAUDE.md" to MemorySource.User,
                "home/.claude/rules/r.md" to MemorySource.Rule,
                "work/docs/a.md" to MemorySource.Import,
                "work/CLAUDE.md" to MemorySource.Project,
                "work/app/CLAUDE.local.md" to MemorySource.Local,
            ),
            ours.map { it.path.value.substringAfter(paths.guestOfHost(tmp.root.toPath()).value + "/") to it.source },
        )
        assertEquals(listOf("user", "user rule", "imported", "project @docs/a.md", "local"), ours.map { it.content })
        assertTrue("不该有读失败：${memory.notes}", memory.notes.none { "失败" in it })
    }
}
