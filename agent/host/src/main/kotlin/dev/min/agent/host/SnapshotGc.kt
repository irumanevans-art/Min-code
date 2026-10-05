package dev.min.agent.host

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 一小时宽限保住已完成快照、尚未来得及 retain 的窗口；回收与发布引用共用仓库锁。 */
internal suspend fun collectSnapshots(repository: SnapshotRepository, nowMs: Long, cleanupPeriodDays: Int) {
    val stamp = repository.dir.resolve("gc-time")
    val last = if (Files.exists(stamp)) Files.readAllBytes(stamp).decodeToString().toLongOrNull() else null
    if (last != null && nowMs - last < DAY_MS) return
    val markedManifests = HashSet<String>()
    val markedContents = HashSet<String>()
    val refs = repository.dir.resolve("refs")
    if (Files.isDirectory(refs, NOFOLLOW_LINKS)) Files.newDirectoryStream(refs).use { sessions ->
        for (session in sessions) {
            currentCoroutineContext().ensureActive()
            if (!Files.isDirectory(session, NOFOLLOW_LINKS)) continue
            val path = session.resolve("roots")
            if (!Files.exists(path, NOFOLLOW_LINKS)) continue
            // 引用损坏时停止整次 GC；不能把无法识别的根当成“没有引用”。
            val roots = snapshotJson.decodeFromString<SnapshotRoots>(Files.readAllBytes(path).decodeToString())
            require(roots.version == 1 && (roots.manifests + roots.contents).all(::validSnapshotId))
            if (nowMs - roots.updatedAtMs > cleanupPeriodDays.toLong() * DAY_MS) {
                Files.delete(path); Files.delete(session); continue
            }
            markedManifests += roots.manifests
            markedContents += roots.contents
        }
    }
    for (id in markedManifests) {
        currentCoroutineContext().ensureActive()
        if (!Files.exists(repository.manifestPath(id))) continue
        val manifest = repository.readManifest(id) ?: error("引用的快照清单损坏，停止回收。")
        markedContents += manifest.entries.filter { it.kind == "file" }.mapNotNull { it.content }
    }
    sweep(repository.manifests, nowMs, markedManifests, objects = false)
    sweep(repository.objects, nowMs, markedContents, objects = true)
    SnapshotRepository.atomic(stamp, nowMs.toString().encodeToByteArray(), sync = true)
}

private suspend fun sweep(directory: Path, nowMs: Long, marked: Set<String>, objects: Boolean) {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return
    Files.walk(directory).use { stream ->
        val paths = stream.iterator()
        while (paths.hasNext()) {
            currentCoroutineContext().ensureActive()
            val path = paths.next()
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) continue
            val id = if (objects) path.parent.fileName.toString() + path.fileName.toString() else path.fileName.toString()
            // 临时文件也会过期，但与年轻对象一样保留一小时，绝不触碰跟随链接的目标。
            if (id !in marked && Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() < nowMs - HOUR_MS) Files.delete(path)
        }
    }
}
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
