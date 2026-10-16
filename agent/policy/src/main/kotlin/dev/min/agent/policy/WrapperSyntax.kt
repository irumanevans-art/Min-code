package dev.min.agent.policy

/**
 * 包装命令（timeout / env / stdbuf …）的选项语法：哪些选项带值、被包装的命令从第几个词开始。
 * auto 模式暂停规则（[autoRuleProgram]）与命令分析共用这一份表——两边各抄一份，迟早一边认得、一边认错。
 */
internal object WrapperSyntax {
    /**
     * [args] 是包装程序名之后的词。返回被包装的命令前要跳过几个词（选项、选项的值、timeout 的时长、flock 的锁文件）；
     * 认不出的写法返回 null——调用方据此不信任这条命令，而不是猜。
     * 只处理选项本身：env 的 `NAME=value`、command 的 `-v` 查询由调用方按各自的语义处理。
     */
    fun skipOptions(program: String, args: List<String>): Int? {
        val cursor = Cursor(args)
        val ok = when (program) {
            "timeout" -> cursor.options(setOf("-s", "--signal", "-k", "--kill-after"), setOf("--foreground", "--preserve-status", "-v", "--verbose")) &&
                cursor.skip(1)
            "nice" -> if (cursor.peek()?.matches(NICE_SHORT) == true) cursor.skip(1) else cursor.options(setOf("-n", "--adjustment"), emptySet())
            "env" -> cursor.options(setOf("-u", "--unset", "-C", "--chdir"), setOf("-i", "--ignore-environment", "-", "-0", "--null"))
            "time" -> cursor.options(setOf("-f", "--format", "-o", "--output"), setOf("-p", "--portability", "-v", "--verbose", "-a", "--append"))
            "command" -> cursor.options(emptySet(), setOf("-p"))
            "stdbuf" -> cursor.stdbufOptions()
            "setsid" -> cursor.options(emptySet(), setOf("-f", "--fork", "-w", "--wait", "-c", "--ctty"))
            "flock" -> cursor.options(setOf("-w", "--timeout", "-E", "--conflict-exit-code"), setOf("-x", "--exclusive", "-s", "--shared", "-n", "--nonblock", "-o", "--close", "-F", "--no-fork")) &&
                cursor.skip(1)
            "watch" -> cursor.options(setOf("-n", "--interval"), setOf("-x", "--exec", "-t", "--no-title", "-d", "--differences", "-g", "--chgexit", "-e", "--errexit", "-p", "--precise"))
            "nohup" -> cursor.options(emptySet(), emptySet())
            "busybox" -> cursor.peek()?.startsWith('-') != true
            "exec" -> cursor.options(setOf("-a"), setOf("-c", "-l", "-cl", "-lc"))
            // -s / -i 开 shell、-e 是 sudoedit、-l / -v / -k 不跑命令：都不在表里 = 认不出
            "sudo" -> cursor.options(
                setOf(
                    "-u", "--user", "-g", "--group", "-C", "--close-from", "-h", "--host", "-p", "--prompt", "-U", "--other-user",
                    "-r", "--role", "-t", "--type", "-T", "--command-timeout", "-D", "--chdir", "-R", "--chroot",
                ),
                setOf(
                    "-A", "--askpass", "-b", "--background", "-B", "--bell", "-E", "--preserve-env", "-H", "--set-home",
                    "-n", "--non-interactive", "-P", "--preserve-groups", "-S", "--stdin", "-k", "--reset-timestamp",
                ),
                optionalValues = setOf("--preserve-env"),
            )
            "doas" -> cursor.options(setOf("-u"), setOf("-n"))
            else -> false
        }
        return if (ok) cursor.index else null
    }

    /** 会换目录再跑被包装命令的选项：命令里的相对路径就不再相对当前目录 */
    fun changesDirectory(program: String, options: List<String>): Boolean = when (program) {
        "env" -> options.any { it == "-C" || it == "--chdir" || it.startsWith("--chdir=") }
        "sudo" -> options.any { it == "-D" || it == "--chdir" || it.startsWith("--chdir=") || it == "-R" || it == "--chroot" || it.startsWith("--chroot=") }
        else -> false
    }

    /** 会跑 `-c 脚本` 的 shell；它们的选项都是 POSIX sh 那一套（短选项可合写，-o / -O 带一个值） */
    val SHELLS = setOf("bash", "sh", "dash", "zsh", "ksh", "mksh", "ash")

    sealed interface ShellInvocation {
        /** `-c 脚本`：脚本是第 [script] 个参数（下标相对程序名之后） */
        data class Inline(val script: Int) : ShellInvocation

        /** 没有脚本文件参数（或带 -s）：从标准输入读脚本 */
        data object Stdin : ShellInvocation

        /** 跑一个脚本文件 */
        data object File : ShellInvocation
    }

    private val SHELL_LONG_FLAGS = setOf(
        "--norc", "--noprofile", "--login", "--posix", "--restricted", "--verbose", "--noediting", "--debugger", "--protected",
    )
    private val SHELL_LONG_VALUES = setOf("--rcfile", "--init-file")

    /**
     * [args] 是 shell 程序名之后的词。选项在脚本之前：`-lc`、`-ec`、`-euo pipefail -c` 都是 -c；
     * 带 -c 时第一个非选项参数才是脚本（`bash -c -e 'x'` 的脚本是 x）。认不出的选项返回 null。
     */
    fun shellInvocation(args: List<String>): ShellInvocation? {
        var i = 0
        var inline = false
        var stdin = false
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" || a == "-" -> { i++; break }
                a.startsWith("--") -> i += when (a) {
                    in SHELL_LONG_FLAGS -> 1
                    in SHELL_LONG_VALUES -> 2
                    else -> return null
                }
                (a.startsWith('-') || a.startsWith('+')) && a.length > 1 -> {
                    val letters = a.substring(1)
                    if (!letters.all { it in 'a'..'z' || it in 'A'..'Z' }) return null
                    if (a[0] == '-' && 'c' in letters) inline = true
                    if (a[0] == '-' && 's' in letters) stdin = true
                    // -o pipefail、-O extglob：合写在一串里也各带一个值（-euo pipefail）
                    i += 1 + letters.count { it == 'o' || it == 'O' }
                    if (i > args.size) return null
                }
                else -> break
            }
        }
        return when {
            inline -> if (i < args.size) ShellInvocation.Inline(i) else null
            stdin || i >= args.size -> ShellInvocation.Stdin
            else -> ShellInvocation.File
        }
    }

    private val NICE_SHORT = Regex("-\\d+|-n-?\\d+")

    private class Cursor(private val args: List<String>) {
        var index = 0
            private set

        fun peek(): String? = args.getOrNull(index)

        /** 还剩至少 [n] 个词才跳；不够 = 写法不完整 */
        fun skip(n: Int): Boolean {
            if (args.size - index < n) return false
            index += n
            return true
        }

        /**
         * [values] 里的选项带一个值（`-s KILL` 或 `--signal=KILL`），[flags] 不带，[optionalValues] 只能以 `=值` 连写带值
         * （sudo 的 `--preserve-env=PATH`）；遇到 `--` 结束；其余以 `-` 开头的都认不出
         */
        fun options(values: Set<String>, flags: Set<String>, optionalValues: Set<String> = emptySet()): Boolean {
            while (peek()?.startsWith('-') == true) {
                val option = peek()!!
                val joined = '=' in option && option.substringBefore('=') in values + optionalValues
                when {
                    option == "--" -> return skip(1)
                    option in values -> if (!skip(2)) return false
                    option in flags || joined -> skip(1)
                    else -> return false
                }
            }
            return true
        }

        /** stdbuf 的 -i / -o / -e 可以连写值（`-oL`），也可以分开写（`-o L`） */
        fun stdbufOptions(): Boolean {
            while (peek()?.startsWith('-') == true) {
                val option = peek()!!
                when {
                    option == "--" -> return skip(1)
                    option in setOf("-i", "-o", "-e", "--input", "--output", "--error") -> if (!skip(2)) return false
                    option.matches(STDBUF_JOINED) || option.substringBefore('=') in setOf("--input", "--output", "--error") && '=' in option -> skip(1)
                    else -> return false
                }
            }
            return true
        }
    }

    private val STDBUF_JOINED = Regex("-[ioe].+")
}
