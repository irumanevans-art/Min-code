package dev.min.agent.kernel

import dev.min.agent.ext.hooks.FileChangedInput
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.spi.CwdWatchPathsChanged
import dev.min.agent.spi.Durability
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject

/**
 * 文件监视（官方 hooks 页「FileChanged」「ConfigChange」）：本会话的 FileChanged 与 ConfigChange 两路监视共用这一个轮询器。
 *
 * **为什么轮询、什么时候轮询**：Min 的内核在桌面 JVM 与 Android 的 proot 路径映射之后都只有一个 [dev.min.agent.spi.WorkspaceFs] 端口，
 * 平台的文件通知（inotify / WatchService）桌面有、Android 上 App 进程读 rootfs 的路径没有同一套，所以统一用 `stat` 比对：同一份代码两边都对。
 * 会话不在跑的时候不保活、不唤醒任何东西——只在回合进行中每 [pollMs] 看一次，另外在每个回合开始前、每批工具跑完之后、回合结束时各看一次
 * （工具改了文件，下一个模型请求之前 hook 就跑完了，hook 写进 `CLAUDE_ENV_FILE` 的变量下一条 Bash 就能用）。
 * 与原文的差别：原文的 watcher 一直在，空闲时文件变了也立刻触发；这里空闲时的变化推迟到下一个回合开始前触发（hook 一样会跑，只是晚一些）。
 * 比对用 `(大小, 修改时间)`：时间戳精度之内、大小不变的改写看不出来。
 *
 * 只有根会话监视（子 agent 没有自己的 hooks 配置面）。
 */
internal class FileWatches(private val rt: SessionRuntime) {
    private val lock = Mutex()
    private val driver by lazy(LazyThreadSafetyMode.PUBLICATION) { HookDriver(rt) }
    internal val config = ConfigWatch(rt)

    /** 回合进行中的轮询间隔。默认值即生产值；内核测试调小 */
    @Volatile
    internal var pollMs: Long = 2_000

    /** 监视中的文件上一次看到的样子；null = 还没有任何要监视的东西（原文：有人点名了文件才起 watcher），清单变空就回到 null */
    private var baseline: Map<GuestPath, FileStat?>? = null

    /** 轮询时统计到「读不了」的路径，每个只说一次 */
    private val unreadable = mutableSetOf<GuestPath>()

    /** 回合开始：起一个只在这个回合里存活的轮询协程。子会话与没有主目录的宿主不监视 */
    fun beginTurn(): Job? {
        if (rt.state.value.origin != null) return null
        // 没有主目录（读不了设置文件）、也没有被点名的文件时，没有东西可看：连轮询协程都不起（回合收尾不多拖任何东西）
        if (rt.deps.memoryHome == null && watchTargets().isEmpty()) return null
        return rt.hostScope.launch {
            while (isActive) {
                delay(pollMs)
                pollNow()
            }
        }
    }

    /** 立刻看一遍：先配置文件（ConfigChange），再被监视的文件（FileChanged）。多处同时调用时排队，不会重复触发 */
    suspend fun pollNow() {
        if (rt.state.value.origin != null) return
        lock.withLock {
            // 监视出任何意外（设置文件读崩、hook 执行器抛出……）都不许带倒回合：记一条说明，下次轮询再试
            catchingNonCancellation {
                config.poll(driver)
                pollFiles()
            }.onFailure { e ->
                catchingNonCancellation {
                    rt.commit(listOf(Note(NoteLevel.Warning, "文件监视出错（${e.javaClass.simpleName}），本次跳过。")), Durability.Batched)
                }
            }
        }
    }

    /** 会话创建 / 打开时记下基线（之后的变化才算「变了」）。没有东西要监视就什么也不做 */
    suspend fun arm() {
        if (rt.state.value.origin != null) return
        lock.withLock {
            config.baseline()
            val targets = watchTargets()
            if (targets.isNotEmpty()) baseline = snapshot(targets)
        }
    }

    /** 现在该盯的文件：matcher 里点名的（工作目录里的字面文件名）+ hooks 动态返回的 watchPaths */
    private fun watchTargets(): List<GuestPath> {
        val s = rt.state.value
        val named = driver.fileChangedWatchNames().map { GuestPath(s.settings.cwd.value.trimEnd('/') + "/" + it) }
        return (named + s.cwdWatchPaths).distinct()
    }

    private suspend fun snapshot(targets: List<GuestPath>): Map<GuestPath, FileStat?> = targets.associateWith { path ->
        val stat = catchingNonCancellation { rt.deps.host.fs.stat(path) }
        if (stat.isFailure && unreadable.add(path)) {
            rt.commit(listOf(Note(NoteLevel.Warning, "FileChanged：${path.value} 不在宿主能读的范围内，这个文件不监视。")), Durability.Batched)
        }
        stat.getOrNull()
    }

    private suspend fun pollFiles() {
        val targets = watchTargets()
        if (targets.isEmpty()) {
            baseline = null
            return
        }
        val current = snapshot(targets)
        val before = baseline
        if (before == null) {
            baseline = current
            return
        }
        // 新进监视清单的路径只记基线、不报变化（清单是动态的，加进来那一刻它的现状不是「变化」）；掉出清单的丢掉
        var kept = before.filterKeys { it in current } + current.filterKeys { it !in before }
        baseline = kept
        for ((path, now) in current) {
            if (path !in before) continue
            val old = before[path]
            val event = when {
                old == null && now != null -> "add"
                old != null && now == null -> "unlink"
                old != null && now != null && (old.size != now.size || old.modifiedMs != now.modifiedMs) -> "change"
                else -> continue
            }
            runFileChanged(path, event)
            // 基线在 hook 跑完之后才前移：hook 跑到一半被取消（回合结束 / 会话卸下），这次变化下次轮询还会再报
            kept = kept + (path to now)
            baseline = kept
        }
    }

    /**
     * FileChanged hook：matcher 对被改文件的 basename，输入 `file_path`（绝对路径）与 `event`（change / add / unlink）。
     * 无决策控制；`continue` 丢弃；读 `watchPaths`（替换动态监视清单）与 `systemMessage`（给人看的一条提示）。CLAUDE_ENV_FILE 由 [HookDriver] 发放
     */
    private suspend fun runFileChanged(path: GuestPath, event: String) {
        val name = path.value.substringAfterLast('/')
        val specific = HookJson.encodeToJsonElement(FileChangedInput.serializer(), FileChangedInput(rt.deps.hookShellPath(path), event)).jsonObject
        val outcome = driver.run(HookEvent.FileChanged, name, specific) ?: return
        rt.applyWatchPaths(watchPathsOf(outcome), rt.state.value.settings.cwd, "FileChanged")
    }
}

/**
 * hook 输出里的 `watchPaths`（CwdChanged / FileChanged 写在顶层）：取退出码 0 的各处理器里最后一个写了这个键的——null = 没有人写（保持原清单），
 * 空列表 = 要清空（原文：「Returning an empty array clears the dynamic list」）。路径是否绝对由 [SessionRuntime.applyWatchPaths] 校验
 */
internal fun watchPathsOf(outcome: HookOutcome?): List<String>? = outcome?.runs?.filter { it.exitCode == 0 }?.mapNotNull { run ->
    catchingNonCancellation {
        HookJson.decodeFromString(dev.min.agent.ext.hooks.HookOutputCommon.serializer(), run.stdout.trim()).watchPaths
    }.getOrNull()
}?.lastOrNull()

/**
 * 替换动态监视清单（原文：「Replaces the current dynamic watch list」；matcher 里写的路径永远在，不在这张表里）。
 * [paths] = null 不动；必须是绝对路径，写错的整份不采纳并留一条说明（保留原清单）。落 [CwdWatchPathsChanged]，重开会话沿用
 */
internal suspend fun SessionRuntime.applyWatchPaths(paths: List<String>?, base: GuestPath, from: String) {
    if (paths == null) return
    val resolved = catchingNonCancellation {
        paths.map {
            require(it.startsWith('/') || Regex("^[A-Za-z]:[/\\\\].*").matches(it)) { "watchPaths 必须是绝对路径。" }
            Worktrees.resolved(it, base)
        }
    }
    resolved.onSuccess { if (it != state.value.cwdWatchPaths) commit(listOf(CwdWatchPathsChanged(it)), Durability.Sync) }
        .onFailure { commit(listOf(Note(NoteLevel.Warning, "$from watchPaths 无效，保留原监视列表。")), Durability.Sync) }
}
