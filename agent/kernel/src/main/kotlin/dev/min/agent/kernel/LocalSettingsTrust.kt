package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsSnapshot
import dev.min.agent.spi.*
import kotlin.coroutines.cancellation.CancellationException

/** 未接受工作区前不能启动仓库提供的 Git；检查失败也不能把仓库声明变成用户授权。 */
internal suspend fun trustedLocalSettings(deps: KernelDeps, snapshot: SettingsSnapshot, cwd: GuestPath, accepted: Boolean): Set<GuestPath> {
    // CLI 环境变量是本机写法，盘符/反斜杠须先映射为宿主通用的 guest 路径，才能比较会话起点。
    val configParent = deps.env("CLAUDE_CONFIG_DIR")?.replace('\\', '/')?.trimEnd('/')?.substringBeforeLast('/')
    val configGuest = configParent?.let {
        if (Regex("^[A-Za-z]:/").containsMatchIn(it)) "/" + it[0].lowercaseChar() + it.drop(2) else it.ifEmpty { "/" }
    }
    val configHome = configGuest?.takeIf { it.startsWith('/') }?.let(::GuestPath) ?: deps.memoryHome
    val result = mutableSetOf<GuestPath>()
    for (layer in snapshot.layers.filter { it.source == ConfigSource.Local }) {
        try {
            val dir = GuestPath(layer.path.value.substringBeforeLast('/'))
            val parent = GuestPath(dir.value.substringBeforeLast('/').ifEmpty { "/" })
            val realParent = deps.canonicalPath(parent).value.trimEnd('/')
            val expected = "$realParent/.claude"
            val linked = deps.host.fs.list(parent).any { it.name == ".claude" && it.kind == FileKind.Symlink } ||
                !deps.canonicalPath(dir).value.equals(expected, ignoreCase = Regex("^[A-Za-z]:").containsMatchIn(deps.gitPath(dir)))
            if (linked) continue
            var ancestor = parent
            var repository: GuestPath? = null
            while (true) {
                if (deps.host.fs.stat(GuestPath(ancestor.value.trimEnd('/') + "/.git")) != null) { repository = ancestor; break }
                if (ancestor.value == "/") break
                ancestor = GuestPath(ancestor.value.substringBeforeLast('/').ifEmpty { "/" })
            }
            // 主目录豁免只适用于本地那份；重定向到仓库根的 local 文件仍须先接受检查。
            if (cwd == configHome && parent == configHome && repository != parent) { result += layer.path; continue }
            if (!accepted) continue
            if (repository == null) { result += layer.path; continue }
            val relative = layer.path.value.removePrefix(repository.value.trimEnd('/') + "/")
            val command = "GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 git -c core.hooksPath=/dev/null -c core.fsmonitor=false ls-files --error-unmatch -- " + Worktrees.quote(relative) + " 2>/dev/null"
            val checked = deps.host.exec.run(ShellCommand(command, repository), ExecOptions(30_000, maxOutputChars = 4096))
            if (checked is ExecResult.Backgrounded) deps.host.exec.kill(checked.handle)
            if (checked is ExecResult.Exited && checked.exitCode == 1 && !checked.truncated) result += layer.path
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 无法证明是用户自己的 local 文件时，继续按项目设置挡住能力授予。
        }
    }
    return result
}
