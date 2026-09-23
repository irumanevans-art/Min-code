package dev.min.code.core.service

import android.content.Context
import android.util.Log
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.claudecode.GuestRuntimeDocs
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.activeDnsServers
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.relay.RelayController
import dev.min.code.core.settings.SettingsStore
import dev.min.code.core.settings.shellCredentialEnv
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.workspace.ProcessTreeKill
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LocalServiceRegistry"

enum class LocalServiceStatus { Starting, Running, Exited, Failed }

enum class LocalServiceStopReason {
    UserStop,
    StopAll,
    Crash,
    StartFailed,
    PortBusy,
}

data class LocalService(
    val id: String,
    val label: String,
    val command: String,
    val cwdGuest: String,
    val port: Int?,
    val status: LocalServiceStatus,
    val startedAtEpochMs: Long,
    val sourceSessionKey: String? = null,
    val exitCode: Int? = null,
    val stopReason: LocalServiceStopReason? = null,
    val lastError: String? = null,
    /** 最近日志尾（面板展开用）；完整环在 registry 内 */
    val logTail: String = "",
)

/**
 * 长驻本地服务的**唯一**进程表。
 *
 * - 独立 proot（`killOnExit=false`），不挂在某个 Claude 会话树上 → 关对话还在。
 * - 入口是 agent/会话链的 [startFromAgent]，不是面板上的第二种启动仪式。
 * - 面板 / FGS / 通知都读 [services]；没有句柄的不标 Running。
 * - 进程表在内存里，但服务比 App 进程活得久 → 开机走一次 [reconcile] 把上次留下的
 *   还活着的服务认回来（登记表见 [LocalServiceStore]）。
 */
class LocalServiceRegistry(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val networkProbe: NetworkProbe,
    /**
     * 托管服务的进程环境里也要有当前供应商的凭据。
     *
     * 托管起来的常常就是一个自己写的脚本、一个 `npx` 起的东西 —— 它们和终端里手敲的
     * 命令没有区别，凭那里有、这里没有，只会让人以为是服务本身的问题。
     */
    private val settingsStore: SettingsStore,
    private val relay: RelayController? = null,
    private val proot: ProotShellRunner = ProotShellRunner(
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
    ),
    private val patcher: RootfsPatcher = RootfsPatcher(),
    /** 跨进程登记表：App 重启后认回孤儿服务的唯一线索 */
    private val store: LocalServiceStore = LocalServiceStore(context),
    /** 读 `/proc/<pid>/cmdline`；抽成参数是为了让对账在单测里不碰真 /proc */
    private val cmdlineOf: (Int) -> String? = procCmdlineReader(),
    // 默认 scope 装 CoroutineExceptionHandler：SupervisorJob 会把未捕获异常丢给
    // 默认 handler（Android 上直接崩 App），这里记日志 + 落盘，不往上传
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, err ->
            Log.e(TAG, "uncaught exception in registry scope", err)
            CrashRecorder.record(context, Thread.currentThread(), err)
        }
    ),
) {
    private data class Handle(
        /** 重启认领来的服务只有 pid，没有 [Process] 句柄 —— 这里是 null */
        @Volatile var process: Process?,
        /** proot 宿主 pid。停服务只需要它，[stop] 因此对认领来的服务一样管用 */
        @Volatile var hostPid: Int? = null,
        /** 建表那一刻的 `/proc/<pid>/cmdline`，落盘与轮询都拿它认人 */
        @Volatile var cmdline: String? = null,
        var waiter: Job? = null,
        val log: StringBuilder = StringBuilder(),
        val logLock: Any = Any(),
        /** 上次把 logTail 推进 StateFlow 的时刻；日志行很密时别每行触发重组 */
        @Volatile var lastUiPushMs: Long = 0L,
    )

    private val handles = ConcurrentHashMap<String, Handle>()
    private val _services = MutableStateFlow<List<LocalService>>(emptyList())
    val services: StateFlow<List<LocalService>> = _services.asStateFlow()

    /**
     * 串行化「去重检查 + 启动登记」：原先是裸的 check-then-act，两个并发调用拿着同一个
     * command+cwd+port 一起穿过检查，同一个服务被双起。启动本身（proot、网络探测）
     * 可能耗时数秒，锁会顺带把启动串行化 —— 启动很罕见，正确性换得起。
     */
    private val startMutex = Mutex()

    /** 串行化登记表落盘，见 [persist] */
    private val persistMutex = Mutex()

    val anyRunning: StateFlow<Boolean> = _services
        .map { list ->
            list.any {
                it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Agent / 会话链调用的主入口。
     * 同 command+cwd+port 已在 Starting/Running 则复用 id，不重起。
     */
    suspend fun startFromAgent(
        command: String,
        cwdGuest: String = "/workspace",
        port: Int? = null,
        label: String? = null,
        sourceSessionKey: String? = null,
    ): Result<String> {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Result.failure(IllegalArgumentException("command empty"))
        val normalizedCwd = CwdPath.normalize(cwdGuest)
        // 检查与启动必须在同一把锁里，否则两个并发调用一起穿过检查、各起一份
        return startMutex.withLock {
            findReusable(cmd, normalizedCwd, port)?.let { return@withLock Result.success(it.id) }
            startLocked(
                label = label?.trim().orEmpty(),
                command = cmd,
                cwdGuest = normalizedCwd,
                port = port,
                sourceSessionKey = sourceSessionKey,
            )
        }
    }

    suspend fun start(
        label: String,
        command: String,
        cwdGuest: String = "/workspace",
        port: Int? = null,
        sourceSessionKey: String? = null,
    ): Result<String> {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Result.failure(IllegalArgumentException("command empty"))
        val normalizedCwd = CwdPath.normalize(cwdGuest)
        // 独立入口也复查重复：绕过 startFromAgent 直接进来，不该把同一条命令再起一份
        return startMutex.withLock {
            findReusable(cmd, normalizedCwd, port)?.let { return@withLock Result.success(it.id) }
            startLocked(
                label = label,
                command = cmd,
                cwdGuest = normalizedCwd,
                port = port,
                sourceSessionKey = sourceSessionKey,
            )
        }
    }

    /** Starting/Running 中 command+cwd+port 相同的条目（端口为 null 时不比端口） */
    private fun findReusable(cmd: String, normalizedCwd: String, port: Int?): LocalService? =
        _services.value.firstOrNull {
            (it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting) &&
                it.command == cmd &&
                it.cwdGuest == normalizedCwd &&
                (port == null || it.port == port)
        }

    /** [startMutex] 保护下的启动主体；入参的 command/cwd 已 trim / normalize */
    private suspend fun startLocked(
        label: String,
        command: String,
        cwdGuest: String,
        port: Int?,
        sourceSessionKey: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        val cmd = command
        val normalizedCwd = cwdGuest
        val runningCount = _services.value.count {
            it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
        }
        if (runningCount >= MAX_RUNNING) {
            return@withContext Result.failure(IllegalStateException("at most $MAX_RUNNING local services"))
        }

        val id = UUID.randomUUID().toString().take(8)
        val niceLabel = label.trim().ifBlank { defaultLabel(cmd, port) }
        val now = System.currentTimeMillis()

        if (port != null) {
            if (port !in 1..65535) {
                return@withContext Result.failure(IllegalArgumentException("bad port: $port"))
            }
            val snap = networkProbe.snapshot()
            if (snap.portsReadable && snap.ports.any { it.port == port }) {
                val blocked = LocalService(
                    id = id,
                    label = niceLabel,
                    command = cmd,
                    cwdGuest = normalizedCwd,
                    port = port,
                    status = LocalServiceStatus.Failed,
                    startedAtEpochMs = now,
                    sourceSessionKey = sourceSessionKey,
                    stopReason = LocalServiceStopReason.PortBusy,
                    lastError = "port $port already listening",
                )
                _services.update { it + blocked }
                return@withContext Result.failure(IllegalStateException("port $port busy"))
            }
        }

        val workspaceId = CLAUDE_CODE_WORKSPACE_ID.toString()
        val workspace = workspaceRepository.getById(workspaceId)
            ?: return@withContext Result.failure(IllegalStateException("workspace missing"))
        val workspaceDir = File(File(context.filesDir, "workspaces"), workspace.root)
        val linuxDir = File(workspaceDir, "linux")
        val filesDir = File(workspaceDir, "files").apply { mkdirs() }
        val tempDir = File(workspaceDir, "tmp").apply { mkdirs() }
        if (!File(linuxDir, "bin/sh").isFile) {
            return@withContext Result.failure(IllegalStateException("rootfs not installed"))
        }

        runCatching {
            patcher.patch(linuxDir, RootfsPatchOptions(nameservers = context.activeDnsServers()))
        }
        val snap = runCatching { networkProbe.snapshot() }.getOrNull()
        runCatching {
            GuestRuntimeDocs.ensureSeeded(
                File(linuxDir, "root/.claude/CLAUDE.md"),
                snap,
            )
        }

        val (area, rel) = CwdPath.split(normalizedCwd)
        val prootCwdRelative = if (area == WorkspaceStorageArea.FILES) rel else ""

        val shellContext = WorkspaceShellContext(
            root = workspace.root,
            command = cmd,
            cwd = prootCwdRelative,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workingDir = filesDir,
            timeoutMillis = 0L,
            killOnExit = false,
            // HOME / USER / SHELL / CI / NO_COLOR / PAGER 由 ProotShellRunner 的基础 env 统一给，
            // 这里只放托管服务自己需要的那几样
            env = buildMap {
                putAll(ClaudeCodeInstaller.nodeEnv())
                if (snap != null) putAll(GuestRuntimeDocs.envFrom(snap))
                // 服务自己 apt install 时别卡 dpkg 交互
                put("DEBIAN_FRONTEND", "noninteractive")
                // 当前供应商的凭据，和终端页签同一套（开关关着时是空的）。
                // 解不开 Keystore 时当没有：服务该起还是要起，只是里面没有 key
                putAll(
                    runCatching {
                        val settings = settingsStore.current()
                        val override = settings.activeProfile?.let { relay?.claudeBaseUrl(it) }
                        shellCredentialEnv(settings, claudeBaseUrlOverride = override)
                    }
                        .onFailure { Log.w(TAG, "读取供应商凭据失败，本地服务将没有 key", it) }
                        .getOrDefault(emptyMap()),
                )
            },
        )

        val entry = LocalService(
            id = id,
            label = niceLabel,
            command = cmd,
            cwdGuest = normalizedCwd,
            port = port,
            status = LocalServiceStatus.Starting,
            startedAtEpochMs = now,
            sourceSessionKey = sourceSessionKey,
        )
        _services.update { it + entry }

        val process = runCatching { proot.launch(shellContext) }.getOrElse { err ->
            Log.e(TAG, "launch failed", err)
            patch(id) { svc ->
                svc.copy(
                    status = LocalServiceStatus.Failed,
                    stopReason = LocalServiceStopReason.StartFailed,
                    lastError = err.message ?: err.toString(),
                )
            }
            return@withContext Result.failure(err)
        }
        if (process == null) {
            val reason = proot.checkAvailability(shellContext) ?: "proot launch failed"
            patch(id) {
                it.copy(
                    status = LocalServiceStatus.Failed,
                    stopReason = LocalServiceStopReason.StartFailed,
                    lastError = reason,
                )
            }
            return@withContext Result.failure(IllegalStateException(reason))
        }

        runCatching { process.outputStream.close() }
        // pid 和 cmdline 趁现在取：proot 已经 exec 完，cmdline 到死都不会再变。
        // 拖到 Running 再取的话，Starting 这 2.5 秒里被杀的 App 会漏登记一个孤儿。
        val hostPid = runCatching { ProcessTreeKill.pidOf(process) }.getOrNull()
        val handle = Handle(
            process = process,
            hostPid = hostPid,
            cmdline = hostPid?.let { runCatching { cmdlineOf(it) }.getOrNull() },
        )
        handles[id] = handle
        persist()

        // 诚实：先 Starting，短窗口内进程仍活（或端口已听）才升 Running
        handle.waiter = scope.launch {
            val drainJob = launch { drainToLog(id, handle, process) }
            val alive = awaitRunning(id, process, port)
            if (!alive) {
                // waitFor 会在下面处理
            }
            val code = runCatching { process.waitFor() }.getOrElse { -1 }
            drainJob.cancel()
            handles.remove(id)
            val current = _services.value.find { it.id == id }
            if (current?.stopReason == LocalServiceStopReason.UserStop ||
                current?.stopReason == LocalServiceStopReason.StopAll
            ) {
                patch(id) {
                    it.copy(
                        status = LocalServiceStatus.Exited,
                        exitCode = code,
                        logTail = logTailOf(handle),
                    )
                }
            } else {
                patch(id) {
                    it.copy(
                        status = if (code == 0) LocalServiceStatus.Exited else LocalServiceStatus.Failed,
                        exitCode = code,
                        stopReason = it.stopReason ?: LocalServiceStopReason.Crash,
                        lastError = it.lastError ?: if (code != 0) "exit $code" else null,
                        logTail = logTailOf(handle),
                    )
                }
            }
            // 进程没了就从登记表里划掉，否则下次开机会拿一个死 pid 去认领
            persist()
            Log.i(TAG, "service $id exited code=$code")
        }

        Result.success(id)
    }

    fun stop(id: String, reason: LocalServiceStopReason = LocalServiceStopReason.UserStop) {
        val handle = handles[id]
        patch(id) {
            it.copy(
                stopReason = reason,
                status = if (it.status == LocalServiceStatus.Starting || it.status == LocalServiceStatus.Running) {
                    LocalServiceStatus.Exited
                } else {
                    it.status
                },
                logTail = handle?.let { h -> logTailOf(h) } ?: it.logTail,
            )
        }
        val proc = handle?.process
        // 认领来的服务只有 pid：句柄留在上一条命的进程里，这条命再也拿不到了
        val claimedPid = handle?.hostPid
        handle?.process = null
        if (proc == null && claimedPid != null && handle != null) {
            // 它的退出感知全靠轮询，这里先把轮询停掉，免得稍后又把状态从 Exited 翻回去
            handle.waiter?.cancel()
            handles.remove(id)
        }
        if (proc != null || claimedPid != null) {
            scope.launch {
                // 先杀 guest 进程树，再杀宿主 proot —— 顺序不能反：宿主一死，后代立刻
                // reparent 到 init，从 root 出发就再也遍历不到，只剩端口还 LISTEN 的
                // 孤儿死壳（`--kill-on-exit` 救不了，理由见 ProcessTreeKill 类注释）。
                // 两步都是阻塞 IO（扫 /proc + 信号宽限），显式压到 IO 上；
                // 取 pid 或杀树失败都不许挡住最后的 destroy。
                val pid = claimedPid
                    ?: proc?.let { runCatching { ProcessTreeKill.pidOf(it) }.getOrNull() }
                if (pid == null) {
                    Log.w(TAG, "stop $id: 取不到宿主 pid，只能退回 Process.destroy()")
                } else {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val killed = ProcessTreeKill.killTree(pid)
                            Log.i(
                                TAG,
                                "stop $id killTree pid=$pid tree=${killed.tree.size} " +
                                    "term=${killed.terminated.size} kill=${killed.killed.size} " +
                                    "gone=${killed.goneBeforeSignal.size}"
                            )
                            // 宿主自己也得手动发信号：Android 的 Process.destroy() 与
                            // destroyForcibly() 都只发 SIGTERM，而 proot 扛得住 SIGTERM
                            // （详见 ProcessTreeKill 类注释「教训二」）。
                            val host = ProcessTreeKill.killHost(pid)
                            Log.i(TAG, "stop $id killHost pid=$pid -> $host")
                        }
                    }.onFailure { Log.w(TAG, "killTree $id", it) }
                }
                // 认领来的服务没有句柄可 destroy：管道本就随上一条命的进程一起没了，
                // 上面的 killTree + killHost 已经是全部手段。
                if (proc != null) {
                    runCatching {
                        // 宿主此时多半已经死了；destroy() 留在这里是为了关掉三条管道，
                        // 顺带当拿不到 pid 时的唯一退路。
                        proc.destroy()
                        if (pid == null) {
                            // 这条升级路径在 Android 上等于再发一次 SIGTERM（destroyForcibly
                            // 没被 override），聊胜于无 —— 换成新运行时才可能真是 SIGKILL。
                            val exited = try {
                                proc.waitFor(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                            } catch (_: InterruptedException) {
                                false
                            }
                            if (!exited) proc.destroyForcibly()
                        }
                    }.onFailure { Log.w(TAG, "stop $id", it) }
                }
            }
            // 停掉的服务不该留在登记表里等着下次开机被认领
            persist()
        }
    }

    fun stopAll(reason: LocalServiceStopReason = LocalServiceStopReason.StopAll) {
        val ids = _services.value
            .filter { it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting }
            .map { it.id }
        ids.forEach { stop(it, reason) }
    }

    /**
     * 开机认回上一条命留下的、还在跑的托管服务。
     *
     * 托管服务是 `killOnExit=false` 的独立 proot —— App 被杀 / 崩溃 / 用户划掉之后它照样跑、
     * 端口照样 LISTEN，可内存里的进程表是空的。不认领的话它就是个谁都看不见、谁都停不掉的
     * 孤儿，还会把同端口的下一次启动顶成 [LocalServiceStopReason.PortBusy]。
     *
     * 认回来的按 Running 进面板：[stop] 只要 pid 就能杀树，所以照样停得掉；同 command+cwd+port
     * 的下一次 [startFromAgent] 会走 [findReusable] 复用它 —— 于是也不再撞自己的端口。
     *
     * 认不回来的（进程没了，或 pid 被复用给了别人）直接丢掉，不往面板上摆一条早就死了的记录。
     * 日志接不回来：那三条管道随上一条命的进程一起没了，认领条目的 logTail 就是空的。
     */
    suspend fun reconcile() {
        val claims = withContext(Dispatchers.IO) {
            runCatching { localServiceClaims(store.read(), cmdlineOf) }.getOrDefault(emptyList())
        }
        if (claims.isEmpty()) return
        startMutex.withLock {
            val known = _services.value.mapTo(mutableSetOf()) { it.id }
            val entries = claims
                .filter { it.alive }
                .map { it.record }
                .filterNot { it.id in known }
                .map { record ->
                    val handle = Handle(
                        process = null,
                        hostPid = record.hostPid,
                        cmdline = record.cmdline,
                    )
                    handles[record.id] = handle
                    handle.waiter = watchClaimed(record.id, handle, record.hostPid)
                    LocalService(
                        id = record.id,
                        label = record.label,
                        command = record.command,
                        cwdGuest = record.cwdGuest,
                        port = record.port,
                        status = LocalServiceStatus.Running,
                        startedAtEpochMs = record.startedAtEpochMs,
                        sourceSessionKey = record.sourceSessionKey,
                    )
                }
            if (entries.isNotEmpty()) _services.update { it + entries }
            Log.i(TAG, "reconcile: claimed ${entries.size} of ${claims.size} recorded")
            // 认不回来的那些就此从登记表里消失
            persist()
        }
    }

    /**
     * 认领来的服务没有 [Process] 句柄，waitFor 不了，只能轮询 `/proc` 看它什么时候没的。
     *
     * 比 cmdline 而不是只看 pid 在不在：服务死掉、pid 被系统复用给别人之后，
     * 只看 pid 会一直以为它还活着。
     *
     * 十秒一轮 —— 托管服务是长驻的，晚十秒把面板状态翻成 Exited 不耽误任何事。
     */
    private fun watchClaimed(id: String, handle: Handle, pid: Int): Job = scope.launch {
        while (true) {
            delay(CLAIM_POLL_MS)
            if (handles[id] !== handle) return@launch // 已经被 stop 摘掉了
            val alive = runCatching { cmdlineOf(pid) }.getOrNull() == handle.cmdline
            if (alive) continue
            handles.remove(id)
            // 退出码拿不到：没有句柄就没有 waitFor，只知道"它不在了"。
            // 不硬塞一个 Crash —— 正常退出和崩溃在这里分不出来，标 Exited 才诚实。
            patch(id) {
                if (it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting) {
                    it.copy(status = LocalServiceStatus.Exited)
                } else {
                    it
                }
            }
            persist()
            Log.i(TAG, "claimed service $id is gone")
            return@launch
        }
    }

    /**
     * 把此刻还活着的托管服务写进跨进程登记表。
     *
     * 只写拿得到 pid 和 cmdline 的那些：两者缺一就认领不回来（认领要逐字比对 cmdline），
     * 落了盘也只是下次开机要清的垃圾。
     *
     * 快照在调用线程同步取、写盘扔到 [scope]（IO）—— [stop] 是从界面线程调的普通函数，
     * 不能在那里碰磁盘。两次 persist 撞在一起时靠 [persistMutex] 串行，写的又都是全量快照，
     * 最坏结果是中间那次白写。
     */
    private fun persist() {
        val records = _services.value.mapNotNull { svc ->
            if (svc.status != LocalServiceStatus.Running && svc.status != LocalServiceStatus.Starting) {
                return@mapNotNull null
            }
            val handle = handles[svc.id] ?: return@mapNotNull null
            val pid = handle.hostPid ?: return@mapNotNull null
            val cmdline = handle.cmdline ?: return@mapNotNull null
            LocalServiceRecord(
                id = svc.id,
                label = svc.label,
                command = svc.command,
                cwdGuest = svc.cwdGuest,
                port = svc.port,
                startedAtEpochMs = svc.startedAtEpochMs,
                sourceSessionKey = svc.sourceSessionKey,
                hostPid = pid,
                cmdline = cmdline,
            )
        }
        scope.launch { persistMutex.withLock { store.write(records) } }
    }

    fun clearFinished() {
        _services.update { list ->
            list.filter {
                it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
            }
        }
    }

    fun logOf(id: String): String {
        val handle = handles[id]
        if (handle != null) return synchronized(handle.logLock) { handle.log.toString() }
        return _services.value.find { it.id == id }?.logTail.orEmpty()
    }

    private suspend fun awaitRunning(id: String, process: Process, port: Int?): Boolean {
        // 最多等 READY_WINDOW_MS：进程还活着 → Running；已死 → 交给 waiter 标 Failed
        val deadline = System.currentTimeMillis() + READY_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            val alive = runCatching { process.isAlive }.getOrDefault(false)
            if (!alive) return false
            val portUp = if (port != null) {
                val snap = runCatching { networkProbe.snapshot() }.getOrNull()
                snap?.portsReadable == true && snap.ports.any { it.port == port }
            } else {
                true // 无端口约定：活着即 Running
            }
            if (portUp || System.currentTimeMillis() > deadline - READY_WINDOW_TAIL_MS) {
                // 有端口但读不到 /proc/net：窗口末尾仍活着 → 诚实升 Running，并保留 Starting 期间的不确定性已过
                if (port != null) {
                    val snap = runCatching { networkProbe.snapshot() }.getOrNull()
                    if (snap?.portsReadable != true && System.currentTimeMillis() < deadline - READY_WINDOW_TAIL_MS) {
                        delay(READY_POLL_INTERVAL_MS)
                        continue
                    }
                }
                patch(id) {
                    if (it.status == LocalServiceStatus.Starting) {
                        it.copy(status = LocalServiceStatus.Running, logTail = logTailOf(handles[id]))
                    } else it
                }
                return true
            }
            delay(READY_POLL_INTERVAL_MS)
        }
        val alive = runCatching { process.isAlive }.getOrDefault(false)
        if (alive) {
            patch(id) {
                if (it.status == LocalServiceStatus.Starting) {
                    it.copy(status = LocalServiceStatus.Running, logTail = logTailOf(handles[id]))
                } else it
            }
        }
        return alive
    }

    private fun drainToLog(id: String, handle: Handle, process: Process) {
        fun suck(stream: java.io.InputStream, prefix: String) {
            scope.launch {
                runCatching {
                    stream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            appendLog(handle, if (prefix.isEmpty()) line else "$prefix$line")
                            // ≥400ms 才推一次 UI：vite/uvicorn 刷屏时否则整表重组把会话页拖死
                            val now = System.currentTimeMillis()
                            if (now - handle.lastUiPushMs >= LOG_UI_INTERVAL_MS) {
                                handle.lastUiPushMs = now
                                patch(id) { it.copy(logTail = logTailOf(handle)) }
                            }
                        }
                    }
                }
                patch(id) { it.copy(logTail = logTailOf(handle)) }
            }
        }
        suck(process.inputStream, "")
        suck(process.errorStream, "")
    }

    private fun appendLog(handle: Handle, line: String) {
        synchronized(handle.logLock) {
            handle.log.append(line).append('\n')
            if (handle.log.length > MAX_LOG_CHARS) {
                handle.log.delete(0, handle.log.length - MAX_LOG_CHARS)
            }
        }
    }

    private fun logTailOf(handle: Handle?): String {
        if (handle == null) return ""
        return synchronized(handle.logLock) {
            val s = handle.log.toString()
            if (s.length <= LOG_TAIL_CHARS) s else s.takeLast(LOG_TAIL_CHARS)
        }
    }

    private fun patch(id: String, transform: (LocalService) -> LocalService) {
        _services.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun defaultLabel(command: String, port: Int?): String {
        val head = command.trim().split(Regex("\\s+")).take(3).joinToString(" ")
        return if (port != null) "$head :$port" else head.take(48)
    }

    private companion object {
        const val MAX_RUNNING = 5
        const val READY_WINDOW_MS = 2_500L
        /** 窗口末尾的收尾余量：过了这个点仍活着就诚实升 Running，不再等端口 */
        const val READY_WINDOW_TAIL_MS = 200L
        /** [awaitRunning] 的轮询间隔 */
        const val READY_POLL_INTERVAL_MS = 150L
        const val MAX_LOG_CHARS = 256 * 1024
        const val LOG_TAIL_CHARS = 8 * 1024
        const val LOG_UI_INTERVAL_MS = 400L

        /** 认领来的服务多久查一次 /proc，见 [watchClaimed] */
        const val CLAIM_POLL_MS = 10_000L
    }
}

/**
 * 判断 Bash 是否应进进程表（独立 proot），而不是会话孙进程。
 *
 * **主路径**：CLI 的 `run_in_background`（及同义 flag）——与桌面满血一致。
 * **辅**：极薄 dev-server 启发式（[DEV_SERVER]）。不是规范，禁止无限加包名。
 * `python app.py` 无 flag → 不进表。
 */
object LocalServiceIntent {
    private val BACKGROUND_FLAG_KEYS = listOf("run_in_background", "runInBackground", "is_background")

    /**
     * 无 flag 时的薄补：明显的前台 dev server。宁可漏，不要把 `ls` / `python script.py` 送去托管。
     * 主路径永远是 [isBackgroundFlag]。
     *
     * 全部带 `^` 锚：只在「命令位置」匹配，不允许在命令串任意位置出现。
     * 调用方必须先把 shell 结构拆段、剥掉包装（见 [splitSegments] / [effectiveWords]），
     * 再把有效命令词 join 成串喂给这些正则。直接 containsMatchIn 会把
     * `pip install uvicorn` / `cat vite.config.ts` 这类一次性命令误托管。
     */
    private val DEV_SERVER = listOf(
        Regex("""^http\.server\b"""), // python[3] -m http.server 经解释器解包后到达这里
        Regex("""^uvicorn\b"""),
        Regex("""^gunicorn\b"""),
        Regex("""^flask\s+run\b"""),
        Regex("""^django-admin\s+runserver\b"""),
        Regex("""^manage\.py\s+runserver\b"""),
        Regex("""^next\s+dev\b"""),
        Regex("""^npm\s+(run\s+)?dev\b"""),
        Regex("""^yarn\s+dev\b"""),
        Regex("""^pnpm\s+dev\b"""),
        Regex("""^vite\b"""),
        Regex("""^serve\b"""), // npx serve / bunx serve 解包后到达这里
        Regex("""^http-server\b"""),
        Regex("""^php\s+-S\b"""),
        Regex("""^rails\s+s(?:erver)?\b"""),
        Regex("""^docker\s+compose\s+up\b"""),
    )

    /** 段首的 `FOO=bar` 环境变量赋值前缀 */
    private val ENV_ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

    /** python / python3 / python3.11 这类解释器名 */
    private val PYTHON_BIN = Regex("""^python[0-9.]*$""")

    /** 无参数 python flag；带参 flag（-W / -X …）不猜，直接当拿不准 → 不托管 */
    private val PYTHON_NOARG_FLAGS = setOf("-u", "-O", "-OO", "-B", "-q", "-I", "-E", "-s", "-S", "-v")

    private val PORT_FLAG = Regex("""(?:--port[= ]|port=)(\d{2,5})\b""", RegexOption.IGNORE_CASE)
    private val TRAILING_PORT = Regex("""\b(\d{4,5})\s*$""")

    fun isBackgroundFlag(input: Map<String, Any?>): Boolean {
        for (k in BACKGROUND_FLAG_KEYS) {
            when (val v = input[k]) {
                is Boolean -> if (v) return true
                is String -> if (v.equals("true", ignoreCase = true)) return true
            }
        }
        return false
    }

    fun looksLongLived(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty()) return false
        // 分词后只看「命令位置」：管道/列表拆段 → 剥 env/sudo/nohup 包装 → 解包
        // npx/bunx/python 解释器。拿不准（奇怪 flag、引号里的内容）就当不是，宁可漏。
        if (splitSegments(c).any { segment -> isDevServer(effectiveWords(segment)) }) return true
        // bare & / nohup 只有带端口线索时才当启发式（仍非主路径）
        val bg = c.endsWith("&") || c.contains(" nohup ") || c.startsWith("nohup ")
        return bg && (PORT_FLAG.containsMatchIn(c) || TRAILING_PORT.containsMatchIn(c.removeSuffix("&").trim()))
    }

    /** 命令位置上的有效命令词是否命中 dev-server 白名单 */
    private fun isDevServer(words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val line = words.joinToString(" ")
        return DEV_SERVER.any { it.containsMatchIn(line) }
    }

    /**
     * 按 shell 结构拆段：`|` / `||` / `&&` / `;` / `&` 各起一段，段首即命令位置。
     * 单双引号内的分隔符不拆（`echo "a|b"` 不会误拆）。引号本身从词里剥掉。
     * 子壳括号当空白丢掉 —— 简化处理，最坏情况是漏托管，不会误托管。
     */
    private fun splitSegments(command: String): List<List<String>> {
        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        fun flushWord() {
            if (sb.isNotEmpty()) {
                current += sb.toString()
                sb.clear()
            }
        }
        fun flushSegment() {
            flushWord()
            if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        }
        var i = 0
        while (i < command.length) {
            val ch = command[i]
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> flushWord()
                ch == ';' -> flushSegment()
                ch == '|' -> {
                    flushSegment()
                    if (i + 1 < command.length && command[i + 1] == '|') i++
                }
                ch == '&' -> {
                    flushSegment()
                    if (i + 1 < command.length && command[i + 1] == '&') i++
                }
                ch == '(' || ch == ')' -> flushWord()
                ch == '\\' && i + 1 < command.length -> {
                    i++
                    sb.append(command[i])
                }
                else -> sb.append(ch)
            }
            i++
        }
        flushSegment()
        return segments
    }

    private fun basename(word: String): String =
        word.substringAfterLast('/').substringAfterLast('\\')

    /**
     * 把一个段的词归约成「有效命令 + 参数」：
     * - 剥掉段首 env 赋值（`FOO=bar cmd`）、`sudo` / `nohup` / `env` 包装、`cd` 不在此处理
     *   （`cd dir && cmd` 已被 [splitSegments] 拆成两段）；
     * - 解包解释器：`npx` / `bunx` 的有效命令是它的第一个非 flag 参数；
     *   `python[3] -m <module>` 的有效命令是模块名；`python[3] <script>` 的有效命令是脚本名
     *   （`python manage.py runserver` → `manage.py runserver`，`python app.py` → `app.py`，不命中白名单）。
     * 任何一步拿不准就返回空表 → 不托管。
     */
    private fun effectiveWords(segment: List<String>): List<String> {
        var words = segment.dropWhile { it.matches(ENV_ASSIGN) }
        // sudo / nohup / env 包装：循环剥（sudo env FOO=bar cmd 这种叠甲也剥）
        while (true) {
            val head = words.firstOrNull()?.let(::basename) ?: return emptyList()
            when (head) {
                "sudo" -> {
                    words = words.drop(1)
                    // sudo 的 flag 可能带值（-u root），不猜：见到 flag 就当拿不准
                    if (words.firstOrNull()?.startsWith("-") == true) return emptyList()
                    words = words.dropWhile { it.matches(ENV_ASSIGN) }
                }
                "nohup" -> {
                    words = words.drop(1)
                    words = words.dropWhile { it.matches(ENV_ASSIGN) }
                }
                "env" -> {
                    words = words.drop(1)
                    if (words.firstOrNull()?.startsWith("-") == true) return emptyList()
                    words = words.dropWhile { it.matches(ENV_ASSIGN) }
                }
                else -> return unwrapInterpreter(words)
            }
        }
    }

    private fun unwrapInterpreter(words: List<String>): List<String> {
        val head = words.firstOrNull()?.let(::basename) ?: return emptyList()
        return when {
            head == "npx" || head == "bunx" -> {
                var rest = words.drop(1)
                // npx 的 flag 里 -p/--package 带值要连值一起跳；其余 flag 只跳自身。
                // 全是 flag 没见过真命令 → 拿不准 → 空表不托管
                while (rest.isNotEmpty() && rest.first().startsWith("-")) {
                    rest = if (rest.first() == "-p" || rest.first() == "--package") {
                        rest.drop(2)
                    } else {
                        rest.drop(1)
                    }
                }
                rest
            }
            head.matches(PYTHON_BIN) -> {
                var rest = words.drop(1)
                while (rest.firstOrNull() in PYTHON_NOARG_FLAGS) rest = rest.drop(1)
                val t = rest.firstOrNull() ?: return emptyList()
                when {
                    t == "-m" -> rest.drop(1) // 模块名成为有效命令
                    t.startsWith("-") -> emptyList() // 带参 flag，拿不准
                    else -> listOf(basename(t)) + rest.drop(1)
                }
            }
            else -> listOf(basename(words.first())) + words.drop(1)
        }
    }

    fun shouldHost(command: String, inputFlags: Map<String, Any?> = emptyMap()): Boolean {
        if (isBackgroundFlag(inputFlags)) return true
        return looksLongLived(command)
    }

    fun guessPort(command: String): Int? {
        val cleaned = command.trim().removeSuffix("&").trim()
        PORT_FLAG.findAll(cleaned).lastOrNull()?.groupValues?.getOrNull(1)
            ?.toIntOrNull()?.takeIf { it in 1..65535 }?.let { return it }
        return TRAILING_PORT.find(cleaned)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    fun stripBackgroundNoise(command: String): String {
        var c = command.trim()
        if (c.endsWith("&")) c = c.dropLast(1).trim()
        if (c.startsWith("nohup ")) c = c.removePrefix("nohup ").trim()
        return c
    }
}
