package dev.min.code.core.engine.min

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.PdfPageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 手机端按页截取的适配逻辑：页码检查、缩放尺寸、单页与合计的体积上限、每页的「第 N 页」标注。
 * 真正的渲染（PdfRenderer）要 Android 运行时，这里用假的渲染器替身；渲染本身不在 JVM 单测范围内。
 */
class AndroidPdfPagesTest {

    /** 假渲染器：记录每次渲染的页号与尺寸，每页返回一段指定长度的「PNG」字节 */
    private class FakeSource(
        override val pageCount: Int,
        private val pageSizes: Map<Int, PdfPageSize> = emptyMap(),
        private val pngBytesPerPage: Int = 1024,
    ) : PdfPageSource {
        val rendered = mutableListOf<Triple<Int, Int, Int>>()
        var closed = false

        override fun pageSize(index: Int): PdfPageSize = pageSizes[index] ?: PdfPageSize(612, 792)

        override fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray {
            rendered += Triple(index, widthPx, heightPx)
            return ByteArray(pngBytesPerPage) { 0x42 }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRasterizer(private val source: PdfPageSource?) : PdfRasterizer {
        var opened = 0
        override fun open(bytes: ByteArray): PdfPageSource? {
            opened++
            return source
        }
    }

    private fun pages(source: PdfPageSource?, range: IntRange) = runBlocking {
        AndroidPdfPages(FakeRasterizer(source)).pages(ByteArray(8), range)
    }

    @Test
    fun `unopenable pdf is a soft failure`() {
        val r = pages(null, 1..1)
        assertTrue("$r", r is PdfPageResult.Failed)
    }

    @Test
    fun `range beyond the file reports the page count and renders nothing`() {
        val src = FakeSource(pageCount = 3)
        val r = pages(src, 2..4)
        assertEquals(3, (r as PdfPageResult.OutOfRange).pageCount)
        assertTrue(src.rendered.isEmpty())
        assertTrue(src.closed)
    }

    @Test
    fun `each page becomes a labelled png in order`() {
        val src = FakeSource(pageCount = 5, pngBytesPerPage = 3)
        val r = pages(src, 2..3) as PdfPageResult.Pages
        assertEquals(5, r.pageCount)
        assertEquals(listOf(1, 2), src.rendered.map { it.first })
        val expected = listOf<Part>(
            TextPart("第 2 页（PNG，1212×1568 像素）"),
            ImagePart("image/png", ImageSource.Base64(Base64.getEncoder().encodeToString(ByteArray(3) { 0x42 }))),
            TextPart("第 3 页（PNG，1212×1568 像素）"),
            ImagePart("image/png", ImageSource.Base64(Base64.getEncoder().encodeToString(ByteArray(3) { 0x42 }))),
        )
        assertEquals(expected, r.parts)
        assertTrue(src.closed)
    }

    @Test
    fun `long edge is capped at 1568 px and the other side keeps the aspect ratio`() {
        val portrait = FakeSource(pageCount = 1)
        pages(portrait, 1..1)
        assertEquals(Triple(0, 1212, 1568), portrait.rendered.single())

        val landscape = FakeSource(pageCount = 1, pageSizes = mapOf(0 to PdfPageSize(792, 612)))
        pages(landscape, 1..1)
        assertEquals(Triple(0, 1568, 1212), landscape.rendered.single())
    }

    @Test
    fun `degenerate page size still renders at least one pixel`() {
        val src = FakeSource(pageCount = 1, pageSizes = mapOf(0 to PdfPageSize(0, 0)))
        pages(src, 1..1)
        val (_, w, h) = src.rendered.single()
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun `single page larger than the per page cap is refused and names the page`() {
        val src = FakeSource(pageCount = 2, pngBytesPerPage = AndroidPdfPages.MAX_PAGE_PNG_BYTES + 1)
        val r = pages(src, 2..2)
        assertTrue("$r", r is PdfPageResult.Failed)
        assertTrue((r as PdfPageResult.Failed).reason, r.reason.contains("第 2 页"))
        assertTrue(src.closed)
    }

    @Test
    fun `pages together over the total cap are refused`() {
        val perPage = AndroidPdfPages.MAX_PAGE_PNG_BYTES
        val pagesNeeded = (AndroidPdfPages.MAX_TOTAL_PNG_BYTES / perPage + 1).toInt()
        val src = FakeSource(pageCount = 20, pngBytesPerPage = perPage)
        val r = pages(src, 1..pagesNeeded)
        assertTrue("$r", r is PdfPageResult.Failed)
        assertTrue(src.closed)
    }

    @Test
    fun `fit keeps the long edge at the cap and never returns zero`() {
        assertEquals(1568 to 1568, AndroidPdfPages.fitSize(PdfPageSize(100, 100)))
        assertEquals(1, AndroidPdfPages.fitSize(PdfPageSize(1, 100_000)).first)
        assertEquals(1, AndroidPdfPages.fitSize(PdfPageSize(0, 0)).second)
    }
}
