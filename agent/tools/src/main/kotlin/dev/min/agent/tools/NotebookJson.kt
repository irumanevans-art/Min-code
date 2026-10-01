package dev.min.agent.tools

import dev.min.agent.spi.GuestPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * Jupyter notebook（.ipynb）的 JSON 零件，Read 与 NotebookEdit 共用。
 *
 * 为什么不直接「解析 → 改 → 整个重新序列化」：整本重写会重排键、改缩进、动数字写法，
 * 一次小改动在 git 里变成整个文件的 diff。所以这里另有一个只认「cells 数组里每个单元占哪段字符」的扫描器，
 * 改动只替换 / 插入 / 删除目标单元那一段，其余字符原样不动。
 */

/** notebook 一次最多处理这么多字节（含内嵌图片的 notebook 常有几 MB；与 Edit 的上限同量级） */
internal const val MAX_NOTEBOOK_BYTES = 10L * 1024 * 1024

internal fun GuestPath.isNotebook(): Boolean = value.endsWith(".ipynb", ignoreCase = true)

/** nbformat 里「字符串或字符串数组」的字段（source、text、data 里的文本）统一取成一个字符串 */
internal fun JsonElement?.multiline(): String = when (this) {
    is JsonArray -> joinToString("") { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
    is JsonPrimitive -> if (isString) content else ""
    else -> ""
}

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 单元的 id；没有 id 的旧格式单元用 `cell-<序号>`（序号从 0 起）。模型与 NotebookEdit 都用这个名字指单元 */
internal fun cellId(cell: JsonObject, index: Int): String = cell.str("id")?.takeIf { it.isNotEmpty() } ?: "cell-$index"

internal class Notebook(val root: JsonObject, val cells: List<JsonObject>) {
    private val nbformat get() = (root["nbformat"] as? JsonPrimitive)?.intOrNull
    private val minor get() = (root["nbformat_minor"] as? JsonPrimitive)?.intOrNull ?: 0

    /** nbformat ≥ 4.5 的单元才有 id（且 schema 要求有）；更旧的格式里加 id 会让 notebook 不合法 */
    val wantsCellIds get() = (nbformat ?: 0) > 4 || (nbformat == 4 && minor >= 5)

    val versionText get() = "nbformat ${nbformat ?: "?"}.${minor}"

    /** 代码语言，给模型一个线索；metadata 里取不到就是 null */
    val language: String?
        get() {
            val meta = root["metadata"] as? JsonObject ?: return null
            return (meta["language_info"] as? JsonObject)?.str("name") ?: (meta["kernelspec"] as? JsonObject)?.str("language")
        }
}

internal sealed interface NotebookParse {
    data class Ok(val notebook: Notebook) : NotebookParse
    data class Bad(val message: String) : NotebookParse
}

/** 解析并检查 notebook 的基本形状。错误信息写给模型：说清是哪里不对、下一步怎么办 */
internal fun parseNotebook(path: GuestPath, text: String): NotebookParse {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: IllegalArgumentException) {
        // SerializationException 是它的子类；别的解析失败（如嵌套过深）也走这里
        return NotebookParse.Bad("$path 不是合法的 JSON，notebook 文件可能损坏：${(e.message ?: "解析失败").lineSequence().first().clip(200)}。可以用 Bash 看看文件内容。")
    }
    if (root !is JsonObject) return NotebookParse.Bad("$path 的顶层不是 JSON 对象，不是 Jupyter notebook。")
    val nbformat = (root["nbformat"] as? JsonPrimitive)?.intOrNull
    if (root["cells"] == null && (root["worksheets"] != null || (nbformat != null && nbformat < 4))) {
        return NotebookParse.Bad("$path 是 nbformat ${nbformat ?: 3} 的旧格式 notebook（没有顶层 cells），不支持。可以先用 Bash 跑 `jupyter nbconvert --to notebook --output 新文件 $path` 升级到 4。")
    }
    val cells = root["cells"] as? JsonArray ?: return NotebookParse.Bad("$path 缺少 `cells` 数组，不是有效的 Jupyter notebook。")
    val objects = cells.mapIndexed { i, c ->
        c as? JsonObject ?: return NotebookParse.Bad("$path 的第 $i 个单元不是 JSON 对象，notebook 结构不对。")
    }
    return NotebookParse.Ok(Notebook(root, objects))
}

internal sealed interface CellRef {
    data class Found(val index: Int) : CellRef
    data class Missing(val message: String) : CellRef
}

/** 按 id 找单元；找不到再按 `cell-<序号>` 当序号用（旧格式没有 id，id 重复时也靠它指到具体一个） */
internal fun findCell(nb: Notebook, ref: String): CellRef {
    val byId = nb.cells.indices.filter { nb.cells[it].str("id") == ref }
    if (byId.size == 1) return CellRef.Found(byId.single())
    if (byId.size > 1) {
        return CellRef.Missing("id `$ref` 在 notebook 里重复出现（序号 ${byId.joinToString("、")}），改用 `cell-<序号>` 指定，如 `cell-${byId.first()}`。")
    }
    val m = Regex("cell-(\\d+)").matchEntire(ref)
    val index = m?.groupValues?.get(1)?.toIntOrNull()
    if (index != null && index < nb.cells.size) return CellRef.Found(index)
    val shown = nb.cells.indices.take(20).joinToString("、") { cellId(nb.cells[it], it) }
    val more = if (nb.cells.size > 20) " 等共 ${nb.cells.size} 个" else ""
    return CellRef.Missing(
        if (nb.cells.isEmpty()) "notebook 里没有单元，找不到 `$ref`。要加第一个单元用 edit_mode=insert 且不给 cell_id。"
        else "notebook 里没有 id 为 `$ref` 的单元。现有单元：$shown$more。先用 Read 看一遍再指定。",
    )
}

// ---- 单元的字符范围扫描 ----

internal class ScanError(message: String) : Exception(message)

/** 单元在文本里占 [start, end)，不含两侧的空白与逗号 */
internal data class Span(val start: Int, val end: Int)

/**
 * cells 数组在文本里的位置与这本 notebook 的排版习惯（缩进、换行）。
 * [unit] 为 null 表示整本压成一行（没有缩进）。
 */
internal class Layout(
    val cells: List<Span>,
    /** cells 数组 `[` 之后的位置 */
    val innerStart: Int,
    /** cells 数组 `]` 的位置 */
    val innerEnd: Int,
    val unit: String?,
    val nl: String,
    /** 单元（数组元素）所在行的缩进 */
    val elemIndent: String,
    /** `"cells"` 这个键所在行的缩进，空数组插入首个单元时用它收尾 */
    val keyIndent: String,
)

internal fun scanLayout(s: String): Layout {
    val sc = Scanner(s)
    sc.expect('{')
    val rootOpen = sc.p - 1
    var firstKey = -1
    var cellsKey = -1
    var arrayOpen = -1
    var arrayClose = -1
    val spans = mutableListOf<Span>()
    while (true) {
        val c = sc.peek() ?: throw ScanError("对象没有闭合")
        if (c == '}') break
        if (firstKey < 0) firstKey = sc.p
        val keyStart = sc.p
        val key = sc.string()
        sc.expect(':')
        if (key == "cells" && arrayOpen < 0) {
            cellsKey = keyStart
            sc.expect('[')
            arrayOpen = sc.p - 1
            while (true) {
                val e = sc.peek() ?: throw ScanError("cells 数组没有闭合")
                if (e == ']') {
                    arrayClose = sc.p
                    sc.p++
                    break
                }
                val start = sc.p
                sc.skipValue()
                spans += Span(start, sc.p)
                if (sc.peek() == ',') sc.p++
            }
        } else {
            sc.skipValue()
        }
        if (sc.peek() == ',') sc.p++
    }
    if (arrayOpen < 0) throw ScanError("找不到 cells 数组")

    val gap = s.substring(rootOpen + 1, firstKey)
    val nl = if (gap.contains("\r\n")) "\r\n" else "\n"
    val unit = if (gap.contains('\n')) gap.substringAfterLast('\n') else null
    val keyIndent = indentBefore(s, cellsKey) ?: unit.orEmpty()
    val elemIndent = spans.firstOrNull()?.let { indentBefore(s, it.start) } ?: (keyIndent + unit.orEmpty())
    return Layout(spans, arrayOpen + 1, arrayClose, unit, nl, elemIndent, keyIndent)
}

/** pos 所在行 pos 之前的部分，全是空白才算缩进；否则（元素没有另起一行）为 null */
private fun indentBefore(s: String, pos: Int): String? {
    val line = s.substring(s.lastIndexOf('\n', pos - 1) + 1, pos)
    return line.takeIf { l -> l.all { it == ' ' || it == '\t' } }
}

/** 只会在已经通过 JSON 解析的文本上跑，所以对格式错误不求友好，抛 [ScanError] 让调用方放弃写入 */
private class Scanner(private val s: String) {
    var p = 0

    fun ws() {
        while (p < s.length && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n' || s[p] == '\r')) p++
    }

    fun peek(): Char? {
        ws()
        return s.getOrNull(p)
    }

    fun expect(c: Char) {
        ws()
        if (p >= s.length || s[p] != c) throw ScanError("位置 $p 处应为 `$c`")
        p++
    }

    /** 读一个字符串，返回引号之间的原文（不反转义；这里只拿它比较键名） */
    fun string(): String {
        expect('"')
        val start = p
        while (p < s.length && s[p] != '"') {
            if (s[p] == '\\') p++
            p++
        }
        if (p >= s.length) throw ScanError("字符串没有闭合")
        return s.substring(start, p++)
    }

    fun skipValue() {
        ws()
        if (p >= s.length) throw ScanError("值不完整")
        when (s[p]) {
            '"' -> string()
            '{', '[' -> {
                var depth = 0
                while (p < s.length) {
                    when (s[p]) {
                        '"' -> {
                            string()
                            continue
                        }
                        '{', '[' -> depth++
                        '}', ']' -> depth--
                    }
                    p++
                    if (depth == 0) return
                }
                throw ScanError("括号没有闭合")
            }
            else -> while (p < s.length && s[p] !in ",]} \t\r\n") p++
        }
    }
}

// ---- 按原排版写出 JSON ----

/**
 * 把一个单元写成与整本 notebook 同样的排版：nbformat 自己的写法是 `indent=1, sort_keys=True, ensure_ascii=False`，
 * 但别的工具（VS Code、jupytext、手写）缩进不同，所以缩进与换行取自文件。键顺序照 [JsonObject] 里的顺序，
 * 数字原样保留字面（JsonPrimitive 存的就是原文），字符串转义照 Python 的 json 模块（Jupyter 的写法）。
 */
internal class NotebookWriter(private val unit: String?, private val nl: String, private val asciiOnly: Boolean) {
    fun write(e: JsonElement, indent: String): String = StringBuilder().also { put(it, e, indent) }.toString()

    private fun put(sb: StringBuilder, e: JsonElement, indent: String) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(e.content)
            is JsonArray -> container(sb, '[', ']', e.size, indent) { i, inner -> put(sb, e[i], inner) }
            is JsonObject -> {
                val entries = e.entries.toList()
                container(sb, '{', '}', entries.size, indent) { i, inner ->
                    quote(sb, entries[i].key)
                    sb.append(if (unit == null) ":" else ": ")
                    put(sb, entries[i].value, inner)
                }
            }
        }
    }

    private inline fun container(sb: StringBuilder, open: Char, close: Char, size: Int, indent: String, item: (Int, String) -> Unit) {
        sb.append(open)
        if (size > 0) {
            val inner = indent + unit.orEmpty()
            for (i in 0 until size) {
                if (i > 0) sb.append(',')
                if (unit != null) sb.append(nl).append(inner)
                item(i, inner)
            }
            if (unit != null) sb.append(nl).append(indent)
        }
        sb.append(close)
    }

    private fun quote(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch.code < 0x20 || (asciiOnly && ch.code >= 0x80) -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    companion object {
        /** 全文只有 ASCII、却带着 `\uXXXX`（≥ 0x80）转义，说明写出这本 notebook 的工具用了 ensure_ascii，新写的内容也照此转义 */
        fun detectAsciiOnly(text: String): Boolean =
            text.all { it.code < 0x80 } && Regex("\\\\u(?!00[0-7])[0-9a-fA-F]{4}").containsMatchIn(text)
    }
}
