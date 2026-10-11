package dev.min.agent.tools

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.testkit.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Read 读 PDF：整份交给模型（不超过 10 页）、pages 本环境不能按页截取、损坏与超限给明确错误 */
class ReadPdfTest {
    private val ctx = TestToolContext()
    private val fs get() = ctx.fs

    /** 代码现造的最小合法 PDF：一个目录、一个 Pages 节点、n 个 Page 对象；测试里不从网上下载任何文件 */
    private fun minimalPdf(pages: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        sb.append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        val kids = (0 until pages).joinToString(" ") { "${3 + it} 0 R" }
        sb.append("2 0 obj << /Type /Pages /Kids [$kids] /Count $pages >> endobj\n")
        for (i in 0 until pages) sb.append("${3 + i} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj\n")
        sb.append("trailer << /Size ${3 + pages} /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `short pdf is sent whole as a document part with a page note`() = runTest {
        val pdf = minimalPdf(2)
        fs.put("/work/a.pdf", pdf, mtime = 7)
        val r = ReadTool.call(ctx, "file_path" to "/work/a.pdf")
        assertFalse(r.isError)
        val doc = r.content.filterIsInstance<DocumentPart>().single()
        assertEquals("application/pdf", doc.mediaType)
        assertEquals(ImageSource.Base64(b64(pdf)), doc.source)
        assertTrue(r.text, r.text.contains("2 页"))
        assertEquals(listOf(FileStamp(GuestPath("/work/a.pdf"), pdf.size.toLong(), 7)), r.touched)
    }

    @Test
    fun `extension is case insensitive`() = runTest {
        fs.put("/work/B.PDF", minimalPdf(1))
        assertEquals(1, ReadTool.call(ctx, "file_path" to "/work/B.PDF").content.filterIsInstance<DocumentPart>().size)
    }

    @Test
    fun `pages argument is refused because this environment cannot cut pages`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(3))
        val r = ReadTool.call(ctx, "file_path" to "/work/a.pdf", "pages" to "1-2")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("本环境不能按页截取"))
        assertTrue(r.content.none { it is DocumentPart })
    }

    @Test
    fun `malformed pages argument is an error about the format`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(3))
        for (bad in listOf("1-", "0", "5-2", "a", "1-21")) {
            val r = ReadTool.call(ctx, "file_path" to "/work/a.pdf", "pages" to bad)
            assertTrue(bad, r.isError)
            assertTrue(bad + " -> " + r.text, r.text.contains("pages"))
        }
    }

    @Test
    fun `pdf over ten pages without pages is refused with the page count`() = runTest {
        fs.put("/work/long.pdf", minimalPdf(11))
        val r = ReadTool.call(ctx, "file_path" to "/work/long.pdf")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("共 11 页") && r.text.contains("超过 10 页"))
        assertTrue(r.text, r.text.contains("本环境不能按页截取"))
    }

    @Test
    fun `page count unknown (no plain page objects) is sent whole and says so`() = runTest {
        // 页对象若藏在压缩的对象流里，正则数不到，只能照实说「页数未知」，交给供应商判
        val pdf = "%PDF-1.5\n1 0 obj << /Type /ObjStm >> endobj\ntrailer << >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
        fs.put("/work/s.pdf", pdf)
        val r = ReadTool.call(ctx, "file_path" to "/work/s.pdf")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("页数未知"))
        assertEquals(1, r.content.filterIsInstance<DocumentPart>().size)
    }

    /**
     * PDF 1.5 起的写法（pdfTeX 等默认）：页对象放进 FlateDecode 压缩的对象流（/Type /ObjStm），明文里一个 `/Type /Page` 都没有
     */
    private fun objectStreamPdf(pages: Int): ByteArray {
        val objs = (0 until pages).joinToString("\n") { "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>" }
        val deflater = java.util.zip.Deflater()
        deflater.setInput(objs.toByteArray(Charsets.ISO_8859_1))
        deflater.finish()
        val buf = ByteArray(64 * 1024)
        val packed = buf.copyOf(deflater.deflate(buf))
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.5\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        w("5 0 obj << /Type /ObjStm /N $pages /First 0 /Filter /FlateDecode /Length ${packed.size} >>\nstream\n")
        out.write(packed)
        w("\nendstream\nendobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
        return out.toByteArray()
    }

    @Test
    fun `pages hidden in a compressed object stream are still counted, so a long pdf is refused`() = runTest {
        fs.put("/work/latex.pdf", objectStreamPdf(12))
        val r = ReadTool.call(ctx, "file_path" to "/work/latex.pdf")
        assertTrue(r.text, r.isError)
        assertTrue(r.text, r.text.contains("共 12 页"))
        assertTrue(r.content.none { it is DocumentPart })
    }

    @Test
    fun `a short pdf with compressed pages is sent whole with its real page count`() = runTest {
        fs.put("/work/short.pdf", objectStreamPdf(3))
        val r = ReadTool.call(ctx, "file_path" to "/work/short.pdf")
        assertFalse(r.text, r.isError)
        assertTrue(r.text, r.text.contains("3 页"))
        assertEquals(1, r.content.filterIsInstance<DocumentPart>().size)
    }

    @Test
    fun `file without pdf header is reported as not a pdf`() = runTest {
        fs.put("/work/fake.pdf", "hello\n%%EOF\n")
        val r = ReadTool.call(ctx, "file_path" to "/work/fake.pdf")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("不是有效的 PDF"))
    }

    @Test
    fun `truncated pdf without end marker is reported as incomplete`() = runTest {
        val whole = minimalPdf(1)
        fs.put("/work/cut.pdf", whole.copyOf(whole.size - 6))
        val r = ReadTool.call(ctx, "file_path" to "/work/cut.pdf")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("不完整"))
    }

    @Test
    fun `encrypted pdf is refused`() = runTest {
        val pdf = "%PDF-1.4\n1 0 obj << /Filter /Standard /V 1 >> endobj\ntrailer << /Encrypt 1 0 R >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
        fs.put("/work/enc.pdf", pdf)
        val r = ReadTool.call(ctx, "file_path" to "/work/enc.pdf")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("加密"))
    }

    @Test
    fun `pdf over the byte limit is refused before reading`() = runTest {
        fs.put("/work/huge.pdf", ByteArray(ReadTool.MAX_PDF_BYTES.toInt() + 1))
        val r = ReadTool.call(ctx, "file_path" to "/work/huge.pdf")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("PDF 太大"))
    }
}
