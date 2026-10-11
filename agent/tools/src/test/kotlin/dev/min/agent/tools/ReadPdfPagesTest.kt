package dev.min.agent.tools

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PdfPagePort
import dev.min.agent.spi.PdfPageResult
import dev.min.agent.spi.ToolContext
import dev.min.agent.testkit.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Read 的 pages：经宿主的 [PdfPagePort] 取页；参数格式、页数上限在工具这一层先挡，不占用端口 */
class ReadPdfPagesTest {
    private val base = TestToolContext()
    private val fs get() = base.fs

    /** 记录端口收到的请求，按给定结果回话 */
    private class FakePages(
        val answer: PdfPageResult,
        override val available: Boolean = true,
    ) : PdfPagePort {
        val asked = mutableListOf<Pair<Int, IntRange>>()
        override suspend fun pages(bytes: ByteArray, pages: IntRange): PdfPageResult {
            asked += bytes.size to pages
            return answer
        }
    }

    private fun ctxWith(port: PdfPagePort): ToolContext = object : ToolContext by base {
        override val pdfPages: PdfPagePort = port
    }

    /** 代码现造的最小 PDF（与 ReadPdfTest 同一套写法）：只为让 Read 的前置检查通过，内容不被端口以外的代码解析 */
    private fun minimalPdf(pages: Int): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        sb.append("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")
        val kids = (0 until pages).joinToString(" ") { "${3 + it} 0 R" }
        sb.append("2 0 obj << /Type /Pages /Kids [$kids] /Count $pages >> endobj\n")
        for (i in 0 until pages) sb.append("${3 + i} 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >> endobj\n")
        sb.append("trailer << /Size ${3 + pages} /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private val sample = PdfPageResult.Pages(
        parts = listOf(
            TextPart("第 2 页"),
            DocumentPart("application/pdf", ImageSource.Base64("AAAA")),
        ),
        pageCount = 5,
    )

    @Test
    fun `pages are taken through the port with the parsed range and the file bytes`() = runTest {
        val pdf = minimalPdf(5)
        fs.put("/work/a.pdf", pdf, mtime = 9)
        val port = FakePages(sample)
        val r = ReadTool.call(ctxWith(port), "file_path" to "/work/a.pdf", "pages" to "2-3")
        assertFalse(r.text, r.isError)
        assertEquals(listOf(pdf.size to (2..3)), port.asked)
        assertTrue(r.content.any { it is DocumentPart })
        assertTrue(r.text, r.text.contains("共 5 页"))
        assertEquals(listOf(FileStamp(GuestPath("/work/a.pdf"), pdf.size.toLong(), 9)), r.touched)
    }

    @Test
    fun `port parts are passed to the model in order after the note`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(5))
        val r = ReadTool.call(ctxWith(FakePages(sample)), "file_path" to "/work/a.pdf", "pages" to "2")
        val texts = r.content.filterIsInstance<TextPart>().map { it.text }
        assertEquals("第 2 页", texts.last())
        assertTrue(r.content.last() is DocumentPart)
    }

    @Test
    fun `page beyond the file reports the real page count and sends nothing`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(3))
        val r = ReadTool.call(ctxWith(FakePages(PdfPageResult.OutOfRange(3))), "file_path" to "/work/a.pdf", "pages" to "4-5")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("共 3 页"))
        assertTrue(r.content.none { it is DocumentPart })
        assertTrue(r.touched.isEmpty())
    }

    @Test
    fun `port failure is reported as the error text`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(3))
        val r = ReadTool.call(ctxWith(FakePages(PdfPageResult.Failed("无法解析这份 PDF 的页面。"))), "file_path" to "/work/a.pdf", "pages" to "1")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("无法解析这份 PDF"))
    }

    @Test
    fun `unavailable port keeps the old refusal`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(3))
        val r = ReadTool.call(ctxWith(FakePages(PdfPageResult.Failed("unused"), available = false)), "file_path" to "/work/a.pdf", "pages" to "1")
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("本环境不能按页截取"))
    }

    @Test
    fun `bad pages syntax and more than twenty pages never reach the port`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(30))
        for (spec in listOf("5-2", "a", "1-21")) {
            val port = FakePages(sample)
            val r = ReadTool.call(ctxWith(port), "file_path" to "/work/a.pdf", "pages" to spec)
            assertTrue(spec, r.isError)
            assertTrue(spec, port.asked.isEmpty())
        }
    }

    @Test
    fun `whole read of a short pdf does not touch the port`() = runTest {
        fs.put("/work/a.pdf", minimalPdf(2))
        val port = FakePages(sample)
        val r = ReadTool.call(ctxWith(port), "file_path" to "/work/a.pdf")
        assertFalse(r.isError)
        assertTrue(port.asked.isEmpty())
        assertEquals(ImageSource.Base64(Base64.getEncoder().encodeToString(minimalPdf(2))), r.content.filterIsInstance<DocumentPart>().single().source)
    }
}
