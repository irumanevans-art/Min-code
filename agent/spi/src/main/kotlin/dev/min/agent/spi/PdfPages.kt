package dev.min.agent.spi

import dev.min.agent.protocol.Part

/*
 * PDF 按页截取（Read 的 pages）。与 UserDelivery.kt 同一条规矩：工具只拿端口，不知道宿主用什么拆页或渲染。
 * 为什么不由工具自己做：仓库的 agent 内核不带 PDF 库；桌面用 PDFBox 拆出文档块，手机用系统渲染器画成图，两端结果形状不同。
 */

sealed interface PdfPageResult {
    /**
     * 取到了。[parts] 原样交给模型（桌面是一份只含所选页的文档块；手机是每页一张图，前面带「第 N 页」标注）。
     * [pageCount] 是整份文件的页数，工具用它写说明。
     */
    class Pages(val parts: List<Part>, val pageCount: Int) : PdfPageResult

    /** 页码超出文件：[pageCount] 是文件实际页数，工具据此告诉模型该改哪个范围 */
    class OutOfRange(val pageCount: Int) : PdfPageResult

    /** 取不到（解析失败、单次体积超上限等）。[reason] 是给模型看的中文原因 */
    class Failed(val reason: String) : PdfPageResult
}

interface PdfPagePort {
    /** 宿主能不能按页截取。不能时 Read 照旧回告「本环境不能按页截取」，不调用 [pages] */
    val available: Boolean

    /** 取 [bytes] 这份 PDF 的 [pages]（1 起算，闭区间）。调用方已检查过页码格式与单次页数上限 */
    suspend fun pages(bytes: ByteArray, pages: IntRange): PdfPageResult

    object Unavailable : PdfPagePort {
        override val available = false
        override suspend fun pages(bytes: ByteArray, pages: IntRange): PdfPageResult =
            PdfPageResult.Failed("本环境不能按页截取 PDF。")
    }
}
