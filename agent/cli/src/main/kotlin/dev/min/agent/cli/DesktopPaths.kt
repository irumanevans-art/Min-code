package dev.min.agent.cli

import dev.min.agent.host.BindMount
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.host.ScopeRules
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HostOs
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * 桌面上 guest 路径与本机路径的约定。模型在 Bash 里写的路径与文件工具用的路径必须是同一套，否则它读得到的文件 shell 里找不到。
 *
 * - Linux / macOS / WSL：guest 路径就是本机路径（根挂在 `/`）。
 * - Windows：照 Git Bash 的写法，`/c/…`、`/d/…` 表示各个盘，每个盘作为一个挂载；其余 guest 路径落到 [scratchRoot] 下。
 *   LocalExec 用 Git Bash 的 bash 跑命令，它认的正是这种路径。
 */
internal class DesktopPaths(
    workspace: Path,
    scratchRoot: Path,
    private val windows: Boolean = HostOs.isWindows,
) {
    private val drives: List<Path> =
        if (windows) FileSystems.getDefault().rootDirectories.toList() else emptyList()

    val fs: RootfsFileSystem = if (windows) {
        Files.createDirectories(scratchRoot)
        RootfsFileSystem(
            root = scratchRoot,
            binds = drives.map { BindMount(GuestPath("/" + driveLetter(it)), it) },
            scopes = ScopeRules(workspace = listOf(guestOfHost(workspace)), device = emptyList()),
        )
    } else {
        RootfsFileSystem(
            root = Path.of("/"),
            scopes = ScopeRules(workspace = listOf(guestOfHost(workspace)), device = emptyList()),
        )
    }

    /** 本机绝对路径 → guest 路径 */
    fun guestOfHost(host: Path): GuestPath {
        val absolute = host.toAbsolutePath().normalize()
        if (!windows) return GuestPath(absolute.toString())
        val root = absolute.root ?: error("不是绝对路径：$host")
        val rest = root.relativize(absolute).toString().replace('\\', '/')
        return GuestPath("/" + driveLetter(root) + if (rest.isEmpty()) "" else "/$rest")
    }

    fun hostOf(guest: GuestPath): Path = fs.hostPath(guest)

    /**
     * shell 打出的当前目录 → guest 路径（Bash 的 cd 跨命令保留用）。Linux / macOS / WSL 原样。
     * Windows 上宿主让 Git Bash 用 `pwd -W` 打 Windows 写法（`D:/kimi/x`）：普通 `pwd` 会按 msys 挂载表换名
     * （临时目录变 `/tmp/...`，Git 安装目录变 `/`、`/usr`），盘符写法才没有歧义。这里把它转成 guest 的盘符写法（`/d/kimi/x`，盘符小写）；
     * 也认 `/d/...` 的 msys 盘符写法。
     * 落在盘符之外的（`//server/share`、msys 自己的 `/usr`）在 guest 里没有对应物，**原样交出去**：它们必然不在项目目录里
     * （项目与附加目录在 Windows 上都是盘符写法），内核据此判出界并重置，而不是悄悄当成没换目录。
     */
    fun guestOfShell(shell: String): GuestPath? {
        if (!windows) return if (shell.startsWith("/")) GuestPath(shell) else null
        Regex("^([A-Za-z]):(?:[/\\\\](.*))?$").matchEntire(shell)?.let { m ->
            val rest = m.groupValues[2].replace('\\', '/').trim('/')
            return GuestPath("/" + m.groupValues[1].lowercase() + if (rest.isEmpty()) "" else "/$rest")
        }
        val m = Regex("^/([A-Za-z])(/.*)?$").matchEntire(shell) ?: return if (shell.startsWith("/")) GuestPath(shell) else null
        return GuestPath("/" + m.groupValues[1].lowercase() + m.groupValues[2].trimEnd('/'))
    }

    private fun driveLetter(root: Path): String = root.toString().trimEnd('\\', '/', ':').lowercase()
}
