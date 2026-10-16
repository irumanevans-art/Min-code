package dev.min.agent.tools

import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/*
 * 把 notebook 渲染成模型好读的文本（Read 读 .ipynb 时用）。
 *
 * 形状：每个单元一个 <cell id=… index=… type=…> 块，里面先是源码，code 单元后面跟它的输出块 <output kind=…>。
 * 不像普通文件那样给行号：改 notebook 靠 cell id 定位，不靠行号。
 */

/** 单次 Read 最多内嵌这么多张图：一个画了几十张图的 notebook 不该把上下文撑爆，多出来的给占位说明 */
internal const val MAX_NOTEBOOK_IMAGES = 8

/** 单个输出块的文本上限，超出保留头尾各一半（报错的 traceback 与进度条，要紧的信息通常在尾部） */
internal const val MAX_OUTPUT_CHARS = 8000

private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
private val IMAGE_MIMES = listOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** 文本与图片按出现顺序交织的结果内容 */
internal class PartsBuilder {
    private val parts = mutableListOf<Part>()
    private val text = StringBuilder()

    fun text(s: String) {
        text.append(s)
    }

    fun image(part: ImagePart) {
        flush()
        parts += part
    }

    fun build(): List<Part> {
        flush()
        return parts
    }

    private fun flush() {
        if (text.isNotEmpty()) parts += TextPart(text.toString())
        text.clear()
    }
}

internal suspend fun readNotebook(input: ReadInput, ctx: ToolContext, stamp: FileStamp): ToolResult {
    val path = input.path
    if (stamp.size == 0L) return okResult("文件存在但是空的：$path", listOf(stamp))
    if (stamp.size > MAX_NOTEBOOK_BYTES) {
        return failResult("$path 有 ${stamp.size} 字节，超过 Read 能处理的 notebook 上限 $MAX_NOTEBOOK_BYTES 字节（多半是内嵌了大量图片输出）。可以用 Bash 配合 jq 或 python 只取需要的单元。")
    }
    val text = TextEncoding.decodeLenient(ctx.fs.read(path, 0, MAX_NOTEBOOK_BYTES.toInt()))
    val nb = when (val parsed = parseNotebook(path, text)) {
        is NotebookParse.Ok -> parsed.notebook
        is NotebookParse.Bad -> return failResult(parsed.message)
    }
    val total = nb.cells.size
    val header = "Jupyter notebook $path（${nb.versionText}${nb.language?.let { "，$it" }.orEmpty()}，共 $total 个单元）\n"
    if (total == 0) return okResult(header + "没有单元。用 NotebookEdit 的 edit_mode=insert（不给 cell_id）加第一个。", listOf(stamp))
    if (input.offset > total) return failResult("offset=${input.offset} 超出 notebook 末尾：$path 只有 $total 个单元（offset 对 notebook 按单元计）。")

    val from = input.offset
    val to = minOf(total, from + input.limit - 1)
    val out = PartsBuilder()
    out.text(header)
    val imagesLeft = intArrayOf(MAX_NOTEBOOK_IMAGES)
    for (n in from..to) renderCell(out, nb.cells[n - 1], n - 1, imagesLeft)
    if (to < total) out.text("（共 $total 个单元，只显示了第 $from–$to 个，用 offset/limit 读其余部分；offset / limit 对 notebook 按单元计，从 1 起）\n")
    return ToolResult(out.build(), touched = listOf(stamp))
}

/** [index] 从 0 起，与 `cell-<序号>` 的序号一致；[imagesLeft] 为 null 表示不内嵌图片（NotebookEdit 回显用） */
internal fun renderCell(out: PartsBuilder, cell: JsonObject, index: Int, imagesLeft: IntArray?) {
    val type = cell.str("cell_type") ?: "unknown"
    val count = (cell["execution_count"] as? JsonPrimitive)?.intOrNull
    out.text("<cell id=\"${cellId(cell, index)}\" index=\"$index\" type=\"$type\"${count?.let { " execution_count=\"$it\"" }.orEmpty()}>\n")
    val source = cell["source"].multiline()
    if (source.isNotEmpty()) out.text(capLines(source.removeSuffix("\n")) + "\n")
    if (type == "code") {
        (cell["outputs"] as? JsonArray)?.forEach { o -> (o as? JsonObject)?.let { renderOutput(out, it, imagesLeft) } }
    }
    out.text("</cell>\n")
}

private fun renderOutput(out: PartsBuilder, o: JsonObject, imagesLeft: IntArray?) {
    when (o.str("output_type")) {
        "stream" -> block(out, "stream=\"${o.str("name") ?: "stdout"}\"", o["text"].multiline())
        "error" -> {
            val head = listOfNotNull(o.str("ename"), o.str("evalue")?.takeIf { it.isNotEmpty() }).joinToString(": ")
            val trace = (o["traceback"] as? JsonArray)?.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            // traceback 通常已经以「ename: evalue」收尾，没有时才把头补上
            block(out, "error", if (trace.isBlank()) head else trace)
        }
        "execute_result", "display_data" -> {
            val kind = if (o.str("output_type") == "execute_result") "result" else "display"
            displayData(out, kind, o["data"] as? JsonObject ?: JsonObject(emptyMap()), imagesLeft)
        }
        else -> Unit
    }
}

private fun displayData(out: PartsBuilder, kind: String, data: JsonObject, imagesLeft: IntArray?) {
    val mime = IMAGE_MIMES.firstOrNull { data[it] != null }
    if (mime != null) {
        // 图片就是这个输出的内容；随带的 text/plain 通常只是 `<Figure size 640x480 …>` 这类噪声，不再重复
        val b64 = data[mime].multiline().filterNot { it.isWhitespace() }
        val size = b64.length.toLong() * 3 / 4
        val note = when {
            imagesLeft == null -> "图片输出（$mime，约 ${size / 1024} KB），此处不回显"
            size > ReadTool.MAX_IMAGE_BYTES -> "图片输出（$mime，约 ${size / 1024} KB），超过 ${ReadTool.MAX_IMAGE_BYTES / 1024 / 1024} MB 上限，已省略"
            imagesLeft[0] <= 0 -> "图片输出（$mime，约 ${size / 1024} KB），本次已内嵌 $MAX_NOTEBOOK_IMAGES 张，其余省略"
            !isBase64(b64) -> "图片输出（$mime）的数据不是合法 base64，已省略"
            else -> null
        }
        if (note != null) {
            out.text("<output kind=\"image\">[$note]</output>\n")
        } else {
            imagesLeft!![0]--
            out.text("<output kind=\"image\" type=\"$mime\">\n")
            out.image(ImagePart(mime, ImageSource.Base64(b64)))
            out.text("</output>\n")
        }
        return
    }
    val plain = data["text/plain"]
    if (plain != null) {
        block(out, "kind=\"$kind\"", plain.multiline())
        return
    }
    val types = data.keys.joinToString("、")
    out.text("<output kind=\"$kind\">[只有 $types，没有文本形式，已省略]</output>\n")
}

private fun isBase64(s: String): Boolean =
    try {
        Base64.getDecoder().decode(s)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

/** 一个输出块：去 ANSI 色码，`\r` 覆盖（进度条）只留最后一版，超长保留头尾，单行再截 */
private fun block(out: PartsBuilder, attr: String, raw: String) {
    val text = capTotal(capLines(collapseCarriageReturns(raw.replace(ANSI, ""))))
    out.text("<output $attr>\n$text${if (text.endsWith("\n")) "" else "\n"}</output>\n")
}

/**
 * 终端里 `\r` 回到行首覆盖；tqdm 之类一行会写几千次，只有最后一次是看得见的。
 * 注意不能用 lineSequence()：它把单个 `\r` 也当换行，覆盖语义就丢了
 */
private fun collapseCarriageReturns(s: String): String =
    s.replace("\r\n", "\n").split('\n').joinToString("\n") { it.trimEnd('\r').substringAfterLast('\r') }

private fun capLines(s: String): String = s.split('\n').joinToString("\n") { line ->
    if (line.length > ReadTool.MAX_LINE_CHARS) line.take(ReadTool.MAX_LINE_CHARS) + "…[该行共 ${line.length} 字符，已截断]" else line
}

private fun capTotal(s: String): String {
    if (s.length <= MAX_OUTPUT_CHARS) return s
    val half = MAX_OUTPUT_CHARS / 2
    return s.take(half) + "\n…[输出共 ${s.length} 字符，中间省略了 ${s.length - MAX_OUTPUT_CHARS} 字符]…\n" + s.takeLast(half)
}
