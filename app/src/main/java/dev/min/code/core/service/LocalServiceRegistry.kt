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
import kotlinx.coroutines.flow.MutableStateFlow
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
    val exitCode: Int? = null,
    val stopReason: LocalServiceStopReason? = null,
    val lastError: String? = null,
)

/**
 * 用户显式登记的长驻本地服务（独立 proot，不挂在某个 Claude 会话树上）。
 *
 * Bash 里 `python app.py &` **不会**出现在这里——那是会话进程的未跟踪孙进程。
 * FGS 门闸应并上 [anyRunning]，否则退后台仍会被杀。
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
    )

    private val handles = ConcurrentHashMap<String, Handle>()
    private val _services = MutableStateFlow<List<LocalService>>(emptyList())
    val services: StateFlow<List<LocalService>> = _services.asStateFlow()

    val anyRunning: StateFlow<Boolean> = _services
        .map { list -> list.any { it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting } }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    suspend fun start(
        label: String,
        command: String,
        cwdGuest: String = "/workspace",
        port: Int? = null,
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

        // live DNS + runtime docs seed（与会话路径对齐）
        runCatching {
            patcher.patch(linuxDir, RootfsPatchOptions(nameservers = context.activeDnsServers()))
        }
        runCatching {
            GuestRuntimeDocs.ensureSeeded(
                File(linuxDir, "root/.claude/CLAUDE.md"),
                networkProbe.snapshot(),
            )
        }

        // ProotShellRunner 的 cwd 是相对 /workspace（files/）的路径；linux 区退回 files 根
        val (area, rel) = CwdPath.split(normalizedCwd)
        val prootCwdRelative = if (area == WorkspaceStorageArea.FILES) rel else ""

        val snap = networkProbe.snapshot()
        val shellContext = WorkspaceShellContext(
            root = workspace.root,
            command = cmd,
            cwd = prootCwdRelative,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workingDir = filesDir,
            timeoutMillis = 0L,
            env = buildMap {
                putAll(ClaudeCodeInstaller.nodeEnv())
                putAll(GuestRuntimeDocs.envFrom(snap))
                // 服务不是 CI 批处理：允许有颜色也无所谓，但保持 PAGER=cat 以免卡住
                put("PAGER", "cat")
                put("CI", "true")
                put("NO_COLOR", "1")
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

        // 关掉 stdin，避免占着管道；stdout/stderr 丢弃（避免撑爆 pipe buffer 卡死）
        runCatching { process.outputStream.close() }
        val handle = Handle(process = process)
        handles[id] = handle
        patch(id) { it.copy(status = LocalServiceStatus.Running) }

        handle.waiter = scope.launch {
            drainQuietly(process)
            val code = runCatching {
                process.waitFor()
            }.getOrElse { -1 }
            handles.remove(id)
            val current = _services.value.find { it.id == id }
            // 若已经是用户 stop 标过的 Failed/Exited，保留 reason
            if (current?.stopReason == LocalServiceStopReason.UserStop ||
                current?.stopReason == LocalServiceStopReason.StopAll
            ) {
                patch(id) {
                    it.copy(
                        status = LocalServiceStatus.Exited,
                        exitCode = code,
                    )
                }
            } else {
                patch(id) {
                    it.copy(
                        status = if (code == 0) LocalServiceStatus.Exited else LocalServiceStatus.Failed,
                        exitCode = code,
                        stopReason = it.stopReason ?: LocalServiceStopReason.Crash,
                        lastError = it.lastError ?: if (code != 0) "exit $code" else null,
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

    private fun patch(id: String, transform: (LocalService) -> LocalService) {
        _services.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun drainQuietly(process: Process) {
        // 各起一个读线程把输出吞掉，防止 pipe 满导致服务阻塞
        fun suck(stream: java.io.InputStream) {
            Thread({
                runCatching {
                    val buf = ByteArray(8 * 1024)
                    while (stream.read(buf) >= 0) {
                        // discard
                    }
                }
            }, "local-svc-drain").apply {
                isDaemon = true
                start()
            }
        }
        suck(process.inputStream)
        suck(process.errorStream)
    }

    private fun defaultLabel(command: String, port: Int?): String {
        val head = command.trim().split(Regex("\\s+")).take(3).joinToString(" ")
        return if (port != null) "$head :$port" else head.take(48)
    }

    private companion object {
        const val MAX_RUNNING = 5
    }
}
