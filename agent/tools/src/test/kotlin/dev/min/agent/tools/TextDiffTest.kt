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
    fun `a big rewrite is still listed line by line, with real add and remove counts`() {
        val before = (1..2000).joinToString("\n") { "old$it" }
        val after = (1..2000).joinToString("\n") { "new$it" }
        val result = TextDiff.diff(before, after)!!
        assertEquals(0, result.omittedChars)
        val lines = result.text.lines()
        assertEquals(2000, lines.count { it.startsWith("-") })
        assertEquals(2000, lines.count { it.startsWith("+") })
        assertTrue("不能退化成只报行数", lines.none { it.contains("不逐行展示") })
    }

    @Test
    fun `a new big file does not claim the trailing newline changed`() {
        val diff = TextDiff.unified("", (1..1000).joinToString("\n") { "line$it" } + "\n")!!
        assertEquals(1000, diff.lines().count { it.startsWith("+") })
        assertTrue(diff.take(200), !diff.contains("末尾的换行变了"))
    }

    @Test
    fun `a diff over the hard cap stops listing lines and says how many changes were left out`() {
        val before = (1..30_000).joinToString("\n") { "old-line-number-$it" }
        val after = (1..30_000).joinToString("\n") { "new-line-number-$it" }
        val result = TextDiff.diff(before, after)!!
        val lines = result.text.lines()
        val marker = lines.last()
        assertTrue(marker, Regex("""@@ 还有 (\d+) 行改动未列出（共 \+30000 −30000） @@""").matches(marker))
        val left = Regex("""还有 (\d+) 行""").find(marker)!!.groupValues[1].toInt()
        val listed = lines.dropLast(1).count { it.startsWith("-") || it.startsWith("+") }
        assertEquals("列出的 + 省略的 = 全部改动行", 60_000, listed + left)
        assertTrue("正文不超过上限", result.text.length - marker.length - 1 <= TextDiff.MAX_DIFF_CHARS)
        assertTrue(result.omittedChars > 0)
    }

    @Test
    fun `a diff under the cap reports no omission`() {
        assertEquals(0, TextDiff.diff("a\n", "b\n")!!.omittedChars)
    }

    @Test
    fun `a very long line is clipped so one line cannot blow up the panel`() {
        val long = "x".repeat(TextDiff.MAX_LINE_CHARS + 500)
        val diff = TextDiff.unified("short\n", long + "\n")!!
        assertTrue(diff.lines().any { it.startsWith("+") && it.length < TextDiff.MAX_LINE_CHARS + 200 })
        assertTrue(diff, diff.contains("本行还有 500 字"))
    }

    @Test
    fun `a change that only differs in line endings or BOM is a one-line note, never null`() {
        val note = "@@ 只有换行符或 BOM 变了 @@"
        assertEquals(note, TextDiff.unified("a\nb\n", "a\r\nb\r\n"))
        assertEquals(note, TextDiff.unified("a\r\nb\r\n", "a\nb\n"))
        assertEquals(note, TextDiff.unified("\uFEFFa\n", "a\n"))
        assertEquals(note, TextDiff.unified("a\n", "\uFEFFa\n"))
        assertNull("原文一模一样才是 null", TextDiff.unified("a\r\n", "a\r\n"))
    }

    @Test
    fun `the diff never carries a file header - the panel adds that from the path`() {
        val diff = TextDiff.unified("a\n", "b\n")!!
        assertTrue(diff, diff.lines().none { it.startsWith("--- ") || it.startsWith("+++ ") })
    }
}
