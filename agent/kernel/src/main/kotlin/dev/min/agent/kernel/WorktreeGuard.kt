package dev.min.agent.kernel

import dev.min.agent.policy.ShellAnalysis
import dev.min.agent.policy.ShellAnalyzer
import dev.min.agent.spi.*

/** 隔离约束在审批之后执行，规则、hook 或 bypass 都不能放行写主 checkout。 */
internal suspend fun worktreeViolation(state: SessionState, deps: KernelDeps, ask: PermissionAsk): String? {
    val binding = state.worktree ?: return null
    val main = deps.canonicalPath(binding.repository)
    val directory = GuestPath("${main.value.trimEnd('/')}/.claude/worktrees")
    fun inMain(path: GuestPath) = Worktrees.under(deps.canonicalPath(path), main) && !Worktrees.under(deps.canonicalPath(path), directory)
    val refusal = "Worktree isolation: cannot redirect this operation into the main checkout $main. Use the active worktree ${binding.path}."
    if (ask.tool in setOf("Edit", "Write", "NotebookEdit") && ask.paths.any { inMain(it.path) }) return refusal
    if (ask.tool !in setOf("Bash", "PowerShell", "Monitor")) return null
    // Bash 从 shell 当前目录（cd 带过来的）起跑：隔离判断也按它起算，附加目录里的主 checkout 不能借 cd 进去
    val cwd = state.shellCwd ?: state.settings.cwd
    if (deps.host.fs.stat(cwd)?.kind != FileKind.Directory || inMain(cwd)) return refusal
    if (ask.tool == "PowerShell") return null
    for (access in ask.commands) {
        // 分析器会剥去前置赋值，原始脚本上的 Git 环境重定向必须先拒绝。
        if (Regex("\\b(?:GIT_DIR|GIT_WORK_TREE)\\s*=").containsMatchIn(access.raw)) return refusal
        val parsed = ShellAnalyzer.analyze(access.raw) as? ShellAnalysis.Parsed
            ?: return "Worktree isolation: command shape cannot be verified. Split it into plain commands in ${binding.path}."
        var commandCwd = cwd
        for (command in parsed.commands) {
            if (command.program.startsWith("-")) return refusal
            if (command.hasExpansion && (command.program == "git" || command.argv.firstOrNull()?.contains('$') == true)) return refusal
            // 环境重定向在分析器剥去赋值之前检验；不能让 env 包装藏掉 GIT_DIR / GIT_WORK_TREE。
            if (command.original.any { it.startsWith("GIT_DIR=") || it.startsWith("GIT_WORK_TREE=") }) return refusal
            for (redirect in command.redirects.filter { it.writesFile }) {
                val target = try { Worktrees.resolved(redirect.target, commandCwd) } catch (_: IllegalArgumentException) { return refusal }
                if (inMain(target)) return refusal
            }
            if (command.program == "cd") {
                if (command.hasExpansion) return refusal
                val operands = command.argv.drop(1).dropWhile { it in setOf("-L", "-P", "-e", "--") }
                val target = operands.singleOrNull()?.takeUnless { it == "-" || it.startsWith('~') } ?: return refusal
                commandCwd = try { Worktrees.resolved(target, commandCwd) } catch (_: IllegalArgumentException) { return refusal }
                if (inMain(commandCwd)) return refusal
            }
            if (command.program == "git") {
                if (inMain(commandCwd)) return refusal
                val args = command.argv.drop(1)
                for ((i, arg) in args.withIndex()) {
                    val target = when {
                        arg in setOf("-C", "--git-dir", "--work-tree") -> args.getOrNull(i + 1) ?: return refusal
                        arg.startsWith("--git-dir=") || arg.startsWith("--work-tree=") -> arg.substringAfter('=')
                        arg.startsWith("-C") && arg.length > 2 -> arg.substring(2)
                        arg.startsWith("--config-env=") -> return refusal
                        arg.startsWith("core.worktree=") -> arg.substringAfter('=')
                        else -> null
                    }
                    if (target != null) {
                        val path = try { Worktrees.resolved(target, commandCwd) } catch (_: IllegalArgumentException) { return refusal }
                        if (inMain(path)) return refusal
                        if (arg.startsWith("-C")) commandCwd = path
                    }
                }
            }
        }
    }
    return null
}
