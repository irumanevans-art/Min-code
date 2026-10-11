package dev.min.agent.kernel

import dev.min.agent.policy.normalizePolicyPath
import dev.min.agent.spi.Durability
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.ShellCwdChanged
import dev.min.agent.spi.catchingNonCancellation

/*
 * Bash 的 `cd` 跨命令保留（CC tools-reference「What persists between commands」）：
 * - 主会话里 `cd` 之后的目录带到后续命令，前提是仍在项目目录或附加目录里；
 * - 子 agent 从不带；出界则重置回项目目录，在那次结果末尾附 `Shell cwd was reset to <dir>`；
 * - `CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR=1` 关掉，每条命令都从项目目录起；
 * - 环境变量不带过去（每条命令本来就是新进程，这里不碰）。
 *
 * 为什么「shell 当前目录」与会话目录（settings.cwd）分开：会话目录决定权限范围、记忆、worktree，
 * 模型 `cd` 进子目录就把它缩小的话，同一会话里再编辑项目根的文件会莫名被拦；反过来，shell 目录出界又必须收回来。
 * 所以 [SessionState.shellCwd] 只管下一条 Bash 从哪开始，范围永远按会话目录 + 附加目录判。
 */

/** CC 官方环境变量：设成真值就关掉 `cd` 的跨命令保留 */
internal const val MAINTAIN_PROJECT_DIR_ENV = "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR"

/** 官方文档给出的、追加在工具结果末尾的那句（模型看的，英文原样） */
internal fun shellCwdResetNote(dir: GuestPath) = "Shell cwd was reset to $dir"

/**
 * 跑一条 Bash 命令并处理目录的带出带入。[run] 是真正起进程的那一步（带网络沙箱的包装）。
 *
 * 子 agent 与环境变量开关打开时原样放行（每条命令从会话目录起，也不让宿主报目录）；否则：
 * 1. 起点 = shell 目录（没有就是会话目录）。起点已经不在范围里或目录没了（被删、附加目录被撤）：**不执行**——
 *    权限分析是按 shell 目录做的（相对路径、凭证目标），换个目录跑就成了没分析过的命令；重置后让模型重发；
 * 2. 命令跑完（[ExecResult.Exited] 且宿主报了 [ExecResult.Exited.endCwd]）才更新：超时、转后台、`exit` 提前结束都没有目录可报。
 *    `run_in_background` 的命令同样从 shell 目录起，但不要求宿主报目录（[ShellCommand.trackCwd] 为假）；
 * 3. 终点按真实路径判范围（先折 `..`、再交宿主解符号链接）：在范围内就记下**真实路径**（别名事后改指向也带不出去），
 *    出界就重置并附说明。
 */
internal suspend fun SessionRuntime.runTrackedShell(command: ShellCommand, run: suspend (ShellCommand) -> ExecResult): ExecResult {
    val s = state.value
    if (s.origin != null || isTruthyEnv(deps.env(MAINTAIN_PROJECT_DIR_ENV))) {
        return run(command.copy(trackCwd = false))
    }
    val project = s.settings.cwd
    val carried = s.shellCwd
    if (carried != null && !(withinScope(carried) && isDirectory(carried))) {
        moveShellCwd(carried, null)
        return ExecResult.Exited(
            1,
            "命令没有执行：上一条命令把 shell 带到了 $carried，现在它已经不存在，或者不在项目目录与附加目录里了。请重新发出这条命令，它会从项目目录开始。",
            false, 0, note = shellCwdResetNote(project),
        )
    }
    val start = carried ?: project
    val result = run(command.copy(cwd = start))
    if (!command.trackCwd) return result
    val exited = result as? ExecResult.Exited ?: return result
    val end = exited.endCwd ?: return result
    val real = deps.canonicalPath(GuestPath(normalizePolicyPath(end.value)))
    if (!withinScope(real)) {
        moveShellCwd(start, null)
        return exited.copy(note = shellCwdResetNote(project))
    }
    moveShellCwd(start, if (real == deps.canonicalPath(project) || real == project) null else real)
    return result
}

/** 项目目录与附加目录（按真实路径）之内才算范围内；`/old-evil` 不是 `/old` 的子目录 */
private fun SessionRuntime.withinScope(dir: GuestPath): Boolean {
    val real = deps.canonicalPath(GuestPath(normalizePolicyPath(dir.value)))
    val roots = listOf(state.value.settings.cwd) + state.value.additionalDirectories
    return roots.any { Worktrees.under(real, deps.canonicalPath(it)) }
}

private suspend fun SessionRuntime.isDirectory(dir: GuestPath): Boolean =
    catchingNonCancellation { deps.host.fs.stat(dir)?.kind == FileKind.Directory }.getOrDefault(false)

/**
 * 把 shell 目录从 [from]（有效起点）换成 [stored]（null = 回会话目录）：真变了才写事件；
 * 有效目录变了就触发 CwdChanged hook（文档：「例如 Claude 执行 cd」，输入 old_cwd / new_cwd）。
 */
private suspend fun SessionRuntime.moveShellCwd(from: GuestPath, stored: GuestPath?) {
    if (state.value.shellCwd != stored) commit(listOf(ShellCwdChanged(stored)), Durability.Sync)
    val to = stored ?: state.value.settings.cwd
    if (from != to) runCwdChangedHooks(from, to)
}
