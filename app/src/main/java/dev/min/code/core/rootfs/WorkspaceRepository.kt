package dev.min.code.core.rootfs

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceArchive
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

    /**
     * 从 [path] 往下递归找名字里含 [query] 的文件和文件夹。
     *
     * 文件页原来只能一层层点进去翻：知道文件叫什么、不知道在哪一层时，除了挨个目录点开没有别的办法。
     *
     * 有界遍历，理由和 `ClaudeCodeManager.searchFiles` 一样：rootfs 动辄几十万个文件，
     * 无界 walk 会让界面卡死。跳过 node_modules / .git 这类"知道里面是什么、不需要搜"的目录，
     * 也跳过 proc / sys / dev —— 那是内核的虚拟文件系统，扫进去可能永远出不来。
     * 扫够 [SEARCH_SCAN_LIMIT] 或攒够 [limit] 条就停，宁可少给也不能卡住。
     *
     * 返回的 [WorkspaceFileEntry.path] 是相对区域根的路径，和 [listFiles] 一致，
     * 所以点击结果可以直接复用文件页原有的打开逻辑。
     */
    suspend fun searchFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        query: String,
        limit: Int = 200,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@withContext emptyList()

        val areaRoot = when (area) {
            WorkspaceStorageArea.FILES -> manager.filesDir(root)
            WorkspaceStorageArea.LINUX -> manager.linuxDir(root)
        }
        val base = if (path.isBlank()) areaRoot else File(areaRoot, path)
        if (!base.isDirectory) return@withContext emptyList()

        val results = ArrayList<WorkspaceFileEntry>(limit.coerceAtMost(64))
        var visited = 0
        base.walkTopDown()
            .onEnter { dir -> dir.name !in SEARCH_SKIP_DIRS && visited < SEARCH_SCAN_LIMIT }
            .forEach { file ->
                if (results.size >= limit || visited > SEARCH_SCAN_LIMIT) return@forEach
                visited++
                if (file == base) return@forEach
                if (!file.name.lowercase().contains(needle)) return@forEach
                results += WorkspaceFileEntry(
                    path = file.relativeTo(areaRoot).invariantSeparatorsPath,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isDirectory) 0L else file.length(),
                    updatedAt = file.lastModified(),
                )
            }
        // 文件夹排前面，其次按名字——和文件页平时的顺序一致
        results.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun mkdir(id: String, area: WorkspaceStorageArea, path: String): WorkspaceFileEntry =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.mkdir(root, path, area)
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

    /**
     * 把 [paths] 打成一个 zip 写进 [outputStream]，条目名相对 [basePath]。
     *
     * 走 [runInterruptible] 而不是 [withContext]：打包是一串阻塞的读写，
     * 取消必须能真的把线程从 `read()` 里打断出来，否则用户点了取消还要等整棵树走完。
     */
    suspend fun exportArchive(
        id: String,
        area: WorkspaceStorageArea,
        basePath: String,
        paths: List<String>,
        outputStream: OutputStream,
        onProgress: (WorkspaceArchive.Progress) -> Unit = {},
    ): WorkspaceArchive.Result {
        val job = coroutineContext[Job]
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.exportArchive(
                root = root,
                basePath = basePath,
                paths = paths,
                area = area,
                outputStream = outputStream,
                skipDirNames = if (area == WorkspaceStorageArea.LINUX) LINUX_SKIP_DIRS else emptySet(),
                isActive = { job?.isActive != false && !Thread.currentThread().isInterrupted },
                onProgress = onProgress,
            )
        }
    }

    /** 按确定顺序列出要导出的节点（「复制到文件夹」那条路要自己逐个写出去） */
    suspend fun archiveNodes(
        id: String,
        area: WorkspaceStorageArea,
        basePath: String,
        paths: List<String>,
        onSkip: (WorkspaceArchive.Skip) -> Unit = {},
    ): List<WorkspaceArchive.ArchiveNode> = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.archiveNodes(
            root = root,
            basePath = basePath,
            paths = paths,
            area = area,
            skipDirNames = if (area == WorkspaceStorageArea.LINUX) LINUX_SKIP_DIRS else emptySet(),
            onSkip = onSkip,
        ).toList()
    }

    suspend fun importArchive(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        inputStream: InputStream,
        wrapInFolder: String?,
        onProgress: (WorkspaceArchive.Progress) -> Unit = {},
    ): WorkspaceArchive.Result {
        val job = coroutineContext[Job]
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.importArchive(
                root = root,
                destinationPath = destinationPath,
                area = area,
                inputStream = inputStream,
                wrapInFolder = wrapInFolder,
                isActive = { job?.isActive != false && !Thread.currentThread().isInterrupted },
                onProgress = onProgress,
            )
        }
    }

    suspend fun importStream(
        id: String,
        area: WorkspaceStorageArea,
        relativePath: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = runInterruptible(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.importStream(root, area, relativePath, inputStream)
    }

    suspend fun ensureDirectory(id: String, area: WorkspaceStorageArea, relativePath: String) =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            manager.ensureDirectory(root, area, relativePath)
            Unit
        }

    /** 开一个不撞名的文件夹，返回它真正用上的相对路径（可能带 ` (1)`） */
    suspend fun reserveFolder(id: String, area: WorkspaceStorageArea, relativePath: String): String =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            val dir = manager.reserveFolder(root, area, relativePath)
            val areaRoot = when (area) {
                WorkspaceStorageArea.FILES -> manager.filesDir(root)
                WorkspaceStorageArea.LINUX -> manager.linuxDir(root)
            }
            dir.canonicalFile.toRelativeString(areaRoot.canonicalFile)
                .replace(File.separatorChar, '/')
        }

    /**
     * 这一批有多大。给「这一包很大，确定要打吗」那张确认框用。
     * 和 [measureUsage] 一样是有界的：数到 [SELECTION_SCAN_LIMIT] 就停，标 [SelectionEstimate.truncated]。
     */
    suspend fun estimateSelection(
        id: String,
        area: WorkspaceStorageArea,
        paths: List<String>,
    ): SelectionEstimate = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        var files = 0
        var bytes = 0L
        var truncated = false
        val nodes = manager.archiveNodes(
            root = root,
            basePath = "",
            paths = paths,
            area = area,
            skipDirNames = if (area == WorkspaceStorageArea.LINUX) LINUX_SKIP_DIRS else emptySet(),
        )
        for (node in nodes) {
            if (node.isDirectory) continue
            files++
            bytes += node.file.length()
            if (files >= SELECTION_SCAN_LIMIT) {
                truncated = true
                break
            }
        }
        SelectionEstimate(files, bytes, truncated)
    }

    suspend fun deleteFile(id: String, area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        withContext(Dispatchers.IO) { manager.deleteFile(root, path, recursive, area) }

    suspend fun moveFile(
        id: String,
        area: WorkspaceStorageArea,
        source: String,
        target: String,
        overwrite: Boolean = false,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        manager.ensureWorkspace(root)
        manager.moveFile(root, source, target, overwrite, area)
    }

    /**
     * 量工作区占用。files/ 和 linux/ 分开走，跳过 proc/sys/dev（就算是空目录，
     * 一旦被 bind-mount 成宿主的 /proc 就会把整台手机扫一遍，界面永远停在「计算中」）。
     * [onProgress] 每隔一段文件报一次，好让界面有数字而不是一句死字。
     */
    suspend fun measureUsage(onProgress: (WorkspaceUsage) -> Unit): WorkspaceUsage =
        withContext(Dispatchers.IO) {
            manager.ensureWorkspace(root)
            var filesBytes = 0L
            var linuxBytes = 0L
            var scanned = 0
            var lastEmit = 0
            fun emit(done: Boolean) {
                onProgress(WorkspaceUsage(filesBytes, linuxBytes, scanned, done, error = null))
            }
            fun onFile(area: WorkspaceStorageArea, size: Long) {
                scanned++
                when (area) {
                    WorkspaceStorageArea.FILES -> filesBytes += size
                    WorkspaceStorageArea.LINUX -> linuxBytes += size
                }
                if (scanned - lastEmit >= USAGE_PROGRESS_EVERY) {
                    lastEmit = scanned
                    emit(done = false)
                }
            }
            val job = coroutineContext[Job]
            try {
                walkUsage(filesDir(), WorkspaceStorageArea.FILES, emptySet(), job, ::onFile)
                emit(done = false)
                walkUsage(linuxDir(), WorkspaceStorageArea.LINUX, LINUX_SKIP_DIRS, job, ::onFile)
                WorkspaceUsage(filesBytes, linuxBytes, scanned, done = true, error = null)
                    .also { onProgress(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "measureUsage failed after $scanned files", e)
                WorkspaceUsage(
                    filesBytes = filesBytes,
                    linuxBytes = linuxBytes,
                    scanned = scanned,
                    done = true,
                    error = e.message ?: "未能算出占用空间",
                ).also { onProgress(it) }
            }
        }

    private fun walkUsage(
        start: File,
        area: WorkspaceStorageArea,
        skipDirs: Set<String>,
        job: Job?,
        onFile: (WorkspaceStorageArea, Long) -> Unit,
    ) {
        if (!start.isDirectory) return
        val root = start.toPath()
        java.nio.file.Files.walkFileTree(
            root,
            emptySet(),
            Int.MAX_VALUE,
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(
                    dir: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (job?.isActive == false) return java.nio.file.FileVisitResult.TERMINATE
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != root && (name in skipDirs || name.startsWith(".l2s."))) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: java.nio.file.Path,
                    attrs: java.nio.file.attribute.BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    if (job?.isActive == false) return java.nio.file.FileVisitResult.TERMINATE
                    if (attrs.isRegularFile && !attrs.isSymbolicLink) onFile(area, attrs.size())
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: java.nio.file.Path,
                    exc: java.io.IOException,
                ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
            },
        )
    }

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
        const val USAGE_PROGRESS_EVERY = 2_000
        val LINUX_SKIP_DIRS = setOf("proc", "sys", "dev", "run")

        /**
         * 搜索时不进的目录。前一半是内核的虚拟文件系统（扫进去可能出不来），
         * 后一半是"知道里面是什么、搜它没意义"的依赖与产物目录。
         */
        val SEARCH_SKIP_DIRS = LINUX_SKIP_DIRS + setOf(
            "node_modules", ".git", ".gradle", "build", "dist", ".venv", "__pycache__",
            ".next", "target", "vendor", ".cache",
        )

        /** 单次搜索最多扫多少个条目。超了就用已有结果 */
        const val SEARCH_SCAN_LIMIT = 40_000

        /** 估一批选中项有多大时最多数多少个文件。超了就报 truncated，界面照实说「至少」 */
        const val SELECTION_SCAN_LIMIT = 20_000
    }
}

/**
 * 工作区占用。files 和 linux 分开报，界面可以写成「文件 12 MB · Rootfs 1.8 GB」
 * 而不是一个看不出构成的总数。
 *
 * [done] 为 false 时是扫描中的中间值；失败时 [error] 非空，但已扫到的数字仍保留。
 */
/**
 * 一批选中项有多大。[truncated] 为 true 说明数到上限就停了，界面要写「至少 N 个 / M」。
 */
data class SelectionEstimate(
    val files: Int,
    val bytes: Long,
    val truncated: Boolean,
) {
    /** 大到值得先问一句「确定吗」。批量工作只活在前台，跑一半被切走就断了 */
    val isLarge: Boolean get() = truncated || bytes > LARGE_BYTES || files > LARGE_FILES

    private companion object {
        const val LARGE_BYTES = 512L * 1024 * 1024
        const val LARGE_FILES = 20_000
    }
}

data class WorkspaceUsage(
    val filesBytes: Long = 0,
    val linuxBytes: Long = 0,
    val scanned: Int = 0,
    val done: Boolean = false,
    val error: String? = null,
) {
    val totalBytes: Long get() = filesBytes + linuxBytes
}
