package dev.min.agent.tools

import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PdfPageResult
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.Base64

data class ReadInput(val path: GuestPath, val offset: Int, val limit: Int, val pages: String? = null)

/**
 * 读文件（规格 §2）。输出 `cat -n` 风格：模型给 Edit 定位、引用「第几行」都靠这个行号。
 *
 * 只读、可并发、中断后可重放——读没有副作用，重放比问「上次读到哪」便宜。
 */
object ReadTool : Tool<ReadInput> {
    override val name = "Read"

    const val DEFAULT_LIMIT = 2000
    const val MAX_LINE_CHARS = 2000
    const val MAX_IMAGE_BYTES = 5L * 1024 * 1024

    /** PDF 整份内联进请求：Anthropic 请求体上限 32MB，base64 膨胀约 4/3，这里取 20MB 原文（再大会超供应商的请求体上限） */
    const val MAX_PDF_BYTES = 20L * 1024 * 1024

    /** 宿主没有按页截取能力（[PdfPagePort] 不可用）时照实说；pages 参数给了也不悄悄整份返回 */
    private const val NO_PAGE_CUT = "本环境不能按页截取 PDF，pages 参数暂不可用。不超过 10 页的 PDF 请去掉 pages 整份读取。"

    /** 文本文件一次最多读入这么多字节：一个几百 MB 的日志不该整个进内存。超出部分读不到，结果里会说明 */
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    private const val BINARY_SNIFF_BYTES = 8 * 1024
    private val IMAGE_TYPES = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    )

    override val inputSchema: JsonObject = schemaObject(
        listOf("file_path"),
        "file_path" to prop("string", "要读的文件的绝对路径（以 / 开头）"),
        "offset" to prop("integer", "起始行号（notebook 为起始单元序号），从 1 开始；默认 1。只在文件很大、需要分段读时才给"),
        "limit" to prop("integer", "最多读多少行（notebook 为单元数）；默认 $DEFAULT_LIMIT"),
        "pages" to prop("string", "PDF 专用：要读的页码，如 \"1-5\" 或 \"3\"，一次最多 ${PdfFile.MAX_PAGES_PER_READ} 页。超过 ${PdfFile.MAX_PAGES_WHOLE} 页的 PDF 必须给它；宿主不能按页截取时会得到说明"),
    )

    override val traits = ToolTraits(readOnly = true, concurrencySafe = true, resumePolicy = ResumePolicy.Replay)

    override fun description(profile: HarnessProfile) =
        "读取一个文件。file_path 必须是绝对路径（相对路径以当前目录为基准自行拼成绝对路径）。" +
            "默认从第 1 行读最多 $DEFAULT_LIMIT 行，每行前带行号；更长的文件用 offset / limit 分段读。" +
            "单行超过 $MAX_LINE_CHARS 字符会被截断。可以读图片（png / jpg / gif / webp）；可以读 PDF（不超过 $MAX_PDF_BYTES 字节；不超过 ${PdfFile.MAX_PAGES_WHOLE} 页整份读取，超过的须用 pages 按页截取，一次最多 ${PdfFile.MAX_PAGES_PER_READ} 页）；不能读其他二进制文件。" +
            "也可以读 Jupyter notebook（.ipynb）：按单元给出 id、类型、源码与输出（输出已截断，图片尽量内嵌），此时 offset / limit 按单元计。" +
            "修改已有文件之前必须先用本工具读过它。读目录请改用 Glob 或 Bash 的 ls。"

    override fun parse(raw: JsonObject): ToolInput<ReadInput> = parseArgs {
        ReadInput(
            path = absolutePath("file_path", raw.reqString("file_path")),
            offset = raw.optInt("offset", min = 1) ?: 1,
            limit = raw.optInt("limit", min = 1) ?: DEFAULT_LIMIT,
            pages = raw.optString("pages")?.takeIf { it.isNotBlank() },
        )
    }

    override fun permissionAsk(input: ReadInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        summary = "读取 ${input.path}",
        paths = listOf(PathAccess(input.path, AccessKind.Read, ctx.fs.scopeOf(input.path))),
    )

    override fun run(input: ReadInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { read(input, ctx) }

    private suspend fun read(input: ReadInput, ctx: ToolContext): ToolResult {
        val path = input.path
        val stat = ctx.fs.stat(path) ?: return failResult("文件不存在：$path。可以用 Glob 找一找正确的路径。", ENOENT)
        if (stat.kind == FileKind.Directory) {
            return failResult("$path 是目录，不是文件。列目录请用 Glob（如 pattern=\"*\"）或 Bash 的 ls。")
        }
        val stamp = FileStamp(path, stat.size, stat.modifiedMs)

        val ext = path.value.substringAfterLast('.', "").lowercase()
        if (ext == "pdf") return readPdf(input, ctx, stamp, stat.size)
        IMAGE_TYPES[ext]?.let { mediaType ->
            if (stat.size > MAX_IMAGE_BYTES) {
                return failResult("图片太大（${stat.size} 字节，上限 ${MAX_IMAGE_BYTES} 字节），无法读取。")
            }
            val bytes = ctx.fs.read(path)
            return ToolResult(
                content = listOf(
                    TextPart("图片 $path（$mediaType，${bytes.size} 字节）"),
                    ImagePart(mediaType, ImageSource.Base64(Base64.getEncoder().encodeToString(bytes))),
                ),
                touched = listOf(stamp),
            )
        }

        // notebook 是 JSON，不能按行读：整本解析后按单元渲染（offset / limit 此时按单元计）
        if (path.isNotebook()) return readNotebook(input, ctx, stamp)

        val bytes = ctx.fs.read(path, 0, MAX_TEXT_BYTES)
        if (bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }) {
            return failResult("$path 是二进制文件，不能按文本读。")
        }
        val cutByBytes = stat.size > bytes.size
        if (bytes.isEmpty()) return okResult("文件存在但是空的：$path", listOf(stamp))

        val lines = splitLines(bytes.decodeToString())
        val total = lines.size
        if (input.offset > total) {
            return failResult("offset=${input.offset} 超出文件末尾：$path 只有 $total 行。")
        }
        val from = input.offset
        val to = minOf(total, from + input.limit - 1)
        val body = (from..to).joinToString("\n") { n -> numbered(n, lines[n - 1]) }

        val notes = buildList {
            if (to < total || cutByBytes) {
                val totalText = if (cutByBytes) "共至少 $total 行" else "共 $total 行"
                add("（$totalText，只显示了 $from–$to 行，用 offset/limit 读其余部分）")
            }
            if (cutByBytes) add("（文件有 ${stat.size} 字节，超过一次读入上限 $MAX_TEXT_BYTES 字节，后面的内容读不到；需要的话用 Grep 或 Bash）")
        }
        return okResult((listOf(body) + notes).joinToString("\n"), listOf(stamp))
    }

    /**
     * PDF：整份内联成文档块。pages 本环境给不了（没有解析库，不能拆页），给了就报错而不是悄悄整份返回——
     * 模型要的是第几页，整份塞进去会让它以为拿到了想要的内容。
     * 页数超过 10 页又不给 pages 也报错：整份发过去多半被供应商以难懂的错误拒绝，不如先说清楚。
     */
    private suspend fun readPdf(input: ReadInput, ctx: ToolContext, stamp: FileStamp, size: Long): ToolResult {
        val path = input.path
        if (size > MAX_PDF_BYTES) return failResult("PDF 太大（$size 字节，上限 $MAX_PDF_BYTES 字节），无法读取。")
        val bytes = ctx.fs.read(path)
        PdfFile.problem(bytes, path.toString())?.let { return failResult(it) }

        input.pages?.let { spec ->
            val range = PdfFile.parsePages(spec)
                ?: return failResult("字段 `pages` 格式不对：应为 \"3\" 或 \"1-5\"，收到 `$spec`。")
            if (range.last - range.first + 1 > PdfFile.MAX_PAGES_PER_READ) {
                return failResult("字段 `pages` 一次最多 ${PdfFile.MAX_PAGES_PER_READ} 页，收到 `$spec`。")
            }
            // 按页截取交给宿主（桌面拆成文档块，手机渲染成图）；宿主不能做时照实说，不悄悄整份返回
            val port = ctx.pdfPages
            if (!port.available) return failResult(NO_PAGE_CUT)
            return when (val got = port.pages(bytes, range)) {
                is PdfPageResult.Pages -> ToolResult(
                    content = listOf(TextPart("PDF $path 第 ${pageSpan(range)}（共 ${got.pageCount} 页）")) + got.parts,
                    touched = listOf(stamp),
                )
                is PdfPageResult.OutOfRange -> failResult("字段 `pages` 超出范围：$path 共 ${got.pageCount} 页，收到 `$spec`。")
                is PdfPageResult.Failed -> failResult(got.reason)
            }
        }

        val pageCount = PdfFile.pageCount(bytes)
        if (pageCount != null && pageCount > PdfFile.MAX_PAGES_WHOLE) {
            val hint = if (ctx.pdfPages.available) {
                "请用 pages 指定页码（如 \"1-5\"，一次最多 ${PdfFile.MAX_PAGES_PER_READ} 页）。"
            } else {
                NO_PAGE_CUT
            }
            return failResult("$path 共 $pageCount 页，超过 ${PdfFile.MAX_PAGES_WHOLE} 页，不能整份读取。$hint")
        }
        val pageNote = pageCount?.let { "$it 页" } ?: "页数未知"
        return ToolResult(
            content = listOf(
                TextPart("PDF $path（application/pdf，${bytes.size} 字节，$pageNote）"),
                DocumentPart("application/pdf", ImageSource.Base64(Base64.getEncoder().encodeToString(bytes))),
            ),
            touched = listOf(stamp),
        )
    }

    /** 页码说明：`2` 或 `2–5`（同一页不写成区间） */
    private fun pageSpan(range: IntRange): String =
        if (range.first == range.last) "${range.first} 页" else "${range.first}–${range.last} 页"

    /** 行号右对齐 6 位 + 制表符；\r\n 的 \r 去掉，免得模型以为行尾有个怪字符 */
    private fun numbered(n: Int, line: String): String {
        val shown = if (line.length > MAX_LINE_CHARS) {
            line.take(MAX_LINE_CHARS) + "…[该行共 ${line.length} 字符，已截断]"
        } else {
            line
        }
        return n.toString().padStart(6) + "\t" + shown
    }

    /** 末尾换行不算多一行 */
    private fun splitLines(text: String): List<String> {
        val parts = text.split('\n').map { it.removeSuffix("\r") }
        return if (text.endsWith('\n')) parts.dropLast(1) else parts
    }
}
