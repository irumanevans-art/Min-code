package dev.min.agent.kernel

import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * 长寿进程（MCP stdio 服务器等）的配额，计在 [HostPorts.processes] 这一层：一个宿主一份，所有会话与子 agent 共用。
 *
 * 为什么要有：Android 12+ 的子进程总数超过上限（全系统所有 App 合计，默认 32 个）时，系统会 SIGKILL 掉一些子进程。
 * 2026-11-07 真机（vivo PD2309，Android 16，默认配置）证实会触发，而且杀的是新起的那批：Claude Code 会话的
 * proot / bash / claude 被杀，更早留下的孤儿反而活着。会话被系统杀掉比我们拒绝起一个服务器糟得多
 * （docs/agent/02-总体架构.md 的子进程预算）。
 * 超额时 [spawn] 抛带说明的 [ProcessSpawnException]，不起进程，不排队——MCP 的调用方会把说明给用户看。
 */
class QuotaProcessPort(
    private val delegate: ProcessPort,
    val limit: Int = MAX_LONG_LIVED_PROCESSES,
) : ProcessPort {
    /** 占着名额的标签。名额从预留（起进程前）到进程退出才释放 */
    private val reserved = ConcurrentHashMap<Any, String>()

    val activeCount: Int get() = reserved.size

    override suspend fun spawn(spec: ProcessSpec): LiveProcess {
        val ticket = Any()
        synchronized(reserved) {
            if (reserved.size >= limit) {
                throw ProcessSpawnException(
                    "长寿进程已达上限 $limit 个（正在跑：${reserved.values.sorted().joinToString("、")}），" +
                        "没有启动「${spec.label}」。先关掉不用的服务器再试。",
                )
            }
            reserved[ticket] = spec.label
        }
        try {
            val process = delegate.spawn(spec)
            process.exit.invokeOnCompletion {
                reserved.remove(ticket)
            }
            return process
        } catch (t: Throwable) {
            reserved.remove(ticket)
            throw t
        }
    }

    companion object {
        /**
         * 同时存在的长寿进程上限。**暂定。**
         * 来历：全系统 phantom 上限 32（模拟器上没触发，2026-11-07 真机上触发了，见类注释），给会话 shell 与后台任务各留余量；
         * MCP 设计 §4 的 stdio 并发上限 3 是「同时在连」，这里是「同时存在」，两个数不是一回事。
         * 算名额时注意：Android 上一个长寿进程至少占 3 个系统名额（proot + 包它的 bash + 服务器本身，见 App 的
         * ProotProcessPort），8 个就是 24 个以上。还没改小，因为没有 Min 引擎里 MCP 服务器实际开几个的真机数据，
         * 改小会让开得多的用户直接起不来服务器；有了数据再定。
         */
        const val MAX_LONG_LIVED_PROCESSES = 8
    }
}
