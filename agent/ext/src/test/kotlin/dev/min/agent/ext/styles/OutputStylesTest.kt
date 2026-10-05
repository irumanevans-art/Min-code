package dev.min.agent.ext.styles

import dev.min.agent.ext.config.InMemoryFs
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * output styles 的读取（CC output-styles.md「File locations」与 frontmatter 规则）：
 * 位置与优先级（managed < user < 项目链、项目链内近者胜、cwd 到仓库根为止）、frontmatter 的宽容解析、
 * 内置样式与磁盘同名覆盖、大小写敏感的选取。
 */
class OutputStylesTest {
    private fun fsOf(vararg files: Pair<String, String>): InMemoryFs = InMemoryFs().apply {
        files.forEach { (path, text) -> file(path, text) }
    }

    private val HOME = "/home/user"

    private fun load(fs: InMemoryFs, cwd: String = "/workspace") = runBlocking {
        OutputStyleFiles.load(fs, GuestPath(cwd), GuestPath(HOME))
    }

    @Test
    fun `user styles load and frontmatter fields are read`() {
        val fs = fsOf(
            "/home/user/.claude/output-styles/review.md" to """
                ---
                name: Code Reviewer
                description: 挑毛病的Reviewer
                keep-coding-instructions: true
                ---
                每次改动都先自查一遍再交付。
            """.trimIndent(),
        )
        val disk = load(fs)
        val style = disk.styles.getValue("Code Reviewer")
        assertEquals("挑毛病的Reviewer", style.description)
        assertTrue(style.keepCodingInstructions)
        assertEquals("每次改动都先自查一遍再交付。", style.body.trim())
        assertEquals("没读到东西就没有说明", 0, disk.notes.size)
    }

    @Test
    fun `a project style beats the user one, and nearest wins along the walk up`() {
        val fs = fsOf(
            "/home/user/.claude/output-styles/same.md" to "---\nname: S\n---\nuser 版",
            "/workspace/sub/.claude/output-styles/same.md" to "---\nname: S\n---\n近处版",
            "/workspace/.claude/output-styles/same.md" to "---\nname: S\n---\n远处版",
            "/workspace/sub/.git/x" to "",
            // 仓库根之上还有一层：不该被看到
            "/.claude/output-styles/same.md" to "---\nname: S\n---\n根外版",
        )
        val disk = load(fs, cwd = "/workspace/sub")
        assertEquals("项目近者胜，且只找到仓库根为止", "近处版", disk.styles.getValue("S").body.trim())
    }

    @Test
    fun `without a git root only the cwd directory is scanned`() {
        val fs = fsOf(
            "/workspace/.claude/output-styles/far.md" to "---\nname: F\n---\n上层",
            "/workspace/sub/.claude/output-styles/near.md" to "---\nname: N\n---\n本层",
        )
        val disk = load(fs, cwd = "/workspace/sub")
        assertTrue("没有 .git 就不往上找", disk.styles.containsKey("N"))
        assertFalse(disk.styles.containsKey("F"))
    }

    @Test
    fun `misspelled fields are ignored and broken YAML still loads by filename`() {
        val fs = fsOf(
            "/home/user/.claude/output-styles/terse.md" to """
                ---
                name: Terse
                keep-coding: true
                describtion: 拼错的
                ---
                少说多做。
            """.trimIndent(),
            "/home/user/.claude/output-styles/broken.md" to "---\nname: [unclosed\n---\n正文还在",
        )
        val disk = load(fs)
        val terse = disk.styles.getValue("Terse")
        assertNull("拼错的字段静默忽略：keep-coding 不等于 keep-coding-instructions", terse.description)
        assertFalse(terse.keepCodingInstructions)
        val broken = disk.styles.getValue("broken")
        assertEquals("YAML 坏了按文件名加载，正文照读", "正文还在", broken.body.trim())
        assertTrue(disk.notes.isNotEmpty())
    }

    @Test
    fun `builtins exist and a disk file can shadow one by name`() {
        assertTrue(OutputStyleFiles.BUILTINS.keys.containsAll(listOf("Default", "Explanatory", "Learning")))
        val fs = fsOf("/home/user/.claude/output-styles/learning.md" to "---\nname: Learning\n---\n我们自己的 Learning")
        val disk = load(fs)
        val def = OutputStyleFiles.resolve(disk.styles, "Learning")
        assertEquals("磁盘覆盖内置同名", "我们自己的 Learning", def!!.body.trim())
    }

    @Test
    fun `resolve is case sensitive and falls back to null`() {
        val disk = emptyMap<String, OutputStyleDef>()
        assertEquals(OutputStyleFiles.BUILTINS.getValue("Explanatory"), OutputStyleFiles.resolve(disk, "Explanatory"))
        assertNull("大小写敏感（CC 同）", OutputStyleFiles.resolve(disk, "explanatory"))
        assertNull(OutputStyleFiles.resolve(disk, "不存在的"))
    }
}
