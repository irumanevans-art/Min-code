package dev.min.agent.tools

import dev.min.agent.testkit.TestToolContext
import dev.min.agent.testkit.call
import dev.min.agent.testkit.text
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Read / Write / Edit / NotebookEdit 的文件编码行为（对齐 CC 2.1.296 / 2.1.299 / 2.1.301）。
 * 共同的判断在 [TextEncoding]，这里既测它本身，也测四个工具怎么用它。
 */
class EncodingToolsTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs

    private val gbk = Charset.forName("GBK")
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val leBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val beBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun utf16le(s: String) = leBom + s.toByteArray(Charsets.UTF_16LE)
    private fun utf16be(s: String) = beBom + s.toByteArray(Charsets.UTF_16BE)
    private fun utf8WithBom(s: String) = utf8Bom + s.toByteArray(Charsets.UTF_8)

    private fun seed(path: String, bytes: ByteArray) {
        fs.put(path, bytes)
        ctx.see(path)
    }

    private suspend fun read(path: String) = ReadTool.call(ctx, "file_path" to path)
    private suspend fun write(path: String, content: String) = WriteTool.call(ctx, "file_path" to path, "content" to content)
    private suspend fun edit(path: String, old: String, new: String) =
        EditTool.call(ctx, "file_path" to path, "old_string" to old, "new_string" to new)

    // ---- TextEncoding 本身 ----

    @Test
    fun `按 BOM 判编码，UTF-32LE 的 BOM 不当 UTF-16LE`() {
        assertEquals(TextEncoding.Utf8, TextEncoding.detect("abc".toByteArray()))
        assertEquals(TextEncoding.Utf8Bom, TextEncoding.detect(utf8WithBom("a")))
        assertEquals(TextEncoding.Utf16Le, TextEncoding.detect(utf16le("a")))
        assertEquals(TextEncoding.Utf16Be, TextEncoding.detect(utf16be("a")))
        val utf32le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0, 'a'.code.toByte(), 0, 0, 0)
        assertEquals(TextEncoding.Utf8, TextEncoding.detect(utf32le))
        assertNull(TextEncoding.decodeWritable(utf32le))
    }

    @Test
    fun `严格解码：GBK、UTF-16BE、截断的 UTF-16LE 都不是可改写的文本`() {
        assertNull(TextEncoding.decodeWritable("中文".toByteArray(gbk)))
        assertNull(TextEncoding.decodeWritable(utf16be("中文")))
        assertNull(TextEncoding.decodeWritable(utf16le("中文").copyOf(5)))
        assertNotNull(TextEncoding.decodeWritable(utf16le("中文")))
        assertEquals("中文", TextEncoding.decodeWritable(utf8WithBom("中文"))!!.text)
    }

    @Test
    fun `扩展名表大小写不敏感，文本类扩展名不在表里`() {
        for (ext in listOf("xlsm", "XLSX", "docx", "parquet", "epub", "heic", "heif", "avif", "zip", "jar", "gz", "7z", "rar")) {
            assertTrue(ext, TextEncoding.isBinaryExtension(ext))
        }
        for (ext in listOf("txt", "kt", "json", "svg", "ipynb", "pdf", "png", "")) assertFalse(ext, TextEncoding.isBinaryExtension(ext))
    }

    // ---- Read ----

    @Test
    fun `Read 带 BOM 的 UTF-16LE 正常读出，内容里没有 BOM`() = runTest {
        fs.put("/work/a.txt", utf16le("你好\nworld\n"))
        val r = read("/work/a.txt")
        assertFalse(r.text, r.isError)
        assertEquals("     1\t你好\n     2\tworld", r.text)
    }

    @Test
    fun `Read 带 BOM 的 UTF-16BE 正常读出`() = runTest {
        fs.put("/work/a.txt", utf16be("你好\nworld\n"))
        assertEquals("     1\t你好\n     2\tworld", read("/work/a.txt").text)
    }

    @Test
    fun `Read UTF-8 BOM 不出现在第一行里`() = runTest {
        fs.put("/work/a.txt", utf8WithBom("key=1\n"))
        assertEquals("     1\tkey=1", read("/work/a.txt").text)
    }

    @Test
    fun `Read 没有 BOM 的 UTF-16 仍按二进制拒绝`() = runTest {
        fs.put("/work/a.txt", "abc".toByteArray(Charsets.UTF_16LE))
        val r = read("/work/a.txt")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("二进制文件"))
    }

    @Test
    fun `Read GBK 文件照旧宽松显示，不拒绝`() = runTest {
        fs.put("/work/a.txt", "abc 中文\n".toByteArray(gbk))
        val r = read("/work/a.txt")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.startsWith("     1\tabc "))
    }

    @Test
    fun `Read 扩展名表命中的文件按二进制拒绝，即使前 8KB 没有 NUL`() = runTest {
        for (name in listOf("a.xlsm", "b.PARQUET", "c.epub", "d.heic", "e.zip", "f.docx")) {
            fs.put("/work/$name", "PK plain looking bytes, no NUL at all")
            val r = read("/work/$name")
            assertTrue(name, r.isError)
            assertTrue(r.text, r.text.contains("二进制文件"))
        }
        fs.put("/work/ok.txt", "PK plain looking bytes")
        assertFalse(read("/work/ok.txt").isError)
    }

    // ---- Write ----

    @Test
    fun `Write 覆盖 GBK 文件被拒绝，文件字节不变`() = runTest {
        val original = "名称=值\n".toByteArray(gbk)
        seed("/work/a.txt", original)
        val r = write("/work/a.txt", "名称=新值\n")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("不是 UTF-8"))
        assertTrue(r.text, r.text.contains("iconv"))
        assertArrayEquals(original, fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Write 覆盖 UTF-16BE 文件被拒绝，文件字节不变`() = runTest {
        val original = utf16be("hello")
        seed("/work/a.txt", original)
        val r = write("/work/a.txt", "hello world")
        assertTrue(r.isError)
        assertArrayEquals(original, fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Write 覆盖带 BOM 的 UTF-8 文件后仍带 BOM`() = runTest {
        seed("/work/a.txt", utf8WithBom("old\n"))
        val r = write("/work/a.txt", "新内容\n")
        assertFalse(r.text, r.isError)
        assertArrayEquals(utf8WithBom("新内容\n"), fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Write 覆盖带 BOM 的 UTF-16LE 文件按 UTF-16LE 加 BOM 写回`() = runTest {
        seed("/work/a.txt", utf16le("old\r\n"))
        val r = write("/work/a.txt", "新内容\r\n")
        assertFalse(r.text, r.isError)
        assertArrayEquals(utf16le("新内容\r\n"), fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Write 新建文件是不带 BOM 的 UTF-8，覆盖无 BOM 的 UTF-8 也不加 BOM`() = runTest {
        assertFalse(write("/work/new.txt", "中文\n").isError)
        assertArrayEquals("中文\n".toByteArray(), fs.bytes("/work/new.txt"))
        seed("/work/b.txt", "旧\n".toByteArray())
        assertFalse(write("/work/b.txt", "新\n").isError)
        assertArrayEquals("新\n".toByteArray(), fs.bytes("/work/b.txt"))
    }

    @Test
    fun `Write 覆盖空文件照常写`() = runTest {
        seed("/work/a.txt", ByteArray(0))
        assertFalse(write("/work/a.txt", "x").isError)
        assertArrayEquals("x".toByteArray(), fs.bytes("/work/a.txt"))
    }

    // ---- Edit ----

    @Test
    fun `Edit GBK 文件被拒绝，文件字节不变`() = runTest {
        val original = "name=中文\nport=1\n".toByteArray(gbk)
        seed("/work/a.txt", original)
        val r = edit("/work/a.txt", "port=1", "port=2")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("不是 UTF-8"))
        assertTrue(r.text, r.text.contains("iconv"))
        assertArrayEquals(original, fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Edit UTF-16BE 文件被拒绝而不是当二进制`() = runTest {
        val original = utf16be("port=1")
        seed("/work/a.txt", original)
        val r = edit("/work/a.txt", "port=1", "port=2")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("iconv"))
        assertArrayEquals(original, fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Edit 带 BOM 的 UTF-16LE：改动后仍是 UTF-16LE 加 BOM，CRLF 照旧`() = runTest {
        seed("/work/a.txt", utf16le("名称=甲\r\nport=1\r\n"))
        val r = edit("/work/a.txt", "port=1", "port=2")
        assertFalse(r.text, r.isError)
        assertArrayEquals(utf16le("名称=甲\r\nport=2\r\n"), fs.bytes("/work/a.txt"))
    }

    @Test
    fun `Edit 带 BOM 的 UTF-8：非 ASCII 字符原样保留，BOM 保留`() = runTest {
        seed("/work/a.txt", utf8WithBom("名称=甲\nport=1\n"))
        assertFalse(edit("/work/a.txt", "port=1", "port=2").isError)
        assertArrayEquals(utf8WithBom("名称=甲\nport=2\n"), fs.bytes("/work/a.txt"))
    }

    // ---- NotebookEdit ----

    private val nb = "{\"cells\":[{\"cell_type\":\"markdown\",\"id\":\"a\",\"metadata\":{},\"source\":[\"x\"]}]," +
        "\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}"

    private suspend fun editNb(path: String) =
        NotebookEditTool.call(ctx, "notebook_path" to path, "cell_id" to "a", "new_source" to "y")

    @Test
    fun `NotebookEdit GBK 文件被拒绝，文件字节不变`() = runTest {
        val original = nb.replace("\"x\"", "\"中文\"").toByteArray(gbk)
        seed("/work/a.ipynb", original)
        val r = editNb("/work/a.ipynb")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("iconv"))
        assertArrayEquals(original, fs.bytes("/work/a.ipynb"))
    }

    @Test
    fun `NotebookEdit 带 BOM 的 UTF-16LE notebook 改完仍是 UTF-16LE 加 BOM`() = runTest {
        seed("/work/a.ipynb", utf16le(nb))
        val r = editNb("/work/a.ipynb")
        assertFalse(r.text, r.isError)
        assertArrayEquals(utf16le(nb.replace("\"x\"", "\"y\"")), fs.bytes("/work/a.ipynb"))
    }

    @Test
    fun `NotebookEdit 带 BOM 的 UTF-8 notebook 保留 BOM`() = runTest {
        seed("/work/a.ipynb", utf8WithBom(nb))
        assertFalse(editNb("/work/a.ipynb").isError)
        assertArrayEquals(utf8WithBom(nb.replace("\"x\"", "\"y\"")), fs.bytes("/work/a.ipynb"))
    }
}
