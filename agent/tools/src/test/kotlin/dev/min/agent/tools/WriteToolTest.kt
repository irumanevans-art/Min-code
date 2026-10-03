package dev.min.agent.tools

import dev.min.agent.testkit.*
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ResumePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteToolTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs

    // ---- parse ----

    @Test
    fun `缺字段与类型错误被拒绝`() {
        assertEquals("缺少必填字段 `file_path`（字符串）。", WriteTool.reject("content" to "x"))
        assertEquals("缺少必填字段 `content`（字符串）。", WriteTool.reject("file_path" to "/a"))
        assertEquals("字段 `content` 必须是字符串。", WriteTool.reject("file_path" to "/a", "content" to 1))
    }

    @Test
    fun `相对路径被拒绝`() {
        val msg = WriteTool.reject("file_path" to "a.txt", "content" to "x")
        assertTrue(msg, msg.startsWith("字段 `file_path` 必须是绝对路径（以 / 开头），收到 `a.txt`。"))
    }

    @Test
    fun `空内容合法`() {
        assertEquals("", WriteTool.parsed("file_path" to "/a", "content" to "").content)
    }

    // ---- 新文件 ----

    @Test
    fun `新文件不要求先读，写入内容并报行数`() = runTest {
        val r = WriteTool.call(ctx, "file_path" to "/work/new.txt", "content" to "a\nb\n")
        assertFalse(r.isError)
        assertEquals("已写入 /work/new.txt（2 行）", r.text)
        assertEquals("a\nb\n", fs.text("/work/new.txt"))
    }

    @Test
    fun `新建文件的改动上报带全是加号行的 diff`() = runTest {
        val r = WriteTool.call(ctx, "file_path" to "/work/new.txt", "content" to "a\nb\n")
        val edit = r.fileEdits.single()
        assertEquals(GuestPath("/work/new.txt"), edit.path)
        assertTrue(edit.created)
        assertFalse(edit.deleted)
        assertEquals("--- /work/new.txt\n+++ /work/new.txt\n+a\n+b", edit.diff)
        assertEquals(0, edit.truncated)
    }

    @Test
    fun `新建一个大文件也逐行上报，不退化成只报行数`() = runTest {
        val content = (1..1500).joinToString("\n") { "line$it" } + "\n"
        val edit = WriteTool.call(ctx, "file_path" to "/work/big.txt", "content" to content).fileEdits.single()
        assertEquals(1500, edit.diff!!.lines().count { it.startsWith("+line") })
        assertFalse(edit.diff!!.contains("末尾的换行变了"))
    }

    @Test
    fun `超大的 diff 被截断：FileEdit 记下省略的字符数，末尾说明还有多少行没列`() = runTest {
        val content = (1..40_000).joinToString("\n") { "line-number-$it" } + "\n"
        val edit = WriteTool.call(ctx, "file_path" to "/work/huge.txt", "content" to content).fileEdits.single()
        assertTrue("省略的字符数 > 0", edit.truncated > 0)
        val last = edit.diff!!.lines().last()
        assertTrue(last, Regex("""@@ 还有 \d+ 行改动未列出（共 \+40000 −0） @@""").matches(last))
    }

    @Test
    fun `新建空文件没有 diff 但仍标为新建`() = runTest {
        val edit = WriteTool.call(ctx, "file_path" to "/work/empty.txt", "content" to "").fileEdits.single()
        assertTrue(edit.created)
        assertEquals(null, edit.diff)
    }

    @Test
    fun `把已有文件写成空内容不算删除`() = runTest {
        fs.put("/work/a.txt", "x\n")
        ctx.see("/work/a.txt")
        val edit = WriteTool.call(ctx, "file_path" to "/work/a.txt", "content" to "").fileEdits.single()
        assertFalse(edit.created)
        assertFalse("清空不是删除", edit.deleted)
        assertEquals("--- /work/a.txt\n+++ /work/a.txt\n-x", edit.diff)
    }

    @Test
    fun `行数：无末尾换行多算最后一行，空内容 0 行`() = runTest {
        assertEquals("已写入 /work/a（2 行）", WriteTool.call(ctx, "file_path" to "/work/a", "content" to "a\nb").text)
        assertEquals("已写入 /work/b（0 行）", WriteTool.call(ctx, "file_path" to "/work/b", "content" to "").text)
        assertEquals("已写入 /work/c（1 行）", WriteTool.call(ctx, "file_path" to "/work/c", "content" to "x\n").text)
    }

    @Test
    fun `touched 是写入后的 stat`() = runTest {
        val r = WriteTool.call(ctx, "file_path" to "/work/new.txt", "content" to "hello")
        assertEquals(listOf(fs.stamp("/work/new.txt")), r.touched)
        assertEquals(GuestPath("/work/new.txt"), r.touched.single().path)
        assertEquals(5L, r.touched.single().size)
    }

    // ---- 先读后写的三种情况 ----

    @Test
    fun `已存在但没读过被拒绝，文件原样不动`() = runTest {
        fs.put("/work/a.txt", "old")
        val r = WriteTool.call(ctx, "file_path" to "/work/a.txt", "content" to "new")
        assertTrue(r.isError)
        assertEquals("/work/a.txt 已存在，修改已有文件前先用 Read 读一遍。", r.text)
        assertEquals("old", fs.text("/work/a.txt"))
        assertTrue(fs.writes.isEmpty())
        assertTrue(r.touched.isEmpty())
    }

    @Test
    fun `读过之后被改（mtime 变了）被拒绝`() = runTest {
        fs.put("/work/a.txt", "old")
        ctx.see("/work/a.txt")
        fs.touch("/work/a.txt")
        val r = WriteTool.call(ctx, "file_path" to "/work/a.txt", "content" to "new")
        assertTrue(r.isError)
        assertEquals("/work/a.txt 在你读过之后被改过，先重新 Read。", r.text)
        assertEquals("old", fs.text("/work/a.txt"))
    }

    @Test
    fun `读过之后大小变了也被拒绝`() = runTest {
        fs.put("/work/a.txt", "old", mtime = 5)
        ctx.see("/work/a.txt")
        fs.put("/work/a.txt", "older!", mtime = 5)
        assertTrue(WriteTool.call(ctx, "file_path" to "/work/a.txt", "content" to "new").isError)
    }

    @Test
    fun `读过且没变可以覆盖`() = runTest {
        fs.put("/work/a.txt", "old")
        ctx.see("/work/a.txt")
        val r = WriteTool.call(ctx, "file_path" to "/work/a.txt", "content" to "new\n")
        assertFalse(r.isError)
        assertEquals("已写入 /work/a.txt（1 行）", r.text)
        assertEquals("new\n", fs.text("/work/a.txt"))
        assertEquals(1, fs.writes.size)
    }

    @Test
    fun `写目录被拒绝`() = runTest {
        fs.mkdir("/work/d")
        val r = WriteTool.call(ctx, "file_path" to "/work/d", "content" to "x")
        assertTrue(r.isError)
        assertEquals("/work/d 是目录，不能当文件写。", r.text)
    }

    // ---- 权限与 traits ----

    @Test
    fun `permissionAsk 标 Write 与该路径的分级`() {
        fs.scopes["/sdcard"] = PathScope.Device
        fs.scopes["/etc"] = PathScope.Rootfs
        val dev = WriteTool.permissionAsk(WriteTool.parsed("file_path" to "/sdcard/x", "content" to ""), ctx)
        assertEquals("Write", dev.tool)
        assertEquals("写入 /sdcard/x", dev.summary)
        assertEquals(listOf(PathAccess(GuestPath("/sdcard/x"), AccessKind.Write, PathScope.Device)), dev.paths)
        val root = WriteTool.permissionAsk(WriteTool.parsed("file_path" to "/etc/x", "content" to ""), ctx)
        assertEquals(PathScope.Rootfs, root.paths.single().scope)
        val ws = WriteTool.permissionAsk(WriteTool.parsed("file_path" to "/work/x", "content" to ""), ctx)
        assertEquals(PathScope.Workspace, ws.paths.single().scope)
    }

    @Test
    fun `traits 是保守默认`() {
        assertFalse(WriteTool.traits.readOnly)
        assertFalse(WriteTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Unknown, WriteTool.traits.resumePolicy)
    }
}
