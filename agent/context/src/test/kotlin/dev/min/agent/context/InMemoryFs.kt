package dev.min.agent.context

import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WorkspaceFs
import java.io.IOException
import java.nio.file.NoSuchFileException

/**
 * 记忆加载测试用的内存文件系统。目录是隐式的（有文件在它下面就存在）；符号链接只解析整条路径一跳，够测规则目录里的链接。
 * [list] 故意按名字**倒序**返回：加载器必须自己排序，不能依赖端口给的顺序。
 */
internal class InMemoryFs(private val workspace: String = "/workspace") : WorkspaceFs {
    private val files = mutableMapOf<String, ByteArray>()
    private val links = mutableMapOf<String, String>()
    private val broken = mutableSetOf<String>()
    val reads = mutableListOf<String>()

    fun file(path: String, text: String) = bytes(path, text.toByteArray(Charsets.UTF_8))

    fun bytes(path: String, content: ByteArray) {
        files[path] = content
    }

    fun link(path: String, target: String) {
        links[path] = target
    }

    /** 读这个路径时抛 IOException（权限不足之类） */
    fun fail(path: String) {
        files[path] = ByteArray(0)
        broken += path
    }

    private fun resolve(path: String) = links[path] ?: path

    private fun isDir(path: String) = path == "/" || (files.keys + links.keys).any { it.startsWith("$path/") }

    override suspend fun stat(path: GuestPath): FileStat? {
        val p = resolve(path.value)
        return when {
            p in files -> FileStat(FileKind.File, files.getValue(p).size.toLong(), 0)
            isDir(p) -> FileStat(FileKind.Directory, 0, 0)
            else -> null
        }
    }

    override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
        val p = resolve(path.value)
        if (p in broken) throw IOException("权限不足")
        val content = files[p] ?: throw NoSuchFileException(p)
        reads += path.value
        val from = offset.toInt().coerceAtMost(content.size)
        return content.copyOfRange(from, minOf(content.size.toLong(), from.toLong() + limit).toInt())
    }

    override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) = error("加载记忆不该写文件")

    override suspend fun list(dir: GuestPath): List<DirEntry> {
        val d = resolve(dir.value)
        val prefix = if (d == "/") "/" else "$d/"
        return (files.keys + links.keys)
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .map { name ->
                val child = prefix + name
                val kind = when (child) {
                    in links -> FileKind.Symlink
                    in files -> FileKind.File
                    else -> FileKind.Directory
                }
                DirEntry(name, kind)
            }
            .sortedByDescending { it.name }
    }

    override fun scopeOf(path: GuestPath): PathScope {
        val p = resolve(path.value)
        return if (p == workspace || p.startsWith("$workspace/")) PathScope.Workspace else PathScope.Rootfs
    }
}
