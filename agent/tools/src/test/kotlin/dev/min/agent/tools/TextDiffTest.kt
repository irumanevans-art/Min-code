package dev.min.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具报给界面的 diff 正文。
 *
 * 形状要对得上界面那套判定（`core/session/ChangedFiles.kt` + `ui/richtext/DiffView.kt`）：
 * `+` / `-` 前缀的行算增删，上下文行带一个空格前缀，`@@` 头只作说明。
 */
class TextDiffTest {

    @Test
    fun `an unchanged text produces no diff`() {
        assertNull(TextDiff.unified("a\nb\n", "a\nb\n"))
        assertNull(TextDiff.unified("", ""))
    }

    @Test
    fun `a single changed line shows it with the surrounding context`() {
        val diff = TextDiff.unified("a\nb\nc\nd\ne\n", "a\nb\nC\nd\ne\n")!!
        assertEquals(listOf(" a", " b", "-c", "+C", " d", " e"), diff.lines())
    }

    @Test
    fun `a new file is all additions`() {
        val diff = TextDiff.unified("", "one\ntwo\n")!!
        assertEquals(listOf("+one", "+two"), diff.lines())
    }

    @Test
    fun `an emptied file is all deletions`() {
        val diff = TextDiff.unified("one\ntwo\n", "")!!
        assertEquals(listOf("-one", "-two"), diff.lines())
    }

    @Test
    fun `a trailing newline difference is visible`() {
        // 行内容一样，只有末尾换行变了：逐行比会比出「什么都没改」，那是错的
        val diff = TextDiff.unified("a\n", "a")!!
        assertTrue(diff, diff.contains("末尾的换行变了") || diff.contains("+a") || diff.contains("-a"))
    }

    @Test
    fun `distant context is elided and counted`() {
        val before = (1..40).joinToString("\n") { "line$it" } + "\n"
        val after = before.replace("line20", "LINE20")
        val diff = TextDiff.unified(before, after)!!
        assertTrue(diff, diff.lines().contains("@@ 上方 16 行未改动 @@"))
        assertTrue(diff, diff.lines().contains("@@ 下方 17 行未改动 @@"))
        assertTrue(diff, diff.lines().contains("-line20"))
        assertTrue(diff, diff.lines().contains("+LINE20"))
    }

    @Test
    fun `a huge rewrite is summarised instead of line by line`() {
        val before = (1..2000).joinToString("\n") { "old$it" }
        val after = (1..2000).joinToString("\n") { "new$it" }
        val diff = TextDiff.unified(before, after)!!
        assertTrue(diff, diff.contains("整段被替换：2000 行 → 2000 行"))
        assertTrue("不能逐行铺开", diff.lines().size < 5)
    }

    @Test
    fun `a very long line is clipped so one line cannot blow up the panel`() {
        val long = "x".repeat(TextDiff.MAX_LINE_CHARS + 500)
        val diff = TextDiff.unified("short\n", long + "\n")!!
        assertTrue(diff.lines().any { it.startsWith("+") && it.length < TextDiff.MAX_LINE_CHARS + 200 })
        assertTrue(diff, diff.contains("本行还有 500 字"))
    }

    @Test
    fun `CRLF is normalized so a line ending change alone is not a diff`() {
        assertNull(TextDiff.unified("a\nb\n", "a\r\nb\r\n"))
    }

    @Test
    fun `the diff never carries a file header - the panel adds that from the path`() {
        val diff = TextDiff.unified("a\n", "b\n")!!
        assertTrue(diff, diff.lines().none { it.startsWith("--- ") || it.startsWith("+++ ") })
    }
}
