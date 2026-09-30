package dev.min.agent.tools

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditToolTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs
    private val path = "/work/a.kt"

    private suspend fun edit(old: String, new: String, replaceAll: Boolean? = null) =
        EditTool.call(ctx, "file_path" to path, "old_string" to old, "new_string" to new, "replace_all" to replaceAll)

    private fun seed(content: String) {
        fs.put(path, content)
        ctx.see(path)
    }

    // ---- parse ----

    @Test
    fun `新旧相同与相对路径被拒绝`() {
        assertEquals("old_string 与 new_string 完全相同，没有要改的内容。",
            EditTool.reject("file_path" to path, "old_string" to "a", "new_string" to "a"))
        assertTrue(EditTool.reject("file_path" to "a.kt", "old_string" to "a", "new_string" to "b").contains("绝对路径"))
    }

    @Test
    fun `入参里的 CRLF 先转成 LF`() {
        assertEquals("a\nb", EditTool.parsed("file_path" to path, "old_string" to "a\r\nb", "new_string" to "c").oldString)
    }

    // ---- 先读后写 ----

    @Test
    fun `没读过的文件不许改`() = runTest {
        fs.put(path, "val a = 1\n")
        val r = edit("1", "2")
        assertTrue(r.isError)
        assertEquals("修改 $path 之前先用 Read 读一遍。", r.text)
        assertEquals("val a = 1\n", fs.text(path))
    }

    @Test
    fun `读过之后被外部改过的文件不许改`() = runTest {
        seed("val a = 1\n")
        fs.touch(path)
        val r = edit("1", "2")
        assertTrue(r.isError)
        assertEquals("$path 在你读过之后被改过，先重新 Read 再改。", r.text)
    }

    // ---- 替换 ----

    @Test
    fun `逐字匹配的修改：写回、记下新状态、返回带行号的改动附近内容`() = runTest {
        seed("line1\nline2\nval a = 1\nline4\n")
        val r = edit("val a = 1", "val a = 2")
        assertFalse(r.isError)
        assertEquals("line1\nline2\nval a = 2\nline4\n", fs.text(path))
        assertEquals(listOf(fs.stamp(path)), r.touched)
        assertTrue(r.text, r.text.startsWith("已修改 $path。\n改动附近的内容：\n"))
        assertTrue(r.text, r.text.contains("     3\tval a = 2"))
        assertFalse("末尾换行不算多出一行", r.text.contains("     5\t"))
    }

    @Test
    fun `整行抄丢了缩进、多了行尾空格：按整行匹配上，并明说差在哪`() = runTest {
        seed("fun f() {\n    x()\n}\n")
        val r = edit("x()   ", "y()")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("缩进不同"))
        assertEquals("fun f() {\n    y()\n}\n", fs.text(path))
    }

    @Test
    fun `多行抄丢了缩进：替换内容按文件原有缩进还原`() = runTest {
        seed("class A {\n    fun f() {\n        x()\n    }\n}\n")
        val r = edit("fun f() {\n    x()\n}", "fun f() {\n    y()\n}")
        assertFalse(r.text, r.isError)
        assertEquals("class A {\n    fun f() {\n        y()\n    }\n}\n", fs.text(path))
    }

    @Test
    fun `多处匹配与找不到时都不写，并告诉模型下一步`() = runTest {
        seed("a()\nb\na()\n")
        val ambiguous = edit("a()", "c()")
        assertTrue(ambiguous.isError)
        assertTrue(ambiguous.text, ambiguous.text.contains("第 1、3 行"))

        val missing = edit("a()\nzzz", "c()")
        assertTrue(missing.isError)
        assertTrue(missing.text, missing.text.contains("第 1、3 行"))
        assertEquals("a()\nb\na()\n", fs.text(path))
        assertTrue(fs.writes.isEmpty())
    }

    @Test
    fun `replace_all 替换全部逐字相同的出现并报数量`() = runTest {
        seed("log()\nx\nlog()\n")
        val r = edit("log()", "trace()", replaceAll = true)
        assertEquals("trace()\nx\ntrace()\n", fs.text(path))
        assertTrue(r.text, r.text.contains("已替换 2 处"))
    }

    @Test
    fun `CRLF 文件改完仍是 CRLF`() = runTest {
        seed("a\r\nb\r\nc\r\n")
        edit("b", "B")
        assertArrayEquals("a\r\nB\r\nc\r\n".encodeToByteArray(), fs.writes.last().second)
    }

    @Test
    fun `BOM 原样保留`() = runTest {
        seed("\uFEFFkey=1\n")
        edit("key=1", "key=2")
        assertEquals("\uFEFFkey=2\n", fs.text(path))
    }

    // ---- 新建与边界 ----

    @Test
    fun `old_string 为空时新建文件；文件不存在又给了原文则报错`() = runTest {
        val created = edit("", "hello\n")
        assertFalse(created.isError)
        assertTrue(created.text.startsWith("已写入 $path。"))
        assertEquals("hello\n", fs.text("/work/a.kt"))

        val missing = EditTool.call(ctx, "file_path" to "/work/none.kt", "old_string" to "x", "new_string" to "y")
        assertTrue(missing.isError)
        assertTrue(missing.text.contains("不存在"))
    }

    @Test
    fun `old_string 为空但文件已有内容时拒绝`() = runTest {
        seed("content\n")
        val r = edit("", "other")
        assertTrue(r.isError)
        assertEquals("content\n", fs.text(path))
    }

    @Test
    fun `二进制文件拒绝按文本编辑`() = runTest {
        fs.put(path, byteArrayOf(0x7F, 0x45, 0x00, 0x46))
        ctx.see(path)
        val r = edit("E", "X")
        assertTrue(r.isError)
        assertTrue(r.text.contains("二进制"))
    }

    @Test
    fun `权限请求按分级标出写入`() {
        fs.scopes["/sdcard"] = PathScope.Device
        val ask = EditTool.permissionAsk(EditTool.parsed("file_path" to "/sdcard/x.txt", "old_string" to "a", "new_string" to "b"), ctx)
        assertEquals(GuestPath("/sdcard/x.txt"), ask.paths.single().path)
        assertEquals(AccessKind.Write, ask.paths.single().kind)
        assertEquals(PathScope.Device, ask.paths.single().scope)
    }
}
