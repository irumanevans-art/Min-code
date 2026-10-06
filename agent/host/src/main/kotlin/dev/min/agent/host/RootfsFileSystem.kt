package dev.min.agent.host

import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WorkspaceFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView

/** guest 里的一个挂载点：guest 路径 → 宿主目录（proot 的 `-b 宿主:guest`） */
data class BindMount(val guest: GuestPath, val host: Path, val readOnly: Boolean = false)

/** 路径分级规则：按**解析后**的 guest 路径判定（工作区里一个指向 /sdcard 的链接，写进去就是写手机文件） */
data class ScopeRules(
    val workspace: List<GuestPath> = listOf(GuestPath("/workspace")),
    val device: List<GuestPath> = listOf(GuestPath("/sdcard"), GuestPath("/storage")),
)

class GuestPathException(val path: GuestPath, reason: String) : IOException("$path：$reason")

/**
 * 在宿主侧直接读写 rootfs 的文件系统（docs/agent/04-子系统设计.md §4.3「路径映射」）。
 * 实测 proot 里读小文件慢 50 倍、遍历慢 37 倍，所以文件工具不经 proot，而是在这里按 proot 的规则自己解析路径。
 *
 * ## 解析规则（与 proot 一致，且不可能越界）
 *
 * - 逐个分量解析 guest 路径；每一段若是符号链接，**按 guest 的根**解释它的目标：rootfs 里 `/lib -> usr/lib`
 *   这种绝对链接指向 rootfs 里的 /usr/lib，而不是宿主的 /usr/lib。
 * - 链接目标是宿主上 rootfs（或某个挂载）内部的绝对路径时，先翻译回 guest 路径——proot 的 `--link2symlink`
 *   模拟硬链接时写的就是宿主路径（`.l2s.` 文件）。
 * - `..` 到根就停（POSIX 语义：先展开链接再回退）。挂载点本身不当作 guest 链接解释（它是宿主的目录或链接）。
 * - 结果只可能落在 rootfs 或某个挂载的宿主目录里：每一步都是由本类映射出来的，从不交给宿主去跟随 guest 的链接。
 *
 * 这个类不认识 Android：Android 宿主传 rootfs 目录与 /sdcard 等挂载，桌面评测传一个临时目录当根。
 * 测试用 Jimfs（内存 Unix 文件系统），所以实现只用 java.nio.file，不用 java.io.File。
 */
class RootfsFileSystem(
    root: Path,
    binds: List<BindMount> = emptyList(),
    private val scopes: ScopeRules = ScopeRules(),
) : WorkspaceFs {
    private val root: Path = root.toAbsolutePath().normalize()

    /** 最长前缀优先：/workspace/cache 的挂载要先于 /workspace 匹配 */
    private val binds = binds.sortedByDescending { it.guest.value.length }

    /** 解析掉全部符号链接之后的 guest 路径 */
    fun canonical(path: GuestPath): GuestPath {
        val pending = ArrayDeque(components(path.value))
        val resolved = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part.isEmpty() || part == ".") continue
            if (part == "..") {
                resolved.removeLastOrNull()
                continue
            }
            resolved += part
            if (isBindRoot(resolved)) continue
            val host = hostOf(resolved)
            if (!Files.isSymbolicLink(host)) continue
            if (++hops > MAX_SYMLINK_HOPS) throw GuestPathException(path, "符号链接层数超过 $MAX_SYMLINK_HOPS，可能有环")
            resolved.removeLast()
            val target = guestTargetOf(Files.readSymbolicLink(host).toString().replace('\\', '/'))
            if (target.startsWith("/")) resolved.clear()
            components(target).asReversed().forEach(pending::addFirst)
        }
        return GuestPath("/" + resolved.joinToString("/"))
    }

    /** guest 路径 → 宿主路径（先解析链接） */
    fun hostPath(path: GuestPath): Path = hostOf(components(canonical(path).value))

    /** 扫描根只解析一次；树内只在真实挂载点换宿主目录，普通条目不重复解析父路径。 */
    internal fun snapshotMounts(root: GuestPath): Map<String, Path> = binds
        .filter { it.guest != root && it.guest.isUnder(root) }
        .associate { it.guest.value.removePrefix(root.value.trimEnd('/') + "/") to it.host }

    override fun scopeOf(path: GuestPath): PathScope {
        val real = canonical(path)
        return when {
            scopes.device.any { real.isUnder(it) } -> PathScope.Device
            scopes.workspace.any { real.isUnder(it) } -> PathScope.Workspace
            else -> PathScope.Rootfs
        }
    }

    override suspend fun stat(path: GuestPath): FileStat? = withContext(Dispatchers.IO) {
        val host = hostPath(path)
        val attrs = try {
            Files.readAttributes(host, BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return@withContext null // 不存在，或是悬空链接
        }
        FileStat(kindOf(attrs), attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        FileChannel.open(hostPath(path), READ).use { channel ->
            val available = (channel.size() - offset).coerceAtLeast(0)
            val buffer = ByteBuffer.allocate(minOf(limit.toLong(), available).toInt())
            channel.position(offset)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.array().copyOf(buffer.position())
        }
    }

    /**
     * 先写同目录下的临时文件再原子改名：写一半被杀不会留下半个文件。原文件的权限位照搬（脚本的可执行位不能写丢）。
     * 父目录不存在就建。写进只读挂载抛异常。
     */
    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val real = canonical(path)
        requireWritable(path)
        val host = hostOf(components(real.value))
        val parent = host.parent ?: throw GuestPathException(path, "不能写根目录")
        Files.createDirectories(parent)
        val temp = parent.resolve(".${host.fileName}.min-${System.nanoTime()}.tmp")
        try {
            FileChannel.open(temp, CREATE_NEW, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(false)
            }
            copyPermissions(from = host, to = temp)
            try {
                Files.move(temp, host, REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, host, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        Unit
    }

    override suspend fun list(dir: GuestPath): List<DirEntry> = withContext(Dispatchers.IO) {
        Files.list(hostPath(dir)).use { entries ->
            // Stream.toList 是 JDK 16 API；Android API 26 上只有 Java 8 的 collect。
            entries.collect(java.util.stream.Collectors.toList())
                .map { DirEntry(it.fileName.toString(), kindOf(Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS))) }
                .sortedBy { it.name }
        }
    }

    // ---- 映射细节 ----

    /** 快照恢复还会删文件/建目录；这些动作必须沿用文件工具的只读挂载边界。 */
    internal fun requireWritable(path: GuestPath) {
        bindFor(canonical(path))?.takeIf { it.readOnly }?.let { throw GuestPathException(path, "位于只读挂载 ${it.guest} 内") }
    }

    private fun hostOf(parts: List<String>): Path {
        val guest = "/" + parts.joinToString("/")
        val bind = binds.firstOrNull { GuestPath(guest).isUnder(it.guest) }
        return if (bind == null) {
            if (parts.isEmpty()) root else root.resolve(parts.joinToString("/"))
        } else {
            val rest = guest.removePrefix(bind.guest.value).trimStart('/')
            if (rest.isEmpty()) bind.host else bind.host.resolve(rest)
        }
    }

    private fun bindFor(path: GuestPath): BindMount? = binds.firstOrNull { path.isUnder(it.guest) }

    private fun isBindRoot(parts: List<String>): Boolean {
        val guest = "/" + parts.joinToString("/")
        return binds.any { it.guest.value == guest }
    }

    /** 链接目标若是宿主上 rootfs / 挂载内部的绝对路径，翻译回 guest 路径；否则原样（按 guest 路径解释） */
    private fun guestTargetOf(target: String): String {
        if (!target.startsWith("/")) return target
        return guestPathForHost(root.fileSystem.getPath(target))?.value ?: target
    }

    /** git worktree 的 .git 文件可能写宿主绝对路径；与链接共用已有的反向映射，不能另猜挂载。 */
    internal fun guestPathForHost(host: Path): GuestPath? {
        val target = host.toAbsolutePath().toString().replace('\\', '/')
        underHost(target, root)?.let { return GuestPath("/$it") }
        binds.forEach { bind -> underHost(target, bind.host)?.let { return GuestPath(bind.guest.value.trimEnd('/') + "/" + it) } }
        if (root == root.root && host.fileSystem == root.fileSystem && target.startsWith('/')) return GuestPath(target)
        return null
    }

    /** [target] 在宿主目录 [base] 之内时返回相对部分，否则 null */
    private fun underHost(target: String, base: Path): String? {
        val prefix = base.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
        if (prefix.isEmpty()) return null
        return when {
            target == prefix -> ""
            target.startsWith("$prefix/") -> target.removePrefix("$prefix/")
            else -> null
        }
    }

    private fun copyPermissions(from: Path, to: Path) {
        if (!Files.exists(from)) return
        val source = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
        val target = Files.getFileAttributeView(to, PosixFileAttributeView::class.java) ?: return
        target.setPermissions(source.readAttributes().permissions())
    }

    private fun kindOf(attrs: BasicFileAttributes): FileKind = when {
        attrs.isSymbolicLink -> FileKind.Symlink
        attrs.isDirectory -> FileKind.Directory
        attrs.isRegularFile -> FileKind.File
        else -> FileKind.Other
    }

    private companion object {
        /** 与 Linux 的 MAXSYMLINKS（40）一致：超过就当成环 */
        const val MAX_SYMLINK_HOPS = 40

        fun components(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        fun GuestPath.isUnder(base: GuestPath): Boolean {
            val b = base.value.trimEnd('/')
            return b.isEmpty() || value == b || value.startsWith("$b/")
        }
    }
}
