package dev.min.agent.policy

/**
 * 对已解析的简单命令做两种判断：是不是**只读**（默认模式下免批，与 Claude Code 一致）；
 * 是不是踩了**安全底线**（任何模式都要人确认，docs/agent/04-子系统设计.md §4.4）。
 *
 * 两张单子都取保守的一侧：只读清单只收确定不写东西的；底线只收「改坏了无法挽回」的，
 * 收太宽会让 bypass 模式也频繁弹窗，用户就会习惯性点允许，底线就失去了意义。
 */
object CommandSafety {
    private val READ_ONLY_PROGRAMS = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "ag", "pwd", "echo", "printf",
        "which", "whereis", "type", "file", "stat", "du", "df", "tree", "cut", "tr", "diff", "cmp", "comm",
        "basename", "dirname", "realpath", "readlink", "uname", "whoami", "id", "nproc", "free", "uptime",
        "ps", "true", "false", "test", "[", "printenv", "jq", "md5sum", "sha1sum", "sha256sum", "sha512sum",
        "cal", "seq", "column", "nl", "od", "hexdump",
        // 只改 shell 自己的当前目录；后面重定向相对哪里由 PolicyEngine 跟踪
        "cd", "pushd", "popd",
    )

    /** find 里会执行或写文件的谓词 */
    private val FIND_SIDE_EFFECTS = setOf("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls")

    private val GIT_READ_ONLY = setOf(
        "status", "log", "diff", "show", "rev-parse", "ls-files", "ls-tree", "blame", "describe", "shortlog",
        "cat-file", "grep", "count-objects", "merge-base", "name-rev", "whatchanged",
    )

    fun isReadOnly(cmd: SimpleCommand): Boolean {
        if (cmd.redirects.any { it.writesFile }) return false
        val args = cmd.argv.drop(1)
        return when (cmd.program) {
            in READ_ONLY_PROGRAMS -> true
            "find" -> args.none { it in FIND_SIDE_EFFECTS }
            "sort" -> args.none { it == "-o" || it.startsWith("--output") }
            // uniq 输入 输出：第二个位置参数是要写的文件
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            "date" -> args.none { it == "-s" || it.startsWith("--set") }
            "hostname" -> args.isEmpty()
            "env" -> args.isEmpty()
            // 分析器只在 -v / -V（查命令在哪）时保留 command
            "command" -> args.firstOrNull() in setOf("-v", "-V")
            "git" -> isGitReadOnly(args)
            else -> false
        }
    }

    private fun isGitReadOnly(args: List<String>): Boolean {
        var rest = args
        // 全局选项：-C 目录、--no-pager 无害；-c 键=值 能改 pager / hooks 路径，不算只读
        while (rest.isNotEmpty()) {
            rest = when (rest.first()) {
                "-C" -> rest.drop(2)
                "--no-pager", "--no-optional-locks" -> rest.drop(1)
                else -> break
            }
        }
        val sub = rest.firstOrNull() ?: return false
        val subArgs = rest.drop(1)
        return when (sub) {
            in GIT_READ_ONLY -> subArgs.none { it == "--output" || it.startsWith("--output=") }
            "branch" -> subArgs.all { it in setOf("-a", "-r", "-v", "-vv", "--list", "--all", "--remotes", "--show-current") }
            "tag" -> subArgs.isEmpty() || subArgs.all { it == "-l" || it == "--list" }
            "remote" -> subArgs.isEmpty() || subArgs == listOf("-v")
            "stash" -> subArgs.firstOrNull() == "list"
            "config" -> subArgs.firstOrNull() in setOf("--get", "--list", "-l", "--get-all")
            else -> false
        }
    }

    private val RECURSIVE_FLAG = Regex("^-[a-zA-Z]*[rR][a-zA-Z]*$")

    /** 删掉就回不来的目标：根、家目录、工作区本身、共享存储、任何顶层目录、当前目录与上级 */
    private val CRITICAL_TARGETS = setOf(
        "/", "/*", "~", "~/", "~/*", "\$HOME", "\${HOME}", ".", "./", "./*", "..", "../", "*",
        "/workspace", "/workspace/", "/workspace/*", "/root", "/root/", "/sdcard", "/sdcard/", "/storage", "/home",
    )
    private val TOP_LEVEL_DIR = Regex("^/[^/]+/?$")

    /**
     * 踩了安全底线就返回原因（给人看的一句话），否则 null。[all] 是同一段命令里的全部简单命令（判断「下载后直接执行」要看前后）。
     */
    fun dangerReason(cmd: SimpleCommand, all: List<SimpleCommand>): String? {
        // sudo / doas 换个身份跑后面那条：剥掉再看（在 proot 里 sudo 多半就是 root，不能当成更安全）
        if (cmd.program == "sudo" || cmd.program == "doas") {
            val inner = elevated(cmd.argv.drop(1)) ?: return null
            return dangerReason(cmd.copy(argv = inner), all)
        }
        val args = cmd.argv.drop(1)
        val targets = args.filterNot { it.startsWith("-") }
        when (cmd.program) {
            "rm", "rmdir", "shred" -> {
                val recursive = cmd.program == "rmdir" || args.any { RECURSIVE_FLAG.matches(it) || it == "--recursive" }
                if (recursive) {
                    targets.firstOrNull { isCritical(it) }?.let { return "递归删除关键路径 $it" }
                    if (cmd.hasExpansion) return "递归删除的目标含未展开的变量，删到哪里要执行时才知道"
                }
            }
            "chmod", "chown", "chgrp" -> if (args.any { RECURSIVE_FLAG.matches(it) || it == "--recursive" }) {
                targets.firstOrNull { isCritical(it) }?.let { return "递归改关键路径 $it 的权限 / 属主" }
            }
            "mkfs", "wipefs", "fdisk", "sfdisk", "parted" -> return "${cmd.program} 会抹掉磁盘或分区"
            "dd" -> args.firstOrNull { it.startsWith("of=/dev/") }?.let { return "dd 直接写设备 ${it.removePrefix("of=")}" }
            "git" -> if (args.firstOrNull() == "push" && args.any { it == "--force" || it == "-f" || it.startsWith("+") }) {
                return "强制推送会改写远端历史"
            }
            // find 的起点在第一个表达式之前；从关键路径起、不按名字筛就删 / 执行，与 rm -rf 同级
            // （`find . -name '*.pyc' -delete` 是日常清理，不拦）
            "find" -> if (args.any { it in FIND_SIDE_EFFECTS } && args.none { it in FIND_NAME_FILTERS }) {
                args.takeWhile { !it.startsWith("-") && it != "!" && it != "(" }.firstOrNull { isCritical(it) }
                    ?.let { return "从关键路径 $it 起批量删除或执行" }
            }
            // xargs rm -rf：删什么来自管道，静态看不到
            "xargs" -> {
                val rm = args.indexOfFirst { it.substringAfterLast('/') in setOf("rm", "rmdir", "shred") }
                if (rm >= 0 && args.drop(rm + 1).any { RECURSIVE_FLAG.matches(it) || it == "--recursive" }) {
                    return "递归删除的目标来自管道，删到哪里要执行时才知道"
                }
            }
        }
        if (cmd.program.startsWith("mkfs.")) return "${cmd.program} 会抹掉磁盘或分区"
        cmd.redirects.firstOrNull { it.writesFile && it.target.startsWith("/dev/") && it.target != "/dev/null" }
            ?.let { return "直接写设备 ${it.target}" }
        // 下载后直接交给解释器执行：curl … | sh
        val readsScriptFromStdin = cmd.program in setOf("sh", "bash", "dash", "zsh") && args.none { !it.startsWith("-") }
        if (readsScriptFromStdin && all.any { it.program == "curl" || it.program == "wget" }) return "下载的内容直接交给 shell 执行"
        return null
    }

    private val FIND_NAME_FILTERS = setOf("-name", "-iname", "-path", "-ipath", "-wholename", "-iwholename", "-regex", "-iregex")

    /** sudo 的选项里 -u / -g / -C / -h / -p 带一个值；遇到 -- 或第一个非选项就是被提权的命令。认不出（-s / -i 开 shell）返回 null */
    private fun elevated(args: List<String>): List<String>? {
        var rest = args
        while (rest.isNotEmpty() && rest.first().startsWith("-")) {
            val opt = rest.first()
            rest = when {
                opt == "--" -> return rest.drop(1).ifEmpty { null }
                opt in setOf("-u", "-g", "-C", "-h", "-p", "-U", "-r", "-t", "-T") -> rest.drop(2)
                else -> rest.drop(1)
            }
        }
        return rest.ifEmpty { null }
    }

    private fun isCritical(target: String): Boolean {
        val t = target.trim()
        return t in CRITICAL_TARGETS || TOP_LEVEL_DIR.matches(t) || ONLY_DOTS_AND_SLASHES.matches(t)
    }

    // `../..`、`./.`、`/*` 这类只由点、斜杠、星号组成的目标
    private val ONLY_DOTS_AND_SLASHES = Regex("^[./*]+$")

    /** 看不透的命令里出现这些词，就按底线处理：看不透 + 可能很危险 = 必须问人 */
    private val DANGEROUS_WORDS = Regex("""(?<![\w.-])(rm|rmdir|shred|mkfs(\.\w+)?|wipefs|fdisk|dd|chmod|chown|sudo|curl|wget)(?![\w-])|git\s+push""")

    fun untrustedDanger(raw: String): String? =
        DANGEROUS_WORDS.find(raw)?.let { "命令里有看不透的写法，又出现了「${it.value.trim()}」" }

    /**
     * 受保护路径：改了会让后续的命令、配置或权限本身被改写。直接写这些地方任何模式都要人确认（Min 比 Claude Code 更严：CC 在 bypass 下放行）。
     */
    fun isProtectedPath(path: String): Boolean {
        val p = path.trimEnd('/')
        val name = p.substringAfterLast('/')
        val whole = "$p/"
        if (name !in PROTECTED_NAMES && isAgentNote(whole)) return false
        return "/.git/" in whole || "/.claude/" in whole || name in PROTECTED_NAMES
    }

    /**
     * 自动记忆目录与计划文件目录**之下**的路径只影响模型读到的内容，不改权限、hooks 或要执行的东西，所以豁免。
     * 豁免只给「干净」的路径：目录之前、之后剩下的那段里只要还有 `/.git/`、`/.claude/` 片段就不豁免
     * （`…/.claude/plans/x/.git/hooks/pre-commit` 仍是版本库钩子）；文件名在保护名单里的由调用方先排除。
     * 目录本身（后面没有任何东西）也不豁免。[whole] 是结尾补了 `/` 的路径
     */
    private fun isAgentNote(whole: String): Boolean {
        val m = AGENT_NOTES.find(whole) ?: return false
        val before = whole.substring(0, m.range.first + 1)
        val rest = whole.substring(m.range.last + 1)
        if (rest.isEmpty()) return false
        return listOf("/.git/", "/.claude/").none { it in before || it in "/$rest" }
    }

    private val PROTECTED_NAMES =
        setOf(".mcp.json", ".bashrc", ".bash_profile", ".bash_login", ".profile", ".zshrc", ".zprofile", ".zshenv", ".gitconfig")

    private val AGENT_NOTES = Regex("""/\.claude/(?:projects/[^/]+/memory|plans)/""")
}
