package dev.min.agent.tools

/**
 * PDF 的最小检查：只看文件头、结束标记、加密标记与页数，**不解析内容**。
 *
 * 为什么不解析：仓库里没有 PDF 库（gradle/libs.versions.toml 无），加依赖要先问。
 * 所以本地不拆页；整份交给模型，由供应商校验内容。这里只挡得住「明显坏掉」的文件，给出可读的错误，
 * 免得整份发过去才被供应商以难懂的 400 拒绝。
 */
internal object PdfFile {
    /** 超过这页数的 PDF 不能整份读，需要 pages（而本环境给不了 pages，所以只能报错） */
    const val MAX_PAGES_WHOLE = 10

    /** pages 一次最多这么多页（与 CC 文档一致） */
    const val MAX_PAGES_PER_READ = 20

    private val HEADER = "%PDF-".toByteArray(Charsets.ISO_8859_1)
    private const val TAIL_BYTES = 1024
    private val PAGE_OBJECT = Regex("/Type\\s*/Page(?![A-Za-z])")

    /** 不是合法 PDF 时返回原因（给模型看的中文）；看起来正常返回 null */
    fun problem(bytes: ByteArray, path: String): String? {
        if (bytes.size < HEADER.size || !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            return "$path 不是有效的 PDF（缺少 %PDF- 文件头）。"
        }
        val tail = bytes.copyOfRange(maxOf(0, bytes.size - TAIL_BYTES), bytes.size).toString(Charsets.ISO_8859_1)
        if (!tail.contains("%%EOF")) {
            return "$path 不完整（末尾缺少 %%EOF 结束标记），可能是下载或写入中断，无法读取。"
        }
        // 加密的 PDF 需要密码，供应商也不认；结构在对象流里时这里可能扫不到，那时供应商照常拒绝
        if (bytes.toString(Charsets.ISO_8859_1).contains("/Encrypt")) {
            return "$path 是加密的 PDF，无法读取（需要密码）。"
        }
        return null
    }

    /**
     * 数 `/Type /Page` 的个数（不含 /Pages）：明文对象里的，加上压缩对象流里的。
     * 对象流（PDF 1.5 起，pdfTeX 等默认）里的页对象在明文里一个都看不到；只数明文的话这类 PDF 全是「页数未知」，
     * 10 页的限制形同虚设，几百页的书会整份发出去，被供应商以页数超限拒绝（这份文档留在历史里，之后每个请求都会再被拒）。
     * 都数不到返回 null：未知，不是零页
     */
    fun pageCount(bytes: ByteArray): Int? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        val n = PAGE_OBJECT.findAll(text).count() + objectStreams(bytes, text).sumOf { PAGE_OBJECT.findAll(it).count() }
        return if (n == 0) null else n
    }

    private val OBJECT_STREAM = Regex("/Type\\s*/ObjStm(?![A-Za-z])")
    private val STREAM_START = Regex("stream\\r?\\n")

    /** 解开的对象流合计最多这么多字节：防压缩炸弹。20 MB 的 PDF 正常解开远小于它，超了就不再往下解（数到多少算多少） */
    private const val MAX_INFLATED_BYTES = 64 * 1024 * 1024

    /** FlateDecode 的对象流解开后的文本。别的过滤器（LZW 等）不解：少见，数不到就落回「页数未知」 */
    private fun objectStreams(bytes: ByteArray, text: String): List<String> {
        val out = mutableListOf<String>()
        var budget = MAX_INFLATED_BYTES
        for (m in OBJECT_STREAM.findAll(text)) {
            val start = STREAM_START.find(text, m.range.last) ?: continue
            val dict = text.substring(text.lastIndexOf("obj", m.range.first).coerceAtLeast(0), start.range.first)
            if (!dict.contains("/FlateDecode")) continue
            val dataStart = start.range.last + 1
            val dataEnd = text.indexOf("endstream", dataStart).takeIf { it > 0 } ?: continue
            val inflated = inflate(bytes, dataStart, dataEnd - dataStart, budget) ?: continue
            budget -= inflated.size
            out += inflated.toString(Charsets.ISO_8859_1)
            if (budget <= 0) break
        }
        return out
    }

    /** zlib 解压 [bytes] 的一段，最多 [limit] 字节；数据坏了返回 null（这一个流不数，不让整个读取失败） */
    private fun inflate(bytes: ByteArray, offset: Int, length: Int, limit: Int): ByteArray? {
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished() && out.size() < limit) {
                val n = inflater.inflate(buf)
                if (n == 0) break // 要更多输入（数据截断）或要预置字典：都解不下去了
                out.write(buf, 0, minOf(n, limit - out.size()))
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /** pages 的写法：`3` 或 `1-5`。解析失败返回 null，由调用方报格式错误 */
    fun parsePages(spec: String): IntRange? {
        val m = Regex("""(\d+)(?:-(\d+))?""").matchEntire(spec.trim()) ?: return null
        val from = m.groupValues[1].toIntOrNull() ?: return null
        val to = m.groupValues[2].ifEmpty { m.groupValues[1] }.toIntOrNull() ?: return null
        if (from < 1 || to < from) return null
        return from..to
    }
}
