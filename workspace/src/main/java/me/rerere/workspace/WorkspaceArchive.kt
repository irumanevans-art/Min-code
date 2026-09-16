package me.rerere.workspace

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 工作区的打包 / 解包。纯 `java.io` + `java.util.zip`，不碰 Android 类型 ——
 * 这样它能在 `workspace/src/test` 里对着真实临时目录跑普通 JUnit。
 *
 * 这里要防的不是"用户手滑"，是 rootfs 本身：一棵 proot 根文件系统里有自指的符号链接
 * （`/usr/bin/X11 -> .`）、有 link2symlink 留下的 `.l2s.*`、有 `proc` 这种根本不该读的目录。
 * 裸走一遍 `File.walkTopDown()` 会转不出来。
 */
object WorkspaceArchive {

    /** 打包/解包到哪一步了。[filesTotal] <= 0 表示"数不过来"，界面该走不定态进度 */
    data class Progress(
        val filesDone: Int,
        val filesTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentEntry: String,
    )

    enum class SkipReason {
        /** 符号链接：不跟进，也不打包 */
        SYMLINK,

        /** proc / sys / dev / run 这类不该读的目录 */
        SYSTEM_DIR,

        /** zip 里指向解压目录之外的条目（zip slip） */
        UNSAFE_PATH,

        /** 读不动：权限、坏块、进程刚好删了它 */
        UNREADABLE,

        /** proot link2symlink 的中间文件 */
        L2S,
    }

    data class Skip(val path: String, val reason: SkipReason)

    data class Result(
        val entries: Int,
        val bytes: Long,
        val skipped: List<Skip>,
    )

    /** 条目数或总字节超上限。已经写出去的部分是残缺的，调用方要当失败处理 */
    class ArchiveLimitException(message: String) : RuntimeException(message)

    const val MAX_ENTRIES = 50_000
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    /** 数总数的上限：超过这个数就别数了，进度条走不定态。和 measureUsage 一个姿势 */
    private const val COUNT_BAILOUT = 50_000
    private const val BUFFER = 64 * 1024

    /** 打包时遇到的一个节点。[relativePath] 一律相对 base、用 `/` 分隔 */
    data class ArchiveNode(
        val file: File,
        val relativePath: String,
        val isDirectory: Boolean,
    )

    /**
     * 把 [sources] 打进一个 zip。条目名相对 [base]。
     *
     * [out] 由本函数负责关闭（和 `WorkspaceManager.exportFile` 一个约定），调用方别再 `use` 一次。
     */
    fun zip(
        base: File,
        sources: List<File>,
        out: OutputStream,
        skipDirNames: Set<String> = emptySet(),
        maxEntries: Int = MAX_ENTRIES,
        isActive: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val skipped = mutableListOf<Skip>()
        // 第一遍只数数，为的是给一条确定性的进度条；数不过来就认了
        var total = 0
        var totalBytes = 0L
        for (node in walk(base, sources, skipDirNames, onSkip = {})) {
            if (node.isDirectory) continue
            total++
            totalBytes += node.file.length()
            if (total > COUNT_BAILOUT) {
                total = -1
                totalBytes = -1L
                break
            }
        }

        var done = 0
        var bytes = 0L
        val buffer = ByteArray(BUFFER)

        ZipOutputStream(BufferedOutputStream(out, BUFFER)).use { zos ->
            for (node in walk(base, sources, skipDirNames, onSkip = { skipped += it })) {
                ensureActive(isActive)
                if (done + 1 > maxEntries) {
                    throw ArchiveLimitException("Too many entries (> $maxEntries)")
                }
                if (node.isDirectory) {
                    // 空目录也要留一条，否则 round-trip 会把结构丢掉
                    zos.putNextEntry(ZipEntry(node.relativePath + "/").apply {
                        time = node.file.lastModified()
                    })
                    zos.closeEntry()
                    continue
                }
                val entry = ZipEntry(node.relativePath).apply { time = node.file.lastModified() }
                val opened = runCatching { node.file.inputStream() }.getOrNull()
                if (opened == null) {
                    skipped += Skip(node.relativePath, SkipReason.UNREADABLE)
                    continue
                }
                zos.putNextEntry(entry)
                opened.use { input ->
                    while (true) {
                        ensureActive(isActive)
                        val n = input.read(buffer)
                        if (n <= 0) break
                        zos.write(buffer, 0, n)
                        bytes += n
                    }
                }
                zos.closeEntry()
                done++
                onProgress(Progress(done, total, bytes, totalBytes, node.relativePath))
            }
        }
        return Result(entries = done, bytes = bytes, skipped = skipped)
    }

    /**
     * 解压到 [destination]。
     *
     * 这里拿到的是 SAF 的 `content://` 流，**没有中央目录可读**（`ZipFile` 要能 seek 的真文件），
     * 所以 `entry.size` 经常是 -1 —— 体积上限只能按**实际写出的字节**算，不能信头里的数。
     */
    fun unzip(
        input: InputStream,
        destination: File,
        maxEntries: Int = MAX_ENTRIES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        isActive: () -> Boolean = { true },
        onConflict: (File) -> File = { it },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        require(destination.isDirectory || destination.mkdirs()) {
            "Destination is not a directory: $destination"
        }
        val root = destination.canonicalFile
        val skipped = mutableListOf<Skip>()
        var done = 0
        var bytes = 0L
        val buffer = ByteArray(BUFFER)

        ZipInputStream(input.buffered(BUFFER)).use { zis ->
            while (true) {
                ensureActive(isActive)
                val entry = zis.nextEntry ?: break
                val safe = sanitizeEntryName(entry.name)
                if (safe == null) {
                    skipped += Skip(entry.name, SkipReason.UNSAFE_PATH)
                    zis.closeEntry()
                    continue
                }
                val target = File(root, safe).canonicalFile
                if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                    skipped += Skip(entry.name, SkipReason.UNSAFE_PATH)
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    zis.closeEntry()
                    continue
                }
                if (done + 1 > maxEntries) throw ArchiveLimitException("Too many entries (> $maxEntries)")
                target.parentFile?.mkdirs()
                val written = onConflict(target)
                written.outputStream().buffered(BUFFER).use { os ->
                    while (true) {
                        ensureActive(isActive)
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        os.write(buffer, 0, n)
                        bytes += n
                        if (bytes > maxTotalBytes) {
                            throw ArchiveLimitException("Archive is larger than $maxTotalBytes bytes")
                        }
                    }
                }
                zis.closeEntry()
                done++
                onProgress(Progress(done, -1, bytes, -1, safe))
            }
        }
        return Result(entries = done, bytes = bytes, skipped = skipped)
    }

    /**
     * 看一眼 zip 的顶层都有些什么名字，用来决定「要不要放进一个新文件夹」。
     *
     * **会读掉这条流**，调用方必须为真正的导入重新打开一条。
     */
    fun peekTopLevelNames(input: InputStream, limit: Int = 32): Set<String> {
        val names = LinkedHashSet<String>()
        ZipInputStream(input.buffered(BUFFER)).use { zis ->
            while (names.size < limit) {
                val entry = zis.nextEntry ?: break
                val safe = sanitizeEntryName(entry.name)
                if (safe != null) names += safe.substringBefore('/')
                zis.closeEntry()
            }
        }
        return names
    }

    /**
     * 按**确定的顺序**走一遍要打包的树：显式递归 + 按名字排序，不是 `File.walkTopDown()`
     * （那个的顺序取决于文件系统）。同一棵树打两次，条目表必须逐字相同。
     */
    fun walk(
        base: File,
        sources: List<File>,
        skipDirNames: Set<String>,
        onSkip: (Skip) -> Unit = {},
    ): Sequence<ArchiveNode> = sequence {
        val baseCanonical = base.canonicalFile
        for (source in sources.sortedBy { it.name }) {
            yieldAll(walkOne(source, baseCanonical, skipDirNames, onSkip))
        }
    }

    private fun walkOne(
        file: File,
        base: File,
        skipDirNames: Set<String>,
        onSkip: (Skip) -> Unit,
    ): Sequence<ArchiveNode> = sequence {
        val name = entryName(base, file)
        if (name == null) return@sequence
        // 源就是 base 自己（导出"当前这个目录"）：不给它自己一条条目，直接往下走
        if (name.isEmpty()) {
            for (child in file.listFiles().orEmpty().sortedBy { it.name }) {
                yieldAll(walkOne(child, base, skipDirNames, onSkip))
            }
            return@sequence
        }
        if (file.name.startsWith(".l2s.")) {
            onSkip(Skip(name, SkipReason.L2S))
            return@sequence
        }
        // 符号链接一律不跟进：rootfs 里有自指的链接，跟进去这个递归就不会停
        if (Files.isSymbolicLink(file.toPath())) {
            onSkip(Skip(name, SkipReason.SYMLINK))
            return@sequence
        }
        if (!file.isDirectory) {
            if (file.isFile) yield(ArchiveNode(file, name, isDirectory = false))
            return@sequence
        }
        if (file.name in skipDirNames) {
            onSkip(Skip(name, SkipReason.SYSTEM_DIR))
            return@sequence
        }
        val children = file.listFiles()
        if (children == null) {
            onSkip(Skip(name, SkipReason.UNREADABLE))
            return@sequence
        }
        if (children.isEmpty()) {
            yield(ArchiveNode(file, name, isDirectory = true))
            return@sequence
        }
        for (child in children.sortedBy { it.name }) {
            yieldAll(walkOne(child, base, skipDirNames, onSkip))
        }
    }

    /**
     * 相对 [base]、用 `/` 分隔的条目名。
     * [file] 就是 base 本身时返回空串；根本不在 base 底下时返回 null（不该打进这个包）。
     */
    fun entryName(base: File, file: File): String? {
        val relative = runCatching {
            file.canonicalFile.toRelativeString(base.canonicalFile)
        }.getOrNull() ?: return null
        if (relative.isEmpty() || relative == ".") return ""
        val slashed = relative.replace(File.separatorChar, '/')
        if (slashed == ".." || slashed.startsWith("../")) return null
        return slashed
    }

    /**
     * 清洗 zip 里的条目名。返回 null = 拒绝这一条。
     *
     * 拒绝的都是能把文件写到解压目录之外去的形状：绝对路径、`..` 段、盘符、NUL。
     */
    fun sanitizeEntryName(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.contains('\u0000')) return null
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        // C:/… 这种盘符前缀在 Windows 上会让 File(root, name) 直接跳出去
        if (normalized.length >= 2 && normalized[1] == ':') return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun ensureActive(isActive: () -> Boolean) {
        if (!isActive()) throw InterruptedException("Archive operation cancelled")
    }
}
