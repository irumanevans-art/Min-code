package dev.min.agent.tools

import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStamp
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class EditInput(val path: GuestPath, val oldString: String, val newString: String, val replaceAll: Boolean)

/**
 * 按 old_string → new_string 改文件的一部分（规格 §4）。匹配链在 [EditMatch]。
 *
 * 进出时处理两件模型看不见的事：CRLF 换行（文件是 CRLF 时，匹配在 \n 形式上做，写回时换回 CRLF）
 * 与 UTF-8 BOM（原样保留）。模型从 Read 的输出里抄 old_string，那里既看不到 \r 也看不到 BOM。
 */
object EditTool : Tool<EditInput> {
    override val name = "Edit"

    override val inputSchema: JsonObject = schemaObject(
        listOf("file_path", "old_string", "new_string"),
        "file_path" to prop("string", "要改的文件的绝对路径（以 / 开头）"),
        "old_string" to prop("string", "要替换的原文，照 Read 的输出逐字抄（不要带行号前缀）"),
        "new_string" to prop("string", "替换成的内容"),
        "replace_all" to prop("boolean", "为 true 时替换所有出现的地方（只认逐字相同的匹配），默认 false"),
    )

    override val traits = ToolTraits()

    override fun description(profile: HarnessProfile) =
        "把文件里的 old_string 替换成 new_string。改之前必须先用 Read 读过这个文件，且读过之后它没被别处改过。" +
            "old_string 必须在文件里唯一：出现多处时带上更多上下文，或设 replace_all 为 true 一并替换。" +
            "old_string 照 Read 的输出逐字抄，但不要带行号前缀。old_string 为空表示新建文件，内容为 new_string。" +
            "改完会返回改动附近的内容（带行号），据此确认缩进与结果。"

    override fun parse(raw: JsonObject): ToolInput<EditInput> = parseArgs {
        val input = EditInput(
            path = absolutePath("file_path", raw.reqString("file_path")),
            oldString = raw.reqString("old_string").toLf(),
            newString = raw.reqString("new_string").toLf(),
            replaceAll = raw.optBool("replace_all") ?: false,
        )
        if (input.oldString == input.newString) throw BadArg("old_string 与 new_string 完全相同，没有要改的内容。")
        input
    }

    override fun permissionAsk(input: EditInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        summary = "修改 ${input.path}",
        paths = listOf(PathAccess(input.path, AccessKind.Write, ctx.fs.scopeOf(input.path))),
    )

    override fun run(input: EditInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { edit(input, ctx) }

    private suspend fun edit(input: EditInput, ctx: ToolContext): ToolResult {
        val path = input.path
        val stat = ctx.fs.stat(path)
        if (stat == null) {
            if (input.oldString.isNotEmpty()) return failResult("$path 不存在。要新建文件时 old_string 留空，或改用 Write。")
            return write(ctx, path, null, TextFile(bom = false, crlf = false, text = ""), input.newString, 1, null, created = true)
        }
        if (stat.kind == FileKind.Directory) return failResult("$path 是目录，不能编辑。")
        if (stat.size > MAX_EDIT_BYTES) return failResult("$path 有 ${stat.size} 字节，超过 Edit 能处理的 $MAX_EDIT_BYTES 字节上限。")
        val seen = ctx.lastSeen(path) ?: return failResult("修改 $path 之前先用 Read 读一遍。")
        if (seen.size != stat.size || seen.modifiedMs != stat.modifiedMs) {
            return failResult("$path 在你读过之后被改过，先重新 Read 再改。")
        }

        val bytes = ctx.fs.read(path, 0, stat.size.toInt())
        if (bytes.take(BINARY_PROBE_BYTES).any { it == 0.toByte() }) return failResult("$path 是二进制文件，不能按文本编辑。")
        val file = TextFile.decode(bytes)

        if (input.oldString.isEmpty()) {
            if (file.text.isNotEmpty()) {
                return failResult("old_string 为空只能用来新建文件或填写空文件，而 $path 已有内容。请给出要替换的原文。")
            }
            // 文件原本就在（只是空的）：给人的话仍说「已写入」，但这不是新建，撤销时不能把文件删掉
            return write(ctx, path, bytes, file, input.newString, 1, null, created = true, newFile = false)
        }

        if (input.replaceAll) {
            val starts = EditMatch.locateAll(file.text, input.oldString)
            if (starts.isEmpty()) {
                return failResult("没有找到与 old_string 逐字相同的内容（replace_all 只认逐字相同）。先 Read 核对原文，或去掉 replace_all 只改一处。")
            }
            val updated = file.text.replace(input.oldString, input.newString)
            val note = "已替换 ${starts.size} 处。"
            return write(ctx, path, bytes, file, updated, EditMatch.lineOf(file.text, starts.first()), note, created = false)
        }

        return when (val located = EditMatch.locate(file.text, input.oldString, input.newString)) {
            is Located.Found -> {
                val updated = file.text.substring(0, located.start) + located.replacement + file.text.substring(located.end)
                val startLine = EditMatch.lineOf(file.text, located.start)
                val note = located.strategy.differenceForModel?.let {
                    "注意：old_string 与文件并不逐字相同（$it），按放宽后的规则匹配到第 $startLine 行起的内容并替换了。"
                }
                write(ctx, path, bytes, file, updated, startLine, note, created = false)
            }
            is Located.Ambiguous -> failResult(
                "old_string 在文件里出现了 ${located.lines.size} 处（第 ${located.lines.joinToString("、")} 行）。" +
                    "带上更多上下文使它唯一，或设 replace_all 为 true 一并替换。",
            )
            is Located.NotFound -> failResult(
                "在 $path 里没有找到 old_string（也试过忽略行尾空白、缩进与标点写法）。" +
                    if (located.similarLines.isEmpty()) "先 Read 核对原文再改。"
                    else "与它第一行相似的是第 ${located.similarLines.joinToString("、")} 行，先 Read 这附近核对原文。",
            )
        }
    }

    private suspend fun write(
        ctx: ToolContext,
        path: GuestPath,
        /** 改之前的原始字节（编辑记录要原样，不能用解码再编码的文本）；文件原来不存在时为 null */
        originalBytes: ByteArray?,
        original: TextFile,
        updatedText: String,
        focusLine: Int,
        note: String?,
        created: Boolean,
        /** 报给界面的 [dev.min.agent.spi.FileEdit.created]：只有文件原本不存在才算新建 */
        newFile: Boolean = created,
    ): ToolResult {
        val stored = preserveWrite(ctx, path, originalBytes, original.copy(text = updatedText).encode())
        val after = checkNotNull(ctx.fs.stat(path)) { "写入后 stat 不到 $path" }
        val text = buildString {
            append(if (created) "已写入 $path。" else "已修改 $path。")
            note?.let { append(it) }
            append("\n改动附近的内容：\n")
            append(snippet(updatedText, focusLine))
        }
        return okResult(
            text,
            listOf(FileStamp(path, after.size, after.modifiedMs)),
            // 改动过的文件报给界面（「改动过的文件」面板）：改之前与之后都在手里，不必再读盘
            listOf(fileEdit(path, original.text, updatedText, created = newFile, stored = stored)),
        )
    }

    /** 改动附近的内容，cat -n 风格（与 Read 一致），前后各带几行，最多 [SNIPPET_MAX_LINES] 行 */
    private fun snippet(text: String, focusLine: Int): String {
        // 末尾换行不算多出一行（与 Read 的数法一致）
        val lines = text.removeSuffix("\n").split('\n')
        val from = (focusLine - SNIPPET_CONTEXT).coerceIn(1, lines.size)
        val to = minOf(from + SNIPPET_MAX_LINES - 1, lines.size)
        return (from..to).joinToString("\n") { n -> "%6d\t%s".format(n, lines[n - 1]) }
    }

    private fun String.toLf(): String = replace("\r\n", "\n")

    /** 文件的文本内容与它的换行、BOM 形式：匹配在 \n 形式上做，写回时照原样还原 */
    private data class TextFile(val bom: Boolean, val crlf: Boolean, val text: String) {
        fun encode(): ByteArray {
            val body = if (crlf) text.replace("\n", "\r\n") else text
            return (if (bom) BOM else "").plus(body).encodeToByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): TextFile {
                val raw = bytes.decodeToString()
                val bom = raw.startsWith(BOM)
                val body = raw.removePrefix(BOM)
                // 全部换行都是 CRLF 才当 CRLF 文件。混合换行的文件原样处理：个别行尾的 \r 留在文本里，
                // 匹配时由「忽略行尾空白」那一级兜住，写回时也不会把别的行改成 CRLF
                val crlf = body.contains("\r\n") && body.count { it == '\n' } == Regex("\r\n").findAll(body).count()
                return TextFile(bom, crlf, if (crlf) body.replace("\r\n", "\n") else body)
            }
        }
    }

    private const val BOM = "\uFEFF"
    private const val MAX_EDIT_BYTES = 10L * 1024 * 1024
    private const val BINARY_PROBE_BYTES = 8 * 1024
    private const val SNIPPET_CONTEXT = 4
    private const val SNIPPET_MAX_LINES = 40
}
