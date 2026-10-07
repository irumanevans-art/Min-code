package dev.min.code.core.engine.min

import dev.min.agent.host.ProcessEnv
import dev.min.agent.host.ProcessReaper
import dev.min.agent.host.ProcessTree
import dev.min.agent.host.spawnLive
import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import me.rerere.workspace.ProcessTreeKill
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceShellContext

/** 窄进程接缝：JVM 单测里换成起真实 JDK 进程的替身，验证取消与回收的时序（同 [ProotProcessHost] 的用意） */
internal interface ProotLiveHost {
    /** 起 proot；环境不可用抛 [ProcessSpawnException] */
    fun launch(context: WorkspaceShellContext): Process
    fun reaperFor(): ProcessReaper
}

internal class AndroidProotLiveHost(private val runner: ProotShellRunner) : ProotLiveHost {
    override fun launch(context: WorkspaceShellContext): Process =
        runner.launch(context) ?: throw ProcessSpawnException(runner.checkAvailability(context) ?: "无法启动 proot")

    override fun reaperFor(): ProcessReaper = ProcProcessReaper()
}

/**
 * [ProcessPort] 的 Android 实现：进 proot 起长寿进程。与 [ProotExec] 用同一份 [base]（同一代挂载快照，同源挂载原则），
 * 命令行由唯一的 [ProotShellRunner] 拼装。换代时已起的进程留在起它的那一代：[LiveProcess] 自己握着进程与回收手段，
 * 关停不经过「当前一代」（同 [MinExec.kill] 的做法，只是这里不需要按 id 找）。
 *
 * - **环境变量**：proot 本来就是 `env -i` + 固定的 HOME / PATH / LANG / TERM / USER / SHELL；[base] 里的 env 一概不继承
 *   （[agentCommandContext] 已清空），只叠加 spec.env，且 [secretEnvNames] 里的名字一律剔除。Android 上模型 key 在
 *   Keystore 里，从不进任何环境变量，[secretEnvNames] 现在是空集，留着是为了以后有凭证类变量时有地方登记。
 * - **argv**：整个 `exec 'a' 'b c'`（POSIX 单引号转义）交给 guest 里的 bash，不经宿主命令行，也没有 Windows 的引号问题。
 * - **回收**：[ProcProcessReaper]（复用 [ProcessTreeKill] 的 /proc 扫描与进程组之外的树回收）。
 */
internal class ProotProcessPort(
    private val base: WorkspaceShellContext,
    private val host: ProotLiveHost,
    private val secretEnvNames: Set<String> = emptySet(),
) : ProcessPort {
    override suspend fun spawn(spec: ProcessSpec): LiveProcess {
        // 不用 exec：那样服务器直接成了 proot 的被跟踪进程，被 SIGKILL 杀掉时 proot 自己以 0 退出（模拟器实测：kill -9 之后
        // 内核报「退出码 0」，像正常退出）。留一个 bash 当父进程，它的 `$?` 对信号杀死给 128+N（137 = SIGKILL），
        // 再用 exit 原样交出去；代价是每个长寿进程多一个 bash（几 MB），回收时进程树照旧自底向上杀
        val script = spec.argv.joinToString(" ") { shellQuote(it) } + "\nexit \$?"
        val env = ProcessEnv.build({ null }, spec.env, secretEnvNames, windows = false)
        val context = agentCommandContext(base, spec.cwd, script).copy(env = env)
        return spawnLive(spec.label, host.reaperFor()) {
            try {
                host.launch(context)
            } catch (e: IllegalArgumentException) {
                // ProotShellRunner 校验环境变量名（"Invalid env key"）
                throw ProcessSpawnException("无法启动「${spec.label}」：${e.message}", e)
            }
        }
    }
}

/**
 * 基于 /proc 的进程树回收：proot 的 tracee 是宿主上的真实进程，从 proot 的 pid 出发自底向上杀，最后才毁 proot 本身
 * （倒过来的话 proot 一死后代全被 reparent 到 init，树就再也走不到了——[ProcessTreeKill] 的「教训」）。
 * 进程组杀不可用：`ProcessBuilder` 不 setsid，proot 与 App 同组，杀组等于杀自己。
 *
 * 所有 IO 可注入，JVM 单测里换成 `ProcessHandle` 版本；默认实现（/proc、android.os.Process）只在设备上跑。
 */
internal class ProcProcessReaper(
    private val pidOf: (Process) -> Int? = ProcessTreeKill::pidOf,
    private val childrenReader: () -> (Int) -> List<Int> = { ProcessTreeKill.procChildrenReader() },
    private val isAlive: (Int) -> Boolean = ProcessTreeKill.procAlive(),
    private val sendSignal: (pid: Int, signal: Int) -> Unit = { pid, signal -> android.os.Process.sendSignal(pid, signal) },
) : ProcessReaper {
    override fun capture(process: Process): ProcessTree = Tree(process, pidOf(process))

    private inner class Tree(private val process: Process, private val rootPid: Int?) : ProcessTree {
        /** 后代，自底向上；根（proot 宿主进程）单独处理，永远最后动 */
        private val descendants = LinkedHashSet<Int>()

        @Synchronized
        override fun refresh() {
            val root = rootPid ?: return
            val children = childrenReader()
            // 根还活着或已死都扫：已死成员的后代在更早的快照里；这里从根和所有已知活成员出发补新出现的
            for (start in listOf(root) + descendants.filter { isAlive(it) }) {
                ProcessTreeKill.descendantsBottomUp(start, children).forEach { descendants += it }
            }
        }

        @Synchronized
        private fun aliveDescendants(): List<Int> = descendants.filter { isAlive(it) }

        override fun terminate() = signal(ProcessTreeKill.SIGTERM)

        override fun kill() {
            signal(ProcessTreeKill.SIGKILL)
            // Android 的 Process.destroy() 只发 SIGTERM（ProcessTreeKill「教训二」），这里只为关三条管道
            try {
                process.destroy()
            } catch (_: RuntimeException) {
            }
        }

        private fun signal(sig: Int) {
            for (pid in aliveDescendants()) {
                try {
                    sendSignal(pid, sig)
                } catch (_: RuntimeException) {
                    // 它自己刚没了
                }
            }
            val root = rootPid ?: return
            if (isAlive(root)) {
                try {
                    sendSignal(root, sig)
                } catch (_: RuntimeException) {
                }
            }
        }

        override fun anyAlive(): Boolean = (rootPid?.let { isAlive(it) } ?: process.isAlive) || aliveDescendants().isNotEmpty()
    }
}
