package dev.min.agent.policy

/**
 * 一条简单命令：去掉了前置的变量赋值与无害的包装（timeout / nice / nohup …）之后的 argv，以及它的重定向。
 * [text] 是规则匹配用的规范化文本（argv 以单个空格连接）；[original] 保留包装前的形态，拒绝 / 询问规则两种都对一遍。
 */
data class SimpleCommand(
    val argv: List<String>,
    val redirects: List<Redirect> = emptyList(),
    /** 有参数含未展开的变量（`$X`、`${X}`）：值在执行时才知道 */
    val hasExpansion: Boolean = false,
    val original: List<String> = argv,
) {
    val program: String get() = argv.firstOrNull()?.substringAfterLast('/').orEmpty()
    val text: String get() = argv.joinToString(" ")
    val originalText: String get() = original.joinToString(" ")
}

data class Redirect(val op: String, val target: String) {
    /** 往文件里写（`>`、`>>`、`&>`、`>|`），目标不是 /dev/null，也不是复制文件描述符（`2>&1`） */
    val writesFile: Boolean
        get() = op.contains('>') && !target.startsWith("&") && target != "/dev/null"
}

sealed interface ShellAnalysis {
    data class Parsed(val commands: List<SimpleCommand>) : ShellAnalysis

    /** 静态看不透（命令替换、eval、here-doc、控制结构……）：解析失败 = 不信任 = 问人 */
    data class Untrusted(val reason: String) : ShellAnalysis
}

/**
 * Bash 命令的保守分析器（docs/agent/04-子系统设计.md §4.4「命令分析」）。
 *
 * 为什么自己写而不用 tree-sitter：在 Android 上不用带原生库；而且这里的目标不是「完整解析 bash」，
 * 而是**只认得出能安全判断的那一小部分**，其余一律交给人——认不出就说认不出，比认错安全。
 * 只保留这一套解析器：Claude Code 新旧两套并跑、旧的仍参与决策，正是回车符绕过的来源。
 */
object ShellAnalyzer {
    private const val MAX_NESTING = 2

    /** 包装命令：剥掉它们，看被包装的那条命令 */
    private val WRAPPERS = setOf("timeout", "nice", "nohup", "time", "command", "env", "exec", "stdbuf")
    private val SHELLS = setOf("bash", "sh", "dash", "zsh")
    private val RESERVED = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "function", "select", "[[", "]]", "!", "{", "}", "coproc",
    )
    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

    fun analyze(script: String): ShellAnalysis = analyze(script, depth = 0)

    private fun analyze(script: String, depth: Int): ShellAnalysis {
        val tokens = when (val t = Tokenizer(script).tokenize()) {
            is TokenizeResult.Failed -> return ShellAnalysis.Untrusted(t.reason)
            is TokenizeResult.Ok -> t.tokens
        }
        val commands = mutableListOf<SimpleCommand>()
        for (group in splitCommands(tokens)) {
            when (val one = buildCommand(group, depth)) {
                is Built.Fail -> return ShellAnalysis.Untrusted(one.reason)
                is Built.Many -> commands += one.commands
            }
        }
        return ShellAnalysis.Parsed(commands)
    }

    private sealed interface Built {
        data class Many(val commands: List<SimpleCommand>) : Built
        data class Fail(val reason: String) : Built
    }

    private fun splitCommands(tokens: List<Token>): List<List<Token>> {
        val groups = mutableListOf<MutableList<Token>>(mutableListOf())
        for (t in tokens) {
            if (t is Token.Separator) groups.add(mutableListOf()) else groups.last().add(t)
        }
        return groups.filter { it.isNotEmpty() }
    }

    private fun buildCommand(tokens: List<Token>, depth: Int): Built {
        val words = mutableListOf<Token.Word>()
        val redirects = mutableListOf<Redirect>()
        var i = 0
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                is Token.Word -> words += t
                is Token.RedirectOp -> {
                    val target = tokens.getOrNull(i + 1) as? Token.Word ?: return Built.Fail("重定向 ${t.op} 后面缺少目标")
                    if (target.expansion) return Built.Fail("重定向目标含变量，写到哪里要执行时才知道")
                    redirects += Redirect(t.op, target.text)
                    i++
                }
                Token.Separator -> Unit
            }
            i++
        }
        // 前置的变量赋值（FOO=1 cmd）不影响命令是什么；只有赋值没有命令的一行什么都不执行
        val firstCommand = words.indexOfFirst { !it.assignment }
        if (firstCommand < 0) return Built.Many(emptyList())
        val argv = words.drop(firstCommand)
        if (argv.first().text in RESERVED && !argv.first().quotedAny) return Built.Fail("含控制结构（${argv.first().text}）")

        val original = argv.map { it.text }
        val unwrapped = unwrap(argv) ?: return Built.Fail("包装命令的写法认不出")
        if (unwrapped.isEmpty()) {
            return Built.Many(listOf(SimpleCommand(original, redirects, argv.any { it.expansion }, original)))
        }
        val program = unwrapped.first().text.substringAfterLast('/')
        if (program == "eval" || program == "source" || program == ".") return Built.Fail("$program 执行的内容静态看不到")

        // bash -c "…"：剥开再查里面那段脚本
        if (program in SHELLS) {
            val c = unwrapped.indexOfFirst { it.text == "-c" }
            if (c >= 0) {
                val inner = unwrapped.getOrNull(c + 1) ?: return Built.Fail("$program -c 后面缺少脚本")
                if (inner.expansion) return Built.Fail("$program -c 的脚本含变量")
                if (depth >= MAX_NESTING) return Built.Fail("$program -c 嵌套过深")
                return when (val nested = analyze(inner.text, depth + 1)) {
                    is ShellAnalysis.Untrusted -> Built.Fail(nested.reason)
                    is ShellAnalysis.Parsed -> Built.Many(nested.commands.map { it.copy(redirects = it.redirects + redirects) })
                }
            }
        }
        return Built.Many(
            listOf(
                SimpleCommand(
                    argv = unwrapped.map { it.text },
                    redirects = redirects,
                    hasExpansion = unwrapped.any { it.expansion },
                    original = original,
                ),
            ),
        )
    }

    /** 剥掉包装命令及其选项；返回 null = 认不出包装的写法 */
    private fun unwrap(argv: List<Token.Word>): List<Token.Word>? {
        var rest = argv
        while (rest.isNotEmpty()) {
            val head = rest.first()
            if (head.quotedAny) return rest
            val name = head.text.substringAfterLast('/')
            if (name !in WRAPPERS) return rest
            rest = rest.drop(1)
            when (name) {
                // timeout [选项] 时长 命令
                "timeout" -> {
                    // -s 信号、-k 时长各带一个值
                    while (rest.firstOrNull()?.text?.startsWith("-") == true) {
                        rest = rest.drop(if (rest.first().text in setOf("-s", "-k", "--signal", "--kill-after")) 2 else 1)
                    }
                    if (rest.isEmpty()) return null
                    rest = rest.drop(1)
                }
                "nice" -> rest = when {
                    rest.firstOrNull()?.text == "-n" -> rest.drop(2)
                    rest.firstOrNull()?.text?.matches(Regex("-n?-?\\d+")) == true -> rest.drop(1)
                    else -> rest
                }
                "time" -> rest = rest.dropWhile { it.text == "-p" }
                // command -v / -V 是查询，不执行后面的命令
                "command" -> {
                    if (rest.firstOrNull()?.text in setOf("-v", "-V")) return rest.let { listOf(head) + it }
                    rest = rest.dropWhile { it.text == "-p" }
                }
                "env" -> rest = rest.dropWhile { it.text.startsWith("-") || ASSIGNMENT.matches(it.text) }
                "stdbuf" -> rest = rest.dropWhile { it.text.startsWith("-") }
                "nohup", "exec" -> Unit
            }
        }
        return rest
    }

    // ---- 词法 ----

    private sealed interface Token {
        /**
         * [quotedAny]：词里有引号或转义（引号里的东西不是关键字）；[expansion]：含未展开的变量；
         * [assignment]：`NAME=…` 且等号之前没有引号（`FOO='a b'` 是赋值，`'FOO'=1` 不是）
         */
        data class Word(val text: String, val quotedAny: Boolean, val expansion: Boolean, val assignment: Boolean = false) : Token
        data class RedirectOp(val op: String) : Token
        data object Separator : Token
    }

    private sealed interface TokenizeResult {
        data class Ok(val tokens: List<Token>) : TokenizeResult
        data class Failed(val reason: String) : TokenizeResult
    }

    private class Tokenizer(private val s: String) {
        private val tokens = mutableListOf<Token>()
        private val word = StringBuilder()
        private var inWord = false
        private var quoted = false
        private var expansion = false

        /** 词里第一个引号 / 转义出现的位置；判断赋值要看等号之前有没有引号 */
        private var quoteStart = -1
        private var i = 0

        fun tokenize(): TokenizeResult {
            while (i < s.length) {
                val c = s[i]
                val fail = when {
                    c == ' ' || c == '\t' -> { endWord(); i++; null }
                    c == '\n' || c == '\r' -> { endWord(); separator(); i++; null }
                    c == '#' && !inWord -> { skipComment(); null }
                    c == '\'' -> singleQuoted()
                    c == '"' -> doubleQuoted()
                    c == '\\' -> escaped()
                    c == '`' -> "含反引号命令替换"
                    c == '$' -> dollar()
                    c == ';' || c == '&' || c == '|' -> operator()
                    c == '<' || c == '>' -> redirect()
                    c == '(' || c == ')' -> "含子 shell 或进程替换"
                    else -> { append(c); i++; null }
                }
                if (fail != null) return TokenizeResult.Failed(fail)
            }
            endWord()
            return TokenizeResult.Ok(tokens)
        }

        private fun append(c: Char) {
            word.append(c)
            inWord = true
        }

        private fun endWord() {
            if (inWord) {
                val text = word.toString()
                val eq = text.indexOf('=')
                val assignment = ASSIGNMENT.matches(text) && (quoteStart < 0 || quoteStart > eq)
                tokens += Token.Word(text, quoted, expansion, assignment)
            }
            word.setLength(0)
            inWord = false
            quoted = false
            expansion = false
            quoteStart = -1
        }

        private fun markQuoted() {
            if (quoteStart < 0) quoteStart = word.length
            quoted = true
            inWord = true
        }

        private fun separator() {
            if (tokens.lastOrNull() != Token.Separator) tokens += Token.Separator
        }

        private fun skipComment() {
            while (i < s.length && s[i] != '\n') i++
        }

        private fun singleQuoted(): String? {
            val end = s.indexOf('\'', i + 1)
            if (end < 0) return "单引号没有闭合"
            markQuoted()
            word.append(s, i + 1, end)
            i = end + 1
            return null
        }

        private fun doubleQuoted(): String? {
            markQuoted()
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return null }
                    c == '\\' && i + 1 < s.length && s[i + 1] in "\"\\$`\n" -> { if (s[i + 1] != '\n') word.append(s[i + 1]); i += 2 }
                    c == '`' -> return "双引号里含反引号命令替换"
                    c == '$' -> {
                        val fail = dollar()
                        if (fail != null) return fail
                    }
                    else -> { word.append(c); i++ }
                }
            }
            return "双引号没有闭合"
        }

        private fun escaped(): String? {
            if (i + 1 >= s.length) return "行尾是孤立的反斜杠"
            val next = s[i + 1]
            if (next != '\n') { markQuoted(); append(next) } // 反斜杠加换行 = 续行
            i += 2
            return null
        }

        private fun dollar(): String? {
            val next = s.getOrNull(i + 1)
            return when {
                next == '(' -> "含命令替换或算术展开 \$(…)"
                // $'\x72m' 会被 bash 解码成 rm：字面上看不出真正的命令
                next == '\'' || next == '"' -> "含 \$'…' / \$\"…\" 引号，真正的内容要 bash 解码后才知道"
                next == '{' -> {
                    val end = s.indexOf('}', i + 2)
                    if (end < 0) return "\${ 没有闭合"
                    if (s.substring(i + 2, end).any { it == '(' || it == '`' || it == '$' }) return "参数展开里含嵌套展开"
                    word.append(s, i, end + 1)
                    inWord = true
                    expansion = true
                    i = end + 1
                    null
                }
                next != null && (next.isLetterOrDigit() || next == '_' || next in "@*#?!$-") -> {
                    var j = i + 1
                    if (next.isLetter() || next == '_') {
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    } else {
                        j++
                    }
                    word.append(s, i, j)
                    inWord = true
                    expansion = true
                    i = j
                    null
                }
                else -> { append('$'); i++; null } // 孤立的 $ 就是字面量
            }
        }

        private fun operator(): String? {
            endWord()
            val two = s.substring(i, minOf(i + 2, s.length))
            when {
                two == "&&" || two == "||" || two == "|&" || two == ";;" -> i += 2
                two == "&>" -> return redirect()
                else -> i++
            }
            separator()
            return null
        }

        private fun redirect(): String? {
            // 紧挨着的数字是文件描述符（2>、1>>）：它属于重定向，不是一个词
            val fd = if (inWord && word.all { it.isDigit() } && !quoted) word.toString() else ""
            if (fd.isNotEmpty()) {
                word.setLength(0)
                inWord = false
            } else {
                endWord()
            }
            val rest = s.substring(i)
            val op = listOf("&>>", "&>", ">>", ">|", ">&", "<<<", "<<", "<>", "<&", ">", "<").first { rest.startsWith(it) }
            if (op == "<<" || op == "<<<") return "含 here-doc / here-string"
            if (rest.startsWith("<(") || rest.startsWith(">(")) return "含进程替换"
            i += op.length
            // >&2、2>&1：复制文件描述符，目标并到 op 里（不是文件）
            if (op.endsWith("&")) {
                val j = generateSequence(i) { it + 1 }.takeWhile { it < s.length && (s[it].isDigit() || s[it] == '-') }.lastOrNull()
                val target = if (j == null) "" else s.substring(i, j + 1)
                if (target.isNotEmpty()) {
                    tokens += Token.RedirectOp(fd + op)
                    tokens += Token.Word("&$target", quotedAny = false, expansion = false)
                    i += target.length
                    return null
                }
            }
            tokens += Token.RedirectOp(fd + op)
            return null
        }
    }
}
