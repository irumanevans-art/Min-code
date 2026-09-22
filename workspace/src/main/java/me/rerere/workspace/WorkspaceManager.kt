package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    /**
     * 额外挂进 rootfs 的宿主目录。
     *
     * 传的是 **provider 而不是现成的列表**：整机存储是设置里随时可开关的一档，
     * 关掉之后下一次起 proot 就不该再挂；构造时定死的话，用户关了开关还得重启 App 才生效。
     */
    private val bindMounts: () -> List<WorkspaceBindMount> = { emptyList() },
) {
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private fun sortedBindMounts(): List<WorkspaceBindMount> =
        bindMounts().sortedByDescending { it.target.trimEnd('/').length }

    init {
        baseDir.mkdirs()
    }

    fun ensureWorkspace(root: String): File {
        val dir = workspaceDir(root)
        filesDir(root).mkdirs()
        linuxDir(root).mkdirs()
        tempDir(root).mkdirs()
        return dir
    }

    fun workspaceDir(root: String): File {
        requireValidRoot(root)
        return File(baseDir, root)
    }

    fun filesDir(root: String): File = File(workspaceDir(root), FILES_DIR)

    fun linuxDir(root: String): File = File(workspaceDir(root), LINUX_DIR)

    fun tempDir(root: String): File = File(workspaceDir(root), TEMP_DIR)

    fun hasRootfs(root: String): Boolean = File(linuxDir(root), "bin/sh").isFile

    fun deleteWorkspace(root: String): Boolean = workspaceDir(root).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
    ): String = fileSystem.readText(filesDir(root), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) {
        val file = fileSystem.resolve(areaDir(root, area), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 [paths]（相对区根）打成一个 zip 写进 [outputStream]，条目名相对 [basePath]。
     *
     * [exportFile] 只认单个文件（`require(file.isFile)`，那条约束还被预览读取依赖着），
     * 文件夹与批量都走这一条。[outputStream] 由打包器负责关。
     */
    fun exportArchive(
        root: String,
        basePath: String,
        paths: List<String>,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
        skipDirNames: Set<String> = emptySet(),
        isActive: () -> Boolean = { true },
        onProgress: (WorkspaceArchive.Progress) -> Unit = {},
    ): WorkspaceArchive.Result {
        val areaRoot = areaDir(root, area)
        val base = fileSystem.resolve(areaRoot, basePath)
        val sources = paths.map { fileSystem.resolve(areaRoot, it) }
            .onEach { require(it.exists()) { "Does not exist: $it" } }
        return WorkspaceArchive.zip(
            base = base,
            sources = sources,
            out = outputStream,
            skipDirNames = skipDirNames,
            isActive = isActive,
            onProgress = onProgress,
        )
    }

    /** 按确定顺序列出要导出的节点。「复制到文件夹」那条路要自己逐个建目录 / 写文件 */
    fun archiveNodes(
        root: String,
        basePath: String,
        paths: List<String>,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        skipDirNames: Set<String> = emptySet(),
        onSkip: (WorkspaceArchive.Skip) -> Unit = {},
    ): Sequence<WorkspaceArchive.ArchiveNode> {
        val areaRoot = areaDir(root, area)
        val base = fileSystem.resolve(areaRoot, basePath)
        val sources = paths.map { fileSystem.resolve(areaRoot, it) }
            .onEach { require(it.exists()) { "Does not exist: $it" } }
        return WorkspaceArchive.walk(base, sources, skipDirNames, onSkip)
    }

    /**
     * 把一个 zip 解到 [destinationPath] 之下。
     * [wrapInFolder] 非空时先在下面开一个（不撞名的）同名文件夹再解 —— 免得一包散文件炸进当前目录。
     */
    fun importArchive(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        inputStream: InputStream,
        wrapInFolder: String? = null,
        isActive: () -> Boolean = { true },
        onProgress: (WorkspaceArchive.Progress) -> Unit = {},
    ): WorkspaceArchive.Result {
        val areaRoot = areaDir(root, area)
        val dest = if (wrapInFolder.isNullOrBlank()) {
            fileSystem.ensureDir(areaRoot, destinationPath)
        } else {
            reserveFolder(root, area, joinPath(destinationPath, wrapInFolder))
        }
        return WorkspaceArchive.unzip(
            input = inputStream,
            destination = dest,
            isActive = isActive,
            onConflict = { f -> if (!f.exists()) f else nonConflicting(f) },
            onProgress = onProgress,
        )
    }

    /** 一条流写进一个相对路径，中间目录自动补齐。导入目录树时逐个文件走这里 */
    fun importStream(
        root: String,
        area: WorkspaceStorageArea,
        relativePath: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = fileSystem.importBytes(areaDir(root, area), relativePath, inputStream)

    fun ensureDirectory(root: String, area: WorkspaceStorageArea, relativePath: String): File =
        fileSystem.ensureDir(areaDir(root, area), relativePath)

    /** 开一个不撞名的文件夹，返回它真正用上的名字（可能带 ` (1)`） */
    fun reserveFolder(root: String, area: WorkspaceStorageArea, relativePath: String): File {
        val dir = fileSystem.nonConflictingTarget(areaDir(root, area), relativePath)
        require(dir.exists() || dir.mkdirs()) { "Failed to create directory: $relativePath" }
        return dir
    }

    private fun nonConflicting(file: File): File {
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 1
        var candidate: File
        do {
            candidate = File(file.parentFile, "$stem ($n)$ext")
            n++
        } while (candidate.exists())
        return candidate
    }

    private fun joinPath(parent: String, child: String): String =
        if (parent.isBlank()) child else "${parent.trimEnd('/')}/$child"

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts().forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private fun resolveRootfsFile(root: String, path: String): File {
        val location = resolveRootfsPath(root, path)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun mkdir(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = fileSystem.mkdir(areaDir(root, area), path)

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean =
        fileSystem.delete(areaDir(root, area), path, recursive)

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): WorkspaceFileEntry = fileSystem.move(areaDir(root, area), source, target, overwrite)

    fun glob(root: String, pattern: String, path: String = ""): List<WorkspaceFileEntry> =
        fileSystem.glob(filesDir(root), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return shellRunner.execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root),
                linuxDir = linuxDir(root),
                tempDir = tempDir(root),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts(),
                env = env,
            )
        )
    }

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(root: String, area: WorkspaceStorageArea): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root)
        WorkspaceStorageArea.LINUX -> linuxDir(root)
    }

    fun cleanupAllTempDirs() {
        val roots = baseDir.listFiles()?.filter { it.isDirectory } ?: return
        for (dir in roots) {
            val root = dir.name
            if (!root.matches(ROOT_NAME_REGEX)) continue
            // PRoot temp files
            tempDir(root).let { if (it.exists()) it.deleteRecursively() }
            // Rootfs /tmp and /var/tmp
            File(linuxDir(root), "tmp").let { if (it.exists()) it.deleteRecursively() }
            File(linuxDir(root), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
        }
    }

    companion object {
        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
