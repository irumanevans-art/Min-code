package dev.min.agent.tools

import dev.min.agent.testkit.*
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ResumePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrepToolTest {
    private val ctx = TestToolContext()

    private fun hit(path: String, line: Int, text: String, context: Boolean = false) =
        GrepHit(GuestPath(path), line, text, context)

    private fun hits(vararg h: GrepHit) {
        ctx.search.hits = h.toList()
    }

    // ---- parse ----

    @Test
    fun `缺 pattern 或为空被拒绝`() {
        assertEquals("缺少必填字段 `pattern`（字符串）。", GrepTool.reject())
        assertEquals("字段 `pattern` 不能为空。", GrepTool.reject("pattern" to ""))
    }

    @Test
    fun `非法正则被拒绝并带上原因`() {
        val msg = GrepTool.reject("pattern" to "foo(")
        assertTrue(msg, msg.startsWith("字段 `pattern` 不是合法的正则："))
        assertTrue(msg, msg.contains("Unclosed group"))
    }

    @Test
    fun `未知 output_mode 被拒绝`() {
        assertEquals(
            "字段 `output_mode` 只能是 files_with_matches、content、count 之一，收到 `lines`。",
            GrepTool.reject("pattern" to "a", "output_mode" to "lines"),
        )
    }

    @Test
    fun `path 必须绝对，数值与布尔字段校验`() {
        assertTrue(GrepTool.reject("pattern" to "a", "path" to "src").startsWith("字段 `path` 必须是绝对路径"))
        assertEquals("字段 `-C` 不能小于 0，收到 -1。", GrepTool.reject("pattern" to "a", "-C" to -1))
        assertEquals("字段 `head_limit` 不能小于 1，收到 0。", GrepTool.reject("pattern" to "a", "head_limit" to 0))
        assertEquals("字段 `-i` 必须是布尔值（true / false）。", GrepTool.reject("pattern" to "a", "-i" to "maybe"))
    }

    @Test
    fun `默认值`() {
        val i = GrepTool.parsed("pattern" to "a")
        assertEquals(GrepMode.FilesWithMatches, i.mode)
        assertEquals(250, i.headLimit)
        assertTrue(i.lineNumbers)
        assertFalse(i.ignoreCase)
        assertEquals(0, i.context)
        assertFalse(i.multiline)
        assertNull(i.path)
        assertNull(i.glob)
    }

    // ---- files_with_matches ----

    @Test
    fun `files_with_matches 按文件去重，保持出现顺序`() = runTest {
        hits(hit("/work/b.kt", 3, "x"), hit("/work/b.kt", 9, "x"), hit("/work/a.kt", 1, "x"))
        assertEquals("/work/b.kt\n/work/a.kt", GrepTool.call(ctx, "pattern" to "x").text)
    }

    @Test
    fun `参数原样交给端口`() = runTest {
        ctx.fs.mkdir("/work/src")
        hits(hit("/work/a.kt", 1, "x"))
        GrepTool.call(ctx, "pattern" to "Foo.*", "path" to "/work/src", "glob" to "*.kt", "-i" to true, "multiline" to true)
        assertEquals(
            GrepQuery(
                pattern = "Foo.*", root = GuestPath("/work/src"), include = "*.kt", ignoreCase = true,
                maxHits = 20_000, contextLines = 0, multiline = true,
            ),
            ctx.search.grepQueries.single(),
        )
    }

    @Test
    fun `没给 path 时搜 cwd`() = runTest {
        hits(hit("/work/a.kt", 1, "x"))
        GrepTool.call(ctx, "pattern" to "x")
        assertEquals(GuestPath("/work"), ctx.search.grepQueries.single().root)
    }

    @Test
    fun `files 模式的 head_limit 按文件数截断`() = runTest {
        hits(hit("/w/1", 1, "x"), hit("/w/2", 1, "x"), hit("/w/3", 1, "x"))
        assertEquals(
            "/w/1\n/w/2\n（只显示前 2 个文件，用 head_limit 调大或缩小搜索范围）",
            GrepTool.call(ctx, "pattern" to "x", "head_limit" to 2).text,
        )
    }

    @Test
    fun `恰好等于 head_limit 不提示`() = runTest {
        hits(hit("/w/1", 1, "x"), hit("/w/2", 1, "x"))
        assertEquals("/w/1\n/w/2", GrepTool.call(ctx, "pattern" to "x", "head_limit" to 2).text)
    }

    @Test
    fun `没有命中不是错误`() = runTest {
        val r = GrepTool.call(ctx, "pattern" to "x")
        assertFalse(r.isError)
        assertEquals("没有匹配", r.text)
    }

    @Test
    fun `搜索本身失败报错而不是没有匹配`() = runTest {
        ctx.search.failure = java.io.IOException("无法读取搜索根 /work（没有读取权限）")
        for (mode in listOf("files_with_matches", "content", "count")) {
            val r = GrepTool.call(ctx, "pattern" to "x", "output_mode" to mode)
            assertTrue(mode, r.isError)
            assertEquals(mode, "搜索失败：无法读取搜索根 /work（没有读取权限）", r.text)
        }
    }

    @Test
    fun `已经有命中时半路出错照常给结果`() = runTest {
        hits(hit("/w/1", 1, "x"))
        ctx.search.failure = java.io.IOException("boom")
        val r = GrepTool.call(ctx, "pattern" to "x")
        assertFalse(r.text, r.isError)
        assertEquals("/w/1", r.text)
    }

    // ---- count ----

    @Test
    fun `count 每个文件一行 路径 冒号 命中数，上下文行不计`() = runTest {
        hits(hit("/w/a", 1, "x"), hit("/w/a", 2, "c", context = true), hit("/w/a", 5, "x"), hit("/w/b", 7, "x"))
        assertEquals("/w/a:2\n/w/b:1", GrepTool.call(ctx, "pattern" to "x", "output_mode" to "count").text)
    }

    @Test
    fun `count 的 head_limit 按文件数截断`() = runTest {
        hits(hit("/w/a", 1, "x"), hit("/w/b", 1, "x"))
        assertEquals(
            "/w/a:1\n（只显示前 1 个文件，用 head_limit 调大或缩小搜索范围）",
            GrepTool.call(ctx, "pattern" to "x", "output_mode" to "count", "head_limit" to 1).text,
        )
    }

    // ---- content ----

    @Test
    fun `content 默认带行号 路径 冒号 行号 冒号 内容`() = runTest {
        hits(hit("/w/a.kt", 12, "val x = 1"), hit("/w/b.kt", 3, "  x"))
        assertEquals(
            "/w/a.kt:12:val x = 1\n/w/b.kt:3:  x",
            GrepTool.call(ctx, "pattern" to "x", "output_mode" to "content").text,
        )
    }

    @Test
    fun `-n 为 false 时不带行号`() = runTest {
        hits(hit("/w/a.kt", 12, "val x = 1"), hit("/w/a.kt", 13, "ctx", context = true))
        assertEquals(
            "/w/a.kt:val x = 1\n/w/a.kt-ctx",
            GrepTool.call(ctx, "pattern" to "x", "output_mode" to "content", "-n" to false, "-C" to 1).text,
        )
    }

    @Test
    fun `上下文行用减号，不相邻的组之间放两个减号`() = runTest {
        hits(
            hit("/w/a.kt", 1, "before", context = true),
            hit("/w/a.kt", 2, "match", context = false),
            hit("/w/a.kt", 3, "after", context = true),
            hit("/w/a.kt", 9, "match2"),
            hit("/w/b.kt", 1, "other"),
        )
        assertEquals(
            "/w/a.kt-1-before\n/w/a.kt:2:match\n/w/a.kt-3-after\n--\n/w/a.kt:9:match2\n--\n/w/b.kt:1:other",
            GrepTool.call(ctx, "pattern" to "match", "output_mode" to "content", "-C" to 1).text,
        )
        assertEquals(1, ctx.search.grepQueries.single().contextLines)
    }

    @Test
    fun `没有 -C 时不放分隔线，相邻的多行命中也不放`() = runTest {
        hits(hit("/w/a", 1, "a\nb"), hit("/w/a", 3, "c"), hit("/w/a", 8, "d"))
        assertEquals(
            "/w/a:1:a\nb\n/w/a:3:c\n/w/a:8:d",
            GrepTool.call(ctx, "pattern" to "x", "output_mode" to "content").text,
        )
        hits(hit("/w/a", 1, "a\nb"), hit("/w/a", 3, "c"))
        assertEquals(
            "/w/a:1:a\nb\n/w/a:3:c",
            GrepTool.call(ctx, "pattern" to "x", "output_mode" to "content", "-C" to 1).text,
        )
    }

    @Test
    fun `content 的 head_limit 按行截断并注明`() = runTest {
        hits(hit("/w/a", 1, "one"), hit("/w/a", 2, "two"), hit("/w/a", 3, "three"))
        val r = GrepTool.call(ctx, "pattern" to "x", "output_mode" to "content", "head_limit" to 2)
        assertEquals("/w/a:1:one\n/w/a:2:two\n（只显示前 2 行，用 head_limit 调大或缩小搜索范围）", r.text)
        // 只多要一条用来判断有没有更多，不把整个仓库都扫上来
        assertEquals(3, ctx.search.grepQueries.single().maxHits)
    }

    @Test
    fun `超长命中行截到 500 字符`() = runTest {
        hits(hit("/w/min.js", 1, "y".repeat(600)))
        val text = GrepTool.call(ctx, "pattern" to "y", "output_mode" to "content").text
        assertEquals("/w/min.js:1:" + "y".repeat(500) + "…", text)
    }

    // ---- 失败与权限 ----

    @Test
    fun `前置上下文超过显示上限仍确认命中，避免误报没有匹配`() = runTest {
        hits(hit("/w/a", 1, "before1", true), hit("/w/a", 2, "before2", true),
            hit("/w/a", 3, "before3", true), hit("/w/a", 4, "match"), hit("/w/a", 5, "after", true))
        assertEquals("/w/a-1-before1\n/w/a-2-before2\n（只显示前 2 行，用 head_limit 调大或缩小搜索范围）",
            GrepTool.call(ctx, "pattern" to "match", "output_mode" to "content", "-C" to 3, "head_limit" to 2).text)
        assertEquals(3, ctx.search.grepQueries.single().maxHits)
    }

    @Test
    fun `count 的命中配额不被上下文占用`() = runTest {
        ctx.search.hits = (1..GrepTool.SCAN_LIMIT).map { hit("/w/a", it, "context", true) } + hit("/w/a", 20001, "match")
        assertEquals("/w/a:1", GrepTool.call(ctx, "pattern" to "match", "output_mode" to "count").text)
    }

    @Test
    fun `搜索根不存在是错误`() = runTest {
        val r = GrepTool.call(ctx, "pattern" to "x", "path" to "/nope")
        assertTrue(r.isError)
        assertEquals("路径不存在：/nope。", r.text)
    }

    @Test
    fun `path 可以是单个文件`() = runTest {
        ctx.fs.put("/work/a.kt", "x")
        hits(hit("/work/a.kt", 1, "x"))
        assertFalse(GrepTool.call(ctx, "pattern" to "x", "path" to "/work/a.kt").isError)
    }

    @Test
    fun `不产生 touched`() = runTest {
        hits(hit("/w/a", 1, "x"))
        assertTrue(GrepTool.call(ctx, "pattern" to "x").touched.isEmpty())
    }

    @Test
    fun `permissionAsk 是搜索根的 Read`() {
        ctx.fs.scopes["/sdcard"] = PathScope.Device
        val ask = GrepTool.permissionAsk(GrepTool.parsed("pattern" to "TODO", "path" to "/sdcard/notes"), ctx)
        assertEquals("Grep", ask.tool)
        assertEquals("搜索 TODO（在 /sdcard/notes）", ask.summary)
        assertEquals(listOf(PathAccess(GuestPath("/sdcard/notes"), AccessKind.Read, PathScope.Device)), ask.paths)
        val dflt = GrepTool.permissionAsk(GrepTool.parsed("pattern" to "TODO"), ctx)
        assertEquals(GuestPath("/work"), dflt.paths.single().path)
    }

    @Test
    fun `traits 只读 可并发 可重放`() {
        assertTrue(GrepTool.traits.readOnly)
        assertTrue(GrepTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Replay, GrepTool.traits.resumePolicy)
    }
}
