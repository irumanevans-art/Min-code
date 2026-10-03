package dev.min.agent.spi

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * 长寿进程端口（docs/agent/detail/MCP设计.md §2）。[Exec] 只有「跑完才返回」，MCP 的 stdio 服务器要一个
 * 能一直读写 stdin / stdout 的进程，所以另开一个端口，不去扩 [Exec]。
 *
 * 实现：桌面 `ProcessBuilder`（Windows 与 [Exec] 同走 Git Bash 的路径映射）；Android 进 proot，与 Exec 共用同一代挂载快照。
 */
interface ProcessPort {
    /**
     * 起一个长寿进程。起不来（配额满、可执行文件不存在、环境不可用）抛 [ProcessSpawnException]，消息可以直接给用户看。
     *
     * 取消语义：调用方协程在 spawn 中途被取消，不会留下进程；返回之后进程的存活**不超过调用方的 Job**——
     * 该 Job 以取消或失败结束时整棵进程树被回收（正常结束不管，由持有者调 [LiveProcess.shutdown]）。
     */
    suspend fun spawn(spec: ProcessSpec): LiveProcess

    /** 默认的不可用端口：没有接进程能力的宿主与测试替身用它，spawn 报明确的错误而不是静默失败 */
    object Unavailable : ProcessPort {
        override suspend fun spawn(spec: ProcessSpec): LiveProcess =
            throw ProcessSpawnException("这个环境没有配置长寿进程端口，无法启动「${spec.label}」。")
    }
}

/**
 * @property argv 命令与参数，原样交给系统执行（不经 shell 解析、不做通配与变量展开）
 * @property cwd 工作目录（guest 路径，由宿主映射成真实路径）
 * @property env 额外环境变量，叠在宿主的白名单继承之上；宿主管理的凭证即使写在这里也会被剔除
 * @property label 给人看的名字（面板、日志、配额说明里用），例如服务器名
 */
data class ProcessSpec(
    val argv: List<String>,
    val cwd: GuestPath,
    val env: Map<String, String> = emptyMap(),
    val label: String,
) {
    init {
        require(argv.isNotEmpty() && argv.first().isNotBlank()) { "argv 不能为空" }
    }
}

/**
 * 一个正在跑的进程。
 *
 * [stdout] 与 [stderr] 各自**只能收集一次**，从进程起来的第一个字节开始缓存，晚收集不丢数据；
 * 进程结束（或 [shutdown]）后流正常结束。stdout 有界缓冲：不收集就会把管道塞满、进程阻塞，和真管道一样；
 * stderr 是日志，缓冲满了丢最旧的块，不会反压进程。
 */
interface LiveProcess {
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>

    /** 写 stdin。stdin 已关或进程已死抛 [java.io.IOException] */
    suspend fun write(bytes: ByteArray)

    suspend fun closeStdin()

    /** 退出码；完成即进程结束（被信号杀掉时按系统习惯是 128 + 信号） */
    val exit: Deferred<Int>

    /**
     * 关停：关 stdin → 等 2 秒 → 进程组 SIGTERM → 2 秒 → SIGKILL（MCP 规范的 stdio 关停顺序 + Codex 的时限）。
     * 「进程组」指这个进程的整棵后代树，不止直接子进程。可重复调用；调用方被取消时收尾照样做完。
     */
    suspend fun shutdown()
}

/** [ProcessPort.spawn] 起不来。[message] 是给用户的完整说明 */
open class ProcessSpawnException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
