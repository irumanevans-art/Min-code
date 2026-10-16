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
            else -> false
        }
        return if (ok) cursor.index else null
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

        /** [values] 里的选项带一个值（`-s KILL` 或 `--signal=KILL`），[flags] 不带；遇到 `--` 结束；其余以 `-` 开头的都认不出 */
        fun options(values: Set<String>, flags: Set<String>): Boolean {
            while (peek()?.startsWith('-') == true) {
                val option = peek()!!
                when {
                    option == "--" -> return skip(1)
                    option in values -> if (!skip(2)) return false
                    option in flags || option.substringBefore('=') in values && '=' in option -> skip(1)
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
