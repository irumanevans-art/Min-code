package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SnapshotBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal class SnapshotLimit(message: String) : Exception(message)
internal class SnapshotDeadline(private val nanos: () -> Long, timeoutMs: Long) {
    private val started = nanos()
    private val limit = timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
    suspend fun check() {
        currentCoroutineContext().ensureActive()
        if (nanos() - started >= limit) throw SnapshotLimit("拍快照超过了时限。")
    }
}
internal data class SnapshotScan(val manifest: SnapshotManifest, val cache: List<SnapshotCacheEntry>, val newBytes: Long)

/** 全量走元数据，只有安全的旧 stat 命中才复用内容。所有映射仍交给 RootfsFileSystem。 */
internal class SnapshotScanner(
    private val fs: RootfsFileSystem,
    private val root: String,
    private val repository: SnapshotRepository,
    private val cache: SnapshotIndex?,
    private val budget: SnapshotBudget,
    private val deadline: SnapshotDeadline,
    private val writeObjects: Boolean,
) {
    private val entries = mutableListOf<SnapshotEntry>()
    private val excluded = mutableListOf<String>()
    private val skipped = mutableListOf<SnapshotSkip>()
    private val nextCache = mutableListOf<SnapshotCacheEntry>()
    private val previous = cache?.files?.associateBy { it.path }.orEmpty()
    private var files = 0
    private var newBytes = 0L
    private val seenNew = HashSet<String>()

    suspend fun scan(): SnapshotScan {
        val guest = GuestPath(root)
        require(fs.canonical(guest).value == root) { "工作区根不能是符号链接。" }
        val host = fs.hostPath(guest)
        require(Files.isDirectory(host, NOFOLLOW_LINKS)) { "工作区不存在或不是目录。" }
        val git = Files.exists(host.resolve(".git"), NOFOLLOW_LINKS)
        var ignore = SnapshotIgnore()
        if (!git) ignore = ignore.withFile("", SnapshotIgnore.DEFAULT)
        if (Files.isDirectory(host.resolve(".git"), NOFOLLOW_LINKS)) {
            readIgnore(host.resolve(".git/info/exclude"))?.let { ignore = ignore.withFile("", it) }
        }
        visit("", host, ignore)
        deadline.check()
        return SnapshotScan(SnapshotManifest(root = root, entries = entries.sortedBy { it.path }, excludedDirectories = excluded.sorted(), skipped = skipped.sortedBy { it.path }), nextCache.sortedBy { it.path }, newBytes)
    }

    private fun readIgnore(path: Path): String? {
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        // 配置也不能无限分配；读不了规则时让整次扫描失败，不能把未知排除误当作不存在。
        if (Files.size(path) > MAX_FILE_BYTES) throw IOException("忽略规则文件过大。")
        return Files.readAllBytes(path).decodeToString()
    }

    private suspend fun visit(relative: String, host: Path, inherited: SnapshotIgnore) {
        deadline.check()
        var ignore = inherited
        try { readIgnore(host.resolve(".gitignore"))?.let { ignore = ignore.withFile(relative, it) } }
        catch (e: IOException) { if (relative.isEmpty()) throw e; skipped += SnapshotSkip(relative, "无法读取目录的忽略规则。"); return }
        val children = try {
            Files.newDirectoryStream(host).use { stream -> stream.toList().sortedBy { it.fileName.toString() } }
        } catch (e: IOException) { if (relative.isEmpty()) throw e; skipped += SnapshotSkip(relative, "无法扫描目录。"); return }
        for (rawHost in children) {
            deadline.check()
            val name = rawHost.fileName.toString()
            val path = if (relative.isEmpty()) name else "$relative/$name"
            if (name == ".git") { excluded += path; continue }
            try {
                // 先看链接本身，不能 hostPath(child) 后再 stat，否则链接目标会进入快照。
                val symlink = Files.isSymbolicLink(rawHost)
                val actual = if (symlink) rawHost else fs.hostPath(GuestPath("$root/$path"))
                val attrs = Files.readAttributes(actual, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (ignore.ignored(path, attrs.isDirectory)) {
                    if (attrs.isDirectory) excluded += path else skipped += SnapshotSkip(path, "被忽略规则排除。")
                    continue
                }
                when {
                    attrs.isSymbolicLink -> {
                        countFile()
                        val link = Files.readSymbolicLink(actual).toString()
                        entries += SnapshotEntry(path, "symlink", attrs.size(), snapshotHash(link.encodeToByteArray()), link = link)
                    }
                    attrs.isDirectory -> {
                        entries += SnapshotEntry(path, "dir")
                        visit(path, actual, ignore)
                    }
                    attrs.isRegularFile -> {
                        countFile()
                        if (attrs.size() > MAX_FILE_BYTES) skipped += SnapshotSkip(path, "超过单文件 4 MiB 上限。")
                        else file(path, actual, attrs)
                    }
                    else -> skipped += SnapshotSkip(path, "不是普通文件、目录或符号链接。")
                }
            } catch (e: IOException) { skipped += SnapshotSkip(path, "无法读取文件或元数据。") }
        }
    }

    private fun countFile() {
        if (++files > budget.maxFiles) throw SnapshotLimit("工作区文件数超过快照上限。")
    }

    private suspend fun file(path: String, host: Path, attrs: BasicFileAttributes) {
        val old = previous[path]
        val mtime = attrs.lastModifiedTime().toMillis()
        val key = attrs.fileKey()?.toString()
        // Android 的 mtime 精度可能是 2 秒；将 index 时刻向下取整，整个同桶都视为 racy。
        val racyCutoff = (cache?.writtenAtMs ?: 0) / 2_000 * 2_000
        val reusable = writeObjects && old != null && old.size == attrs.size() && old.mtimeMs == mtime && old.fileKey == key &&
            mtime < racyCutoff && validSnapshotId(old.content) && Files.exists(repository.objectPath(old.content))
        val id = if (reusable) old.content else {
            val bytes = Files.readAllBytes(host)
            deadline.check()
            val after = Files.readAttributes(host, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!after.isRegularFile || after.size() != attrs.size() || after.lastModifiedTime() != attrs.lastModifiedTime() || after.fileKey() != attrs.fileKey()) {
                skipped += SnapshotSkip(path, "读取期间文件发生变化。"); return
            }
            val hash = snapshotHash(bytes)
            if (writeObjects && !Files.exists(repository.objectPath(hash)) && seenNew.add(hash)) {
                if (bytes.size.toLong() > budget.maxNewBytes - newBytes) throw SnapshotLimit("新增内容超过快照字节上限。")
                repository.writeContent(bytes)
                newBytes += bytes.size
            }
            hash
        }
        entries += SnapshotEntry(path, "file", attrs.size(), id, executable(host))
        nextCache += SnapshotCacheEntry(path, attrs.size(), mtime, key, id)
    }

    companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        val EXECUTE_BITS = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
        fun executable(path: Path): Boolean = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?.readAttributes()?.permissions()?.any { it in EXECUTE_BITS } ?: false
    }
}
