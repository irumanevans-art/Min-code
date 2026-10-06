package dev.min.agent.host

import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

@Serializable internal data class SnapshotEntry(val path: String, val kind: String, val size: Long = 0, val content: String? = null, val executable: Boolean = false, val link: String? = null)
@Serializable internal data class SnapshotSkip(val path: String, val reason: String)
@Serializable internal data class SnapshotManifest(val version: Int = 1, val root: String, val entries: List<SnapshotEntry>, val excludedDirectories: List<String>, val skipped: List<SnapshotSkip>) {
    fun visible(path: String): Boolean = excludedDirectories.none { path == it || path.startsWith("$it/") } &&
        skipped.none { path == it.path || path.startsWith(it.path + "/") } &&
        entries.none { it.kind == "symlink" && path.startsWith(it.path + "/") }
}
@Serializable internal data class SnapshotCacheEntry(val path: String, val size: Long, val mtimeMs: Long, val fileKey: String?, val content: String)
@Serializable internal data class SnapshotIndex(val version: Int = 1, val writtenAtMs: Long, val scannedAtMs: Long, val manifest: String, val files: List<SnapshotCacheEntry>)
@Serializable internal data class SnapshotRoots(val version: Int = 1, val session: String, val updatedAtMs: Long, val manifests: List<String>, val contents: List<String>)

internal val snapshotJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
internal fun snapshotHash(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2).also { out -> digest.forEachIndexed { i, byte ->
        val n = byte.toInt() and 255
        out[i * 2] = hex[n ushr 4]; out[i * 2 + 1] = hex[n and 15]
    } }.concatToString()
}
internal fun validSnapshotId(id: String): Boolean = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
internal fun normalizedGuest(path: String): String {
    require(path.startsWith('/')) { "检查点路径必须是绝对路径。" }
    val parts = ArrayList<String>()
    path.split('/').forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts += it } }
    return "/" + parts.joinToString("/")
}

/** 同进程锁配合文件锁：不同实例/进程也不能在回收时删除正在提交的引用。 */
internal class SnapshotRepository(val dir: Path) {
    val objects: Path get() = dir.resolve("objects")
    val manifests: Path get() = dir.resolve("manifests")
    @Volatile private var cachedIndex: Pair<ByteArray, SnapshotIndex>? = null
    private val mutex = locks.computeIfAbsent(dir.toAbsolutePath().normalize()) { Mutex() }

    suspend fun <T> locked(check: suspend () -> Unit = {}, timer: SnapshotTimer? = null, block: suspend () -> T): T {
        val lockStarted = System.nanoTime()
        return mutex.withLock {
            check()
            Files.createDirectories(dir)
            FileChannel.open(dir.resolve("lock"), CREATE, WRITE).use { channel ->
                var lock: java.nio.channels.FileLock? = null
                while (lock == null) {
                    check(); currentCoroutineContext().ensureActive()
                    lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) delay(10)
                }
                timer?.let { it.nanos["lock"] = it.nanos.getValue("lock") + System.nanoTime() - lockStarted }
                try { block() } finally { lock.release() }
            }
        }
    }

    fun objectPath(id: String): Path { require(validSnapshotId(id)); return objects.resolve(id.take(2)).resolve(id.drop(2)) }
    fun manifestPath(id: String): Path { require(validSnapshotId(id)); return manifests.resolve(id) }
    suspend fun readContent(id: String): ByteArray? {
        if (!validSnapshotId(id)) return null
        return catchingNonCancellation {
            val output = java.io.ByteArrayOutputStream()
            InflaterInputStream(Files.newInputStream(objectPath(id))).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            bytes.takeIf { snapshotHash(it) == id }
        }.getOrNull()
    }
    suspend fun writeContent(bytes: ByteArray, id: String = snapshotHash(bytes)): Pair<String, Boolean> {
        val context = currentCoroutineContext()
        return writeContentChecked(bytes, id) { context.ensureActive() }
    }
    fun writeContentChecked(bytes: ByteArray, id: String, knownAbsent: Boolean = false, parents: MutableSet<Path>? = null, check: () -> Unit): Pair<String, Boolean> {
        check()
        val path = objectPath(id)
        if (!knownAbsent && Files.exists(path)) return id to false
        val buffer = java.io.ByteArrayOutputStream()
        DeflaterOutputStream(buffer).use { output ->
            var offset = 0
            while (offset < bytes.size) {
                check()
                val count = minOf(64 * 1024, bytes.size - offset)
                output.write(bytes, offset, count); offset += count
            }
        }
        val compressed = buffer.toByteArray()
        if (parents != null && path.parent !in parents) {
            Files.createDirectories(path.parent)
            parents.add(path.parent)
        }
        atomic(path, compressed, sync = false, createParent = parents == null)
        return id to true
    }
    fun readManifest(id: String): SnapshotManifest? {
        if (!validSnapshotId(id)) return null
        return catchingNonCancellation {
            val bytes = Files.readAllBytes(manifestPath(id))
            require(snapshotHash(bytes) == id)
            snapshotJson.decodeFromString<SnapshotManifest>(bytes.decodeToString()).also { manifest ->
                require(manifest.version == 1)
                require(manifest.entries.map { it.path }.distinct().size == manifest.entries.size)
                (manifest.entries.map { it.path } + manifest.excludedDirectories + manifest.skipped.map { it.path }).forEach {
                    require(it.isNotEmpty() && !it.startsWith('/') && it.split('/').none { part -> part in listOf("", ".", "..") })
                }
            }
        }.getOrNull()
    }
    fun writeManifest(manifest: SnapshotManifest): String {
        val bytes = snapshotJson.encodeToString(manifest).encodeToByteArray()
        val id = snapshotHash(bytes)
        if (!Files.exists(manifestPath(id))) atomic(manifestPath(id), bytes, sync = true)
        return id
    }
    fun readIndex(): SnapshotIndex? = catchingNonCancellation {
        val bytes = Files.readAllBytes(dir.resolve("index"))
        // 每次仍读磁盘；只有字节完全相同才复用解码，其他进程写入和损坏都不能被内存缓存遮住。
        cachedIndex?.takeIf { it.first.contentEquals(bytes) }?.second ?: snapshotJson
            .decodeFromString<SnapshotIndex>(bytes.decodeToString())
            .also { require(it.version == 1); cachedIndex = bytes to it }
    }.getOrNull()
    fun writeIndex(index: SnapshotIndex) {
        val bytes = snapshotJson.encodeToString(index).encodeToByteArray()
        atomic(dir.resolve("index"), bytes, sync = false)
        cachedIndex = bytes to index
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, Mutex>()
        fun atomic(path: Path, bytes: ByteArray, sync: Boolean, createParent: Boolean = true) {
            if (createParent) Files.createDirectories(path.parent)
            val temp = path.parent.resolve(".tmp-${UUID.randomUUID()}")
            try {
                FileChannel.open(temp, CREATE_NEW, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    if (sync) channel.force(true)
                }
                try { Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp, path, REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temp) }
        }
    }
}
