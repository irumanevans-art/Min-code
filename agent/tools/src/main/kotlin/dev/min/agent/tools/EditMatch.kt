package dev.min.agent.tools

/*
 * Edit 的匹配链：在文件内容里找 old_string，找不到逐字相同的就逐级放宽（规格 §4）。纯函数，单独测。
 *
 * 逐级放宽的依据：模型抄 old_string 时最常见的偏差依次是行尾空白、整体缩进、标点写法（弯引号、破折号、不换行空格）。
 * 每一级都要求**唯一**匹配：找到多处就停下让模型补上下文，而不是猜一处改——改错地方比改不了代价大得多。
 * 不做编辑距离式的模糊替换：Aider 写过又弃用了，匹配到的常常不是模型想改的那段。
 *
 * 所有输入都已经是「\n 换行」的形式（CRLF 由 [EditTool] 在进出时转换）。
 */

internal enum class MatchStrategy(val differenceForModel: String?) {
    Exact(null),
    TrailingWhitespace("行尾空白不同"),
    Indentation("缩进不同（替换内容已按文件原有缩进调整）"),
    Punctuation("引号、破折号或不换行空格的写法不同"),
}

internal sealed interface Located {
    /** 用 [replacement] 替换内容里 [start, end) 这一段 */
    data class Found(val start: Int, val end: Int, val replacement: String, val strategy: MatchStrategy) : Located

    /** 同一级找到多处：各处起始行号（从 1 起） */
    data class Ambiguous(val lines: List<Int>, val strategy: MatchStrategy) : Located

    /** 哪一级都没找到；[similarLines] 是与 old_string 第一行相似的行号，给模型自己核对 */
    data class NotFound(val similarLines: List<Int>) : Located
}

internal object EditMatch {
    fun locate(content: String, old: String, new: String): Located {
        exact(content, old, new)?.let { return it }
        lineBased(content, old, new, MatchStrategy.TrailingWhitespace) { it.trimEnd() }?.let { return it }
        lineBased(content, old, new, MatchStrategy.Indentation) { it.trim() }?.let { return it }
        punctuation(content, old, new)?.let { return it }
        return Located.NotFound(similarLines(content, old))
    }

    /** replace_all 只认逐字相同：一次改很多处时，任何放宽都可能把不该改的一并改掉 */
    fun locateAll(content: String, old: String): List<Int> = occurrences(content, old)

    private fun exact(content: String, old: String, new: String): Located? {
        val found = occurrences(content, old)
        return when (found.size) {
            0 -> null
            1 -> Located.Found(found[0], found[0] + old.length, new, MatchStrategy.Exact)
            else -> Located.Ambiguous(found.map { lineOf(content, it) }, MatchStrategy.Exact)
        }
    }

    /**
     * 按整行比较（每行先过 [normalize]）。old_string 必须覆盖整行：只是某一行里的一个片段时逐字匹配就能找到，
     * 找不到说明差在别处，按行放宽也帮不上忙。
     */
    private fun lineBased(
        content: String,
        old: String,
        new: String,
        strategy: MatchStrategy,
        normalize: (String) -> String,
    ): Located? {
        val oldLines = old.removeSuffix("\n").split('\n')
        if (oldLines.all { it.isBlank() }) return null
        val lines = Lines(content)
        val target = oldLines.map(normalize)
        val hits = (0..lines.count - oldLines.size).filter { i ->
            target.indices.all { k -> normalize(lines.text(i + k)) == target[k] }
        }
        return when (hits.size) {
            0 -> null
            1 -> {
                val first = hits[0]
                val last = first + oldLines.size - 1
                val start = lines.start(first)
                // old 以换行结尾时连同最后一行的换行一起替换，否则只替换到行尾
                val end = if (old.endsWith("\n")) lines.endWithNewline(last) else lines.end(last)
                val replacement = if (strategy == MatchStrategy.Indentation) {
                    reindent(new, from = indentOf(oldLines), to = indentOf((first..last).map(lines::text)))
                } else {
                    new
                }
                Located.Found(start, end, replacement, strategy)
            }
            else -> Located.Ambiguous(hits.map { it + 1 }, strategy)
        }
    }

    /** 标点归一化是逐字符一对一替换，长度不变，所以归一化后的下标可以直接用在原文上 */
    private fun punctuation(content: String, old: String, new: String): Located? {
        val normalizedOld = normalizePunctuation(old)
        if (normalizedOld == old && normalizePunctuation(content) == content) return null
        val found = occurrences(normalizePunctuation(content), normalizedOld)
        return when (found.size) {
            0 -> null
            1 -> Located.Found(found[0], found[0] + old.length, new, MatchStrategy.Punctuation)
            else -> Located.Ambiguous(found.map { lineOf(content, it) }, MatchStrategy.Punctuation)
        }
    }

    /** 一对一替换（长度不变）。用 \u 转义写：这些字符肉眼分不出来，写成字面量没人能核对 */
    private val PUNCTUATION = mapOf(
        // 弯单引号 → '
        '\u2018' to '\'', '\u2019' to '\'', '\u201A' to '\'', '\u201B' to '\'',
        // 弯双引号 → "
        '\u201C' to '"', '\u201D' to '"', '\u201E' to '"', '\u201F' to '"',
        // 连字符、不换行连字符、数字线、短破折号、长破折号、减号 → -
        '\u2010' to '-', '\u2011' to '-', '\u2012' to '-', '\u2013' to '-', '\u2014' to '-', '\u2212' to '-',
        // 不换行空格、窄不换行空格、全角空格 → 普通空格
        '\u00A0' to ' ', '\u202F' to ' ', '\u3000' to ' ',
    )

    private fun normalizePunctuation(text: String): String =
        buildString(text.length) { text.forEach { append(PUNCTUATION[it] ?: it) } }

    /** 与 old_string 第一行（去掉首尾空白、至少 3 个字符）相似的行，最多 5 个 */
    private fun similarLines(content: String, old: String): List<Int> {
        val probe = old.lineSequence().map { it.trim() }.firstOrNull { it.length >= 3 } ?: return emptyList()
        val needle = normalizePunctuation(probe)
        val lines = Lines(content)
        return (0 until lines.count)
            .filter { normalizePunctuation(lines.text(it)).contains(needle) }
            .take(5)
            .map { it + 1 }
    }

    private fun occurrences(content: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = content.indexOf(needle, from)
            if (i < 0) return found
            found += i
            from = i + needle.length
        }
    }

    fun lineOf(content: String, index: Int): Int = content.subSequence(0, index).count { it == '\n' } + 1

    private fun indentOf(lines: List<String>): String =
        lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()

    /** 把 new_string 里每行开头的 [from] 缩进换成 [to]；缩进不以 [from] 开头的行（及空行）原样保留 */
    private fun reindent(text: String, from: String, to: String): String {
        if (from == to) return text
        return text.split('\n').joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith(from) -> to + line.removePrefix(from)
                else -> line
            }
        }
    }

    /** 按行切分并记住每行在原文里的起止下标 */
    private class Lines(private val content: String) {
        private val starts: List<Int> = buildList {
            add(0)
            content.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        val count: Int = if (content.endsWith("\n")) starts.size - 1 else starts.size

        fun start(line: Int): Int = starts[line]
        fun end(line: Int): Int = if (line + 1 < starts.size) starts[line + 1] - 1 else content.length
        fun endWithNewline(line: Int): Int = if (line + 1 < starts.size) starts[line + 1] else content.length
        fun text(line: Int): String = content.substring(start(line), end(line))
    }
}
