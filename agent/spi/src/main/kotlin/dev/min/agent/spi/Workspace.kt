package dev.min.agent.spi

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * rootfs 里（guest 视角）的绝对路径。内核与工具只说 guest 路径；
 * 映射到宿主真实路径是宿主实现（Android 的 PathMapper）的事，内核不知道宿主路径长什么样。
 */
@Serializable
@JvmInline
value class GuestPath(val value: String) {
    init {
        require(value.startsWith("/")) { "guest path must be absolute: $value" }
    }

    override fun toString(): String = value
}

enum class FileKind { File, Directory, Symlink, Other }

data class FileStat(val kind: FileKind, val size: Long, val modifiedMs: Long)

data class DirEntry(val name: String, val kind: FileKind)

/**
 * 文件系统端口。Android 宿主在 App 进程里直接读写 rootfs（不经 proot：实测 proot 内读小文件慢 50 倍），
 * 桌面宿主直接读写本地目录。越界（映射到 rootfs 与 bind mount 之外）一律抛异常，由宿主实现保证。
 */
interface WorkspaceFs {
    suspend fun stat(path: GuestPath): FileStat?

    /** 读 [offset] 起最多 [limit] 字节 */
    suspend fun read(path: GuestPath, offset: Long = 0, limit: Int = Int.MAX_VALUE): ByteArray

    /** 先写临时文件再改名，保住原文件的权限位；写一半被杀不会留下半个文件。父目录不存在就建 */
    suspend fun writeAtomic(path: GuestPath, bytes: ByteArray)

    /** 只删文件，不递归目录；沿用 writeAtomic 的映射与只读挂载边界。 */
    suspend fun deleteFile(path: GuestPath) { throw UnsupportedOperationException("宿主没有文件删除端口") }

    suspend fun list(dir: GuestPath): List<DirEntry>

    fun scopeOf(path: GuestPath): PathScope
}

data class GrepQuery(
    val pattern: String,
    val root: GuestPath,
    val include: String? = null,
    val ignoreCase: Boolean = false,
    val literal: Boolean = false,
    /** 只限制实际命中（isContext=false）的条数；附带上下文不占配额，输出行预算由工具单独处理 */
    val maxHits: Int = 500,
    /** 命中行前后各带几行上下文（ripgrep 的 -C） */
    val contextLines: Int = 0,
    /** 允许跨行匹配（ripgrep 的 -U --multiline-dotall） */
    val multiline: Boolean = false,
    /** 凭证必须在读文件前排除，且不占命中预算。 */
    val exclude: (GuestPath) -> Boolean = { false },
)

/** [isContext] 为真表示这是命中行旁边的上下文行，不是命中本身 */
data class GrepHit(val path: GuestPath, val line: Int, val text: String, val isContext: Boolean = false)

data class GlobQuery(val pattern: String, val root: GuestPath, val maxResults: Int = 1_000, val exclude: (GuestPath) -> Boolean = { false })

/** 搜索端口。Android 宿主用随 APK 带的 ripgrep 在宿主路径上跑，结果映射回 guest 路径 */
interface Search {
    fun grep(query: GrepQuery): Flow<GrepHit>
    fun glob(query: GlobQuery): Flow<GuestPath>
}

data class ShellCommand(
    val script: String,
    val cwd: GuestPath,
    val env: Map<String, String> = emptyMap(),
    val network: GuestNetwork = GuestNetwork.Offline,
    /**
     * 要宿主在命令正常跑完后报告 shell 最终停在哪个目录（[ExecResult.Exited.endCwd]），让 Bash 的 `cd` 能带到下一条命令。
     * 做法在宿主：把脚本包一层（[ShellCwdProbe]）、目录写进宿主自己的临时文件，**不走输出流**——给模型和给用户的输出、
     * 日志、后台通知里因此都没有标记，输出被截断也不会丢。默认关：hook、Monitor、Skill 的 shell 与后台 hook 都不要这个。
     */
    val trackCwd: Boolean = false,
    /**
     * 每次都先在同一个 shell 里执行的前导脚本（Bash 的 `CLAUDE_ENV_FILE`：hook 写进去的 `export` 要在命令之前生效，
     * 见 [ShellPreamble]）。由内核在权限判定**之后**、起进程之前设置，宿主只负责接在脚本前面：
     * [script] 仍是模型写的原文，权限分析、网络批准卡、日志看到的都是它，不含前导。默认空
     */
    val preamble: String? = null,
)

data class ExecOptions(
    val timeoutMs: Long,
    /** 前台最多等这么久，还没结束就转后台、返回句柄（Codex 的 10 s 做法）；null = 一直等到超时 */
    val foregroundBudgetMs: Long? = null,
    val maxOutputChars: Int = ToolTraits.DEFAULT_RESULT_BUDGET_CHARS,
)

/** 后台进程的句柄。落进 ToolEffectPending，进程被杀后凭它重新挂上 */
@Serializable
data class ExecHandle(val id: String, val pid: Int? = null, val logPath: GuestPath? = null)

sealed interface ExecResult {
    /** [output] 是 stdout 与 stderr 按到达顺序合并、超长时保留头尾各一半的结果 */
    data class Exited(
        val exitCode: Int,
        val output: String,
        val truncated: Boolean,
        val durationMs: Long,
        /**
         * 命令结束时 shell 所在目录（宿主已换成 guest 路径）。只在 [ShellCommand.trackCwd] 且命令跑完了才有：
         * 脚本里 `exit` / `exec` / `set -e` 提前结束、宿主认不出路径时为 null，调用方当作这次没换目录
         */
        val endCwd: GuestPath? = null,
        /** 内核追加给模型的一句说明（如 `Shell cwd was reset to <dir>`），工具把它接在结果末尾；宿主不会填 */
        val note: String? = null,
    ) : ExecResult
    data class TimedOut(val output: String, val truncated: Boolean) : ExecResult
    data class Backgrounded(val handle: ExecHandle, val outputSoFar: String) : ExecResult
}

/**
 * 进程端口。Android 宿主进 proot；kill 必须按进程组回收——
 * 基线实测 SIGKILL 掉 proot 后 tracee 全部存活（research/baseline）。
 */
interface Exec {
    /** null 表示这个宿主没有 guest 隔离；不能由 IS_SANDBOX 或代理 env 推断。 */
    val guestIsolation: GuestIsolation? get() = null
    suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult
    suspend fun kill(handle: ExecHandle)

    /**
     * 句柄对应的进程还活着吗：宿主按自己登记的 Process 直接判断，**不起探测进程**（N3：靠 `kill -0`
     * 探活会让每个存活 hook 每几秒起一个进程，exec-logs 下的日志无限堆积）。null = 这个端口说不了（默认），
     * 调用方退回自己的探测
     */
    suspend fun isAlive(handle: ExecHandle): Boolean? = null

    /**
     * 后台句柄的退出码：宿主在进程结束时记下，之后能查（只留最近一批）。null = 还在跑、句柄不是本端口发的、
     * 记录已被挤掉，或这个端口说不了（默认）。为什么要有它：后台命令跑完要告诉模型「成功还是失败」，
     * 只知道「不在了」（[isAlive] 为假）区分不了；日志文件里也没有退出码
     */
    suspend fun exitOf(handle: ExecHandle): Int? = null

    /**
     * 本宿主进程的 pid：后台 hook 暂存目录的 owner 记号用它（实例 id + pid），启动清扫据此认「这是谁留下的」。
     * 默认 0 = 说不了（owner 记号写 0，清扫对它认不了主、不敢删）。
     * 为什么走端口：内核拿 pid 只有 `ProcessHandle.current()`，Android 33 以下没有它——
     * 宿主侧（`android.os.Process.myPid()`）才知道自己平台怎么取
     */
    fun currentPid(): Long = 0L

    /**
     * 宿主 pid 还活着吗（按宿主自己的进程表，不是 guest shell 的 `kill -0`）：启动清扫判「owner 进程死了没」用。
     * 桌面多终端的 owner 是别的 JVM 进程、Android 是前一世的 App 进程，都只能从宿主侧看；
     * Git Bash 的 `kill -0` 拿 Windows pid 问 msys 进程表，对活进程也报「不存在」，会把活实例的目录当孤儿删掉。
     * null = 这个端口说不了（默认），调用方按「活着」处理——删错了没有回头路
     */
    suspend fun isPidAlive(pid: Long): Boolean? = null
}

/** 大输出、截图等会话附件。返回会话 artifacts 目录下的相对路径 */
interface ArtifactSink {
    suspend fun put(name: String, bytes: ByteArray): String

    /**
     * [put] 同名附件会返回的路径，**不写任何东西**；这个宿主说不出就返回 null（默认）。
     * 给「路径要事先告诉别的进程、文件由对方去写」的用法：`CLAUDE_ENV_FILE` 要在 hook 启动前把路径放进它的环境，
     * 而 [put] 会覆盖已有内容，不能拿来探路
     */
    fun locate(name: String): String? = null
}
