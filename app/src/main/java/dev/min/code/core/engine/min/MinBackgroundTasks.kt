package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ExecHandle
import dev.min.code.core.engine.BackgroundTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * 进程内记一份「转入后台的命令」：[MinExec] 在 Bash 返回 `Backgrounded` 时登记，用户停止 / 进程退出时更新状态。
 *
 * ## 为什么要自己记
 *
 * 内核没有把后台句柄留在日志里（`ToolEffectPending` 总是不带 handle，`ExecResult.Backgrounded` 只进了工具结果的文字），
 * `ProotExec` 又没有列表接口，所以界面要的「任务条」没有现成数据源。这里在宿主侧登记，再由 [attributeTasks]
 * 按「哪个会话的工具结果里出现了这个句柄 id」归到会话——id 是 32 位十六进制，Bash 的回执必须把它告诉模型，
 * 所以靠它归属比解析回执的措辞稳。**进程重启后登记表是空的**（设计 §3.8：Min 的后台句柄重启后会丢，M3 再管）。
 *
 * 状态只有三种：在跑 / 自己结束了（pid 不在了）/ 被用户停了。pid 拿不到时没法判断自然结束，保持「在跑」直到被停。
 */
class MinBackgroundTasks(
    private val clock: () -> Long = System::currentTimeMillis,
    private val alive: (Int) -> Boolean = { pid -> File("/proc/$pid").exists() },
) {
    data class Tracked(
        val handle: ExecHandle,
        val command: String,
        val startedAt: Long,
        val status: BackgroundTask.Status = BackgroundTask.Status.Running,
        val endedAt: Long? = null,
    )

    private val _tasks = MutableStateFlow<List<Tracked>>(emptyList())
    val tasks: StateFlow<List<Tracked>> = _tasks.asStateFlow()

    @Synchronized
    fun started(handle: ExecHandle, command: String) {
        _tasks.value = _tasks.value.filterNot { it.handle.id == handle.id } + Tracked(handle, command, clock())
    }

    /** 用户停了它。已经结束的不改（别把「完成」改写成「被停」） */
    @Synchronized
    fun stopped(id: String) = update(id) { if (it.status == BackgroundTask.Status.Running) it.copy(status = BackgroundTask.Status.Stopped, endedAt = clock()) else it }

    /** 对一遍 pid：不在了就记为自己结束 */
    @Synchronized
    fun refresh() {
        _tasks.value = _tasks.value.map { t ->
            val pid = t.handle.pid
            if (t.status == BackgroundTask.Status.Running && pid != null && !alive(pid)) {
                t.copy(status = BackgroundTask.Status.Done, endedAt = clock())
            } else t
        }
    }

    private fun update(id: String, change: (Tracked) -> Tracked) {
        _tasks.value = _tasks.value.map { if (it.handle.id == id) change(it) else it }
    }

    /**
     * 有在跑的任务时每 [intervalMs] 对一遍 pid，没有就睡着（不白白唤醒 CPU）。组合根在引擎的作用域里 launch 它。
     * 2 秒：任务条上「完成」晚两秒出现没人在意，而手机上轮询越稀越省电。
     */
    suspend fun watch(intervalMs: Long = 2_000) {
        _tasks.map { list -> list.any { it.status == BackgroundTask.Status.Running && it.handle.pid != null } }
            .collectLatest { anyRunning ->
                while (anyRunning) {
                    delay(intervalMs)
                    refresh()
                }
            }
    }

    fun find(id: String): Tracked? = _tasks.value.firstOrNull { it.handle.id == id }
}

/**
 * 把登记表里的任务归给树里出现过它句柄 id 的会话（见 [MinBackgroundTasks] 头注释）。
 * 同一个句柄出现在几个会话里（子 agent 读了父会话的日志路径之类）时，归给第一个发起它的：按 [nodes] 顺序、
 * 先出现在 Bash 回执里的那个——这里只看「结果文字里含 id」，所以实际上取第一个含它的调用。
 */
fun attributeTasks(tracked: List<MinBackgroundTasks.Tracked>, nodes: Collection<SessionState>, label: (String) -> String): List<BackgroundTask> {
    if (tracked.isEmpty()) return emptyList()
    return tracked.mapNotNull { t ->
        val call = nodes.firstNotNullOfOrNull { s ->
            s.toolResults.entries.firstOrNull { (_, r) -> r.content.any { it is TextPart && t.handle.id in it.text } }?.key
        } ?: return@mapNotNull null
        BackgroundTask(
            id = t.handle.id, kind = BackgroundTask.Kind.Shell, label = label(t.command), status = t.status,
            startedAt = t.startedAt, endedAt = t.endedAt, toolUseId = call.value, pid = t.handle.pid, logRef = t.handle.logPath?.value,
        )
    }
}
