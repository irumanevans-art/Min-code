package dev.min.agent.context

/**
 * 记忆文件的纯文本解析：`@` 导入与规则的 frontmatter。不碰文件系统，单测直接喂字符串。
 * 语义照 Claude Code 官方文档（code.claude.com/docs/en/memory；research/inventory/claude-code/03-extensions.md §1.2、§1.4），
 * 同一份 CLAUDE.md 在 CC 与 Min 里导入的是同一批文件。
 */
internal object MemoryMarkdown {

    /**
     * 文本里的 `@路径` 导入，按出现顺序，原样路径（已去掉 `#锚点`、`\ ` 还原成空格），尚未解析成绝对路径。
     *
     * - `@` 必须在行首或空白之后：`a@b.com` 不是导入。
     * - 围栏代码块（``` 或 ~~~）与行内代码里的不解析——文档里想提一个路径又不导入，就用反引号包住（CC 原文）。
     * - 路径到第一个未转义的空白为止；首字符必须是 `./`、`~/`、`/` 或 ASCII 字母数字与 `._-`，
     *   所以用引号包住的路径不导入（与 CC 一致）。
     */
    fun imports(text: String): List<String> {
        val found = mutableListOf<String>()
        var fence: Fence? = null
        for (line in text.lines()) {
            val open = fence
            if (open != null) {
                if (open.closedBy(line)) fence = null
                continue
            }
            val opening = Fence.openedBy(line)
            if (opening != null) {
                fence = opening
                continue
            }
            IMPORT.findAll(withoutCodeSpans(line)).forEach { match ->
                val path = match.groupValues[1].substringBefore('#').replace("\\ ", " ")
                if (isImportPath(path)) found += path
            }
        }
        return found
    }

    /**
     * 规则文件的 frontmatter：只认 `paths`（CC 只读这一个字段，其余忽略）。没有 frontmatter 或没闭合时 [Frontmatter.body] 是全文。
     * 不是完整的 YAML 解析器：只认 `paths: a, b`、`paths: [a, b]` 与下一行起的 `- a` 列表，够覆盖 CC 文档里的写法。
     */
    fun splitFrontmatter(text: String): Frontmatter {
        val lines = text.split('\n')
        if (lines.first().trimEnd() != "---") return Frontmatter(emptyList(), text)
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return Frontmatter(emptyList(), text)
        return Frontmatter(pathsIn(lines.subList(1, end)), lines.drop(end + 1).joinToString("\n"))
    }

    private fun pathsIn(yaml: List<String>): List<String> {
        val at = yaml.indexOfFirst { PATHS_KEY.matches(it) }
        if (at < 0) return emptyList()
        val inline = PATHS_KEY.matchEntire(yaml[at])!!.groupValues[1].trim()
        val raw = if (inline.isNotEmpty()) {
            splitOutsideBraces(if (inline.startsWith("[") && inline.endsWith("]")) inline.substring(1, inline.length - 1) else inline)
        } else {
            yaml.drop(at + 1)
                .takeWhile { it.isBlank() || LIST_ITEM.matches(it) }
                .mapNotNull { LIST_ITEM.matchEntire(it)?.groupValues?.get(1) }
        }
        return raw.map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    }

    /** 按逗号拆，花括号里的逗号不算：带花括号后缀的扩展名写法是一个 glob，不是两个 */
    private fun splitOutsideBraces(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in text) {
            when {
                c == '{' -> depth++
                c == '}' && depth > 0 -> depth--
                c == ',' && depth == 0 -> {
                    parts += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(c)
        }
        parts += current.toString()
        return parts
    }

    private fun isImportPath(path: String): Boolean = when {
        path.isEmpty() -> false
        path.startsWith("./") || path.startsWith("~/") -> true
        path.startsWith("/") -> path != "/"
        else -> path[0] in 'a'..'z' || path[0] in 'A'..'Z' || path[0] in '0'..'9' || path[0] in "._-"
    }

    /** 把一行里的行内代码换成空格。反引号串要找到等长的另一串才算闭合，否则就是普通字符（CommonMark） */
    private fun withoutCodeSpans(line: String): String {
        if ('`' !in line) return line
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                out.append(line[i++])
                continue
            }
            val runEnd = runEnd(line, i)
            val close = closingRun(line, runEnd, runEnd - i)
            if (close < 0) {
                out.append(line, i, runEnd)
                i = runEnd
            } else {
                out.append(' ')
                i = close
            }
        }
        return out.toString()
    }

    private fun runEnd(line: String, from: Int): Int {
        var j = from
        while (j < line.length && line[j] == '`') j++
        return j
    }

    /** 从 [from] 起找长度恰为 [length] 的反引号串，返回它之后的位置；找不到返回 -1 */
    private fun closingRun(line: String, from: Int, length: Int): Int {
        var k = from
        while (k < line.length) {
            if (line[k] != '`') {
                k++
                continue
            }
            val end = runEnd(line, k)
            if (end - k == length) return end
            k = end
        }
        return -1
    }

    /** 围栏代码块：开头至多 3 个空格缩进，三个以上同种字符；闭合用同种字符、不短于开头、后面只能有空白 */
    private class Fence(val char: Char, val length: Int) {
        fun closedBy(line: String): Boolean {
            val m = FENCE.matchEntire(line) ?: return false
            val marker = m.groupValues[1]
            return marker[0] == char && marker.length >= length && m.groupValues[2].isBlank()
        }

        companion object {
            fun openedBy(line: String): Fence? {
                val m = FENCE.matchEntire(line) ?: return null
                val marker = m.groupValues[1]
                // 反引号围栏的 info string 里不能再有反引号，否则那是一段行内代码（CommonMark）
                if (marker[0] == '`' && '`' in m.groupValues[2]) return null
                return Fence(marker[0], marker.length)
            }
        }
    }

    private val FENCE = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val IMPORT = Regex("""(?:^|\s)@((?:[^\s\\]|\\ )+)""")
    private val PATHS_KEY = Regex("""^paths\s*:(.*)$""")
    private val LIST_ITEM = Regex("""^\s*-\s*(.+)$""")
}

/** 规则文件拆开后的样子。[paths] 非空 = 按需加载的规则 */
internal data class Frontmatter(val paths: List<String>, val body: String)
