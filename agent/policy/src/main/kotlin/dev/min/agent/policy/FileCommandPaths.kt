package dev.min.agent.policy

import dev.min.agent.spi.AccessKind

internal data class FileOperand(val target: String, val kind: AccessKind, val glob: Boolean = false)

internal val FILE_READ_COMMANDS = setOf(
    "cat", "head", "tail", "wc", "ls", "stat", "file", "du", "df", "tree", "cut", "diff", "cmp", "comm",
    "realpath", "readlink", "md5sum", "sha1sum", "sha256sum", "sha512sum", "column", "nl", "od", "hexdump",
    "sort", "uniq", "grep", "egrep", "fgrep", "rg", "ag", "jq", "find",
)

/** 已知读文件命令的静态操作数也属于读取，不能靠 Bash 绕过 Device 文件审批。 */
internal fun readCommandPaths(cmd: SimpleCommand): List<FileOperand>? {
    if (cmd.program !in FILE_READ_COMMANDS) return emptyList()
    if (cmd.hasExpansion) return null
    val paths = mutableListOf<FileOperand>()
    var options = true
    val searches = cmd.program in setOf("grep", "egrep", "fgrep", "rg", "ag")
    var needsPattern = searches || cmd.program == "jq"
    var i = 1
    fun read(index: Int, target: String = cmd.argv[index]) {
        if (target != "-") paths += FileOperand(target, AccessKind.Read, index in cmd.unquotedGlobIndices)
    }
    while (i < cmd.argv.size) {
        val arg = cmd.argv[i]
        if (cmd.program == "find" && (arg in setOf("-H", "-L", "-P") || arg.matches(Regex("-O[0-3]")))) { i++; continue }
        if (cmd.program == "find" && arg == "-files0-from") {
            if (++i >= cmd.argv.size) return null
            read(i); i++; continue
        }
        if (cmd.program == "find" && (arg.startsWith('-') || arg in setOf("!", "(", ")"))) break
        if (options && arg == "--") options = false
        else if (options && arg.startsWith('-')) {
            when {
                searches && (arg == "-e" || arg == "--regexp" || arg.startsWith("--regexp=") || arg.startsWith("-e") && arg.length > 2) -> {
                    needsPattern = false
                    if (arg == "-e" || arg == "--regexp") i++
                }
                (searches || cmd.program == "jq") && (arg == "-f" || arg == "--file" || arg == "--from-file" || arg == "--ignore-file") -> {
                    if (arg != "--ignore-file") needsPattern = false
                    if (++i >= cmd.argv.size) return null
                    read(i)
                }
                cmd.program == "jq" && arg in setOf("--arg", "--argjson", "--argfile", "--slurpfile", "--rawfile") -> {
                    i += 2
                    if (i >= cmd.argv.size) return null
                    if (arg !in setOf("--arg", "--argjson")) read(i)
                }
                searches && arg.startsWith("-f") && arg.length > 2 -> { needsPattern = false; read(i, arg.substring(2)) }
                arg.startsWith("--file=") || arg.startsWith("--from-file=") || arg.startsWith("--ignore-file=") -> {
                    if (!arg.startsWith("--ignore-file=")) needsPattern = false
                    read(i, arg.substringAfter('='))
                }
                cmd.program in setOf("head", "tail") && arg in setOf("-n", "-c", "--lines", "--bytes") -> i++
                searches && arg in setOf("-m", "-A", "-B", "-C", "-g", "-t", "-T", "--max-count", "--context", "--glob", "--type", "--encoding", "--color") -> i++
                cmd.program == "cut" && arg in setOf("-b", "-c", "-f", "-d", "--bytes", "--characters", "--fields", "--delimiter", "--output-delimiter") -> i++
                '=' in arg && arg.substringAfter('=').startsWith('/') -> read(i, arg.substringAfter('='))
            }
        } else if (needsPattern) needsPattern = false
        else read(i)
        i++
    }
    // 没给目标的 ls/tree/du 读取当前目录；cd /sdcard && ls 也要经过手机读取审批。
    val recursiveGrep = cmd.program in setOf("grep", "egrep", "fgrep") && cmd.argv.drop(1).any {
        it == "--recursive" || it == "--directories=recurse" || it.startsWith('-') && !it.startsWith("--") && ('r' in it || 'R' in it)
    }
    if (paths.isEmpty() && (cmd.program in setOf("ls", "tree", "du", "find", "rg", "ag") || recursiveGrep)) paths += FileOperand(".", AccessKind.Read)
    return paths
}

/** 只认参数含义明确的文件命令；未知选项、展开及无法定位的目标留给常规审批。 */
internal fun fileCommandPaths(cmd: SimpleCommand): List<FileOperand>? {
    if (cmd.program !in setOf("mkdir", "touch", "mv", "cp") || cmd.hasExpansion || cmd.hasUnquotedGlob) return null
    val operands = mutableListOf<String>()
    var targetDirectory: String? = null
    var options = true
    var i = 1
    while (i < cmd.argv.size) {
        val arg = cmd.argv[i]
        when {
            options && arg == "--" -> options = false
            options && arg.startsWith("-") -> {
                if (cmd.program in setOf("cp", "mv") && (arg == "-t" || arg == "--target-directory" || arg.startsWith("--target-directory="))) {
                    if (targetDirectory != null) return null
                    targetDirectory = if ('=' in arg) arg.substringAfter('=') else cmd.argv.getOrNull(++i) ?: return null
                    i++
                    continue
                }
                val flags = when (cmd.program) {
                    "mkdir" -> "pv"
                    "touch" -> "acmh"
                    "mv" -> "finvT"
                    else -> "aRrfinvpPLHT"
                }
                if (cmd.program == "mkdir" && arg == "-m") {
                    if (cmd.argv.getOrNull(++i)?.matches(Regex("[0-7]{3,4}")) != true) return null
                } else if (!(arg == "--parents" && cmd.program == "mkdir") &&
                    !(arg == "--no-target-directory" && cmd.program in setOf("cp", "mv")) &&
                    !(!arg.startsWith("--") && arg.length > 1 && arg.drop(1).all { it in flags })) return null
            }
            else -> operands += arg
        }
        i++
    }
    if (operands.isEmpty() || operands.any { it.isEmpty() }) return null
    if (cmd.program in setOf("mkdir", "touch")) return operands.map { FileOperand(it, AccessKind.Write) }
    if (targetDirectory == null && operands.size < 2 || targetDirectory?.isEmpty() == true) return null
    val sources = if (targetDirectory == null) operands.dropLast(1) else operands
    val destination = targetDirectory ?: operands.last()
    val kind = if (cmd.program == "mv") AccessKind.Write else AccessKind.Read
    // 目的地可能是目录；同时检查最终文件名，防止 cp x/.bashrc . 绕过受保护文件底线。
    return sources.map { FileOperand(it, kind) } + FileOperand(destination, AccessKind.Write) +
        sources.map { FileOperand(destination.trimEnd('/') + "/" + it.trimEnd('/').substringAfterLast('/'), AccessKind.Write) }
}
