package dev.min.agent.kernel

import dev.min.agent.spi.LiveProcess
import dev.min.agent.spi.ProcessPort
import dev.min.agent.spi.ProcessSpawnException
import dev.min.agent.spi.ProcessSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * 长寿进程（MCP stdio 服务器等）的配额，计在 [HostPorts.processes] 这一层：一个宿主一份，所有会话与子 agent 共用。
 *
 * 为什么要有：Android 12+ 会悄悄杀掉「phantom 子进程」超过上限的 App（全系统 32 个，真机上限未测），
 * 被系统杀掉整个 App 比我们拒绝起一个服务器糟得多（docs/agent/02-总体架构.md 的子进程预算）。
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
         * 同时存在的长寿进程上限。**暂定，真机测过 phantom 进程上限后再调。**
         * 来历：全系统 phantom 上限 32（模拟器上实测未触发），给会话 shell 与后台任务各留余量；
         * MCP 设计 §4 的 stdio 并发上限 3 是「同时在连」，这里是「同时存在」，两个数不是一回事。
         */
        const val MAX_LONG_LIVED_PROCESSES = 8
    }
}
