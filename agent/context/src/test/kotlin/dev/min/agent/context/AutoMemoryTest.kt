package dev.min.agent.context

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.MemorySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动记忆的位置与索引截断（设计 §2.1–§2.3）：项目键（含 worktree 经 gitdir / commondir 指回主仓库）、
 * 前 200 行 / 前 25 KB 的截断与用量行数。
 */
class AutoMemoryTest {
    private val fs = InMemoryFs()

    private fun key(cwd: String) = runBlocking { AutoMemory.projectKey(fs, GuestPath(cwd)) }

    private fun index(dir: String, files: Map<String, String> = emptyMap()): AutoMemory.Index {
        files.forEach { (path, text) -> fs.file(path, text) }
        return runBlocking { AutoMemory.loadIndex(fs, GuestPath(dir)) }
    }

    // ---- 项目键 ----

    @Test
    fun `every character that is not alphanumeric becomes a dash`() {
        assertEquals("-root-proj", AutoMemory.key("/root/proj"))
        assertEquals("-root-My-Proj-2026", AutoMemory.key("/root/My Proj 2026"))
        // 非 ASCII 字母也不算 [A-Za-z0-9]：键里只剩 ASCII（「中文」是两个字符，各换一个 -）
        assertEquals("-w---", AutoMemory.key("/w/中文"))
    }

    @Test
    fun `a plain repo keys by its own root`() {
        fs.file("/repo/.git/HEAD", "ref: refs/heads/main")
        assertEquals("-repo", key("/repo"))
        assertEquals("-repo", key("/repo/src/api"))
    }

    @Test
    fun `a worktree keys by the main repository it points back to`() {
        // 主仓库的 .git 是目录；worktree 的 .git 是文件，gitdir 指到自己的 git 目录，commondir 指回主仓库
        fs.file("/repo/.git/HEAD", "ref: refs/heads/main")
        fs.file("/repo/.git/worktrees/wt/commondir", "../..")
        fs.file("/wt/.git", "gitdir: /repo/.git/worktrees/wt\n")
        assertEquals("-repo", key("/wt/src"))
    }

    @Test
    fun `relative gitdir and commondir resolve the same as absolute ones`() {
        fs.file("/repo/.git/HEAD", "ref")
        fs.file("/repo/.git/worktrees/wt2/commondir", "../..")
        fs.file("/wt2/.git", "gitdir: ../repo/.git/worktrees/wt2\n")
        assertEquals("-repo", key("/wt2"))
    }

    @Test
    fun `a gitdir without commondir keeps the worktree where the git file lives`() {
        // 子模块的 .git 文件长这样：gitdir 指自己的 git 目录，没有 commondir；仓库根就是 .git 文件所在的目录
        fs.file("/sub/.git/modules/x/HEAD", "ref")
        fs.file("/checkout/.git", "gitdir: /sub/.git/modules/x\n")
        assertEquals("-checkout", key("/checkout"))
    }

    @Test
    fun `outside a git repository the key is the cwd`() {
        fs.file("/plain/README.md", "no .git here")
        assertEquals("-plain", key("/plain"))
    }

    @Test
    fun `the default directory sits under home with the project key`() {
        fs.file("/repo/.git/HEAD", "ref")
        val dir = runBlocking { AutoMemory.directory(fs, GuestPath("/repo"), GuestPath("/root")) }
        assertEquals("/root/.claude/projects/-repo/memory", dir.value)
    }

    @Test
    fun `the directory override accepts absolute and tilde paths only`() {
        assertEquals("/mem", AutoMemory.directoryOverride(GuestPath("/root"), "/mem").let { it?.value })
        assertEquals("/root/notes", AutoMemory.directoryOverride(GuestPath("/root"), "~/notes").let { it?.value })
        assertNull(AutoMemory.directoryOverride(GuestPath("/root"), "notes"))
        assertNull(AutoMemory.directoryOverride(GuestPath("/root"), "../etc"))
    }

    // ---- 索引截断与用量 ----

    @Test
    fun `the index is loaded whole when it fits`() {
        val idx = index("/m", mapOf("/m/MEMORY.md" to "user.md: 喜欢简洁\nfeedback.md: 别抢方向盘\n"))
        assertEquals(MemorySource.Auto, idx.file!!.source)
        assertEquals("/m/MEMORY.md", idx.file!!.path.value)
        assertEquals("user.md: 喜欢简洁\nfeedback.md: 别抢方向盘", idx.file!!.content.trimEnd())
        assertEquals(2, idx.info.totalLines)
        assertEquals(2, idx.info.loadedLines)
    }

    @Test
    fun `the index is truncated at 200 lines and the usage says so`() {
        val text = (1..205).joinToString("\n") { "line $it" } + "\n"
        val idx = index("/m", mapOf("/m/MEMORY.md" to text))
        assertEquals("line 200", idx.file!!.content.split('\n')[199])
        // 正文收掉了尾部的空行（渲染时也会 trim）：200 行内容之间是 199 个换行
        assertEquals(199, idx.file!!.content.count { it == '\n' })
        assertEquals(205, idx.info.totalLines)
        assertEquals(200, idx.info.loadedLines)
    }

    @Test
    fun `the index is truncated at 25 KB by whole lines`() {
        val text = (1..100).joinToString("") { it.toString().padEnd(999, 'x') + "\n" } // 每行 1000 字节
        val idx = index("/m", mapOf("/m/MEMORY.md" to text))
        // 第 26 行会把累计推过 25 KB：整行截，保留 25 行
        assertEquals(25, idx.info.loadedLines)
        assertEquals(100, idx.info.totalLines)
        assertTrue(idx.file!!.content.toByteArray(Charsets.UTF_8).size <= AutoMemory.MAX_INDEX_BYTES)
    }

    @Test
    fun `a first line over the byte cap is cut at the byte budget`() {
        val text = "x".repeat(30_000) + "\nsecond line\n"
        val idx = index("/m", mapOf("/m/MEMORY.md" to text))
        assertEquals(AutoMemory.MAX_INDEX_BYTES, idx.file!!.content.toByteArray(Charsets.UTF_8).size)
        assertEquals(1, idx.info.loadedLines)
        assertEquals(2, idx.info.totalLines)
    }

    @Test
    fun `crlf and bom are normalized like other memory files`() {
        val idx = index("/m", mapOf("/m/MEMORY.md" to "\uFEFFa\r\nb\r\n"))
        assertEquals("a\nb", idx.file!!.content)
        assertEquals(2, idx.info.totalLines)
        assertEquals(2, idx.info.loadedLines)
    }

    @Test
    fun `a missing or blank index still reports the directory with zero usage`() {
        val missing = index("/m")
        assertNull(missing.file)
        assertEquals(0, missing.info.totalLines)
        assertEquals(0, missing.info.loadedLines)
        val blank = index("/m2", mapOf("/m2/MEMORY.md" to "  \n\n"))
        assertNull(blank.file)
    }
}
