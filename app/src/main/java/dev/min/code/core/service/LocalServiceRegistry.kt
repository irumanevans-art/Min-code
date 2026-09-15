package dev.min.code.core.service

import android.content.Context
import android.util.Log
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.claudecode.CwdPath
import dev.min.code.core.claudecode.GuestRuntimeDocs
import dev.min.code.core.network.NetworkProbe
import dev.min.code.core.network.activeDnsServers
import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.rootfs.WorkspaceRepository
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
import kotlinx.coroutines.withContext
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
 */
class LocalServiceRegistry(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val networkProbe: NetworkProbe,
    private val proot: ProotShellRunner = ProotShellRunner(
        nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
    ),
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Handle(
        @Volatile var process: Process?,
        var waiter: Job? = null,
        val log: StringBuilder = StringBuilder(),
        val logLock: Any = Any(),
        /** 上次把 logTail 推进 StateFlow 的时刻；日志行很密时别每行触发重组 */
        @Volatile var lastUiPushMs: Long = 0L,
    )

    private val handles = ConcurrentHashMap<String, Handle>()
    private val _services = MutableStateFlow<List<LocalService>>(emptyList())
    val services: StateFlow<List<LocalService>> = _services.asStateFlow()

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
        val existing = _services.value.firstOrNull {
            (it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting) &&
                it.command == cmd &&
                it.cwdGuest == normalizedCwd &&
                (port == null || it.port == port)
        }
        if (existing != null) return Result.success(existing.id)
        return start(
            label = label?.trim().orEmpty(),
            command = cmd,
            cwdGuest = normalizedCwd,
            port = port,
            sourceSessionKey = sourceSessionKey,
        )
    }

    suspend fun start(
        label: String,
        command: String,
        cwdGuest: String = "/workspace",
        port: Int? = null,
        sourceSessionKey: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return@withContext Result.failure(IllegalArgumentException("command empty"))
        val runningCount = _services.value.count {
            it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
        }
        if (runningCount >= MAX_RUNNING) {
            return@withContext Result.failure(IllegalStateException("at most $MAX_RUNNING local services"))
        }

        val normalizedCwd = CwdPath.normalize(cwdGuest)
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
            env = buildMap {
                putAll(ClaudeCodeInstaller.nodeEnv())
                if (snap != null) putAll(GuestRuntimeDocs.envFrom(snap))
                put("PAGER", "cat")
                put("CI", "true")
                put("NO_COLOR", "1")
                put("DEBIAN_FRONTEND", "noninteractive")
                put("USER", "root")
                put("SHELL", "/bin/bash")
                put("HOME", "/root")
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
        val handle = Handle(process = process)
        handles[id] = handle

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
        handle?.process = null
        if (proc != null) {
            scope.launch {
                runCatching {
                    proc.destroy()
                    val exited = try {
                        proc.waitFor(1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        false
                    }
                    if (!exited) proc.destroyForcibly()
                }.onFailure { Log.w(TAG, "stop $id", it) }
            }
        }
    }

    fun stopAll(reason: LocalServiceStopReason = LocalServiceStopReason.StopAll) {
        val ids = _services.value
            .filter { it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting }
            .map { it.id }
        ids.forEach { stop(it, reason) }
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
            if (portUp || System.currentTimeMillis() > deadline - 200) {
                // 有端口但读不到 /proc/net：窗口末尾仍活着 → 诚实升 Running，并保留 Starting 期间的不确定性已过
                if (port != null) {
                    val snap = runCatching { networkProbe.snapshot() }.getOrNull()
                    if (snap?.portsReadable != true && System.currentTimeMillis() < deadline - 200) {
                        delay(150)
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
            delay(150)
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
            Thread({
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
            }, "local-svc-log-$id").apply {
                isDaemon = true
                start()
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
        const val MAX_LOG_CHARS = 256 * 1024
        const val LOG_TAIL_CHARS = 8 * 1024
        const val LOG_UI_INTERVAL_MS = 400L
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
     */
    private val DEV_SERVER = listOf(
        Regex("""python3?\s+-m\s+http\.server\b"""),
        Regex("""\buvicorn\b"""),
        Regex("""\bgunicorn\b"""),
        Regex("""\bflask\s+run\b"""),
        Regex("""\bdjango-admin\s+runserver\b"""),
        Regex("""\bmanage\.py\s+runserver\b"""),
        Regex("""\bnext\s+dev\b"""),
        Regex("""\bnpm\s+(run\s+)?dev\b"""),
        Regex("""\byarn\s+dev\b"""),
        Regex("""\bpnpm\s+dev\b"""),
        Regex("""\bvite\b"""),
        Regex("""\bhttp-server\b"""),
        Regex("""\bnpx\s+serve\b"""),
        Regex("""\bphp\s+-S\b"""),
        Regex("""\brails\s+s(erver)?\b"""),
        Regex("""\bdocker\s+compose\s+up\b"""),
    )

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
        if (DEV_SERVER.any { it.containsMatchIn(c) }) return true
        // bare & / nohup 只有带端口线索时才当启发式（仍非主路径）
        val bg = c.endsWith("&") || c.contains(" nohup ") || c.startsWith("nohup ")
        return bg && (PORT_FLAG.containsMatchIn(c) || TRAILING_PORT.containsMatchIn(c.removeSuffix("&").trim()))
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
