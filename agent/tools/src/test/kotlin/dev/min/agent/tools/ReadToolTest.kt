package dev.min.agent.tools

import dev.min.agent.testkit.*
import dev.min.agent.protocol.ImageSource
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.ResumePolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadToolTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs

    // ---- parse ----

    @Test
    fun `缺 file_path 被拒绝`() {
        assertEquals("缺少必填字段 `file_path`（字符串）。", ReadTool.reject())
    }

    @Test
    fun `file_path 不是字符串被拒绝`() {
        assertEquals("字段 `file_path` 必须是字符串。", ReadTool.reject("file_path" to 5))
    }

    @Test
    fun `相对路径被拒绝并提示用绝对路径`() {
        val msg = ReadTool.reject("file_path" to "src/a.kt")
        assertTrue(msg, msg.startsWith("字段 `file_path` 必须是绝对路径（以 / 开头），收到 `src/a.kt`。请用绝对路径"))
    }

    @Test
    fun `offset 与 limit 必须是不小于 1 的整数`() {
        assertEquals("字段 `offset` 不能小于 1，收到 0。", ReadTool.reject("file_path" to "/a", "offset" to 0))
        assertEquals("字段 `limit` 必须是整数，收到 abc。", ReadTool.reject("file_path" to "/a", "limit" to "abc"))
        assertEquals("字段 `limit` 不能小于 1，收到 -3。", ReadTool.reject("file_path" to "/a", "limit" to -3))
    }

    @Test
    fun `默认 offset 为 1 limit 为 2000，字符串数字也接受`() {
        val d = ReadTool.parsed("file_path" to "/a")
        assertEquals(1, d.offset)
        assertEquals(2000, d.limit)
        assertEquals(7, ReadTool.parsed("file_path" to "/a", "offset" to "7").offset)
    }

    // ---- 正常路径 ----

    @Test
    fun `读小文件带行号，制表符分隔，行号右对齐 6 位`() = runTest {
        fs.put("/work/a.txt", "alpha\nbeta\n")
        val r = ReadTool.call(ctx, "file_path" to "/work/a.txt")
        assertFalse(r.isError)
        assertEquals("     1\talpha\n     2\tbeta", r.text)
    }

    @Test
    fun `没有末尾换行时最后一行照样显示，CRLF 的回车被去掉`() = runTest {
        fs.put("/work/a.txt", "x\r\ny")
        assertEquals("     1\tx\n     2\ty", ReadTool.call(ctx, "file_path" to "/work/a.txt").text)
    }

    @Test
    fun `touched 记下读到的文件的 stamp`() = runTest {
        fs.put("/work/a.txt", "hello\n", mtime = 42)
        val r = ReadTool.call(ctx, "file_path" to "/work/a.txt")
        assertEquals(listOf(FileStamp(GuestPath("/work/a.txt"), 6, 42)), r.touched)
    }

    // ---- 分页与截断 ----

    private fun numbers(n: Int) = (1..n).joinToString("") { "l$it\n" }

    @Test
    fun `limit 截断时末尾说明总行数与已显示范围`() = runTest {
        fs.put("/work/n.txt", numbers(5))
        val r = ReadTool.call(ctx, "file_path" to "/work/n.txt", "limit" to 2)
        assertEquals(
            "     1\tl1\n     2\tl2\n（共 5 行，只显示了 1–2 行，用 offset/limit 读其余部分）",
            r.text,
        )
    }

    @Test
    fun `offset 从中间读，读到末尾就不再有说明`() = runTest {
        fs.put("/work/n.txt", numbers(5))
        val mid = ReadTool.call(ctx, "file_path" to "/work/n.txt", "offset" to 3, "limit" to 2)
        assertEquals(
            "     3\tl3\n     4\tl4\n（共 5 行，只显示了 3–4 行，用 offset/limit 读其余部分）",
            mid.text,
        )
        val tail = ReadTool.call(ctx, "file_path" to "/work/n.txt", "offset" to 5)
        assertEquals("     5\tl5", tail.text)
    }

    @Test
    fun `默认最多读 2000 行`() = runTest {
        fs.put("/work/big.txt", numbers(2500))
        val r = ReadTool.call(ctx, "file_path" to "/work/big.txt")
        val lines = r.text.lines()
        assertEquals("  2000\tl2000", lines[1999])
        assertEquals("（共 2500 行，只显示了 1–2000 行，用 offset/limit 读其余部分）", lines[2000])
        assertEquals(2001, lines.size)
    }

    @Test
    fun `offset 超出末尾是错误并说明行数`() = runTest {
        fs.put("/work/n.txt", numbers(3))
        val r = ReadTool.call(ctx, "file_path" to "/work/n.txt", "offset" to 9)
        assertTrue(r.isError)
        assertEquals("offset=9 超出文件末尾：/work/n.txt 只有 3 行。", r.text)
    }

    @Test
    fun `超长行截断并标注原长度`() = runTest {
        fs.put("/work/w.txt", "a".repeat(2005) + "\nok\n")
        val r = ReadTool.call(ctx, "file_path" to "/work/w.txt")
        assertEquals("     1\t" + "a".repeat(2000) + "…[该行共 2005 字符，已截断]\n     2\tok", r.text)
    }

    @Test
    fun `文件超过一次读入上限时说明后面读不到`() = runTest {
        fs.put("/work/huge.txt", "x\n".repeat(ReadTool.MAX_TEXT_BYTES / 2 + 10))
        val r = ReadTool.call(ctx, "file_path" to "/work/huge.txt", "limit" to 1)
        assertTrue(r.text, r.text.contains("（共至少 ${ReadTool.MAX_TEXT_BYTES / 2} 行，只显示了 1–1 行"))
        assertTrue(r.text, r.text.contains("超过一次读入上限"))
    }

    // ---- 可预期失败 ----

    @Test
    fun `空文件不是错误`() = runTest {
        fs.put("/work/e.txt", "")
        val r = ReadTool.call(ctx, "file_path" to "/work/e.txt")
        assertFalse(r.isError)
        assertEquals("文件存在但是空的：/work/e.txt", r.text)
        assertEquals(1, r.touched.size)
    }

    @Test
    fun `不存在`() = runTest {
        val r = ReadTool.call(ctx, "file_path" to "/work/nope.txt")
        assertTrue(r.isError)
        assertEquals("文件不存在：/work/nope.txt。可以用 Glob 找一找正确的路径。", r.text)
        assertTrue(r.touched.isEmpty())
    }

    @Test
    fun `目录建议用 Glob 或 ls`() = runTest {
        fs.mkdir("/work/src")
        val r = ReadTool.call(ctx, "file_path" to "/work/src")
        assertTrue(r.isError)
        assertEquals("/work/src 是目录，不是文件。列目录请用 Glob（如 pattern=\"*\"）或 Bash 的 ls。", r.text)
    }

    @Test
    fun `二进制文件被拒绝（前 8KB 有 NUL）`() = runTest {
        fs.put("/work/b.bin", byteArrayOf(0x41, 0x00, 0x42))
        val r = ReadTool.call(ctx, "file_path" to "/work/b.bin")
        assertTrue(r.isError)
        assertEquals("/work/b.bin 是二进制文件，不能按文本读。", r.text)
    }

    @Test
    fun `NUL 在 8KB 之后不算二进制`() = runTest {
        fs.put("/work/t.txt", ByteArray(9000) { 'a'.code.toByte() } + byteArrayOf(0))
        assertFalse(ReadTool.call(ctx, "file_path" to "/work/t.txt").isError)
    }

    // ---- 图片 ----

    @Test
    fun `图片返回 base64 的 ImagePart 加一句说明，touched 有记录`() = runTest {
        fs.put("/work/p.PNG", byteArrayOf(1, 2, 3))
        val r = ReadTool.call(ctx, "file_path" to "/work/p.PNG")
        assertFalse(r.isError)
        assertEquals("图片 /work/p.PNG（image/png，3 字节）", r.text)
        val img = r.images.single()
        assertEquals("image/png", img.mediaType)
        assertEquals(ImageSource.Base64("AQID"), img.source)
        assertEquals(1, r.touched.size)
    }

    @Test
    fun `jpg 与 webp 的媒体类型`() = runTest {
        fs.put("/work/a.jpg", byteArrayOf(1))
        fs.put("/work/b.webp", byteArrayOf(1))
        assertEquals("image/jpeg", ReadTool.call(ctx, "file_path" to "/work/a.jpg").images.single().mediaType)
        assertEquals("image/webp", ReadTool.call(ctx, "file_path" to "/work/b.webp").images.single().mediaType)
    }

    @Test
    fun `超过 5MB 的图片是错误`() = runTest {
        fs.put("/work/big.png", ByteArray(5 * 1024 * 1024 + 1))
        val r = ReadTool.call(ctx, "file_path" to "/work/big.png")
        assertTrue(r.isError)
        assertEquals("图片太大（5242881 字节，上限 5242880 字节），无法读取。", r.text)
        assertTrue(r.images.isEmpty())
    }

    // ---- 权限与 traits ----

    @Test
    fun `permissionAsk 列出读取的路径与分级`() {
        fs.scopes["/sdcard"] = PathScope.Device
        val ask = ReadTool.permissionAsk(ReadTool.parsed("file_path" to "/sdcard/a.txt"), ctx)
        assertEquals("Read", ask.tool)
        assertEquals("读取 /sdcard/a.txt", ask.summary)
        assertEquals(listOf(PathAccess(GuestPath("/sdcard/a.txt"), AccessKind.Read, PathScope.Device)), ask.paths)
        assertTrue(ask.commands.isEmpty())
    }

    @Test
    fun `traits 只读 可并发 可重放`() {
        assertTrue(ReadTool.traits.readOnly)
        assertTrue(ReadTool.traits.concurrencySafe)
        assertEquals(ResumePolicy.Replay, ReadTool.traits.resumePolicy)
    }
}
