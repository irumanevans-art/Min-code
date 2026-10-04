package dev.min.agent.testkit

import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ContentId
import dev.min.agent.spi.FileRestore
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ManifestId
import dev.min.agent.spi.PathChange
import dev.min.agent.spi.PathChangeKind
import dev.min.agent.spi.RestoreResult
import dev.min.agent.spi.SkippedPath
import dev.min.agent.spi.SnapshotBudget
import dev.min.agent.spi.SnapshotResult
import dev.min.agent.spi.SnapshotStats
import dev.min.agent.spi.SnapshotStore
import dev.min.agent.spi.WorkspaceSnapshots
import java.security.MessageDigest

/**
 * 内存里的快照仓库，架在 [FakeFs] 上：语义照端口约定（只碰根下的路径、[FileRestore] 只补根外的），
 * 但没有排除规则、预算与回收——那些是宿主实现的事，由宿主的测试管。
 *
 * [beforeSnapshot] 在每次拍快照前调用，测试用它卡住快照（验证工具闸）或抛异常；[unavailable] 非空时快照直接拍不成。
 */
class FakeSnapshots(private val fs: FakeFs) : WorkspaceSnapshots {
    private val contents = mutableMapOf<String, ByteArray>()
    private val manifests = mutableMapOf<String, Map<String, String>>()

    var beforeSnapshot: suspend () -> Unit = {}
    var unavailable: String? = null

    /** 每次快照的根，按顺序 */
    val snapshots = mutableListOf<GuestPath>()

    /** 每次 restore 的参数，按顺序 */
    val restores = mutableListOf<Pair<ManifestId?, List<FileRestore>>>()

    /** 每个会话最近一次 retain 的根 */
    val retained = mutableMapOf<SessionId, Pair<Set<ManifestId>, Set<ContentId>>>()

    fun content(id: ContentId): String? = contents[id.value]?.decodeToString()

    override fun store(root: GuestPath): SnapshotStore = Store(root.value.trimEnd('/'))

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inner class Store(private val root: String) : SnapshotStore {
        private fun under(path: String) = path.startsWith("$root/")

        private fun scan(): Map<String, String> = fs.paths().filter(::under).associateWith { p ->
            val bytes = fs.bytes(p)!!
            hash(bytes).also { contents[it] = bytes }
        }

        override suspend fun snapshot(budget: SnapshotBudget): SnapshotResult {
            beforeSnapshot()
            snapshots += GuestPath(root)
            unavailable?.let { return SnapshotResult.Unavailable(it) }
            val entries = scan()
            val id = hash(entries.toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" }.encodeToByteArray())
            manifests[id] = entries
            return SnapshotResult.Taken(ManifestId(id), SnapshotStats(entries.size, entries.values.sumOf { contents.getValue(it).size.toLong() }))
        }

        override suspend fun preserve(content: ByteArray): ContentId = ContentId(hash(content).also { contents[it] = content.copyOf() })

        override suspend fun read(id: ContentId): ByteArray? = contents[id.value]?.copyOf()

        override suspend fun diff(from: ManifestId, to: ManifestId?): List<PathChange> {
            val a = manifests.getValue(from.value)
            val b = if (to == null) scan() else manifests.getValue(to.value)
            return (a.keys + b.keys).sorted().mapNotNull { p ->
                val x = a[p]
                val y = b[p]
                when {
                    x == y -> null
                    x == null -> PathChange(GuestPath(p), PathChangeKind.Added, null, ContentId(y!!))
                    y == null -> PathChange(GuestPath(p), PathChangeKind.Deleted, ContentId(x), null)
                    else -> PathChange(GuestPath(p), PathChangeKind.Modified, ContentId(x), ContentId(y))
                }
            }
        }

        override suspend fun restore(target: ManifestId?, extra: List<FileRestore>): RestoreResult {
            restores += target to extra
            val restored = mutableListOf<GuestPath>()
            val deleted = mutableListOf<GuestPath>()
            val skipped = mutableListOf<SkippedPath>()
            if (target != null) {
                val want = manifests.getValue(target.value)
                val now = scan()
                for (p in (want.keys + now.keys).sorted()) {
                    val w = want[p]
                    when {
                        w == now[p] -> Unit
                        w == null -> { fs.delete(p); deleted += GuestPath(p) }
                        else -> { fs.put(p, contents.getValue(w)); restored += GuestPath(p) }
                    }
                }
            }
            for (r in extra) {
                if (target != null && under(r.path.value)) continue
                val c = r.content
                when {
                    c == null -> { fs.delete(r.path.value); deleted += r.path }
                    contents[c.value] == null -> skipped += SkippedPath(r.path, "备份已清理")
                    else -> { fs.put(r.path.value, contents.getValue(c.value)); restored += r.path }
                }
            }
            return RestoreResult(restored, deleted, skipped)
        }

        override suspend fun retain(sessionId: SessionId, manifests: Set<ManifestId>, contents: Set<ContentId>) {
            retained[sessionId] = manifests to contents
        }

        override suspend fun forget(sessionId: SessionId) {
            retained.remove(sessionId)
        }
    }
}
