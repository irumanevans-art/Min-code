package dev.min.agent.cli

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.spi.PdfPageResult
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** 桌面按页截取（PDFBox）：拆出的文件只含所选页、顺序不变；页码越界与解析失败各自有结果 */
class PdfBoxPagesTest {
    /** 现造一份 n 页的 PDF，每页写一行「PAGE i」，拆出之后用文字认出是哪一页；不下载任何文件 */
    private fun pdf(n: Int): ByteArray {
        val doc = PDDocument()
        repeat(n) { i ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText("PAGE ${i + 1}")
                cs.endText()
            }
        }
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    /** 每页的文字（去掉首尾空白） */
    private fun textOfEachPage(bytes: ByteArray): List<String> = Loader.loadPDF(bytes).use { doc ->
        (1..doc.numberOfPages).map { p -> PDFTextStripper().apply { startPage = p; endPage = p }.getText(doc).trim() }
    }

    private fun decode(part: DocumentPart): ByteArray =
        Base64.getDecoder().decode((part.source as ImageSource.Base64).data)

    @Test
    fun `middle range keeps exactly those pages in order and reports the real count`() = runTest {
        val r = PdfBoxPages.pages(pdf(5), 2..3)
        assertTrue("$r", r is PdfPageResult.Pages)
        r as PdfPageResult.Pages
        assertEquals(5, r.pageCount)
        val part = r.parts.single() as DocumentPart
        assertEquals("application/pdf", part.mediaType)
        assertEquals(listOf("PAGE 2", "PAGE 3"), textOfEachPage(decode(part)))
    }

    @Test
    fun `last single page is cut out alone`() = runTest {
        val r = PdfBoxPages.pages(pdf(5), 5..5) as PdfPageResult.Pages
        assertEquals(listOf("PAGE 5"), textOfEachPage(decode(r.parts.single() as DocumentPart)))
    }

    @Test
    fun `page beyond the file is out of range with the real count`() = runTest {
        val r = PdfBoxPages.pages(pdf(3), 2..4)
        assertEquals(3, (r as PdfPageResult.OutOfRange).pageCount)
    }

    @Test
    fun `broken pdf body is a soft failure, not an exception`() = runTest {
        val r = PdfBoxPages.pages("%PDF-1.4\nnot a real pdf body\n%%EOF\n".toByteArray(Charsets.ISO_8859_1), 1..1)
        assertTrue("$r", r is PdfPageResult.Failed)
    }
}
