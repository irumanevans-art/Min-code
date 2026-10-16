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
    /** 仅记录引号外的通配，避免把字面 '*.ts' 当成可能扩成选项的输入。 */
    val hasUnquotedGlob: Boolean = false,
    val fromXargs: Boolean = false,
    /** 保留外层调用用于规则原文匹配，内层 argv 仍负责 deny 与底线分析。 */
    val outerForms: List<List<String>> = emptyList(),
    val unquotedGlobIndices: Set<Int> = emptySet(),
    /** 命令前的赋值词（去掉引号后的 `NAME=value`），[original] 不含它们 */
    val assignments: List<String> = emptyList(),
    /**
     * 剥掉的前缀里改变「这条命令怎么跑」的部分：不在安全名单上的赋值（含 env / sudo 后面的，见 [ShellVariables]）、
     * sudo / doas、会建锁文件的 flock。非空时这条命令不算只读，允许规则只认带着这些前缀的原文；
     * 拒绝 / 询问规则照旧按内层命令匹配——加个前缀躲不开拒绝。
     */
    val riskyPrefix: List<String> = emptyList(),
    /** 外层调用连同它前面的赋值（[outerForms] 不带赋值，给拒绝 / 询问规则用） */
    val outerWritten: List<List<String>> = emptyList(),
) {
    val program: String get() = argv.firstOrNull()?.substringAfterLast('/').orEmpty()
    val text: String get() = argv.joinToString(" ")
    val originalText: String get() = original.joinToString(" ")
    private val written: String get() = (assignments + original).joinToString(" ")

    /** 拒绝 / 询问规则对的写法：内层命令、包装前的原文、外层调用，都去掉前置赋值——加前缀躲不开拒绝 */
    val denyForms: List<String>
        get() = listOf(text, originalText) + outerForms.map { it.joinToString(" ") }

    /** 允许规则对的写法：[riskyPrefix] 非空时只认带着赋值的原文与外层调用（前缀也得批准），否则同 [denyForms] 再加带赋值的原文 */
    val allowForms: List<String>
        get() {
            val outer = outerWritten.map { it.joinToString(" ") }
            return if (riskyPrefix.isNotEmpty()) listOf(written) + outer else denyForms + written + outer
        }
}

data class Redirect(val op: String, val target: String) {
    val readsFile: Boolean get() = op.contains('<') && !target.startsWith("&") && target != "/dev/null"
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

    /**
     * 包装命令：剥掉它们，看被包装的那条命令（选项语法在 [WrapperSyntax]）。sudo / doas 也剥——不剥的话
     * `sudo bash -c 'rm -rf /'`、`sudo timeout 5 rm -rf /` 的内层命令底线看不见；换身份这件事记进 [SimpleCommand.riskyPrefix]。
     */
    private val WRAPPERS = setOf(
        "timeout", "nice", "nohup", "time", "command", "env", "exec", "stdbuf", "xargs", "setsid", "flock", "watch", "busybox",
        "sudo", "doas",
    )

    /** 这些包装本身就改变命令怎么跑：换身份、建锁文件 */
    private val RISKY_WRAPPERS = setOf("sudo", "doas", "flock")

    /** 包装后面可以直接跟 `NAME=value`，效果同前置赋值 */
    private val ASSIGNING_WRAPPERS = setOf("env", "sudo")
    private val RESERVED = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "function", "select", "[[", "]]", "!", "{", "}", "coproc",
    )
    private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

    fun analyze(script: String): ShellAnalysis = analyze(script, depth = 0)

    /** 规则与命令共用词法；这里只剥包装前缀，不把 sh -c 的正文当成已获批准的内层命令。 */
    internal fun autoRuleExecutable(spec: String): String? {
        val tokens = (Tokenizer(spec).tokenize() as? TokenizeResult.Ok)?.tokens ?: return null
        if (tokens.any { it !is Token.Word }) return null
        return autoRuleProgram(tokens.filterIsInstance<Token.Word>().map { it.text })
    }

    private fun analyze(script: String, depth: Int): ShellAnalysis {
        val tokens = when (val t = Tokenizer(script).tokenize()) {
            is TokenizeResult.Failed -> return ShellAnalysis.Untrusted(t.reason)
            is TokenizeResult.Ok -> t.tokens
        }
        val commands = mutableListOf<SimpleCommand>()
        val groups = splitCommands(tokens) ?: return ShellAnalysis.Untrusted("连接符前后缺少命令")
        for (group in groups) {
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

    private fun splitCommands(tokens: List<Token>): List<List<Token>>? {
        val groups = mutableListOf<MutableList<Token>>(mutableListOf())
        var needsRight = false
        for (t in tokens) {
            if (t is Token.Separator) {
                val required = t.op in setOf("&&", "||", "|", "|&")
                if (t.op == ";;" || groups.last().isEmpty() && t.op != "\n") return null
                if (needsRight && t.op != "\n") return null
                needsRight = required || needsRight
                if (groups.last().isNotEmpty()) groups.add(mutableListOf())
            } else {
                groups.last().add(t)
                needsRight = false
            }
        }
        if (needsRight) return null
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
                is Token.Separator -> Unit
            }
            i++
        }
        val firstCommand = words.indexOfFirst { !it.assignment }
        val prefix = if (firstCommand < 0) words else words.take(firstCommand)
        // 只有赋值没有命令的一行本身什么都不执行，但给 PATH、IFS、BASH_ARGV0 这类变量赋值会改变同一行后面的命令
        if (firstCommand < 0) {
            prefix.firstNotNullOfOrNull { ShellVariables.standaloneRisk(it.text) }?.let { return Built.Fail(it) }
            return Built.Many(emptyList())
        }
        prefix.firstNotNullOfOrNull { ShellVariables.integerRisk(it.text) }?.let { return Built.Fail(it) }
        val argv = words.drop(firstCommand)
        if (argv.first().text in RESERVED && !argv.first().quotedAny) return Built.Fail("含控制结构（${argv.first().text}）")

        val original = argv.map { it.text }
        val u = unwrap(argv) ?: return Built.Fail("包装命令的写法认不出")
        u.assignments.firstNotNullOfOrNull { ShellVariables.integerRisk(it.text) }?.let { return Built.Fail(it) }
        val assignments = prefix.map { it.text }
        val riskyPrefix = (prefix + u.assignments).filterNot { ShellVariables.isSafePrefix(it.text, it.expansion) }.map { it.text } + u.risks
        val wrapped = argv.take(argv.size - u.words.size)
        val fromXargs = wrapped.any { it.text.substringAfterLast('/') == "xargs" }
        val unwrapped = u.words
        // 剥开内层脚本（bash -c、watch）后：外层的重定向、前缀与 xargs 标记都要带到每条内层命令上
        fun nested(script: Token.Word, what: String): Built {
            if (script.expansion) return Built.Fail("$what 的脚本含变量")
            if (depth >= MAX_NESTING) return Built.Fail("$what 嵌套过深")
            return when (val inner = analyze(script.text, depth + 1)) {
                is ShellAnalysis.Untrusted -> Built.Fail(inner.reason)
                is ShellAnalysis.Parsed -> Built.Many(inner.commands.map {
                    it.copy(
                        redirects = it.redirects + redirects,
                        outerForms = it.outerForms + listOf(original),
                        outerWritten = it.outerWritten + listOf(assignments + original),
                        // 剥开的 xargs 仍会从 stdin 补参数；再剥 shell 不能丢掉已有递归删除底线。
                        fromXargs = it.fromXargs || fromXargs,
                        riskyPrefix = riskyPrefix + it.riskyPrefix,
                    )
                })
            }
        }
        u.watchScript?.let { return nested(it, "watch") }
        if (unwrapped.isEmpty()) {
            return Built.Many(
                listOf(SimpleCommand(original, redirects, argv.any { it.expansion }, original, assignments = assignments, riskyPrefix = riskyPrefix)),
            )
        }
        val program = unwrapped.first().text.substringAfterLast('/')
        if (program == "eval" || program == "source" || program == ".") return Built.Fail("$program 执行的内容静态看不到")

        // bash -c "…"、bash -lc "…"、bash -euo pipefail -c "…"：剥开再查里面那段脚本
        if (program in WrapperSyntax.SHELLS) {
            when (val invocation = WrapperSyntax.shellInvocation(unwrapped.drop(1).map { it.text })) {
                null -> return Built.Fail(
                    if (unwrapped.drop(1).any { it.text == "-c" }) "$program -c 后面缺少脚本" else "$program 的选项认不出",
                )
                is WrapperSyntax.ShellInvocation.Inline -> return nested(unwrapped[1 + invocation.script], "$program -c")
                else -> Unit
            }
        }
        return Built.Many(
            listOf(
                SimpleCommand(
                    argv = unwrapped.map { it.text },
                    redirects = redirects,
                    hasExpansion = unwrapped.any { it.expansion },
                    original = original,
                    hasUnquotedGlob = unwrapped.any { it.unquotedGlob },
                    fromXargs = fromXargs,
                    unquotedGlobIndices = unwrapped.indices.filter { unwrapped[it].unquotedGlob }.toSet(),
                    assignments = assignments,
                    riskyPrefix = riskyPrefix,
                ),
            ),
        )
    }

    /**
     * 剥完包装剩下的：[words] 是被包装的命令；[assignments] 是 env / sudo 后面的 `NAME=value`；
     * [risks] 是本身改变命令怎么跑的包装（sudo、flock）；[watchScript] 非空时 watch 会把它交给 `sh -c`。
     */
    private class Unwrapped(
        val words: List<Token.Word>,
        val assignments: List<Token.Word>,
        val risks: List<String>,
        val watchScript: Token.Word? = null,
    )

    /** 剥掉包装命令及其选项；返回 null = 认不出包装的写法 */
    private fun unwrap(argv: List<Token.Word>): Unwrapped? {
        var rest = argv
        val assignments = mutableListOf<Token.Word>()
        val risks = mutableListOf<String>()
        while (rest.isNotEmpty()) {
            val head = rest.first()
            if (head.quotedAny) break
            val name = head.text.substringAfterLast('/')
            if (name !in WRAPPERS) break
            rest = rest.drop(1)
            // 带参数的 xargs 会改变内层 argv，不能拿内层规则替它作批准；command -v / -V 是查询，不执行后面的命令
            if (name == "xargs" && (rest.isEmpty() || rest.first().text.startsWith("-"))) return Unwrapped(listOf(head) + rest, assignments, risks)
            if (name == "xargs") continue
            if (name == "command" && rest.firstOrNull()?.text in setOf("-v", "-V")) return Unwrapped(listOf(head) + rest, assignments, risks)
            val texts = rest.map { it.text }
            val skipped = WrapperSyntax.skipOptions(name, texts) ?: return null
            // env -C 目录 / sudo -D 目录：内层命令里的相对路径不再相对当前目录，路径判定会看错
            if (WrapperSyntax.changesDirectory(name, texts.take(skipped))) return null
            if (name in RISKY_WRAPPERS) risks += name
            // watch 不带 -x 时把剩下的参数用空格拼起来交给 sh -c：按内联脚本分析
            if (name == "watch" && texts.take(skipped).none { it == "-x" || it == "--exec" }) {
                val script = rest.drop(skipped)
                if (script.isEmpty()) return null
                val joined = Token.Word(script.joinToString(" ") { it.text }, quotedAny = true, expansion = script.any { it.expansion })
                return Unwrapped(emptyList(), assignments, risks, watchScript = joined)
            }
            rest = rest.drop(skipped)
            if (name in ASSIGNING_WRAPPERS) {
                // 包装自己解析这些参数，引号不影响它们是不是赋值
                val assigned = rest.takeWhile { ASSIGNMENT.matches(it.text) }
                assignments += assigned
                rest = rest.drop(assigned.size)
            }
        }
        return Unwrapped(rest, assignments, risks)
    }

    // ---- 词法 ----

    private sealed interface Token {
        /**
         * [quotedAny]：词里有引号或转义（引号里的东西不是关键字）；[expansion]：含未展开的变量；
         * [assignment]：`NAME=…` 且等号之前没有引号（`FOO='a b'` 是赋值，`'FOO'=1` 不是）
         */
        data class Word(val text: String, val quotedAny: Boolean, val expansion: Boolean, val assignment: Boolean = false, val unquotedGlob: Boolean = false) : Token
        data class RedirectOp(val op: String) : Token
        data class Separator(val op: String) : Token
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
        private var unquotedGlob = false

        /** 词里第一个引号 / 转义出现的位置；判断赋值要看等号之前有没有引号 */
        private var quoteStart = -1
        private var i = 0

        fun tokenize(): TokenizeResult {
            while (i < s.length) {
                val c = s[i]
                val fail = when {
                    c == ' ' || c == '\t' -> { endWord(); i++; null }
                    c == '\n' || c == '\r' -> { endWord(); separator("\n"); i++; null }
                    c == '#' && !inWord -> { skipComment(); null }
                    c == '\'' -> singleQuoted()
                    c == '"' -> doubleQuoted()
                    c == '\\' -> escaped()
                    c == '`' -> "含反引号命令替换"
                    c == '$' -> dollar()
                    c == ';' || c == '&' || c == '|' -> operator()
                    c == '<' || c == '>' -> redirect()
                    c == '(' || c == ')' -> "含子 shell 或进程替换"
                    else -> { if (c in "*?[") unquotedGlob = true; append(c); i++; null }
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
                tokens += Token.Word(text, quoted, expansion, assignment, unquotedGlob)
            }
            word.setLength(0)
            inWord = false
            quoted = false
            expansion = false
            unquotedGlob = false
            quoteStart = -1
        }

        private fun markQuoted() {
            if (quoteStart < 0) quoteStart = word.length
            quoted = true
            inWord = true
        }

        private fun separator(op: String) {
            if (op != "\n" || tokens.lastOrNull() != Token.Separator("\n")) tokens += Token.Separator(op)
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
            val op = if (two in setOf("&&", "||", "|&", ";;")) two else s[i].toString()
            when {
                two == "&&" || two == "||" || two == "|&" || two == ";;" -> i += 2
                two == "&>" -> return redirect()
                else -> i++
            }
            separator(op)
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
