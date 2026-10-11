package dev.min.agent.spi

/**
 * 「命令结束时 shell 停在哪」的探针，Android（proot）与桌面（本机 shell）两个宿主共用同一份脚本与解析，
 * 免得两边各写一套、行为慢慢分叉。
 *
 * 为什么把用户脚本整个 `eval` 进去：脚本末尾如果有没收口的 heredoc，直接在后面拼探针会被它吞掉，
 * 探针文字还会漏进模型看到的输出；eval 把用户文本限制在自己的解析范围里。代价是语法错误的报错前缀多一个 `eval:`，无关紧要。
 *
 * 目录写进文件而不是 stdout：命令转后台后才结束时，标记会混进日志与结束通知；输出被截断（保留头尾）时也不会丢。
 * 用户脚本里 `exit` / `exec` / `set -e` 失败 → 探针没机会跑 → 没有文件 → 调用方当作没换目录（正是想要的）。
 * 用户自己设了 EXIT trap 也不碍事：探针在 `exit` 之前已经写好，trap 在 `exit` 时才跑。
 */
object ShellCwdProbe {
    /**
     * 包出带探针的脚本。[shellFile] 是**shell 里看得见**的写法（桌面 Windows 要走 Git Bash 的盘符路径），由宿主给。
     * [pwdCommand] 是打印当前目录的那条命令：默认 `command pwd`（`command` 绕开用户定义的同名函数）；
     * Windows 的 Git Bash 要 `command pwd -W`，见 `DesktopPaths.guestOfShell`
     */
    fun wrap(script: String, shellFile: String, pwdCommand: String = "command pwd"): String =
        "eval ${quote(script)}\n" +
            "__min_rc=\$?\n" +
            // `>|` 不受 noclobber 影响；写失败（目录没了、shell 不认 -W）静默，只是这次没有目录可报
            "{ $pwdCommand >| ${quote(shellFile)}; } 2>/dev/null\n" +
            "exit \$__min_rc\n"

    /**
     * 读到的文件内容 → shell 打出的目录；空、不是绝对路径（`/...` 或 `C:/...` 盘符写法）、含控制字符一律 null（当作没换目录）。
     * 怎么变成 guest 路径是宿主的事
     */
    fun parse(text: String): String? {
        val line = text.lineSequence().firstOrNull()?.trimEnd('\r')?.takeIf { it.isNotEmpty() } ?: return null
        if (line.length > MAX_PATH_CHARS || line.any { it < ' ' }) return null
        if (!line.startsWith("/") && !DRIVE.containsMatchIn(line)) return null
        return line
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private const val MAX_PATH_CHARS = 4096
    private val DRIVE = Regex("^[A-Za-z]:[/\\\\]")
}

/**
 * `CLAUDE_ENV_FILE` 的前导（官方 hooks 页「Persist environment variables」：每条 Bash 命令先把文件内容当 shell 代码执行，再跑命令本身）。
 * Android（proot）与桌面两个宿主共用同一份拼法，免得两边各写一套慢慢分叉。
 *
 * 放在 [ShellCwdProbe] 的 `eval` **外面**：前导要在同一个 shell 里生效（`export` 才留得住），而用户脚本里的 `exit` 之类不该吞掉它；
 * 文件还没有（没有 hook 写过）就什么都不做；文件里的语法错误只是这条命令的 stderr 多一行，不会让命令不跑
 */
object ShellPreamble {
    /** [files] 是 shell 里看得见的路径，按给定顺序逐个 source；没有文件时返回 null */
    fun forFiles(files: List<String>): String? =
        files.takeIf { it.isNotEmpty() }?.joinToString("\n") { "if [ -f ${quote(it)} ]; then . ${quote(it)}; fi" }

    /** 前导接在脚本前面（没有前导时原样） */
    fun apply(preamble: String?, script: String): String = if (preamble.isNullOrEmpty()) script else preamble + "\n" + script

    private fun quote(value: String) = "'" + value.replace("'", "'\''") + "'"
}
