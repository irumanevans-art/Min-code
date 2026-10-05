package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFileAttributeView

/** 写前检查每个 guest 父目录；不能让 canonical 把链接展开后替我们悄悄写到另一个地方。 */
internal class SnapshotRestorer(
    private val fs: RootfsFileSystem,
    private val root: String,
    private val repository: SnapshotRepository,
    private val scan: suspend (RootfsFileSystem) -> SnapshotManifest?,
) {
    private val restored = mutableListOf<GuestPath>()
    private val deleted = mutableListOf<GuestPath>()
    private val skipped = linkedMapOf<GuestPath, String>()

    suspend fun restore(target: ManifestId?, extra: List<FileRestore>): RestoreResult {
        val want = target?.let { repository.readManifest(it.value) }
        if (target != null && (want == null || want.root != root)) {
            skipped[GuestPath(root)] = "快照备份已清理、损坏或不属于这个工作区。"
            extra.forEach { skipped[it.path] = "目标清单缺失，无法判断编辑记录的覆盖范围。" }
            return result()
        }
        if (want != null) {
            val now = scan(fs)
            if (now == null) { skipped[GuestPath(root)] = "无法完整扫描当前工作区，没有动文件。"; return result() }
            // 两边看不见的区域留下说明，也不会拿“不存在”作为删除理由。
            (want.excludedDirectories + now.excludedDirectories).forEach { skipped[GuestPath("$root/$it")] = "排除的目录未恢复。" }
            (want.skipped + now.skipped).forEach { skipped[GuestPath("$root/${it.path}")] = it.reason }
            val changes = snapshotChanges(want, now)
            // 删除先从深处开始，空目录才删；大文件或被排除内容会自然阻止父目录删除。
            for ((path, x, y) in changes.sortedByDescending { it.first.count { c -> c == '/' } }) {
                if (x != null || y == null) continue
                apply(GuestPath("$root/$path")) { delete(GuestPath("$root/$path"), y.kind) }
            }
            for ((path, x, y) in changes.sortedBy { it.first.count { c -> c == '/' } }) {
                if (x == null) continue
                val guest = GuestPath("$root/$path")
                if (x.kind == "symlink" || y?.kind == "symlink") { skipped[guest] = "符号链接不恢复。"; continue }
                apply(guest) {
                    when (x.kind) {
                        "dir" -> {
                            safeParents(guest, create = true)
                            val host = fs.hostPath(guest)
                            if (Files.exists(host, NOFOLLOW_LINKS) && !Files.isDirectory(host, NOFOLLOW_LINKS)) Files.delete(host)
                            Files.createDirectories(host)
                            restored += guest
                        }
                        "file" -> write(guest, x.content?.let(::ContentId), x.executable)
                        else -> skipped[guest] = "清单里的路径类型无法恢复。"
                    }
                }
            }
        }
        for (file in extra) {
            currentCoroutineContext().ensureActive()
            val guest = GuestPath(normalizedGuest(file.path.value))
            val relative = guest.value.takeIf { it.startsWith("$root/") }?.removePrefix("$root/")
            if (want != null && relative != null && want.visible(relative)) continue
            apply(guest) {
                if (file.content == null) delete(guest, "file") else write(guest, file.content, null)
            }
        }
        return result()
    }

    private suspend fun apply(path: GuestPath, action: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        try { action() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: UnsafeSnapshotPath) { skipped[path] = e.message.orEmpty() }
        catch (e: Exception) { skipped[path] = "文件写入、删除或权限恢复失败。" }
    }

    private fun safeParents(path: GuestPath, create: Boolean) {
        val value = normalizedGuest(path.value)
        if (value == "/") throw UnsafeSnapshotPath("不能恢复根目录。")
        val parts = value.trimStart('/').split('/')
        val base = fs.hostPath(GuestPath("/"))
        if (base.fileSystem.separator == "\\" && parts.any { '\\' in it || ':' in it }) throw UnsafeSnapshotPath("路径包含宿主分隔符。")
        for (count in 0 until parts.size) {
            val parent = GuestPath("/" + parts.take(count).joinToString("/"))
            if (fs.canonical(parent) != parent) throw UnsafeSnapshotPath("父目录是符号链接，拒绝写入。")
            val host = fs.hostPath(parent)
            if (Files.isSymbolicLink(host)) throw UnsafeSnapshotPath("父目录是符号链接，拒绝写入。")
            if (!Files.exists(host, NOFOLLOW_LINKS)) {
                if (!create) throw UnsafeSnapshotPath("父目录不存在。")
                fs.requireWritable(parent)
                Files.createDirectory(host)
            }
            if (!Files.isDirectory(host, NOFOLLOW_LINKS)) throw UnsafeSnapshotPath("父路径不是目录，拒绝写入。")
        }
        if (fs.canonical(GuestPath(value)).value != value || Files.isSymbolicLink(fs.hostPath(GuestPath(value)))) throw UnsafeSnapshotPath("符号链接不恢复。")
        fs.requireWritable(GuestPath(value))
    }

    private suspend fun write(path: GuestPath, content: ContentId?, executable: Boolean?) {
        val bytes = content?.let { repository.readContent(it.value) }
        if (bytes == null) { skipped[path] = "内容备份已清理或损坏。"; return }
        safeParents(path, create = true)
        val host = fs.hostPath(path)
        if (Files.isDirectory(host, NOFOLLOW_LINKS)) Files.delete(host)
        fs.writeAtomic(path, bytes)
        if (executable != null) {
            Files.getFileAttributeView(host, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)?.let { view ->
                val permissions = view.readAttributes().permissions().toMutableSet()
                permissions.removeAll(SnapshotScanner.EXECUTE_BITS)
                if (executable) permissions.addAll(SnapshotScanner.EXECUTE_BITS)
                view.setPermissions(permissions)
            }
        }
        restored += path
        skipped.remove(path)
    }

    private fun delete(path: GuestPath, kind: String) {
        safeParents(path, create = false)
        val host = fs.hostPath(path)
        if (kind == "symlink" || Files.isSymbolicLink(host)) { skipped[path] = "符号链接不删除。"; return }
        // 编辑记录没有“删目录”的授权，只能恢复那个确切文件。
        if (kind != "dir" && Files.isDirectory(host, NOFOLLOW_LINKS)) throw UnsafeSnapshotPath("目标是目录，文件编辑记录不能删除它。")
        if (Files.deleteIfExists(host)) deleted += path
    }
    private fun result() = RestoreResult(restored.distinct(), deleted.distinct(), skipped.map { SkippedPath(it.key, it.value) })
}

private class UnsafeSnapshotPath(message: String) : Exception(message)
