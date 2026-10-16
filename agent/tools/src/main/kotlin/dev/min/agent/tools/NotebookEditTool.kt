package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

enum class NotebookEditMode(val wire: String) {
    Replace("replace"), Insert("insert"), Delete("delete");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

data class NotebookEditInput(
    val path: GuestPath,
    val cellId: String?,
    val newSource: String,
    val cellType: String?,
    val mode: NotebookEditMode,
)

/**
 * 改 Jupyter notebook 的一个单元：替换 / 插入 / 删除，按 cell id 定位（语义对齐 Claude Code 的 NotebookEdit）。
 *
 * 只改目标单元那一段文本，其余字符（缩进、键顺序、metadata、别的单元的输出）原样不动——
 * 这样 notebook 在 git 里的 diff 就只有这一个单元。实现见 [scanLayout] 与 [NotebookWriter]。
 * 「先读后改」与 Edit 同一套：[ToolContext.lastSeen] 的大小 + mtime。
 */
object NotebookEditTool : Tool<NotebookEditInput> {
    override val name = "NotebookEdit"

    private val CELL_TYPES = listOf("code", "markdown", "raw")

    override val inputSchema: JsonObject = schemaObject(
        listOf("notebook_path"),
        "notebook_path" to prop("string", "要改的 .ipynb 文件的绝对路径（以 / 开头）"),
        "cell_id" to prop("string", "目标单元的 id（Read 输出里 <cell id=…> 的值；旧格式没有 id 的单元是 cell-<序号>）。replace / delete 必填；insert 时新单元插在它后面，不给就插到最前"),
        "new_source" to prop("string", "单元的新源码。replace / insert 必填，delete 不用"),
        "cell_type" to prop("string", "单元类型。insert 必填；replace 时不给就保持原类型，给了就转换类型", enum = CELL_TYPES),
        "edit_mode" to prop("string", "replace（默认）/ insert / delete", enum = NotebookEditMode.entries.map { it.wire }),
    )

    // 写类工具的保守默认：会写、不可并发、中断后结果未知（写本身是原子的，但不能假设重放无害）
    override val traits = ToolTraits()

    override fun description(profile: HarnessProfile) =
        "编辑 Jupyter notebook（.ipynb）的单元：edit_mode 为 replace（默认，换掉 cell_id 那个单元的源码）、" +
            "insert（在 cell_id 之后插入新单元，不给 cell_id 就插到最前，必须给 cell_type）、delete（删掉该单元）。" +
            "notebook_path 必须是绝对路径。改之前必须先用 Read 读过这个 notebook，且读过之后它没被别处改过。" +
            "cell_id 取自 Read 的输出；旧格式没有 id 的单元用 cell-<序号>（序号从 0 起）。" +
            "只改目标单元，其余内容与排版原样保留；替换 code 单元会清空它原有的输出与执行序号（需要重新运行才有新输出）。"

    override fun parse(raw: JsonObject): ToolInput<NotebookEditInput> = parseArgs {
        val path = absolutePath("notebook_path", raw.reqString("notebook_path"))
        if (!path.isNotebook()) throw BadArg("字段 `notebook_path` 必须是 .ipynb 文件，收到 `$path`。改别的文件请用 Edit 或 Write。")
        val modeText = raw.optString("edit_mode") ?: "replace"
        val mode = NotebookEditMode.of(modeText)
            ?: throw BadArg("字段 `edit_mode` 必须是 replace、insert 或 delete，收到 `$modeText`。")
        val cellType = raw.optString("cell_type")
        if (cellType != null && cellType !in CELL_TYPES) throw BadArg("字段 `cell_type` 必须是 code、markdown 或 raw，收到 `$cellType`。")
        val cellId = raw.optString("cell_id")?.takeIf { it.isNotBlank() }
        when (mode) {
            NotebookEditMode.Insert -> if (cellType == null) throw BadArg("edit_mode 为 insert 时必须给 `cell_type`（code / markdown / raw）。")
            NotebookEditMode.Replace, NotebookEditMode.Delete ->
                if (cellId == null) throw BadArg("edit_mode 为 ${mode.wire} 时必须给 `cell_id`。cell_id 取自 Read 这个 notebook 的输出。")
        }
        val newSource = if (mode == NotebookEditMode.Delete) {
            raw.optString("new_source").orEmpty()
        } else {
            raw.reqString("new_source")
        }
        NotebookEditInput(path, cellId, newSource.replace("\r\n", "\n"), cellType, mode)
    }

    override fun permissionAsk(input: NotebookEditInput, ctx: ToolContext) = PermissionAsk(
        tool = name,
        // 与 Edit 一样按写路径声明：Edit(…) 规则（含 Write / NotebookEdit 同族）按路径管它
        summary = "修改 notebook ${input.path}",
        paths = listOf(PathAccess(input.path, AccessKind.Write, ctx.fs.scopeOf(input.path))),
    )

    override fun run(input: NotebookEditInput, ctx: ToolContext): Flow<ToolProgress> = doneFlow { edit(input, ctx) }

    private suspend fun edit(input: NotebookEditInput, ctx: ToolContext): ToolResult {
        val path = input.path
        val stat = ctx.fs.stat(path)
            ?: return failResult("$path 不存在。NotebookEdit 只能改已有的 notebook；新建请用 Write 写入一个 notebook 的 JSON。", ENOENT)
        if (stat.kind == FileKind.Directory) return failResult("$path 是目录，不能编辑。")
        if (stat.size > MAX_NOTEBOOK_BYTES) return failResult("$path 有 ${stat.size} 字节，超过 NotebookEdit 能处理的 $MAX_NOTEBOOK_BYTES 字节上限。")
        staleReadProblem(ctx, path, stat)?.let { return failResult(it) }

        val originalBytes = ctx.fs.read(path, 0, stat.size.toInt())
        // 严格解码，理由同 Edit：非 UTF-8 的文件宽松解码再写回会把非 ASCII 字符全换成 U+FFFD
        val decoded = TextEncoding.decodeWritable(originalBytes) ?: return failResult(TextEncoding.refusal(path, "修改"))
        val text = decoded.text
        val nb = when (val parsed = parseNotebook(path, text)) {
            is NotebookParse.Ok -> parsed.notebook
            is NotebookParse.Bad -> return failResult(parsed.message)
        }
        val layout = try {
            scanLayout(text)
        } catch (e: ScanError) {
            return failResult("无法在 $path 里定位各个单元的位置（${e.message}），为免写坏文件没有修改。")
        }
        if (layout.cells.size != nb.cells.size) return failResult("$path 的单元边界与解析结果对不上，为免写坏文件没有修改。")
        val writer = NotebookWriter(layout.unit, layout.nl, NotebookWriter.detectAsciiOnly(text))

        val change = when (input.mode) {
            NotebookEditMode.Replace -> replace(input, nb, layout, writer, text)
            NotebookEditMode.Insert -> insert(input, nb, layout, writer, text)
            NotebookEditMode.Delete -> delete(input, nb, layout, text)
        }
        if (change.error != null) return failResult(change.error)

        // 写之前把结果重新解析一遍：除了目标单元，其余（含顶层其它字段）必须与原来完全相等。
        // 拼接出错 / 转义出错时宁可拒绝写入，也不留下半个损坏的 notebook
        val after = parseNotebook(path, change.text)
        if (after !is NotebookParse.Ok || after.notebook.cells != change.cells || after.notebook.root - "cells" != nb.root - "cells") {
            return failResult("改动后的 notebook 校验不通过，为免写坏文件没有修改。")
        }

        val stored = preserveWrite(ctx, path, originalBytes, decoded.encoding.encode(change.text))
        val written = checkNotNull(ctx.fs.stat(path)) { "写入后 stat 不到 $path" }
        val echo = PartsBuilder()
        change.shown?.let { renderCell(echo, it, change.shownIndex, imagesLeft = null) }
        val noIds = after.notebook.cells.indices.any { after.notebook.cells[it].str("id").isNullOrEmpty() }
        val full = buildString {
            append(change.message)
            if (change.shown != null) append("\n").append(echo.build().filterIsInstance<TextPart>().joinToString("") { it.text })
            if (noIds && input.mode != NotebookEditMode.Replace) {
                append("\n注意：这个 notebook 里有单元没有 id，它们的 cell-<序号> 会随位置变化，再指定之前先 Read。")
            }
        }
        return okResult(
            full.trimEnd(),
            listOf(FileStamp(path, written.size, written.modifiedMs)),
            listOf(fileEdit(path, text, change.text, stored = stored)),
            diagnostics = stored.diagnostics,
        )
    }

    /** 一次改动的结果：新全文、预期的单元列表（供写前校验）、给模型的话；[error] 非空表示拒绝 */
    private class Change(
        val text: String = "",
        val cells: List<JsonObject> = emptyList(),
        val message: String = "",
        val shown: JsonObject? = null,
        val shownIndex: Int = 0,
        val error: String? = null,
    )

    private fun fail(message: String) = Change(error = message)

    private fun replace(input: NotebookEditInput, nb: Notebook, layout: Layout, writer: NotebookWriter, text: String): Change {
        val index = when (val ref = findCell(nb, input.cellId!!)) {
            is CellRef.Found -> ref.index
            is CellRef.Missing -> return fail(ref.message)
        }
        val old = nb.cells[index]
        val oldType = old.str("cell_type")
        if (oldType == null && input.cellType == null) return fail("单元 ${input.cellId} 缺少 cell_type，替换时需要同时给 cell_type。")
        val updated = replaceCell(old, input.newSource, input.cellType)
        val span = layout.cells[index]
        val newText = text.replaceRange(span.start, span.end, writer.write(updated, layout.elemIndent))
        val cleared = oldType == "code" && updated.str("cell_type") == "code" &&
            ((old["outputs"] as? JsonArray)?.isNotEmpty() == true || old["execution_count"].let { it != null && it !is JsonNull })
        val message = buildString {
            append("已替换单元 ${cellId(updated, index)}（序号 $index）。")
            if (input.cellType != null && input.cellType != oldType) append("类型由 $oldType 改为 ${input.cellType}。")
            if (cleared) append("它原有的输出与执行序号已清空，需要重新运行才有新输出。")
        }
        return Change(newText, nb.cells.toMutableList().also { it[index] = updated }, message, updated, index)
    }

    private fun insert(input: NotebookEditInput, nb: Notebook, layout: Layout, writer: NotebookWriter, text: String): Change {
        val after = when (val id = input.cellId) {
            null -> -1
            else -> when (val ref = findCell(nb, id)) {
                is CellRef.Found -> ref.index
                is CellRef.Missing -> return fail(ref.message)
            }
        }
        val at = after + 1
        val id = if (nb.wantsCellIds) newCellId(nb) else null
        val cell = newCell(input.cellType!!, input.newSource, id)
        val cellText = writer.write(cell, layout.elemIndent)
        val sep = when {
            layout.cells.size >= 2 -> text.substring(layout.cells[0].end, layout.cells[1].start)
            layout.unit == null -> ","
            else -> "," + layout.nl + layout.elemIndent
        }
        val newText = when {
            layout.cells.isEmpty() ->
                text.replaceRange(
                    layout.innerStart,
                    layout.innerEnd,
                    if (layout.unit == null) cellText else layout.nl + layout.elemIndent + cellText + layout.nl + layout.keyIndent,
                )
            at < layout.cells.size -> text.replaceRange(layout.cells[at].start, layout.cells[at].start, cellText + sep)
            else -> text.replaceRange(layout.cells.last().end, layout.cells.last().end, sep + cellText)
        }
        val where = if (after < 0) "最前面" else "单元 ${cellId(nb.cells[after], after)} 之后"
        val message = "已在${where}插入新的 ${input.cellType} 单元 ${cellId(cell, at)}（序号 $at）。现在共 ${nb.cells.size + 1} 个单元。"
        return Change(newText, nb.cells.toMutableList().also { it.add(at, cell) }, message, cell, at)
    }

    private fun delete(input: NotebookEditInput, nb: Notebook, layout: Layout, text: String): Change {
        val index = when (val ref = findCell(nb, input.cellId!!)) {
            is CellRef.Found -> ref.index
            is CellRef.Missing -> return fail(ref.message)
        }
        val spans = layout.cells
        // 删掉单元连同它一侧的逗号与空白：非末尾的删到下一个单元开头，末尾的从上一个单元结尾删起，唯一的清空数组
        val newText = when {
            spans.size == 1 -> text.replaceRange(layout.innerStart, layout.innerEnd, "")
            index < spans.size - 1 -> text.removeRange(spans[index].start, spans[index + 1].start)
            else -> text.removeRange(spans[index - 1].end, spans[index].end)
        }
        val gone = nb.cells[index]
        val message = "已删除单元 ${cellId(gone, index)}（原序号 $index，类型 ${gone.str("cell_type") ?: "unknown"}）。现在共 ${nb.cells.size - 1} 个单元。"
        return Change(newText, nb.cells.toMutableList().also { it.removeAt(index) }, message)
    }

    /**
     * 换掉单元的源码。code 单元同时清空 outputs 与 execution_count：旧输出是旧源码跑出来的，
     * 留着会让模型（和之后读 notebook 的人）把它当成新源码的结果。Claude Code 的 NotebookEdit 也这么做；
     * 注意这与 Jupyter 界面不同——界面里改了源码输出还留着（只是变成「过期」），但在 .ipynb 文件层面没有「过期」标记可记。
     * source 沿用原单元的写法（字符串或按行数组），其余键与键顺序不动。
     */
    private fun replaceCell(old: JsonObject, newSource: String, newType: String?): JsonObject {
        val m = LinkedHashMap(old)
        m["source"] = sourceValue(newSource, asString = old["source"].let { it is JsonPrimitive && it.isString })
        if (newType != null) m["cell_type"] = JsonPrimitive(newType)
        if ((newType ?: old.str("cell_type")) == "code") {
            m["outputs"] = JsonArray(emptyList())
            m["execution_count"] = JsonNull
        } else {
            // markdown / raw 单元按 nbformat 不能有这两个键
            m.remove("outputs")
            m.remove("execution_count")
        }
        // 原来的键是字母序（nbformat 自己的写法）就保持字母序，新增的键也落在该在的位置；否则追加在末尾
        val wasSorted = old.keys.toList() == old.keys.sorted()
        return JsonObject(if (wasSorted) m.toSortedMap() else m)
    }

    /** nbformat 4.5 的单元：键按字母序（nbformat 写文件时 sort_keys），metadata 必有；code 单元带空 outputs 与 null 执行序号 */
    private fun newCell(type: String, source: String, id: String?): JsonObject {
        val m = sortedMapOf<String, JsonElement>(
            "cell_type" to JsonPrimitive(type),
            "metadata" to JsonObject(emptyMap()),
            "source" to sourceValue(source, asString = false),
        )
        if (id != null) m["id"] = JsonPrimitive(id)
        if (type == "code") {
            m["outputs"] = JsonArray(emptyList())
            m["execution_count"] = JsonNull
        }
        return JsonObject(m)
    }

    /** 与 Jupyter 一致：按行拆成数组，除最后一行外每行保留结尾的 \n；空源码是空数组 */
    private fun sourceValue(text: String, asString: Boolean): JsonElement {
        if (asString) return JsonPrimitive(text)
        val lines = mutableListOf<JsonElement>()
        var start = 0
        while (start < text.length) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) {
                lines += JsonPrimitive(text.substring(start))
                break
            }
            lines += JsonPrimitive(text.substring(start, nl + 1))
            start = nl + 1
        }
        return JsonArray(lines)
    }

    /**
     * nbformat 4.5 的单元 id：`^[a-zA-Z0-9-_]+$`，1–64 个字符，notebook 内唯一。
     * 取 8 位小写十六进制（Jupyter 自己用 uuid4().hex[:8]），与现有 id 撞了就重抽。
     */
    private fun newCellId(nb: Notebook): String {
        val used = nb.cells.mapNotNull { it.str("id") }.toSet()
        while (true) {
            val id = Random.nextInt().toUInt().toString(16).padStart(8, '0')
            if (id !in used) return id
        }
    }
}
