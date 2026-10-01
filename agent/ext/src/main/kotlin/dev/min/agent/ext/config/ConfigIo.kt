package dev.min.agent.ext.config

import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation

/**
 * 配置文件的读取与 guest 路径小工具。ext 与 context 之间没有依赖，所以这里不能复用 MemoryLoader 里的同名私有函数。
 * 路径按 POSIX 字面处理 `.` 与 `..`，不跟随链接（链接由宿主的 stat / read 自己解析）。
 */
internal object GuestPaths {
    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun join(dir: String, name: String): String = normalize("$dir/$name")

    fun parentOf(path: String): String = normalize(path.substringBeforeLast('/', "/").ifEmpty { "/" })

    /** `/`、`/a`、`/a/b`……直到 [path] 本身，宽 → 窄 */
    fun ancestors(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf("/") + parts.indices.map { i -> "/" + parts.subList(0, i + 1).joinToString("/") }
    }
}

/**
 * 读文件的统一出口：读不了的记一条给人看的说明、返回 null，不抛异常——
 * 一个坏文件（权限、链接成环、过大）不该拖垮整个配置加载。**文件不存在不算错误，不记说明**。
 */
internal class ConfigIo(private val fs: WorkspaceFs) {
    val notes = mutableListOf<String>()

    /** 路径对应的文件类型；不存在返回 null；stat 本身失败记一条说明也返回 null */
    suspend fun kind(path: String): FileKind? =
        attempt(path) { fs.stat(GuestPath(path)) }?.kind

    /**
     * 读成文本（去 BOM、统一换行）。不存在 → null（静默）；不是普通文件、超过 [MAX_BYTES]、含 NUL → 记说明返回 null。
     * 空文件返回 ""，由调用方决定算不算内容。
     */
    suspend fun readText(path: String): String? {
        val stat = attempt(path) { fs.stat(GuestPath(path)) } ?: return null
        if (stat.kind != FileKind.File) {
            notes += "跳过 $path：不是普通文件"
            return null
        }
        if (stat.size > MAX_BYTES) {
            notes += "跳过 $path：文件超过 ${MAX_BYTES / 1024} KiB，不像配置文件"
            return null
        }
        val bytes = attempt(path) { fs.read(GuestPath(path), 0, MAX_BYTES.toInt()) } ?: return null
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n")
        if ('\u0000' in text) {
            notes += "跳过 $path：看起来不是文本文件"
            return null
        }
        return text
    }

    /** 目录里的条目，按名字排序（端口给的顺序不可依赖，不排序前缀就可能变）；不存在或不是目录返回空 */
    suspend fun entries(dir: String): List<DirEntry> {
        if (kind(dir) != FileKind.Directory) return emptyList()
        return (attempt(dir) { fs.list(GuestPath(dir)) } ?: emptyList()).sortedBy { it.name }
    }

    /** 条目的真实类型：符号链接要 stat 一下才知道指向文件还是目录 */
    suspend fun resolvedKind(dir: String, entry: DirEntry): FileKind? =
        if (entry.kind == FileKind.Symlink) kind(GuestPaths.join(dir, entry.name)) else entry.kind

    /**
     * [root] 下所有 `.md` 文件，深度优先、每层按名字排序，返回相对 [root] 的路径片段（目录名……文件名）。
     * 指向文件的符号链接照收；指向目录的不跟（端口里认不出链接成环），记一条说明。最多下钻 [MAX_DEPTH] 层。
     */
    suspend fun markdownFiles(root: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        suspend fun walk(dir: String, prefix: List<String>) {
            for (entry in entries(dir)) {
                val path = GuestPaths.join(dir, entry.name)
                when (resolvedKind(dir, entry)) {
                    FileKind.Directory ->
                        if (entry.kind == FileKind.Symlink) notes += "跳过 $path：符号链接目录暂不跟随"
                        else if (prefix.size >= MAX_DEPTH) notes += "跳过 $path：目录嵌套超过 $MAX_DEPTH 层"
                        else walk(path, prefix + entry.name)
                    FileKind.File -> if (entry.name.endsWith(".md") && entry.name.length > 3) out += prefix + entry.name
                    else -> Unit
                }
            }
        }
        walk(root, emptyList())
        return out
    }

    private inline fun <T> attempt(path: String, block: () -> T): T? =
        catchingNonCancellation(block).getOrElse { e ->
            notes += "读取 $path 失败：${e.message ?: e::class.simpleName}"
            null
        }

    companion object {
        /** 配置、skill、agent 定义都是人手写的小文件；1 MiB 以上多半放错了东西 */
        const val MAX_BYTES = 1L shl 20
        const val MAX_DEPTH = 8
    }
}
