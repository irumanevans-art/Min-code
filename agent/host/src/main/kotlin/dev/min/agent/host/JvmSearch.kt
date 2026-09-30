package dev.min.agent.host

import dev.min.agent.spi.GlobQuery
import dev.min.agent.spi.GrepHit
import dev.min.agent.spi.GrepQuery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.FileSystem
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.regex.Pattern

/**
 * [Search] 的纯 JVM 实现：Android 宿主拿不到随 APK 的 ripgrep 时兜底，桌面 CLI 直接用。
 *
 * ## 为什么走 [RootfsFileSystem]
 * 搜索根先经 [RootfsFileSystem.hostPath] 映射到宿主目录（按 guest 的根解析链接、认挂载、越界抛异常），
 * 结果再拼回 guest 路径——模型只认 guest 路径。
 *
 * ## 遍历规则
 * - **不跟随符号链接**（文件与目录都跳过）：跟随会让一个指向 rootfs 之外的链接绕过 [RootfsFileSystem] 的 guest 根规则。
 * - 跳过 `.git`、`node_modules` 目录；跳过二进制文件（前 8 KB 含 NUL）与超过 [MAX_FILE_BYTES] 的文件（整读进内存，不冒 OOM 的险）。
 * - 结果按相对路径排序、同文件按行号排序，稳定，便于测试与缓存。
 *
 * 与 ripgrep 的差异见 agent/host/README.md「已知限制」。
 */
class JvmSearch(private val fs: RootfsFileSystem) : Search {

    override fun grep(query: GrepQuery): Flow<GrepHit> = flow {
        val rootGuest = fs.canonical(query.root)
        val root = fs.hostPath(rootGuest)
        val regex = compile(query)
        // include 走 ripgrep -g 的习惯：不含 '/' 的模式按文件名匹配任意深度，所以 `*.kt` 能命中 src/a.kt
        val include = query.include?.let { GlobFilter(root.fileSystem, it, anyDepthWhenBare = true) }
        var hits = 0
        for (file in candidates(root)) {
            currentCoroutineContext().ensureActive()
            if (include != null && !include.matches(file.rel)) continue
            val text = readText(file.host) ?: continue
            for (hit in scan(text, regex, query, guestOf(rootGuest, file.rel))) {
                if (!hit.isContext) {
                    if (hits >= query.maxHits) return@flow
                    hits++
                }
                emit(hit)
            }
            if (hits >= query.maxHits) return@flow
        }
    }.flowOn(Dispatchers.IO)

    override fun glob(query: GlobQuery): Flow<GuestPath> = flow {
        val rootGuest = fs.canonical(query.root)
        val root = fs.hostPath(rootGuest)
        val filter = GlobFilter(root.fileSystem, query.pattern, anyDepthWhenBare = false)
        var found = 0
        for (file in candidates(root)) {
            currentCoroutineContext().ensureActive()
            if (!filter.matches(file.rel)) continue
            if (found++ >= query.maxResults) return@flow
            emit(guestOf(rootGuest, file.rel))
        }
    }.flowOn(Dispatchers.IO)

    // ---- 遍历 ----

    /** [rel] 是相对搜索根、以 '/' 分隔的路径（根本身是文件时为空串） */
    private class Candidate(val host: Path, val rel: String)

    /** 列出搜索根下全部可搜的普通文件，已按相对路径排序。根是文件时只有它自己 */
    private fun candidates(root: Path): List<Candidate> {
        val out = ArrayList<Candidate>()
        // walkFileTree 默认不跟随链接：链接在 visitFile 里 isRegularFile 为假，被跳过；目录链接不会被进入
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir != root && dir.fileName.toString() in SKIP_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out += Candidate(file, if (file == root) "" else relative(root, file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        out.sortBy { it.rel }
        return out
    }

    private fun relative(root: Path, file: Path): String =
        root.relativize(file).joinToString("/") { it.toString() }

    private fun guestOf(rootGuest: GuestPath, rel: String): GuestPath {
        if (rel.isEmpty()) return rootGuest
        return GuestPath(rootGuest.value.trimEnd('/') + "/" + rel)
    }

    /** 二进制、过大或读不了的文件返回 null（跳过） */
    private fun readText(file: Path): String? {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return null
            val bytes = Files.readAllBytes(file)
            val head = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until head) if (bytes[i] == 0.toByte()) return null
            return String(bytes, Charsets.UTF_8) // 非法字节变成替换符，不抛
        } catch (_: IOException) {
            return null
        }
    }

    // ---- 匹配 ----

    private fun compile(query: GrepQuery): Pattern {
        var flags = 0
        if (query.ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (query.multiline) flags = flags or Pattern.DOTALL or Pattern.MULTILINE
        val source = if (query.literal) Pattern.quote(query.pattern) else query.pattern
        return Pattern.compile(source, flags)
    }

    /** 单个文件的命中（含上下文行），按行号排好序 */
    private fun scan(text: String, regex: Pattern, query: GrepQuery, path: GuestPath): List<GrepHit> {
        val lines = splitLines(text)
        // 每条命中：起止行（从 1 起）与文本。多行命中的文本含换行，GrepTool 靠它算结束行
        val matches = ArrayList<Triple<Int, Int, String>>()
        if (query.multiline) {
            val starts = lineStarts(text)
            val m = regex.matcher(text)
            while (matches.size < query.maxHits && m.find()) {
                val startLine = lineAt(starts, m.start())
                val endLine = lineAt(starts, if (m.end() > m.start()) m.end() - 1 else m.start())
                matches += Triple(startLine, endLine, m.group())
            }
        } else {
            for ((i, line) in lines.withIndex()) {
                if (matches.size >= query.maxHits) break
                if (regex.matcher(line).find()) matches += Triple(i + 1, i + 1, line)
            }
        }
        if (matches.isEmpty()) return emptyList()

        val hits = matches.map { (start, _, t) -> GrepHit(path, start, t) }
        val n = query.contextLines
        if (n <= 0) return hits

        // 上下文行：命中占用的行不再重复给；相邻命中的上下文互相重叠时同一行只给一次
        val taken = HashSet<Int>()
        for ((s, e, _) in matches) for (l in s..e) taken += l
        val context = sortedSetOf<Int>()
        for ((s, e, _) in matches) {
            for (l in maxOf(1, s - n) until s) if (l !in taken) context += l
            for (l in e + 1..minOf(lines.size, e + n)) if (l !in taken) context += l
        }
        return (hits + context.map { GrepHit(path, it, lines[it - 1], isContext = true) }).sortedBy { it.line }
    }

    /** 按 \n 切行，去掉行尾的 \r；末尾换行不产生多余的空行 */
    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        val lines = if (text.endsWith('\n')) parts.dropLast(1) else parts
        return lines.map { it.removeSuffix("\r") }
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        return starts.toIntArray()
    }

    /** 偏移量所在的行号（从 1 起） */
    private fun lineAt(starts: IntArray, offset: Int): Int {
        val idx = starts.binarySearch(offset)
        return if (idx >= 0) idx + 1 else -idx - 1
    }

    /**
     * glob 匹配，相对搜索根。Java 的 glob 里「双星斜杠」前缀要求至少再有一层目录，
     * 而模型（与 ripgrep）习惯这种写法也命中根下的文件，所以带这个前缀的模式额外用去掉前缀的版本再试一次。
     */
    private class GlobFilter(private val fileSystem: FileSystem, pattern: String, anyDepthWhenBare: Boolean) {
        private val matchers: List<PathMatcher>
        private val nameOnly: PathMatcher?

        init {
            val list = ArrayList<PathMatcher>()
            list += fileSystem.getPathMatcher("glob:$pattern")
            if (pattern.startsWith("**/")) list += fileSystem.getPathMatcher("glob:${pattern.removePrefix("**/")}")
            matchers = list
            nameOnly = if (anyDepthWhenBare && '/' !in pattern) fileSystem.getPathMatcher("glob:$pattern") else null
        }

        fun matches(rel: String): Boolean {
            if (rel.isEmpty()) return true // 搜索根本身是文件：调用方点名的，不再过滤
            val path = fileSystem.getPath(rel)
            if (matchers.any { it.matches(path) }) return true
            return nameOnly?.matches(path.fileName) == true
        }
    }

    private companion object {
        val SKIP_DIRS = setOf(".git", "node_modules")
        const val BINARY_SNIFF_BYTES = 8 * 1024
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
