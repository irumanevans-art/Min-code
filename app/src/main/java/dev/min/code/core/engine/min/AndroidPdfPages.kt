package dev.min.code.core.engine.min

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.PdfPagePort
import dev.min.agent.spi.PdfPageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.Base64

/*
 * 手机端的按页截取（Read 的 pages）：系统 PdfRenderer 把所选页渲染成 PNG，每页一张图、前面写「第 N 页」。
 * 为什么不拆 PDF 而是渲染成图：手机上不引入 PDF 库（App 的许可页不动）；系统渲染器自带，模型用图看版式也够用。
 * 这里只做适配：页码检查、缩放、体积上限、标注。渲染本身在 [PdfRasterizer] 后面，JVM 单测不碰 Android 运行时。
 */

/** 一页的尺寸，单位是 PDF 点（1/72 英寸） */
internal data class PdfPageSize(val widthPt: Int, val heightPt: Int)

/** 一份已打开的 PDF。用完必须 [close]（释放系统渲染器与临时文件） */
internal interface PdfPageSource : Closeable {
    val pageCount: Int

    /** 第 [index]（0 起算）页的尺寸 */
    fun pageSize(index: Int): PdfPageSize

    /** 把第 [index] 页渲染成 [widthPx]×[heightPx] 的 PNG 字节 */
    fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray
}

/** 打开 PDF 的入口。打不开（损坏、需要密码）返回 null */
internal interface PdfRasterizer {
    fun open(bytes: ByteArray): PdfPageSource?
}

internal class AndroidPdfPages(private val rasterizer: PdfRasterizer) : PdfPagePort {
    override val available = true

    override suspend fun pages(bytes: ByteArray, pages: IntRange): PdfPageResult = withContext(Dispatchers.IO) {
        val source = rasterizer.open(bytes) ?: return@withContext PdfPageResult.Failed("无法打开这份 PDF（文件可能已损坏，或需要密码）。")
        source.use { src ->
            val n = src.pageCount
            if (pages.last > n) return@withContext PdfPageResult.OutOfRange(n)
            val parts = mutableListOf<Part>()
            var total = 0L
            for (page in pages) {
                val (w, h) = fitSize(src.pageSize(page - 1))
                val png = src.renderPng(page - 1, w, h)
                // 单图上限：供应商对单张图片有大小限制，超过就不发（模型照样知道这页有问题，比被供应商以难懂的错误拒绝强）
                if (png.size > MAX_PAGE_PNG_BYTES) {
                    return@withContext PdfPageResult.Failed("第 $page 页渲染后有 ${png.size} 字节，超过单页 $MAX_PAGE_PNG_BYTES 字节的上限，无法发送。请只要更少的页。")
                }
                // 合计上限：一次最多 20 页，合计仍要留在请求体上限之内（base64 膨胀约 4/3）
                total += png.size
                if (total > MAX_TOTAL_PNG_BYTES) {
                    return@withContext PdfPageResult.Failed("所选页渲染后合计超过 $MAX_TOTAL_PNG_BYTES 字节的上限，请少要几页。")
                }
                parts += TextPart("第 $page 页（PNG，${w}×${h} 像素）")
                parts += ImagePart("image/png", ImageSource.Base64(Base64.getEncoder().encodeToString(png)))
            }
            PdfPageResult.Pages(parts, n)
        }
    }

    companion object {
        /** 长边的像素上限：与供应商对图片的推荐尺寸同级；再大供应商也会缩，白占体积 */
        const val MAX_LONG_EDGE_PX = 1568

        /** 单页 PNG 原文上限（base64 后约 4MB，低于供应商单图 5MB 的限制） */
        const val MAX_PAGE_PNG_BYTES = 3 * 1024 * 1024

        /** 一次读取的 PNG 原文合计上限（base64 后约 16MB，留出请求体的余量） */
        const val MAX_TOTAL_PNG_BYTES = 12L * 1024 * 1024

        /** 缩放到长边 [MAX_LONG_EDGE_PX]，短边按比例；两边都至少 1 像素 */
        fun fitSize(size: PdfPageSize): Pair<Int, Int> {
            val longEdge = maxOf(size.widthPt, size.heightPt, 1).toDouble()
            val scale = MAX_LONG_EDGE_PX / longEdge
            val w = Math.round(size.widthPt * scale).toInt().coerceAtLeast(1)
            val h = Math.round(size.heightPt * scale).toInt().coerceAtLeast(1)
            return w to h
        }
    }
}
