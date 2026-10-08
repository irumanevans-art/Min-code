package dev.min.agent.host

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** 存储根在 rootfs 外；每次操作取当前文件系统，不能把 Android 的某一代挂载永久缓存。 */
class CasWorkspaceSnapshots(
    storageRoot: Path,
    private val currentFileSystem: () -> RootfsFileSystem,
    private val home: GuestPath = GuestPath("/root"),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timing: ((SnapshotTiming) -> Unit)? = null,
) : WorkspaceSnapshots {
    /** 调试探针仍取当前一代映射，计时仓库只隔离存储，不能重造挂载。 */
    fun fileSystemForProbe(): RootfsFileSystem = currentFileSystem()
    val directory: Path = storageRoot.resolve("snapshots")
    private val stores = ConcurrentHashMap<String, CasSnapshotStore>()
    override fun store(root: GuestPath): SnapshotStore {
        val path = normalizedGuest(root.value)
        return stores.computeIfAbsent(path) {
            val label = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "root" }
            CasSnapshotStore(path, SnapshotRepository(directory.resolve(snapshotHash(path.encodeToByteArray()).take(16) + "-" + label)), currentFileSystem, normalizedGuest(home.value), clockMs, nanoTime, timing)
        }
    }
    /** 仓库首次超预算会记失败；设置层将来可用此入口明确要求重新尝试。 */
    suspend fun retry(root: GuestPath) = withContext(Dispatchers.IO) {
        val repository = (store(root) as CasSnapshotStore).repository
        repository.locked { Files.deleteIfExists(repository.dir.resolve("unavailable")) }
        Unit
    }
    /**
     * 设置页的「占用」：每个工作区一个仓库目录，含本进程没打开过的。[SnapshotUsage.id] 是目录名（传给 [clear]），
     * [SnapshotUsage.label] 是工作区目录名（取自仓库目录名里 hash 之后的部分），给人认。按占用从大到小。
     */
    suspend fun usage(): List<SnapshotUsage> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.newDirectoryStream(directory).use { dirs ->
            dirs.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.map { path ->
                var bytes = 0L
                Files.walk(path).use { stream ->
                    stream.forEach { if (Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(it) }
                }
                val name = path.fileName.toString()
                SnapshotUsage(name, name.substringAfter('-', name), bytes)
            }
        }.sortedByDescending { it.bytes }
    }

    /**
     * 清空一个工作区的仓库（设置页的「清理」，设计 §3.5「清空 = 删所有根后回收」）：在仓库锁里删掉保留根、清单、内容与扫描索引，
     * 只留锁文件。之后这个工作区里已有会话的旧检查点撤不回（预览会说「没有快照」，编辑记录里存过的内容同样没了），
     * 新的回合照常拍新的。**索引必须一起删**：它记着「这个文件上次的哈希」用来跳过重读，内容删了而索引还在，
     * 下一张快照会引用已经不存在的内容。[id] 必须是 [usage] 给出的目录名，其它一律拒绝（不跟随、不越界）。
     * 不走 [collectSnapshots] 的宽限与 24 小时节流：用户点「清理」要的是现在就腾出空间。
     */
    suspend fun clear(id: String) {
        require(id.isNotEmpty() && '/' !in id && '\\' !in id && id != "." && id != "..") { "仓库名不合法。" }
        val path = directory.resolve(id)
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        withContext(Dispatchers.IO) {
            val repository = SnapshotRepository(path)
            repository.locked {
                Files.newDirectoryStream(path).use { entries ->
                    for (entry in entries) {
                        if (entry.fileName.toString() == "lock") continue
                        if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            Files.walk(entry).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                        } else Files.deleteIfExists(entry)
                    }
                }
            }
        }
    }

    /** 所有工作区，包括本进程未打开的；会话关闭时后台调用，设置接线可覆盖 30 天。 */
    suspend fun gc(cleanupPeriodDays: Int = 30): Unit = withContext(Dispatchers.IO) {
        require(cleanupPeriodDays >= 0)
        if (Files.isDirectory(directory)) Files.newDirectoryStream(directory).use { dirs ->
            dirs.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
                val repository = SnapshotRepository(path)
                repository.locked { collectSnapshots(repository, clockMs(), cleanupPeriodDays) }
            }
        }
    }
}

internal class CasSnapshotStore(
    private val root: String,
    internal val repository: SnapshotRepository,
    private val currentFileSystem: () -> RootfsFileSystem,
    private val home: String,
    private val clockMs: () -> Long,
    private val nanoTime: () -> Long,
    private val timing: ((SnapshotTiming) -> Unit)?,
) : SnapshotStore {
    override suspend fun snapshot(budget: SnapshotBudget): SnapshotResult = withContext(Dispatchers.IO) {
        val started = nanoTime()
        val measuredStart = System.nanoTime()
        val timer = SnapshotTimer()
        var first = false
        try {
            val fs = currentFileSystem()
            if (root == "/" || root == home || timer.measure("guestResolve") { fs.hostPath(GuestPath(root)) == fs.hostPath(GuestPath(home)) }) return@withContext SnapshotResult.Unavailable("不在根目录或主目录上拍整树快照。")
            if (budget.timeoutMs <= 0 || budget.firstTimeoutMs <= 0 || budget.maxFiles < 0 || budget.maxNewBytes < 0) return@withContext SnapshotResult.Unavailable("快照预算无效。")
            first = timer.measure("index") { repository.readIndex() } == null
            val deadline = SnapshotDeadline(nanoTime, if (first) budget.firstTimeoutMs else budget.timeoutMs)
            val result = withTimeoutOrNull(if (first) budget.firstTimeoutMs else budget.timeoutMs) { repository.locked(deadline::check, timer) {
                val unavailable = repository.dir.resolve("unavailable")
                if (Files.exists(unavailable)) return@locked SnapshotResult.Unavailable(Files.readAllBytes(unavailable).decodeToString())
                val index = timer.measure("index") { repository.readIndex() }
                try {
                    val scan = SnapshotScanner(fs, root, repository, index, budget, deadline, true, timer).scan()
                    deadline.check()
                    // 清单只有全扫描通过后才能发布，不能让半张清单被当作“文件不存在”。
                    val id = timer.measure("manifestSync") { repository.writeManifest(scan.manifest) }
                    // 不推进没变的 index 时间，保留旧 racy 桶直到文件确实重写了缓存。
                    if (index == null || index.manifest != id || index.files != scan.cache) {
                        timer.measure("index") { repository.writeIndex(SnapshotIndex(writtenAtMs = clockMs(), scannedAtMs = clockMs(), manifest = id, files = scan.cache)) }
                    }
                    val files = scan.manifest.entries.filter { it.kind == "file" }
                    SnapshotResult.Taken(ManifestId(id), SnapshotStats(files.size, files.sumOf { it.size }, scan.newBytes, scan.manifest.skipped.size, (nanoTime() - started) / 1_000_000))
                } catch (e: SnapshotLimit) {
                    if (index == null) SnapshotRepository.atomic(unavailable, e.message.orEmpty().encodeToByteArray(), sync = true)
                    SnapshotResult.Unavailable(e.message.orEmpty())
                }
            } }
            if (result == null && first) markFirstFailure("拍快照超过了时限。")
            result ?: SnapshotResult.Unavailable("拍快照超过了时限。")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: SnapshotLimit) {
            if (first) catchingNonCancellation { markFirstFailure(e.message.orEmpty()) }
            SnapshotResult.Unavailable(e.message.orEmpty())
        }
        catch (e: Exception) { SnapshotResult.Unavailable("工作区或快照仓库无法读取或写入。") }
        finally { timing?.invoke(timer.result(System.nanoTime() - measuredStart)) }
    }

    private suspend fun markFirstFailure(reason: String) {
        // 等待另一个会话的锁也可能超时；不让登记说明再次无限等待。
        withTimeoutOrNull(100) { repository.locked {
            if (repository.readIndex() == null) SnapshotRepository.atomic(repository.dir.resolve("unavailable"), reason.encodeToByteArray(), sync = true)
        } }
    }

    override suspend fun preserve(content: ByteArray): ContentId? = withContext(Dispatchers.IO) {
        catchingNonCancellation { repository.locked { ContentId(repository.writeContent(content).first) } }.getOrNull()
    }
    override suspend fun read(id: ContentId): ByteArray? = withContext(Dispatchers.IO) { repository.readContent(id.value) }

    override suspend fun diff(from: ManifestId, to: ManifestId?): List<PathChange> = withContext(Dispatchers.IO) {
        repository.locked {
            val a = repository.readManifest(from.value) ?: return@locked emptyList()
            val b = if (to != null) repository.readManifest(to.value) else currentScan(currentFileSystem())
            if (b == null || a.root != root || b.root != root) return@locked emptyList()
            snapshotChanges(a, b).map { (path, x, y) -> PathChange(GuestPath("$root/$path"), when { x == null -> PathChangeKind.Added; y == null -> PathChangeKind.Deleted; else -> PathChangeKind.Modified }, x?.content?.let(::ContentId), y?.content?.let(::ContentId), x?.kind == "symlink" || y?.kind == "symlink") }
        }
    }

    internal suspend fun currentScan(fs: RootfsFileSystem): SnapshotManifest? = catchingNonCancellation {
        val budget = SnapshotBudget.DESKTOP
        SnapshotScanner(fs, root, repository, null, budget, SnapshotDeadline(nanoTime, budget.firstTimeoutMs), false).scan().manifest
    }.getOrNull()

    override suspend fun restore(target: ManifestId?, extra: List<FileRestore>): RestoreResult = withContext(Dispatchers.IO) {
        try { repository.locked { SnapshotRestorer(currentFileSystem(), root, repository, ::currentScan).restore(target, extra) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { RestoreResult(emptyList(), emptyList(), (listOf(GuestPath(root)) + extra.map { it.path }).distinct().map { SkippedPath(it, "快照仓库或工作区无法恢复。") }) }
    }

    override suspend fun retain(sessionId: SessionId, manifests: Set<ManifestId>, contents: Set<ContentId>) = withContext(Dispatchers.IO) {
        require(manifests.all { validSnapshotId(it.value) } && contents.all { validSnapshotId(it.value) })
        repository.locked {
            val refs = SnapshotRoots(session = sessionId.value, updatedAtMs = clockMs(), manifests = manifests.map { it.value }.sorted(), contents = contents.map { it.value }.sorted())
            SnapshotRepository.atomic(refPath(sessionId), snapshotJson.encodeToString(refs).encodeToByteArray(), sync = true)
        }
    }
    override suspend fun forget(sessionId: SessionId) = withContext(Dispatchers.IO) {
        repository.locked { Files.deleteIfExists(refPath(sessionId)); Files.deleteIfExists(refPath(sessionId).parent); Unit }
    }
    private fun refPath(sessionId: SessionId) = repository.dir.resolve("refs").resolve(snapshotHash(sessionId.value.encodeToByteArray())).resolve("roots")
}

internal fun snapshotChanges(a: SnapshotManifest, b: SnapshotManifest): List<Triple<String, SnapshotEntry?, SnapshotEntry?>> {
    val before = a.entries.associateBy { it.path }
    val after = b.entries.associateBy { it.path }
    return (before.keys + after.keys).sorted().mapNotNull { path ->
        if (!a.visible(path) || !b.visible(path) || before[path] == after[path]) null else Triple(path, before[path], after[path])
    }
}

/** [CasWorkspaceSnapshots.usage] 的一行 */
data class SnapshotUsage(val id: String, val label: String, val bytes: Long)
