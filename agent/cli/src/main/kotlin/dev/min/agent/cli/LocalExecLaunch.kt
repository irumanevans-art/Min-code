package dev.min.agent.cli

import dev.min.agent.host.HandleProcessReaper
import java.nio.file.Files
import java.nio.file.Path

/** Windows 的命令正文不经 argv，避免 JDK 与 msys 对内嵌引号的解释不同。 */
internal class LocalExecLaunch(private val shell: List<String>, logDir: Path, id: String) {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val script = logDir.resolve("$id.sh")
    private val pidFile = logDir.resolve("$id.pid")
    private val discover = MsysProcesses(shell.first()).newDiscoverer(pidFile)

    fun argv(command: String): List<String> {
        if (!windows) return shell + command
        Files.writeString(script, "echo \$\$ > ${posixQuote(bashPath(pidFile))}\n" + command + "\n", Charsets.UTF_8)
        // 保留 login shell，只把执行字符串的 -c 改成读取脚本；调用者沿用原来的 bash -lc / bash -c 配置。
        val fileShell = shell.mapNotNull { when (it) { "-lc" -> "-l"; "-c" -> null; else -> it } }
        return fileShell + script.toAbsolutePath().toString()
    }

    /** 退出线程与杀树共享锁：pid 文件必须留到最后一轮发现结束，否则根已死的孙进程会丢失。 */
    @Synchronized
    fun cleanup() {
        if (windows) {
            Files.deleteIfExists(script)
            Files.deleteIfExists(pidFile)
        }
    }

    @Synchronized
    fun killTree(process: Process) {
        if (!windows) {
            val handle = process.toHandle()
            handle.descendants().toList().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
            return
        }
        val tree = HandleProcessReaper({ discover }).capture(process)
        // msys 的 exec 会换 Windows pid，且被杀前仍可能 fork；与长寿进程端口一样反复发现，连续两轮安静才收尾。
        var quiet = 0
        repeat(SWEEP_PASSES) {
            tree.refresh()
            if (tree.anyAlive()) {
                quiet = 0
                tree.kill()
                Thread.sleep(SWEEP_PAUSE_MS)
            } else if (++quiet >= 2) {
                return
            }
        }
    }

    private companion object {
        const val SWEEP_PASSES = 20
        const val SWEEP_PAUSE_MS = 100L
    }
}
