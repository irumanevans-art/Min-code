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

    /** 数普通对象里的 `/Type /Page` 个数（不含 /Pages）。页对象若藏在压缩的对象流里数不到，返回 null：未知，不是零页 */
    fun pageCount(bytes: ByteArray): Int? {
        val n = PAGE_OBJECT.findAll(bytes.toString(Charsets.ISO_8859_1)).count()
        return if (n == 0) null else n
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
