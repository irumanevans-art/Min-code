package dev.min.agent.ext.skills

/**
 * skill / command 正文里的参数替换。语义照 Claude Code 文档（skills.md「Argument substitution」），
 * 边角照 Claude Code 2.1.286 / 2.1.301 二进制里的实现（同样的替换顺序、同样的「值不再被二次展开」）：
 *
 * - `$ARGUMENTS`：参数原文（照用户敲的，不拆不改）；`$ARGUMENTS[N]` 与 `$N`：第 N 个参数（从 0 起，shell 风格引号）；
 *   `$名字`：frontmatter `arguments` 里声明的名字，按位置对应。
 * - 越界的 `$ARGUMENTS[N]` / `$N` **原样留在正文里**且不算「收到了参数」；越界的具名占位符展开成空串且算收到了。
 * - 没有任何占位符收到参数、而参数非空时，在正文末尾追加 `\n\nARGUMENTS: <原文>`，模型才看得到用户敲了什么。
 * - `\$1`、`\$ARGUMENTS`、`\$名字` 里的单个反斜杠转义：输出字面的 `$…`；`\\$1` 的反斜杠不是转义，`$1` 照常展开。
 * - 参数值里即使含 `$1` 之类也按字面插入，不递归展开——所以这里单趟从左到右扫，插入的值不会被再扫。
 * - [args] 为 null（调用方没给参数）：整段原样返回，连转义也不处理（与 CC 一致）；空串则照常处理。
 *
 * 为什么不用正则全局替换：值里的 `$` 与后一步替换会串味，单趟扫描天然没有这个问题，也不需要哨兵字符。
 */
object SkillArguments {
    /**
     * 把参数原文拆成参数列表：空白分隔，单引号 / 双引号包住的算一个（双引号里 `\"`、`\\`、`\$`、`` \` `` 转义），
     * 引号外的反斜杠转义下一个字符；引号没闭合就吃到末尾。拆不出任何参数（空串 / 纯空白）返回空列表。
     * 不做变量、通配、管道等 shell 展开——参数是给模型读的文字，不是命令。
     */
    fun parse(args: String?): List<String> {
        if (args.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inToken = false
        var i = 0
        while (i < args.length) {
            val c = args[i]
            when {
                c.isWhitespace() -> {
                    if (inToken) out += cur.toString()
                    cur.setLength(0)
                    inToken = false
                }
                c == '\'' -> {
                    inToken = true
                    val end = args.indexOf('\'', i + 1).let { if (it < 0) args.length else it }
                    cur.append(args, i + 1, end)
                    i = end
                }
                c == '"' -> {
                    inToken = true
                    i++
                    while (i < args.length && args[i] != '"') {
                        val d = args[i]
                        if (d == '\\' && i + 1 < args.length && args[i + 1] in DOUBLE_QUOTE_ESCAPABLE) {
                            cur.append(args[i + 1])
                            i += 2
                        } else {
                            cur.append(d)
                            i++
                        }
                    }
                }
                c == '\\' && i + 1 < args.length -> {
                    inToken = true
                    cur.append(args[++i])
                }
                else -> {
                    inToken = true
                    cur.append(c)
                }
            }
            i++
        }
        if (inToken) out += cur.toString()
        return out
    }

    /**
     * 替换 [content] 里的参数占位符。[argNames] 是 frontmatter `arguments` 声明的名字（空白名与纯数字名先被滤掉，位置按滤掉之后算——与 CC 一致；
     * 超过 [MAX_NAME_LENGTH] 字符的名字不参与替换，留下的名字最多取前 [MAX_NAMES] 个，其余的占位符原样留在正文里）；
     * [appendIfUnused] 为真且没有占位符收到参数时，末尾追加 `ARGUMENTS: …`。
     */
    fun substitute(content: String, args: String?, argNames: List<String> = emptyList(), appendIfUnused: Boolean = true): String {
        if (args == null) return content
        val values = parse(args)
        // 顺序照 CC 2.1.301：先滤空白名与纯数字名并按剩下的位置编号，再丢掉超过 64 字符的名字（它的位置留空、占位符原样），
        // 然后只取前 64 个，最后名字长的先试：`$issue_id` 不能被名为 `issue` 的占位符吃掉前缀（CC 同样按长度倒序）
        val named = argNames.filter { it.isNotBlank() && !it.all(Char::isDigit) }
            .mapIndexed { index, name -> name to index }
            .filter { it.first.length <= MAX_NAME_LENGTH }
            .take(MAX_NAMES)
            .sortedByDescending { it.first.length }
        var received = false
        val out = StringBuilder(content.length + args.length)
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length && content[i + 1] == '$' && (i == 0 || content[i - 1] != '\\') &&
                startsPlaceholder(content, i + 2, named)
            ) {
                out.append('$')
                i += 2
                continue
            }
            if (c != '$') {
                out.append(c)
                i++
                continue
            }
            val start = i + 1
            val hit = matchNamed(content, start, named)
            if (hit != null) {
                received = true
                out.append(values.getOrNull(hit.second).orEmpty())
                i = start + hit.first.length
                continue
            }
            if (content.startsWith("ARGUMENTS", start)) {
                val after = start + "ARGUMENTS".length
                val index = indexAfter(content, after, '[', ']')
                if (index != null) {
                    // 越界：整段 `$ARGUMENTS[N]` 原样保留，也不让里头的 `$ARGUMENTS` 再被展开
                    val value = values.getOrNull(index.first)
                    if (value == null) out.append(content, i, index.second) else { received = true; out.append(value) }
                    i = index.second
                    continue
                }
                received = true
                out.append(args)
                i = after
                continue
            }
            val digits = digitsAt(content, start)
            if (digits > start && (digits >= content.length || !isWord(content[digits]))) {
                val value = content.substring(start, digits).toIntOrNull()?.let(values::getOrNull)
                if (value == null) out.append(content, i, digits) else { received = true; out.append(value) }
                i = digits
                continue
            }
            out.append(c)
            i++
        }
        if (!received && appendIfUnused && args.isNotEmpty()) out.append("\n\nARGUMENTS: ").append(args)
        return out.toString()
    }

    /** [from] 起是不是一个会被展开的占位符的开头：数字、`ARGUMENTS`，或某个声明的名字（后面不再接 `[` 与单词字符） */
    private fun startsPlaceholder(s: String, from: Int, named: List<Pair<String, Int>>): Boolean =
        (from < s.length && s[from].isAsciiDigit()) || s.startsWith("ARGUMENTS", from) || matchNamed(s, from, named) != null

    private fun matchNamed(s: String, from: Int, named: List<Pair<String, Int>>): Pair<String, Int>? =
        named.firstOrNull { (name, _) ->
            s.startsWith(name, from) && (from + name.length >= s.length || s[from + name.length].let { it != '[' && !isWord(it) })
        }

    /** `[` + 数字 + `]` 紧跟在 [from] 处：返回（数字，`]` 之后的位置）；形状不对返回 null */
    private fun indexAfter(s: String, from: Int, open: Char, close: Char): Pair<Int, Int>? {
        if (from >= s.length || s[from] != open) return null
        val end = digitsAt(s, from + 1)
        if (end == from + 1 || end >= s.length || s[end] != close) return null
        val n = s.substring(from + 1, end).toIntOrNull() ?: return Int.MAX_VALUE to end + 1
        return n to end + 1
    }

    private fun digitsAt(s: String, from: Int): Int {
        var j = from
        while (j < s.length && s[j].isAsciiDigit()) j++
        return j
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun isWord(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    /** 具名参数最多填几个（CC 2.1.301：名字多到成千上万时曾卡住数秒，二进制里是 `.slice(0, 64)`） */
    const val MAX_NAMES = 64

    /** 单个参数名最长多少字符（UTF-16 单元，与 JS 的 `.length` 一致）；超过的名字整个跳过，不截断（二进制里是 `name.length <= 64` 的过滤） */
    const val MAX_NAME_LENGTH = 64

    private val DOUBLE_QUOTE_ESCAPABLE = setOf('"', '\\', '$', '`')
}
