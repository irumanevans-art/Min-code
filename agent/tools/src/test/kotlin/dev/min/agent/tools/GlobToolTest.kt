package dev.min.agent.tools

import dev.min.agent.testkit.*
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GlobQuery
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

class GlobToolTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs

    private fun found(vararg paths: String) {
        ctx.search.globbed = paths.map(::GuestPath)
    }

    // ---- parse ----

    @Test
    fun `缺 pattern 或为空被拒绝`() {
        assertEquals("缺少必填字段 `pattern`（字符串）。", GlobTool.reject())
        assertEquals("字段 `pattern` 不能为空，例如 `**/*.kt`。", GlobTool.reject("pattern" to "  "))
    }

    @Test
    fun `path 必须是绝对路径`() {
        val msg = GlobTool.reject("pattern" to "*.kt", "path" to "src")
        assertTrue(msg, msg.startsWith("字段 `path` 必须是绝对路径（以 / 开头），收到 `src`。"))
    }

    @Test
    fun `path 可省略`() {
        assertNull(GlobTool.parsed("pattern" to "*.kt").path)
    }

    // ---- 结果 ----

    @Test
    fun `按修改时间从新到旧排序，路径为绝对路径`() = runTest {
        fs.put("/work/old.kt", "", mtime = 10)
        fs.put("/work/new.kt", "", mtime = 30)
        fs.put("/work/mid.kt", "", mtime = 20)
        found("/work/old.kt", "/work/new.kt", "/work/mid.kt")
        val r = GlobTool.call(ctx, "pattern" to "*.kt")
        assertFalse(r.isError)
        assertEquals("/work/new.kt\n/work/mid.kt\n/work/old.kt", r.text)
    }

    @Test
    fun `mtime 相同按路径字典序，结果稳定`() = runTest {
        fs.put("/work/b.kt", "", mtime = 5)
        fs.put("/work/a.kt", "", mtime = 5)
        found("/work/b.kt", "/work/a.kt")
        assertEquals("/work/a.kt\n/work/b.kt", GlobTool.call(ctx, "pattern" to "*.kt").text)
    }

    @Test
    fun `没有 path 时用 cwd，有 path 时用 path，并把模式原样交给端口`() = runTest {
        fs.mkdir("/other")
        found()
        GlobTool.call(ctx, "pattern" to "**/*.kt")
        GlobTool.call(ctx, "pattern" to "*.md", "path" to "/other")
        assertEquals(
            listOf(
                GlobQuery("**/*.kt", GuestPath("/work"), 1_000),
                GlobQuery("*.md", GuestPath("/other"), 1_000),
            ),
            ctx.search.globQueries,
        )
    }

    @Test
    fun `没有结果不是错误`() = runTest {
        found()
        val r = GlobTool.call(ctx, "pattern" to "*.zzz")
        assertFalse(r.isError)
        assertEquals("没有匹配的文件", r.text)
    }

    @Test
    fun `超过 100 条只列前 100 条并提示，列的是最新的 100 条`() = runTest {
        val paths = (1..150).map { "/work/f$it.txt" }
        paths.forEachIndexed { i, p -> fs.put(p, "", mtime = (i + 1).toLong()) }
        found(*paths.toTypedArray())
        val lines = GlobTool.call(ctx, "pattern" to "*.txt").text.lines()
        assertEquals(101, lines.size)
        assertEquals("/work/f150.txt", lines.first())
        assertEquals("/work/f51.txt", lines[99])
        assertEquals("（只列出前 100 条，请缩小范围）", lines[100])
    }

    @Test
    fun `恰好 100 条不提示`() = runTest {
        val paths = (1..100).map { "/work/f$it.txt" }
        paths.forEach { fs.put(it, "", mtime = 1) }
        found(*paths.toTypedArray())
        val lines = GlobTool.call(ctx, "pattern" to "*.txt").text.lines()
        assertEquals(100, lines.size)
    }

    @Test
    fun `端口截在 1000 时告知排序不完整`() = runTest {
        val paths = (1..1200).map { "/work/f$it.txt" }
        found(*paths.toTypedArray())
        val text = GlobTool.call(ctx, "pattern" to "*.txt").text
        assertTrue(text, text.endsWith("（匹配数达到 1000，只在前 1000 条里排了序，较新的文件可能没列出来）"))
    }

    @Test
    fun `列出之后被删的文件排在最后而不是丢掉`() = runTest {
        fs.put("/work/a.kt", "", mtime = 1)
        found("/work/gone.kt", "/work/a.kt")
        assertEquals("/work/a.kt\n/work/gone.kt", GlobTool.call(ctx, "pattern" to "*.kt").text)
    }

    // ---- 可预期失败 ----

    @Test
    fun `搜索根不存在或不是目录是错误`() = runTest {
        val missing = GlobTool.call(ctx, "pattern" to "*", "path" to "/nope")
        assertTrue(missing.isError)
        assertEquals("目录不存在：/nope。", missing.text)
        fs.put("/work/f.txt", "")
        val file = GlobTool.call(ctx, "pattern" to "*", "path" to "/work/f.txt")
        assertTrue(file.isError)
        assertEquals("/work/f.txt 不是目录，path 要给目录。", file.text)
    }

    @Test
    fun `cwd 不存在时同样报错`() = runTest {
        val bare = TestToolContext(cwd = GuestPath("/missing"), createCwd = false)
        assertTrue(GlobTool.call(bare, "pattern" to "*").isError)
    }

    @Test
    fun `不产生 touched`() = runTest {
        fs.put("/work/a.kt", "")
        found("/work/a.kt")
        assertTrue(GlobTool.call(ctx, "pattern" to "*.kt").touched.isEmpty())
    }

    // ---- 权限与 traits ----

    @Test
    fun `permissionAsk 是搜索根的 Read，默认根是 cwd`() {
        fs.scopes["/sdcard"] = PathScope.Device
        val dflt = GlobTool.permissionAsk(GlobTool.parsed("pattern" to "*.kt"), ctx)
        assertEquals("Glob", dflt.tool)
        assertEquals(listOf(PathAccess(GuestPath("/work"), AccessKind.Read, PathScope.Workspace)), dflt.paths)
        val dev = GlobTool.permissionAsk(GlobTool.parsed("pattern" to "*", "path" to "/sdcard/DCIM"), ctx)
        assertEquals(listOf(PathAccess(GuestPath("/sdcard/DCIM"), AccessKind.Read, PathScope.Device)), dev.paths)
    }

    @Test
    fun `traits 只读 可并发 可重放`() {
        assertTrue(GlobTool.traits.readOnly)
        assertTrue(GlobTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Replay, GlobTool.traits.resumePolicy)
    }
}
