package dev.min.code.core.rootfs

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.uuid.Uuid

private const val TAG = "WorkspaceRepository"

/**
 * 唯一的那个工作区。这个 App 只有一份 Linux 环境，不需要多工作区、不需要数据库：
 * id 是固定常量，状态直接看磁盘（`linux/bin/sh` 在不在、有没有安装中的标记）。
 *
 * 字段名和 RikkaHub 的 `WorkspaceEntity` 对齐，是为了让从那边搬来的 Claude Code 代码
 * （安装器、配置存储、文件页、终端页）几乎不用改。
 */
data class WorkspaceEntity(
    val id: String,
    val name: String,
    /** 目录名（`workspaces/<root>/`），恒等于 id */
    val root: String,
    /** [WorkspaceShellStatus] 的 name */
    val shellStatus: String,
)

/**
 * 固定的工作区 id。搬来的代码到处写 `CLAUDE_CODE_WORKSPACE_ID.toString()`，保留同名同类型的常量，
 * 目录名就是这串 uuid（`workspaces/c0dec0de-…/`）。
 */
val CLAUDE_CODE_WORKSPACE_ID: Uuid = Uuid.parse("c0dec0de-0003-4000-8000-000000000003")

/**
 * 单工作区版的仓库。方法签名照抄 RikkaHub 的 `WorkspaceRepository`（`id` 参数保留但只认
 * [CLAUDE_CODE_WORKSPACE_ID]），这样 `core/claudecode` 那一整套零改动迁入。
 */
class WorkspaceRepository(
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
) {
    private val root = CLAUDE_CODE_WORKSPACE_ID.toString()

    private val _workspace = MutableStateFlow(snapshot())
    val workspace: StateFlow<WorkspaceEntity> = _workspace

    /** 安装中的标记文件：进程被杀在半路时，下次启动据此判成 BROKEN 而不是 READY */
    private val installingMarker: File get() = File(manager.workspaceDir(root), ".installing")

    private fun snapshot(): WorkspaceEntity {
        manager.ensureWorkspace(root)
        val status = when {
            installingMarker.exists() -> WorkspaceShellStatus.BROKEN
            manager.hasRootfs(root) -> WorkspaceShellStatus.READY
            else -> WorkspaceShellStatus.DISABLED
        }
        return WorkspaceEntity(id = root, name = "Min", root = root, shellStatus = status.name)
    }

    fun refresh() {
        _workspace.value = snapshot()
    }

    suspend fun getById(id: String): WorkspaceEntity? = withContext(Dispatchers.IO) {
        if (id != root) null else snapshot().also { _workspace.value = it }
    }

    fun linuxDir(): File = manager.linuxDir(root)
    fun filesDir(): File = manager.filesDir(root)
    fun workspaceDir(): File = manager.workspaceDir(root)

    /**
     * 下载并安装 rootfs（会清空现有的 Linux 目录）。
     * runInterruptible 让协程取消转成线程中断，打断 install 内阻塞的下载/解压循环。
     */
    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        if (id != root) return false
        manager.ensureWorkspace(root)
        installingMarker.writeText("")
        _workspace.value = _workspace.value.copy(shellStatus = WorkspaceShellStatus.INSTALLING.name)
        try {
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(root, url, onProgress)
            }
            installingMarker.delete()
            refresh()
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { installingMarker.delete(); refresh() }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) { installingMarker.delete(); refresh() }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: url=$url", e)
            // 标记留着：半截 rootfs 不能被当成可用
            refresh()
            throw e
        }
    }

    suspend fun listFiles(id: String, area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.listFiles(root, path, area)
        }

    suspend fun readText(id: String, path: String): String = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.readText(root, path)
    }

    suspend fun writeText(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.writeText(root, path, text, overwrite)
        }

    /**
     * 读取文本用于应用内预览/编辑。FILES 区走 [WorkspaceManager.readText]（自带大小保护）；
     * LINUX 区通过 exportFile 读入内存，所以这里显式限大小，避免大文件撑爆内存。
     */
    suspend fun readTextForPreview(id: String, area: WorkspaceStorageArea, path: String): String =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            when (area) {
                WorkspaceStorageArea.FILES -> manager.readText(root, path)
                WorkspaceStorageArea.LINUX -> {
                    val size = manager.fileSize(root, path, area)
                    require(size <= MAX_PREVIEW_BYTES) { "文件过大，无法预览（$size bytes）" }
                    ByteArrayOutputStream().use { out ->
                        manager.exportFile(root, path, area, out)
                        out.toString(Charsets.UTF_8.name())
                    }
                }
            }
        }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.importFile(root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(id: String, area: WorkspaceStorageArea, path: String): Long =
        withContext(Dispatchers.IO) { manager.fileSize(root, path, area) }

    suspend fun exportFile(id: String, area: WorkspaceStorageArea, path: String, outputStream: OutputStream) =
        withContext(Dispatchers.IO) { manager.exportFile(root, path, area, outputStream) }

    suspend fun deleteFile(id: String, area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        withContext(Dispatchers.IO) { manager.deleteFile(root, path, recursive, area) }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): WorkspaceCommandResult = runInterruptible(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.executeCommand(root, command, cwd, timeoutMillis, stdin, env)
    }

    private companion object {
        const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024
    }
}
