package dev.min.code.ui.richtext

/**
 * 公式保护：交给 intellij-markdown 之前，把 LaTeX 公式改写成解析器不会碰的形状。
 *
 * 不保护的话，`$$` 块里的 `a_1 * b_2` 会变斜体，块内以 `-` / `#` / `1.` 开头的行变成列表 / 标题，
 * 行内 `\(a*b*c\)` 的星号被当强调吃掉。这里**不渲染**公式（不引 KaTeX / WebView / jlatexmath，
 * 理由见 [MathBlock]），只保证原文一个字符不改地显示出来，并且看得出是公式。
 *
 * - display 块：独占一行的 `$$` … `$$`、`\[` … `\]`（开闭行 trim 后正好是分隔符、块内至少一行非空），
 *   以及整行 `$$ … $$`。做法是在块**前后各插一行**语言为 [MATH_FENCE_LANG] 的围栏，块本身一个字符不动：
 *   变成 fenced code 之后解析器天然不碰里面，渲染端见到这个语言就走 [MathBlock]。
 *   分隔符留在块里，复制出来就是模型写的原文。单行的 `\[ … \]` 不认：`\[1\]` 是 Markdown 里转义方括号的正常写法。
 * - 行内 `\( … \)`（同一行内成对）包成 code span，渲染端用 [inlineMathSource] 认出来换成公式样式。
 * - 单个 `$ … $` 行内**不处理**：这是编码助手，`$HOME`、`$1`、`$5 and $10` 这种 shell 变量和价格比行内公式多得多，
 *   误判一次就把一段命令说明排成等宽乱码，代价大于偶尔少认一个行内公式。
 * - 已经在 fenced code（``` / ~~~，含缩进、引用 / 列表前缀、更长的围栏）和行内代码里的内容绝不处理。
 *
 * 流式：正文逐 token 变长，每次都整段重跑。这里是纯函数、只看已到的文本；没闭合的块原样不动，闭合那一刻才成块，
 * 已经成块的不会被后来的字推翻。唯一的例外是一个一直没闭合的 `$$` 后来闭合了，会把中间已经成块的 `\[` 一并吞进来——
 * 那本来就是同一个块。成本线性：每行往后第一个闭合行、第一个非空行都在一趟倒序里算好，不会每个开头都往后扫到底。
 */
fun protectMath(text: String): String {
    // 快速路径：绝大多数回答里一个公式都没有，不值得切行
    if (!text.contains("$$") && !text.contains("\\[") && !text.contains("\\(")) return text

    val lines = text.split('\n')
    val n = lines.size
    val trimmed = Array(n) { lines[it].trim() }
    // next*[i]：第 i 行及以后第一条满足条件的行，没有就是 n
    val nextDollar = IntArray(n + 1) { n }
    val nextBracketClose = IntArray(n + 1) { n }
    val nextNonBlank = IntArray(n + 1) { n }
    val nextFence = IntArray(n + 1) { n }
    for (i in n - 1 downTo 0) {
        nextDollar[i] = if (trimmed[i] == DOLLARS) i else nextDollar[i + 1]
        nextBracketClose[i] = if (trimmed[i] == BRACKET_CLOSE) i else nextBracketClose[i + 1]
        nextNonBlank[i] = if (trimmed[i].isNotEmpty()) i else nextNonBlank[i + 1]
        nextFence[i] = if (openingFence(lines[i]) != null) i else nextFence[i + 1]
    }

    fun displayEnd(i: Int): Int {
        val close = when (trimmed[i]) {
            DOLLARS -> nextDollar[i + 1]
            BRACKET_OPEN -> nextBracketClose[i + 1]
            else -> return if (isSingleLineDisplay(trimmed[i])) i else -1
        }
        if (close == n) return -1 // 没闭合：原样不动（流式时就是「还没写完」）
        if (nextNonBlank[i + 1] >= close) return -1 // 空块不算公式
        // 中间隔着一道围栏：公式里不会有 ``` / ~~~ 行，那个「闭合行」其实在后面的代码块里，配上了就把代码块劈成两半
        if (nextFence[i + 1] < close) return -1
        return close
    }

    val out = StringBuilder(text.length + 32)
    var fence: Fence? = null
    var i = 0
    while (i < n) {
        val line = lines[i]
        if (i > 0) out.append('\n')
        val open = fence
        if (open != null) {
            out.append(line)
            if (open.closedBy(line)) fence = null
            i++
            continue
        }
        val opened = openingFence(line)
        if (opened != null) {
            fence = opened
            out.append(line)
            i++
            continue
        }
        val end = displayEnd(i)
        if (end >= 0) {
            // 围栏带上开头行的缩进，列表项里的公式才留在列表项里
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            var longest = 0
            for (k in i..end) longest = maxOf(longest, longestBacktickRun(lines[k]))
            val bar = "`".repeat(maxOf(MIN_FENCE, longest + 1))
            out.append(indent).append(bar).append(MATH_FENCE_LANG).append('\n')
            for (k in i..end) {
                if (k > i) out.append('\n')
                out.append(lines[k])
            }
            out.append('\n').append(indent).append(bar)
            i = end + 1
            continue
        }
        appendWithInlineMath(line, out)
        i++
    }
    return out.toString()
}

/** 公式块改写成的 fenced code 的语言标记。GitHub 也把 ```math 当公式，模型自己这么写的一并走公式样式 */
const val MATH_FENCE_LANG = "math"

/**
 * CODE_SPAN 节点原文（含两头反引号）如果是 [protectMath] 包起来的行内公式，返回公式原文 `\( … \)`；否则 null。
 * 模型自己写的 `` `\(x\)` `` 也会被认成公式——显示的字一样，只是换了样式，无害。
 */
fun inlineMathSource(codeSpan: String): String? {
    val run = codeSpan.takeWhile { it == '`' }.length
    if (run == 0 || codeSpan.length < run * 2) return null
    var inner = codeSpan.substring(run, codeSpan.length - run)
    // CommonMark：两头各有一个空格且不全是空格时各去掉一个
    if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.isNotBlank()) {
        inner = inner.substring(1, inner.length - 1)
    }
    val ok = inner.length > INLINE_OPEN.length + INLINE_CLOSE.length &&
        inner.startsWith(INLINE_OPEN) && inner.endsWith(INLINE_CLOSE)
    return if (ok) inner else null
}

private const val DOLLARS = "$$"
private const val BRACKET_OPEN = "\\["
private const val BRACKET_CLOSE = "\\]"
private const val INLINE_OPEN = "\\("
private const val INLINE_CLOSE = "\\)"

/** CommonMark 的围栏至少三个 */
private const val MIN_FENCE = 3

/** 整行 `$$ … $$`：中间不空、也不再有 `$$`（`$$a$$ 和 $$b$$` 是两个公式夹着字，不是一个块） */
private fun isSingleLineDisplay(t: String): Boolean {
    if (t.length <= DOLLARS.length * 2 || !t.startsWith(DOLLARS) || !t.endsWith(DOLLARS)) return false
    val inner = t.substring(DOLLARS.length, t.length - DOLLARS.length)
    return inner.isNotBlank() && !inner.contains(DOLLARS)
}

private class Fence(val char: Char, val length: Int) {
    /** 收尾：同一种字符、不短于开头、后面只剩空白 */
    fun closedBy(line: String): Boolean {
        val start = fenceStart(line, allowListMarker = false)
        var end = start
        while (end < line.length && line[end] == char) end++
        return end - start >= length && line.substring(end).isBlank()
    }
}

private fun openingFence(line: String): Fence? {
    val start = fenceStart(line, allowListMarker = true)
    val c = line.getOrNull(start) ?: return null
    if (c != '`' && c != '~') return null
    var end = start
    while (end < line.length && line[end] == c) end++
    if (end - start < MIN_FENCE) return null
    // 反引号围栏的 info 串里不能再有反引号，否则这是一行行内代码（```a``` 之类），不是围栏
    if (c == '`' && line.indexOf('`', end) >= 0) return null
    return Fence(c, end - start)
}

/**
 * 跳过行首的缩进和容器前缀（引用的 `>`，开头行还跳列表标记），返回围栏字符该出现的位置。
 * 缩进不设上限：宁可把缩进代码里的 ``` 也当围栏（结果只是少处理一段公式），也不要漏掉列表深处的围栏去改代码。
 */
private fun fenceStart(line: String, allowListMarker: Boolean): Int {
    var k = 0
    while (true) {
        while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
        if (k >= line.length) return k
        if (line[k] == '>') {
            k++
            continue
        }
        if (!allowListMarker) return k
        if (line[k] in "-*+" && line.getOrNull(k + 1) == ' ') {
            k += 2
            continue
        }
        var d = k
        while (d < line.length && line[d].isDigit() && d - k < MAX_LIST_DIGITS) d++
        if (d > k && line.getOrNull(d)?.let { it == '.' || it == ')' } == true && line.getOrNull(d + 1) == ' ') {
            k = d + 2
            continue
        }
        return k
    }
}

/** CommonMark 有序列表的序号最多 9 位 */
private const val MAX_LIST_DIGITS = 9

/**
 * 一行正文：行内代码原样跳过，同一行内成对的 `\( … \)` 包成 code span。
 * 反引号串要比公式里最长的反引号串长一截，否则公式里的反引号会提前把 code span 收掉。
 */
private fun appendWithInlineMath(line: String, out: StringBuilder) {
    if (!line.contains(INLINE_OPEN)) {
        out.append(line)
        return
    }
    // 往后已经找不到同长收尾的反引号串长度：再遇到同长的就不必再扫一遍（保证单行成本不退化成平方）
    val deadRuns = HashSet<Int>()
    // 这一行往后已经没有 `\)` 了
    var closerLeft = true
    var k = 0
    while (k < line.length) {
        val c = line[k]
        if (c == '`') {
            val run = runLength(line, k)
            val close = if (run in deadRuns) -1 else findRun(line, k + run, run)
            val end = if (close < 0) {
                deadRuns += run
                k + run // 没配上的反引号串就是字面的反引号
            } else {
                close + run // 整个 code span 原样抄
            }
            out.append(line, k, end)
            k = end
            continue
        }
        if (c == '\\' && k + 1 < line.length) {
            if (line[k + 1] == '(' && closerLeft) {
                val close = line.indexOf(INLINE_CLOSE, k + 2)
                if (close < 0) {
                    closerLeft = false
                } else if ((k + 2 until close).any { !line[it].isWhitespace() }) {
                    val end = close + INLINE_CLOSE.length
                    val ticks = "`".repeat(longestBacktickRun(line, k, end) + 1)
                    out.append(ticks).append(line, k, end).append(ticks)
                    k = end
                    continue
                }
            }
            // 反斜杠转义成对抄：`\\(` 里第一对是转义的反斜杠，后面的 `(` 不是公式开头
            out.append(c).append(line[k + 1])
            k += 2
            continue
        }
        out.append(c)
        k++
    }
}

private fun runLength(s: String, from: Int): Int {
    var e = from
    while (e < s.length && s[e] == '`') e++
    return e - from
}

/** 从 [from] 起第一个长度正好是 [len] 的反引号串的起点，没有就 -1 */
private fun findRun(s: String, from: Int, len: Int): Int {
    var p = from
    while (p < s.length) {
        if (s[p] == '`') {
            val r = runLength(s, p)
            if (r == len) return p
            p += r
        } else {
            p++
        }
    }
    return -1
}

private fun longestBacktickRun(s: String, from: Int = 0, to: Int = s.length): Int {
    var best = 0
    var cur = 0
    for (i in from until to) {
        if (s[i] == '`') {
            cur++
            if (cur > best) best = cur
        } else {
            cur = 0
        }
    }
    return best
}
