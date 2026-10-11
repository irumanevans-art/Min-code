package dev.min.agent.otel

/**
 * 工具名与 Bash 命令的「安全形式」：属性里不含用户自选内容（MCP 服务器名、命令参数……），默认就能导出。
 * 原文（monitoring-usage 的 `claude_code.tool` 一节）：
 * - `tool_name_safe`：不含用户自选内容的工具名；MCP 工具记为 `mcp_other`，playwright 的 `browser_*` 这类固定形状除外；
 * - `bash_command_class`：Bash 第一个程序的类别（`vcs`、`package_manager`、`other`、`unparsed`）；
 * - `bash_argv0`：第一个程序在固定名单里就是它的名字（如 `git`、`npm`），否则 `other`。
 * 原文没有公布名单与类别全集；下面的名单是 Min 自己定的，只会增不会把已有的名字挪到别的类别。
 */
internal object OtelToolNames {
    private val PLAYWRIGHT = Regex("mcp__playwright__browser_[a-z_]+")

    fun safe(name: String): String = when {
        PLAYWRIGHT.matches(name) -> name
        name.startsWith("mcp__") -> "mcp_other"
        else -> name
    }
}

internal object OtelBash {
    class Classified(val commandClass: String, val argv0: String)

    private val VCS = setOf("git", "hg", "svn")
    private val PACKAGE_MANAGERS = setOf(
        "npm", "npx", "pnpm", "yarn", "bun", "pip", "pip3", "pipx", "uv", "poetry", "cargo", "gem", "bundle",
        "brew", "apt", "apt-get", "dnf", "yum", "pacman", "apk", "composer", "conda", "nuget",
    )

    /** 其余在名单里的常见程序：类别是 other，但 argv0 记它的名字 */
    private val KNOWN = setOf(
        "ls", "cat", "grep", "rg", "find", "cd", "echo", "sed", "awk", "curl", "wget", "docker", "kubectl", "make",
        "python", "python3", "node", "java", "gradle", "mvn", "go", "rustc", "tsc", "pytest", "ssh", "scp", "rsync",
        "tar", "rm", "cp", "mv", "mkdir", "touch", "chmod", "sleep", "head", "tail", "sort", "diff", "which", "pwd",
        "test", "true", "false", "sh", "bash",
    )

    /** 不改变「真正在跑什么」的包装：看它后面的程序 */
    private val WRAPPERS = setOf("sudo", "env", "time", "nohup", "exec", "command", "nice")

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")

    private val UNPARSED = Classified("unparsed", "other")

    fun classify(command: String): Classified {
        val first = firstProgram(command) ?: return UNPARSED
        val base = first.substringAfterLast('/').substringAfterLast("\\").removeSuffix(".exe").lowercase()
        if (base.isEmpty()) return UNPARSED
        val cls = when (base) {
            in VCS -> "vcs"
            in PACKAGE_MANAGERS -> "package_manager"
            else -> "other"
        }
        val argv0 = if (base in VCS || base in PACKAGE_MANAGERS || base in KNOWN) base else "other"
        return Classified(cls, argv0)
    }

    /** 第一个真正的程序词；解析不了（引号没闭合、开头是子 shell / 展开 / 关键字）返回 null */
    private fun firstProgram(command: String): String? {
        var i = 0
        val s = command
        fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }
        repeat(MAX_WORDS) {
            skipSpaces()
            if (i >= s.length) return null
            val word = readWord(s, i) ?: return null
            i = word.second
            val text = word.first
            if (ASSIGNMENT.matches(text)) return@repeat // 前缀的环境变量赋值
            if (text in WRAPPERS) return@repeat
            if (text.startsWith("-") && i > 0) return@repeat // 包装的选项（sudo -E ……）
            return text
        }
        return null
    }

    /** 读一个词：到空白或 shell 元字符为止；带引号的整段当一个词；含展开（$ 与反引号）或开头是括号 / 关键字则解析不了 */
    private fun readWord(s: String, start: Int): Pair<String, Int>? {
        var i = start
        val sb = StringBuilder()
        if (s[i] in "({!`") return null
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\'' || c == '"' -> {
                    val end = s.indexOf(c, i + 1)
                    if (end < 0) return null
                    val inner = s.substring(i + 1, end)
                    if (c == '"' && ('$' in inner || '`' in inner)) return null
                    sb.append(inner)
                    i = end + 1
                }
                c == '$' || c == '`' -> return null
                c.isWhitespace() || c in ";|&<>()" -> break
                else -> { sb.append(c); i++ }
            }
        }
        val word = sb.toString()
        if (word in KEYWORDS) return null
        return word to i
    }

    private val KEYWORDS = setOf("if", "for", "while", "until", "case", "function", "select", "then", "do", "{", "}", "[[")
    private const val MAX_WORDS = 16
}
