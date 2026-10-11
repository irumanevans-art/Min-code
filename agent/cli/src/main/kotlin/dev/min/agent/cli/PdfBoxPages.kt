package dev.min.agent.cli

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.spi.PdfPagePort
import dev.min.agent.spi.PdfPageResult
import dev.min.agent.tools.ReadTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/*
 * 桌面的按页截取（Read 的 pages）：PDFBox 只留下所选页、存成一份新 PDF，作为文档块交给模型。
 * 为什么用文档块而不是渲染成图：PDF 原样保真（文字可选、表格不糊），模型直接读原版式。
 * 为什么只在 CLI：PDFBox 是 Apache-2.0 且体积大；手机端用系统渲染器（见 App 的 AndroidPdfPages），不引入它。
 */
internal object PdfBoxPages : PdfPagePort {
    override val available = true

    override suspend fun pages(bytes: ByteArray, pages: IntRange): PdfPageResult = withContext(Dispatchers.IO) {
        try {
            Loader.loadPDF(bytes).use { doc ->
                val n = doc.numberOfPages
                if (pages.last > n) return@withContext PdfPageResult.OutOfRange(n)
                // 从后往前删：先删前面的页，后面页的下标就全变了，倒着删省得换算
                for (index in n - 1 downTo 0) if (index + 1 !in pages) doc.removePage(index)
                val out = ByteArrayOutputStream()
                doc.save(out)
                val subset = out.toByteArray()
                // 拆出的文件一般比原件小；仍超过单次上限时（如原件结构很特别）照实说，不把超大文档发给供应商
                if (subset.size > ReadTool.MAX_PDF_BYTES) {
                    return@withContext PdfPageResult.Failed("所选页拆出后有 ${subset.size} 字节，超过 ${ReadTool.MAX_PDF_BYTES} 字节的上限，请少要几页。")
                }
                PdfPageResult.Pages(
                    parts = listOf(DocumentPart("application/pdf", ImageSource.Base64(Base64.getEncoder().encodeToString(subset)))),
                    pageCount = n,
                )
            }
        } catch (e: IOException) {
            PdfPageResult.Failed("无法解析这份 PDF（${e.message ?: "未知错误"}），按页截取失败。")
        } catch (e: RuntimeException) {
            // PDFBox 对畸形结构偶尔抛运行时异常。CancellationException 也是运行时异常，必须原样抛出，不能当成解析失败吞掉
            if (e is CancellationException) throw e
            PdfPageResult.Failed("无法解析这份 PDF，按页截取失败。")
        }
    }
}
