package dev.min.agent.host

import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.SnapshotBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.coroutines.CoroutineContext

internal class SnapshotLimit(message: String) : Exception(message)
internal class SnapshotDeadline(private val nanos: () -> Long, timeoutMs: Long) {
    private val started = nanos()
    private val limit = timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000) * 1_000_000
    suspend fun check() {
        check(currentCoroutineContext())
    }
    fun check(context: CoroutineContext) {
        context.ensureActive()
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
    private val timer: SnapshotTimer = SnapshotTimer(),
) {
    private val entries = mutableListOf<SnapshotEntry>()
    private val excluded = mutableListOf<String>()
    private val skipped = mutableListOf<SnapshotSkip>()
    private val nextCache = mutableListOf<SnapshotCacheEntry>()
    private val previous = cache?.files?.associateBy { it.path }.orEmpty()
    private var files = 0
    private var newBytes = 0L
    private val seenNew = HashSet<String>()
    private val objectParents = HashSet<Path>()
    private val mounts = fs.snapshotMounts(GuestPath(root))
    private lateinit var context: CoroutineContext
    private var existingObjects: Set<String>? = null

    suspend fun scan(): SnapshotScan {
        context = currentCoroutineContext()
        if (previous.size >= 1_000) existingObjects = timer.measure("traversalStat") {
            // 仓库锁排除了 GC；批量枚举避免 Windows 上每个 cache 命中都单独打开对象路径。
            buildSet {
                if (Files.isDirectory(repository.objects)) Files.newDirectoryStream(repository.objects).use { buckets ->
                    for (bucket in buckets) {
                        deadline.check(context)
                        if (bucket.fileName.toString().length != 2 || !Files.isDirectory(bucket, NOFOLLOW_LINKS)) continue
                        Files.newDirectoryStream(bucket).use { objects ->
                            for (objectPath in objects) {
                                deadline.check(context)
                                val id = bucket.fileName.toString() + objectPath.fileName.toString()
                                if (validSnapshotId(id)) add(id)
                            }
                        }
                    }
                }
            }
        }
        val guest = GuestPath(root)
        require(timer.measure("guestResolve") { fs.canonical(guest).value } == root) { "工作区根不能是符号链接。" }
        val host = timer.measure("guestResolve") { fs.hostPath(guest) }
        require(Files.isDirectory(host, NOFOLLOW_LINKS)) { "工作区不存在或不是目录。" }
        val git = Files.exists(host.resolve(".git"), NOFOLLOW_LINKS)
        var ignore = SnapshotIgnore()
        if (!git) ignore = ignore.withFile("", SnapshotIgnore.DEFAULT)
        walk("", host, ignore)
        deadline.check()
        return SnapshotScan(SnapshotManifest(root = root, entries = entries.sortedBy { it.path }, excludedDirectories = excluded.sorted(), skipped = skipped.sortedBy { it.path }), nextCache.sortedBy { it.path }, newBytes)
    }

    private fun readIgnore(path: Path): String? {
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        // 配置也不能无限分配；读不了规则时让整次扫描失败，不能把未知排除误当作不存在。
        if (Files.size(path) > MAX_FILE_BYTES) throw IOException("忽略规则文件过大。")
        return Files.readAllBytes(path).decodeToString()
    }

    private fun walk(relative: String, host: Path, inherited: SnapshotIgnore) {
        deadline.check(context)
        var ignore = inherited
        try {
            timer.measure("ignore") {
                gitExclude(relative, host)?.let { ignore = ignore.withExcludeFile(relative, it) }
                readIgnore(host.resolve(".gitignore"))?.let { ignore = ignore.withFile(relative, it) }
            }
        } catch (e: IOException) {
            if (relative.isEmpty()) throw e
            skipped += SnapshotSkip(relative, "无法读取目录的忽略规则。"); return
        }
        val started = System.nanoTime()
        val accounted = timer.nanos.values.sum()
        try {
            // 深度 1 让子目录交给自己的规则上下文，不让遍历器与递归各打开一次子目录。
            Files.walkFileTree(host, emptySet(), 1, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deadline.check(context)
                    if (dir == host) return FileVisitResult.CONTINUE
                    item(dir, attrs)
                    return FileVisitResult.SKIP_SUBTREE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    deadline.check(context)
                    item(file, attrs)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    deadline.check(context)
                    if (file == host && relative.isEmpty()) throw exc
                    skipped += SnapshotSkip(pathOf(file), "无法读取文件或元数据。")
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        if (relative.isEmpty()) throw exc
                        skipped += SnapshotSkip(relative, "无法扫描目录。")
                    }
                    return FileVisitResult.CONTINUE
                }
                private fun pathOf(path: Path): String {
                    // Unix 的反斜杠是合法文件名，只有宿主分隔符才能转换成 guest 的斜线。
                    val local = host.relativize(path).toString().replace(host.fileSystem.separator, "/")
                    return if (relative.isEmpty()) local else if (local.isEmpty()) relative else "$relative/$local"
                }
                private fun item(rawHost: Path, walked: BasicFileAttributes) {
                    val path = pathOf(rawHost)
                    if (rawHost.fileName.toString() == ".git") { excluded += path; return }
                    try {
                        // 挂载遮住底层条目，且沿用映射表；普通 guest 链接只记录本身。
                        val actual = mounts[path] ?: rawHost
                        val attrs = if (actual == rawHost) walked else timer.measure("traversalStat") {
                            Files.readAttributes(actual, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        }
                        if (timer.measure("ignore") { ignore.ignoredEntry(path, attrs.isDirectory) }) {
                            if (attrs.isDirectory) excluded += path else skipped += SnapshotSkip(path, "被忽略规则排除。")
                            return
                        }
                        when {
                            attrs.isSymbolicLink -> {
                                countFile()
                                val link = timer.measure("traversalStat") { Files.readSymbolicLink(actual).toString() }
                                entries += SnapshotEntry(path, "symlink", attrs.size(), timer.measure("readHash") { snapshotHash(link.encodeToByteArray()) }, link = link)
                            }
                            attrs.isDirectory -> { entries += SnapshotEntry(path, "dir"); walk(path, actual, ignore) }
                            attrs.isRegularFile -> {
                                countFile()
                                if (attrs.size() > MAX_FILE_BYTES) skipped += SnapshotSkip(path, "超过单文件 4 MiB 上限。")
                                else file(path, actual, attrs)
                            }
                            else -> skipped += SnapshotSkip(path, "不是普通文件、目录或符号链接。")
                        }
                    } catch (e: IOException) { skipped += SnapshotSkip(path, "无法读取文件或元数据。") }
                }
            })
        } finally {
            val own = System.nanoTime() - started - (timer.nanos.values.sum() - accounted)
            timer.nanos["traversalStat"] = timer.nanos.getValue("traversalStat") + own
        }
    }

    private fun gitExclude(relative: String, host: Path): String? {
        val marker = host.resolve(".git")
        if (!Files.exists(marker, NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) return null
        val directory = if (relative.isEmpty()) root else "$root/$relative"
        var git = "$directory/.git"
        if (Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
            val pointer = readIgnore(marker)?.lineSequence()?.firstOrNull().orEmpty()
            if (!pointer.startsWith("gitdir: ")) throw IOException("Git 目录指针无效。")
            git = resolveGitPath(directory, pointer.removePrefix("gitdir: ").trim())
        }
        val common = readIgnore(fs.hostPath(GuestPath("$git/commondir")))?.trim()
        if (!common.isNullOrEmpty()) git = resolveGitPath(git, common)
        return readIgnore(fs.hostPath(GuestPath("$git/info/exclude")))
    }

    private fun resolveGitPath(base: String, target: String): String {
        val path = fs.hostPath(GuestPath(base)).fileSystem.getPath(target)
        if (path.isAbsolute) fs.guestPathForHost(path)?.let { return normalizedGuest(it.value) }
        val relative = target.replace('\\', '/')
        if (!relative.startsWith('/') && path.isAbsolute) throw IOException("Git 目录不在挂载范围内。")
        return normalizedGuest(if (relative.startsWith('/')) relative else "$base/$relative")
    }

    private fun countFile() {
        if (++files > budget.maxFiles) throw SnapshotLimit("工作区文件数超过快照上限。")
    }

    private fun file(path: String, host: Path, attrs: BasicFileAttributes) {
        val old = previous[path]
        val mtime = attrs.lastModifiedTime().toMillis()
        val key = attrs.fileKey()?.toString()
        // Android 的 mtime 精度可能是 2 秒；将 index 时刻向下取整，整个同桶都视为 racy。
        val racyCutoff = (cache?.writtenAtMs ?: 0) / 2_000 * 2_000
        val reusable = writeObjects && old != null && old.size == attrs.size() && old.mtimeMs == mtime && old.fileKey == key &&
            mtime < racyCutoff && validSnapshotId(old.content) && (existingObjects?.contains(old.content) ?: timer.measure("traversalStat") { Files.exists(repository.objectPath(old.content)) })
        val id = if (reusable) old.content else {
            timer.contentReads++
            val bytes = timer.measure("readHash") { Files.readAllBytes(host) }
            deadline.check(context)
            val after = timer.measure("traversalStat") { Files.readAttributes(host, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            if (!after.isRegularFile || after.size() != attrs.size() || after.lastModifiedTime() != attrs.lastModifiedTime() || after.fileKey() != attrs.fileKey()) {
                skipped += SnapshotSkip(path, "读取期间文件发生变化。"); return
            }
            val hash = timer.measure("readHash") { snapshotHash(bytes) }
            if (writeObjects && !Files.exists(repository.objectPath(hash)) && seenNew.add(hash)) {
                if (bytes.size.toLong() > budget.maxNewBytes - newBytes) throw SnapshotLimit("新增内容超过快照字节上限。")
                timer.measure("objects") { repository.writeContentChecked(bytes, hash, knownAbsent = true, parents = objectParents) { context.ensureActive() } }
                newBytes += bytes.size
            }
            hash
        }
        entries += SnapshotEntry(path, "file", attrs.size(), id, timer.measure("traversalStat") { executable(host) })
        nextCache += SnapshotCacheEntry(path, attrs.size(), mtime, key, id)
    }

    companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        val EXECUTE_BITS = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
        fun executable(path: Path): Boolean = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            ?.readAttributes()?.permissions()?.any { it in EXECUTE_BITS } ?: false
    }
}
