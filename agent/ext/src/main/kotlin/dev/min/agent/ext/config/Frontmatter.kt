package dev.min.agent.ext.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** frontmatter 里的值。只有四种形状，够表达 skills / agents / commands 文档里出现过的全部写法 */
sealed interface FmValue {
    /** `key:` 后面什么都没有，或写了 `null` / `~` */
    data object Null : FmValue

    /**
     * [quoted] 为真表示带引号或来自 `|` `>` 块：一定是字符串，不做布尔 / 数字推断（`"true"` 不是布尔）。
     * 不带引号的标量保留原文，由取值方按字段需要解释——skills 文档的布尔接受 yes/no/on/off/1/0，这是 YAML 1.2 没有的。
     */
    data class Scalar(val text: String, val quoted: Boolean) : FmValue
    data class Seq(val items: List<FmValue>) : FmValue
    data class Mapping(val entries: Map<String, FmValue>) : FmValue

    /** 转成 JSON（hooks、metadata、mcpServers 这类原样保留给以后的执行方）。不带引号的 true/false/数字按 YAML 1.2 推断 */
    fun toJson(): JsonElement = when (this) {
        Null -> JsonNull
        is Scalar -> if (quoted) JsonPrimitive(text) else plainToJson(text)
        is Seq -> JsonArray(items.map { it.toJson() })
        is Mapping -> JsonObject(entries.mapValues { it.value.toJson() })
    }

    private fun plainToJson(text: String): JsonElement = when {
        text.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        text.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        text.toLongOrNull() != null -> JsonPrimitive(text.toLong())
        FLOAT.matches(text) -> JsonPrimitive(text.toDouble())
        else -> JsonPrimitive(text)
    }

    private companion object {
        val FLOAT = Regex("""-?\d+\.\d+""")
    }
}

/**
 * 一个 Markdown 文件拆出的 frontmatter。
 * [present] 为假（没有开头的 `---`，或没闭合）时 [fields] 为空、[body] 是全文；
 * YAML 本身不合法时 [present] 仍为真但 [fields] 为空——CC 文档：「If the YAML between the markers doesn't parse,
 * the skill still loads with no fields set」。[notes] 只给人看，说清哪一行为什么被跳过。
 */
data class Frontmatter(
    val present: Boolean,
    val fields: Map<String, FmValue>,
    val body: String,
    val notes: List<String>,
)

/**
 * frontmatter 解析器：YAML 的一个够用的子集（项目没有 YAML 库，也不想为几个字段引一个）。
 *
 * 认：`key: value`、单双引号串（双引号含 `\n` `\"` `\uXXXX` 等转义）、`key: [a, b]` / `{a: b}`（可嵌套、可跨行）、
 * `- item` 列表（缩进的、与 key 同缩进的都行）、嵌套映射与「列表里的映射」（hooks 的写法）、
 * `|` `>` 块标量（含 `-` `+` 收尾符）、折行续写的普通标量与引号串、整行与行尾 `#` 注释。
 *
 * 不认、不猜：锚点 `&` / 别名 `*` / 标签 `!`、块标量的缩进指示符（`|2`）——这些键整个跳过并记一条说明，别的键照常读。
 * 真正的 YAML 错误（缩进不一致、普通值里有未加引号的 `: `、引号没闭合、缩进里有制表符）——
 * 整个 frontmatter 当作没有字段，与 CC 一致；`description: a: b` 这种常见笔误就是这样被 CC 丢掉的，Min 也丢并说明原因。
 *
 * 只认开头的 `---` 在文件**第一行**（CC 文档原文）；闭合是后面第一个只含 `---`（或 `...`）的行。
 */
object FrontmatterParser {
    fun parse(text: String, label: String): Frontmatter {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.first().trimEnd() != "---") return Frontmatter(false, emptyMap(), normalized, emptyList())
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            ?: return Frontmatter(
                false, emptyMap(), normalized,
                listOf("$label：frontmatter 的开头有 `---` 却没有闭合的 `---`，整个文件当作正文"),
            )
        val body = lines.drop(end + 1).joinToString("\n").trimStart('\n')
        val yaml = Yaml(lines.subList(1, end).toMutableList(), label)
        val fields = try {
            yaml.document()
        } catch (e: YamlError) {
            yaml.notes += "$label：frontmatter 不是合法 YAML（${e.message}），整个 frontmatter 被忽略，字段都按未设置处理（与 Claude Code 一致）"
            emptyMap()
        }
        return Frontmatter(true, fields, body, yaml.notes)
    }
}

private class YamlError(message: String) : Exception(message)
private class Unsupported(message: String) : Exception(message)

private val PLAIN_KEY = Regex("""^([A-Za-z0-9_$][^:#]*?)\s*:(?:\s+(.*))?$""")
private val QUOTED_KEY = Regex("""^(?:"([^"]*)"|'([^']*)')\s*:(?:\s+(.*))?$""")
private val BLOCK_HEADER = Regex("""^([|>])([+-]?)([1-9]?)([+-]?)$""")
private const val NO_QUOTE = '\u0000'

private class Yaml(private val lines: MutableList<String>, private val label: String) {
    val notes = mutableListOf<String>()
    private var i = 0

    /** 文件里的行号（1 起；第 1 行是开头的 `---`） */
    private fun at(index: Int = i) = "第 ${index + 2} 行"

    fun document(): Map<String, FmValue> {
        skipBlank()
        if (i >= lines.size) return emptyMap()
        val top = parseMapping(indentOf(lines[i]))
        skipBlank()
        if (i < lines.size) throw YamlError("${at()}的缩进比第一个字段还浅")
        return top
    }

    // ---- 结构 ----

    private fun parseMapping(indent: Int): Map<String, FmValue> {
        val out = LinkedHashMap<String, FmValue>()
        while (true) {
            skipBlank()
            if (i >= lines.size) break
            val line = lines[i]
            val ind = indentOf(line)
            if (ind < indent) break
            if (ind > indent) throw YamlError("${at()}的缩进与同级字段不一致")
            val (key, rest) = splitKey(line.substring(ind))
                ?: throw YamlError("${at()}不是 `key: value` 形式：${line.trim().take(40)}")
            val keyAt = i
            i++
            val value = try {
                parseValue(rest.orEmpty(), indent, sameIndentSeq = true)
            } catch (u: Unsupported) {
                notes += "$label：跳过字段 `$key`（${at(keyAt)}）：${u.message}"
                skipChildren(indent)
                continue
            }
            if (key in out) notes += "$label：字段 `$key` 重复（${at(keyAt)}），取后一个"
            out[key] = value
        }
        return out
    }

    private fun parseSeq(indent: Int): FmValue.Seq {
        val items = mutableListOf<FmValue>()
        while (true) {
            skipBlank()
            if (i >= lines.size) break
            val line = lines[i]
            val ind = indentOf(line)
            if (ind < indent) break
            if (ind > indent) throw YamlError("${at()}的缩进与同级列表项不一致")
            if (!isSeqItem(line, ind)) break // `key:\n- a\nnext: b`：同缩进的非 `-` 行是列表的结尾
            val after = line.substring(ind + 1)
            val rest = after.trimStart()
            val offset = ind + 1 + (after.length - rest.length)
            when {
                rest.isEmpty() || rest.startsWith("#") -> {
                    i++
                    items += parseNested(indent, sameIndentSeq = false)
                }
                rest == "-" || rest.startsWith("- ") -> {
                    lines[i] = " ".repeat(offset) + rest // 「- - x」：把项当作缩进到 offset 的新行再解析
                    items += parseSeq(offset)
                }
                splitKey(rest) != null -> {
                    lines[i] = " ".repeat(offset) + rest // 「- key: v」后面同缩进的行是同一个映射的其余键
                    items += FmValue.Mapping(parseMapping(offset))
                }
                else -> {
                    i++
                    items += parseValue(rest, indent, sameIndentSeq = false)
                }
            }
        }
        return FmValue.Seq(items)
    }

    /** `key:` 后面没有内容时看下一行：更深的缩进是嵌套块；映射里还允许与 key 同缩进的 `- a` */
    private fun parseNested(parentIndent: Int, sameIndentSeq: Boolean): FmValue {
        skipBlank()
        if (i >= lines.size) return FmValue.Null
        val line = lines[i]
        val ind = indentOf(line)
        return when {
            ind > parentIndent -> when {
                isSeqItem(line, ind) -> parseSeq(ind)
                splitKey(line.substring(ind)) != null -> FmValue.Mapping(parseMapping(ind))
                else -> { // 换行后的流式集合或标量也是上一个键的值（YAML 块上下文允许，真解析器都接受）
                    i++ // 这一行还没有像 parseMapping 那样被消费掉，先吃掉再解析
                    parseValue(line.substring(ind), ind, sameIndentSeq = false)
                }
            }
            ind == parentIndent && sameIndentSeq && isSeqItem(line, ind) -> parseSeq(ind)
            else -> FmValue.Null
        }
    }

    /** [rest] 是冒号（或 `- `）之后的同行内容；多行的值从 [i]（下一行）接着读 */
    private fun parseValue(rest: String, indent: Int, sameIndentSeq: Boolean): FmValue {
        val t = rest.trim()
        return when {
            t.isEmpty() || t.startsWith("#") -> parseNested(indent, sameIndentSeq)
            t[0] == '|' || t[0] == '>' -> parseBlockScalar(t, indent)
            t[0] == '&' || t[0] == '*' || t[0] == '!' -> throw Unsupported("锚点、别名与标签（`${t.take(12)}`）暂不支持")
            t[0] == '[' || t[0] == '{' -> parseFlowAtLineEnd(t)
            t[0] == '"' || t[0] == '\'' -> parseQuotedScalar(t)
            t[0] == '@' || t[0] == '`' -> throw YamlError("${at(i - 1)}的值以保留字符 `${t[0]}` 开头，需要加引号")
            else -> parsePlain(t, indent)
        }
    }

    // ---- 标量 ----

    private fun parsePlain(first: String, indent: Int): FmValue {
        val sb = StringBuilder(cutComment(first, quoteAware = false).trim())
        // 折行续写：后面比 key 缩进更深的非空行接在同一个值后面，用空格连接
        while (i < lines.size && lines[i].isNotBlank() && indentOf(lines[i]) > indent && !lines[i].trim().startsWith("#")) {
            sb.append(' ').append(cutComment(lines[i].trim(), quoteAware = false).trim())
            i++
        }
        val text = sb.toString()
        if (": " in text || text.endsWith(":")) {
            throw YamlError("${at(i - 1)}的值里有未加引号的 `:`（`${text.take(40)}`），要写进值里就加上引号")
        }
        return if (text == "null" || text == "~" || text == "Null" || text == "NULL") FmValue.Null else FmValue.Scalar(text, false)
    }

    private fun parseQuotedScalar(first: String): FmValue {
        var acc = first
        while (true) {
            val scanned = scanQuoted(acc, 0)
            if (scanned != null) {
                val tail = acc.substring(scanned.second).trim()
                if (tail.isNotEmpty() && !tail.startsWith("#")) throw YamlError("${at(i - 1)}在值后面多出内容：`${tail.take(30)}`")
                return FmValue.Scalar(scanned.first, true)
            }
            if (i >= lines.size) throw YamlError("${at(i - 1)}的引号没有闭合")
            acc += (if (lines[i].isBlank()) "\n" else " ") + lines[i].trim() // 引号串折行：换行折成一个空格
            i++
        }
    }

    /** 在 s[from]（是引号）处读一个引号串，返回（值，结束位置之后）；没闭合返回 null */
    private fun scanQuoted(s: String, from: Int): Pair<String, Int>? {
        val q = s[from]
        val sb = StringBuilder()
        var k = from + 1
        while (k < s.length) {
            val c = s[k]
            when {
                q == '\'' && c == '\'' ->
                    if (k + 1 < s.length && s[k + 1] == '\'') {
                        sb.append('\'')
                        k += 2
                    } else {
                        return sb.toString() to k + 1
                    }
                q == '"' && c == '"' -> return sb.toString() to k + 1
                q == '"' && c == '\\' -> {
                    if (k + 1 >= s.length) return null
                    val (text, next) = escape(s, k + 1)
                    sb.append(text)
                    k = next
                }
                else -> {
                    sb.append(c)
                    k++
                }
            }
        }
        return null
    }

    private fun escape(s: String, k: Int): Pair<String, Int> {
        fun hex(n: Int): Pair<String, Int> {
            val digits = s.drop(k + 1).take(n)
            val code = digits.takeIf { it.length == n }?.toIntOrNull(16)
                ?: throw YamlError("${at(i - 1)}的转义 `\\${s[k]}` 后面不是 $n 位十六进制数")
            return String(Character.toChars(code)) to k + 1 + n
        }
        return when (val c = s[k]) {
            'n' -> "\n" to k + 1
            't' -> "\t" to k + 1
            'r' -> "\r" to k + 1
            '0' -> "\u0000" to k + 1
            'e' -> "\u001b" to k + 1
            'a' -> "\u0007" to k + 1
            'b' -> "\b" to k + 1
            'f' -> "\u000c" to k + 1
            'v' -> "\u000b" to k + 1
            '"', '\\', '/', ' ' -> c.toString() to k + 1
            'x' -> hex(2)
            'u' -> hex(4)
            'U' -> hex(8)
            else -> throw YamlError("${at(i - 1)}有不认识的转义 `\\$c`")
        }
    }

    // ---- `|` 与 `>` 块 ----

    private fun parseBlockScalar(header: String, parentIndent: Int): FmValue {
        val h = BLOCK_HEADER.matchEntire(cutComment(header, quoteAware = false).trim())
            ?: throw YamlError("${at(i - 1)}的块标量头 `${header.take(10)}` 写法不对")
        if (h.groupValues[3].isNotEmpty()) throw Unsupported("块标量的缩进指示符（`${h.value}`）暂不支持")
        val chomp = h.groupValues[2].ifEmpty { h.groupValues[4] }
        val folded = h.groupValues[1] == ">"

        var blockIndent = -1
        val content = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                content += ""
                i++
                continue
            }
            val ind = l.length - l.trimStart(' ').length
            if (blockIndent < 0) {
                if (ind <= parentIndent) break
                blockIndent = ind
            }
            if (ind < blockIndent) break
            content += l.substring(blockIndent)
            i++
        }
        var trailing = 0
        while (content.isNotEmpty() && content.last().isEmpty()) {
            content.removeAt(content.lastIndex)
            trailing++
        }
        val body = if (folded) fold(content) else content.joinToString("\n")
        val text = when {
            content.isEmpty() -> if (chomp == "+") "\n".repeat(trailing) else ""
            chomp == "-" -> body
            chomp == "+" -> body + "\n".repeat(1 + trailing)
            else -> "$body\n"
        }
        return FmValue.Scalar(text, true)
    }

    /** YAML 折叠：相邻行用空格连接，一个空行变一个换行，更深缩进的行保持原样换行 */
    private fun fold(content: List<String>): String {
        val sb = StringBuilder()
        var prev: String? = null
        for (line in content) {
            val p = prev
            when {
                p == null -> sb.append(line)
                line.isEmpty() -> sb.append('\n')
                p.isEmpty() -> sb.append(line)
                line.startsWith(" ") || p.startsWith(" ") -> sb.append('\n').append(line)
                else -> sb.append(' ').append(line)
            }
            prev = line
        }
        return sb.toString()
    }

    // ---- `[a, b]` 与 `{a: b}` ----

    private fun parseFlowAtLineEnd(first: String): FmValue {
        // 逐行先去注释再拼：拼成一行后，行中间的 `#` 就分不清是不是注释了
        var acc = cutComment(first, quoteAware = true).trim()
        while (flowDepth(acc) > 0) {
            if (i >= lines.size) throw YamlError("${at(i - 1)}的括号没有闭合")
            acc += " " + cutComment(lines[i], quoteAware = true).trim()
            i++
        }
        return parseFlow(acc)
    }

    /** 括号嵌套深度（引号与注释里的括号不算）；返回 > 0 表示还没闭合 */
    private fun flowDepth(s: String): Int {
        var depth = 0
        var quote = NO_QUOTE
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                quote != NO_QUOTE -> if (c == '\\' && quote == '"') k++ else if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '#' && (k == 0 || s[k - 1].isWhitespace()) -> return depth
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
            }
            k++
        }
        return depth
    }

    private fun parseFlow(s: String): FmValue {
        val close = if (s.first() == '[') ']' else '}'
        if (s.last() != close || flowDepth(s) != 0) throw YamlError("${at(i - 1)}的 `${s.take(40)}` 括号不配对或后面多出内容")
        val parts = splitTopLevel(s.substring(1, s.length - 1)).map { it.trim() }.filter { it.isNotEmpty() }
        if (close == ']') return FmValue.Seq(parts.map { flowItem(it) })
        return FmValue.Mapping(
            parts.associate { part ->
                val (k, v) = splitKey(part) ?: throw YamlError("${at(i - 1)}的 `{…}` 里 `$part` 不是 `key: value`")
                k to flowItem(v.orEmpty().trim())
            },
        )
    }

    private fun flowItem(s: String): FmValue = when {
        s.isEmpty() || s == "null" || s == "~" -> FmValue.Null
        s[0] == '[' || s[0] == '{' -> parseFlow(s)
        s[0] == '"' || s[0] == '\'' -> {
            val scanned = scanQuoted(s, 0) ?: throw YamlError("${at(i - 1)}的引号没有闭合")
            if (s.substring(scanned.second).isNotBlank()) throw YamlError("${at(i - 1)}在引号串后面多出内容")
            FmValue.Scalar(scanned.first, true)
        }
        s[0] == '&' || s[0] == '*' || s[0] == '!' -> throw Unsupported("锚点、别名与标签暂不支持")
        else -> FmValue.Scalar(s, false)
    }

    /** 按顶层逗号切（引号与括号里的逗号不算） */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var quote = NO_QUOTE
        var start = 0
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                quote != NO_QUOTE -> if (c == '\\' && quote == '"') k++ else if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '[' || c == '{' -> depth++
                c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> {
                    parts += s.substring(start, k)
                    start = k + 1
                }
            }
            k++
        }
        parts += s.substring(start)
        return parts
    }

    // ---- 行工具 ----

    private fun skipBlank() {
        while (i < lines.size && (lines[i].isBlank() || lines[i].trim().startsWith("#"))) i++
    }

    /** 跳过当前键剩下的子内容：更深缩进的行，以及与键同缩进的 `- ` 列表项 */
    private fun skipChildren(indent: Int) {
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) {
                i++
                continue
            }
            val ind = l.length - l.trimStart(' ').length
            if (ind > indent || (ind == indent && isSeqItem(l, ind))) i++ else break
        }
    }

    private fun indentOf(line: String): Int {
        val ind = line.length - line.trimStart(' ').length
        if (ind < line.length && line[ind] == '\t') throw YamlError("${at()}的缩进里有制表符，YAML 只许用空格")
        return ind
    }

    private fun isSeqItem(line: String, indent: Int): Boolean =
        line.length > indent && line[indent] == '-' && (line.length == indent + 1 || line[indent + 1] == ' ')

    private fun splitKey(s: String): Pair<String, String?>? {
        QUOTED_KEY.matchEntire(s)?.let { m -> return m.groupValues[1].ifEmpty { m.groupValues[2] } to m.groups[3]?.value }
        val m = PLAIN_KEY.matchEntire(s) ?: return null
        return m.groupValues[1].trim() to m.groups[2]?.value
    }

    /** 去掉行尾注释：`#` 在行首或前面是空白才算注释；[quoteAware] 时引号里的 `#` 不算 */
    private fun cutComment(s: String, quoteAware: Boolean): String {
        var quote = NO_QUOTE
        for (k in s.indices) {
            val c = s[k]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                quoteAware && (c == '"' || c == '\'') -> quote = c
                c == '#' && (k == 0 || s[k - 1].isWhitespace()) -> return s.substring(0, k)
            }
        }
        return s
    }
}
