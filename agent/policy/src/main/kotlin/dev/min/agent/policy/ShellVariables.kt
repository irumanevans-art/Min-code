package dev.min.agent.policy

/**
 * 哪些变量赋值会改变「命令怎么被找到、怎么运行」。名单取自 Claude Code 2.1.301 二进制（CC 2.1.296 修过
 * 「先给 BASH_ARGV0 赋值再用它就被自动放行」，同一族的漏洞 Min 当时是把所有前置赋值整个丢掉）。
 */
internal object ShellVariables {
    /**
     * 前置在命令前也不改变它做什么的变量（CC 的 SAFE_ENV_VARS 原样）。只有这些、且值是简单字面量时，
     * `NODE_ENV=test npm test` 才按 `npm test` 判只读、匹配允许规则；其余前置赋值（`LD_PRELOAD=x`、`PATH=./bin`）要连赋值一起批准。
     */
    private val SAFE_PREFIX = setOf(
        "GOEXPERIMENT", "GOOS", "GOARCH", "CGO_ENABLED", "GO111MODULE", "RUST_BACKTRACE", "RUST_LOG", "NODE_ENV",
        "PYTHONUNBUFFERED", "PYTHONDONTWRITEBYTECODE", "PYTEST_DISABLE_PLUGIN_AUTOLOAD", "PYTEST_DEBUG", "ANTHROPIC_API_KEY",
        "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_TIME", "CHARSET", "TERM", "COLORTERM", "NO_COLOR", "FORCE_COLOR", "TZ",
        "LS_COLORS", "LSCOLORS", "GREP_COLOR", "GREP_COLORS", "GCC_COLORS", "TIME_STYLE", "BLOCK_SIZE", "BLOCKSIZE",
        "COLUMNS", "LINES", "CLICOLOR", "CLICOLOR_FORCE", "CI", "DEBIAN_FRONTEND", "GIT_TERMINAL_PROMPT",
    )

    /** CC 剥前置赋值时对值的要求：不带引号、空格、展开的简单字面量 */
    private val SAFE_VALUE = Regex("[A-Za-z0-9_./:-]+")

    /**
     * 单独一行赋值就会改变同一行后面命令的查找或执行方式（CC 的 `$8e` 名单，比较时不分大小写：zsh 的 `path` 与 PATH 绑定）。
     * CC 名单里的 lang / language / lc_* / textdomain* 没收：它们影响的是 `$"…"` 的翻译查找，而 Min 的词法见到 `$"` 就整条不信任。
     */
    private val LOOKUP_CHANGING = setOf(
        "path", "home", "dirstack", "tmpprefix", "bash_env", "env", "cdpath", "globignore", "shell", "fpath",
        "bash_loadables_path", "module_path", "manpath", "mailpath", "readnullcmd", "nullcmd", "histfile", "zdotdir",
        "functions", "commands", "aliases", "galiases", "saliases", "bash_cmds", "bash_aliases", "execignore",
        "bash_source_path", "histchars",
    )
    private val LOOKUP_CHANGING_PREFIXES = listOf("ld_", "dyld_", "bash_func_")

    /** 改分词、跟踪输出或 shell 选项，后面的命令静态就看不准了；BASH_ARGV0 会改 `$0`（CC 2.1.296 修的就是它） */
    private val PARSING_CHANGING = setOf("IFS", "PS4", "PROMPT4", "SHELLOPTS", "BASHOPTS", "BASH_VERSINFO", "BASH_ARGV0")

    /**
     * 带整数属性的特殊变量（CC 的 KDt 名单）：赋值时 bash 对值做算术求值，`SECONDS='a[$(id)]'` 里的下标会执行命令。
     * 前置赋值和单独赋值都算。
     */
    private val INTEGER_SPECIALS = setOf(
        "RANDOM", "SECONDS", "LINENO", "OPTIND", "MAILCHECK", "HISTCMD", "SRANDOM", "EPOCHSECONDS", "EPOCHREALTIME", "BASHPID",
        "BASH_MONOSECONDS", "BASH_TRAPSIG", "COLUMNS", "LINES", "SHLVL", "ERRNO", "TMOUT", "HISTSIZE", "SAVEHIST",
        "TRY_BLOCK_ERROR", "TRY_BLOCK_INTERRUPT", "KEYTIMEOUT", "LISTMAX", "LOGCHECK", "PERIOD", "FUNCNEST", "UID", "EUID",
        "GID", "EGID", "REPORTTIME", "REPORTMEMORY", "DIRSTACKSIZE", "BAUD", "ZLE_RPROMPT_INDENT", "MBEGIN", "MEND", "PPID",
        "ARGC", "ZSH_SUBSHELL", "TTYIDLE", "status",
    )
    private val PLAIN_INTEGER = Regex("-?[0-9]+")

    /** [text] 是去掉引号后的 `NAME=value`；[expansion] 是值里有没有未展开的变量 */
    fun isSafePrefix(text: String, expansion: Boolean): Boolean {
        val name = text.substringBefore('=')
        return name in SAFE_PREFIX && !expansion && SAFE_VALUE.matches(text.substringAfter('=')) && integerRisk(text) == null
    }

    /** 单独一行赋值的风险（给人看的原因），没有返回 null */
    fun standaloneRisk(text: String): String? {
        val name = text.substringBefore('=')
        val lower = name.lowercase()
        if (lower in LOOKUP_CHANGING || LOOKUP_CHANGING_PREFIXES.any { lower.startsWith(it) }) {
            return "给 $name 赋值会改变后面命令的查找或执行方式"
        }
        if (name in PARSING_CHANGING) return "给 $name 赋值会改变后面命令的解析方式，静态看不准"
        return integerRisk(text)
    }

    /** 整数属性变量的值不是纯整数：赋值时的算术求值能执行下标里的命令 */
    fun integerRisk(text: String): String? {
        val name = text.substringBefore('=')
        if (name !in INTEGER_SPECIALS || PLAIN_INTEGER.matches(text.substringAfter('='))) return null
        return "给 $name 赋的值会被算术求值，值里的下标可以执行命令"
    }
}
