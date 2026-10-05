package dev.min.agent.cli

import dev.min.agent.host.HandleProcessReaper
import dev.min.agent.host.JvmProcessReaper
import dev.min.agent.host.ProcessEnv
import dev.min.agent.host.ProcessReaper
import dev.min.agent.host.spawnLive
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * [ProcessPort] 的桌面实现：`ProcessBuilder` 起进程，与 [LocalExec] 走同一个 shell（Windows 上是 Git Bash）
 * 和同一套 guest → 本机路径映射（[hostDirOf]）。只给桌面用，Android 走 proot（app 里另有实现）。
 *
 * 为什么放 `:agent:cli`：路径映射（[DesktopPaths]）与 Git Bash 的约定都在这里，`:agent:host` 看不到它们；
 * 而「包装进程、关停、回收」这些与平台无关的部分在 `:agent:host`（`JvmLiveProcess`），Android 实现共用。
 *
 * - **不把 argv 放进命令行**：Windows 上 JDK 拼命令行时不转义内嵌的双引号，Git Bash 再按自己的规则拆一遍，
 *   带空格、引号、反斜杠的参数会被拆碎。所以把 `exec` 写进一个临时启动脚本（POSIX 单引号转义，原样保真），
 *   命令行里只剩脚本路径。脚本在进程结束时删掉。
 * - 环境变量走 [ProcessEnv]：白名单继承 + spec.env，[secretEnvNames] 里的名字（模型 key 等 Min 管理的凭证）一律剔除。
 * - 回收按整棵后代树（`ProcessHandle.descendants()`；Windows 上再用 msys 的进程表补全，见 [MsysProcesses]），
 *   顺序见 [dev.min.agent.host.JvmLiveProcess.shutdown]。
 */
class LocalProcessPort(
    private val hostDirOf: (GuestPath) -> Path,
    private val launchDir: Path,
    private val secretEnvNames: Set<String>,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
    private val shell: List<String> = listOf("bash", "-l"),
    private val readEnv: (String) -> String? = System::getenv,
    /** 每个进程一个回收器；参数是启动脚本记下 msys pid 的文件（只有 Windows 用得上，见 [MsysProcesses]） */
    private val newReaper: (pidFile: Path) -> ProcessReaper = { pidFile ->
        if (windows) {
            HandleProcessReaper({ MsysProcesses(shell.first()).newDiscoverer(pidFile) }, { Files.deleteIfExists(pidFile) })
        } else {
            JvmProcessReaper
        }
    },
) : ProcessPort {

    override suspend fun spawn(spec: ProcessSpec): LiveProcess {
        val dir = hostDirOf(spec.cwd)
        val env = ProcessEnv.build(readEnv, spec.env, secretEnvNames, windows)
        val name = "launch-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val script = launchDir.resolve("$name.sh")
        val pidFile = launchDir.resolve("$name.pid")
        val live = try {
            spawnLive(spec.label, newReaper(pidFile)) {
                Files.createDirectories(launchDir)
                sweepStale()
                Files.write(script, launcherScript(spec.argv, pidFile).toByteArray(Charsets.UTF_8))
                val builder = ProcessBuilder(shell + script.toAbsolutePath().toString()).directory(dir.toFile())
                builder.environment().apply { clear(); putAll(env) }
                builder.start()
            }
        } catch (e: Throwable) {
            Files.deleteIfExists(script)
            Files.deleteIfExists(pidFile)
            throw e
        }
        // 启动脚本进程一结束就可以删；pid 文件要留到回收做完（孤儿还在时回收要靠它认回，见 ProcessTree.close）
        live.exit.invokeOnCompletion { Files.deleteIfExists(script) }
        return live
    }

    /** 自然退出的进程没人走回收，pid 文件会留下：下次起进程时顺手清掉一周前的 */
    private fun sweepStale() {
        val cutoff = System.currentTimeMillis() - STALE_MS
        Files.list(launchDir).use { files ->
            files.filter { it.fileName.toString().startsWith("launch-") && Files.getLastModifiedTime(it).toMillis() < cutoff }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /**
     * Windows 上第一行把自己的 msys pid 写进 [pidFile]（路径用 bash 认得的写法），之后 `exec` 保持同一个 pid——
     * 回收时靠它认回 fork 出来的、Windows 眼里没有父进程的孙进程。
     */
    private fun launcherScript(argv: List<String>, pidFile: Path): String {
        val record = if (windows) "echo \$\$ > ${posixQuote(bashPath(pidFile))}\n" else ""
        return record + "exec " + argv.joinToString(" ") { posixQuote(it) } + "\n"
    }

    private companion object {
        const val STALE_MS = 7L * 24 * 3600 * 1000
    }
}

/**
 * CLI 自己读的、不许带进长寿子进程的凭证环境变量名：会话模型与快速模型的 key（`--api-key-env` 可改名）和两个搜索后端的 key。
 * 新增任何 CLI 从环境变量读的凭证，都要加到这里。
 */
internal fun desktopSecretEnvNames(vararg modelKeyEnvs: String?): Set<String> =
    (modelKeyEnvs.filterNotNull() + listOf(BRAVE_KEY_ENV, TAVILY_KEY_ENV)).toSet()
