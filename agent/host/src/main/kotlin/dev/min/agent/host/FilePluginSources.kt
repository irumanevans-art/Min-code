package dev.min.agent.host

import dev.min.agent.spi.*
import kotlinx.coroutines.*
import java.net.URI
import java.net.HttpURLConnection
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** 文件副作用限制在 plugins 根下；新包拒绝所有链接，防止解压、复制和清理越过安装边界。 */
class FilePluginSources(private val fs: RootfsFileSystem, private val exec: Exec, private val pluginsRoot: GuestPath) : PluginSourceHost {
    private fun owned(path: GuestPath): Path {
        val root = fs.hostPath(pluginsRoot).toAbsolutePath().normalize()
        val result = fs.hostPath(path).toAbsolutePath().normalize()
        require(result.startsWith(root) && result != root) { "插件副作用超出安装目录" }
        var cursor: Path? = result
        while (cursor != null && cursor != root.parent) {
            require(!Files.isSymbolicLink(cursor)) { "安装目录包含符号链接" }
            if (Files.exists(cursor, NOFOLLOW_LINKS)) require(cursor.toRealPath().startsWith(root.toAbsolutePath().normalize())) { "安装目录链接逃逸" }
            if (cursor == root) break
            cursor = cursor.parent
        }
        return result
    }
    override suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val lockPath = fs.hostPath(GuestPath(pluginsRoot.value + "/.market.lock"))
        Files.createDirectories(lockPath.parent)
        FileChannel.open(owned(GuestPath(pluginsRoot.value + "/.market.lock")), CREATE, WRITE).use { channel ->
            var lock: java.nio.channels.FileLock? = null
            while (lock == null) {
                coroutineContext.ensureActive()
                try { lock = channel.tryLock() } catch (_: OverlappingFileLockException) { }
                if (lock == null) delay(50)
            }
            try { block() } finally { lock.release() }
        }
    }
    override suspend fun remove(path: GuestPath) = withContext(Dispatchers.IO) {
        val target = owned(path)
        if (Files.exists(target, NOFOLLOW_LINKS)) Files.walk(target).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { file ->
                // Windows 的 git pack 文件带只读位；只在已校验的私有暂存树内清位，避免成功 clone 被清理异常覆盖。
                if (!Files.isSymbolicLink(file)) Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView::class.java, NOFOLLOW_LINKS)?.setReadOnly(false)
                Files.delete(file)
            }
        }
    }
    override suspend fun copy(source: GuestPath, destination: GuestPath, limits: PluginFetchLimits) = withContext(Dispatchers.IO) {
        val origin = fs.hostPath(source)
        val target = owned(destination)
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "目标已存在" }
        var size = 0L
        var count = 0
        Files.walk(origin).use { entries -> entries.forEach { file ->
            val relative = origin.relativize(file)
            if (relative.any { it.toString() == ".git" }) return@forEach
            coroutineContext.ensureActive()
            require(!Files.isSymbolicLink(file) && file.toRealPath().startsWith(origin.toRealPath())) { "插件包含符号链接或链接逃逸" }
            val to = target.resolve(relative).normalize()
            require(to.startsWith(target)) { "复制路径逃逸" }
            if (Files.isDirectory(file, NOFOLLOW_LINKS)) Files.createDirectories(to)
            else {
                require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "不支持特殊文件" }
                size += Files.size(file); count++
                require(size <= limits.expandedBytes && count <= limits.files) { "插件超过展开大小或文件数上限" }
                Files.createDirectories(to.parent)
                Files.copy(file, to)
            }
        } }
    }
    override suspend fun fetch(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits) {
        when (source.type) {
            "directory" -> copy(GuestPath(source.location), destination, limits)
            "file" -> {
                val file = fs.hostPath(GuestPath(source.location))
                require(Files.size(file) <= limits.downloadBytes && !Files.isSymbolicLink(file)) { "清单过大或是链接" }
                fs.writeAtomic(GuestPath(destination.value + "/.claude-plugin/marketplace.json"), Files.readAllBytes(file))
            }
            "url" -> fs.writeAtomic(GuestPath(destination.value + "/.claude-plugin/marketplace.json"), download(source.location, limits.downloadBytes))
            "archive" -> {
                val bytes = if (source.location.startsWith("/")) withContext(Dispatchers.IO) {
                    val file = fs.hostPath(GuestPath(source.location)); require(Files.size(file) <= limits.downloadBytes) { "压缩包超过下载上限" }; Files.readAllBytes(file)
                } else download(source.location, limits.downloadBytes)
                source.sha256?.let { expected -> require(digest(bytes).equals(expected, true)) { "压缩包 SHA256 不匹配" } }
                extract(bytes, owned(destination), limits)
            }
            "github", "git" -> {
                val location = if (source.type == "github") "https://github.com/${source.location}.git" else source.location
                require(!location.startsWith('-') && !location.contains('\n') && !location.contains('\u0000')) { "无效 git 源" }
                source.ref?.let { require(!it.startsWith('-') && !it.contains('\n')) { "无效 git ref" } }
                source.sha?.let { require(Regex("[a-f0-9]{40}").matches(it)) { "无效 git SHA" } }
                val raw = GuestPath(destination.value + "-git")
                owned(raw); Files.createDirectories(owned(raw).parent)
                val flags = source.ref?.let { " --branch ${quote(it)}" }.orEmpty()
                try {
                    val result = exec.run(ShellCommand("git -c core.hooksPath=/dev/null -c protocol.ext.allow=never clone --no-recurse-submodules$flags -- ${quote(location)} ${quote(raw.value)}" +
                        (source.sha?.let { " && git -C ${quote(raw.value)} -c core.hooksPath=/dev/null checkout --detach ${quote(it)}" }.orEmpty()), pluginsRoot,
                        mapOf("GIT_TERMINAL_PROMPT" to "0", "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null")), ExecOptions(120_000))
                    require(result is ExecResult.Exited && result.exitCode == 0) { "git 拉取失败或超时" }
                    val path = source.path?.let { safeRelative(it); "/$it" }.orEmpty()
                    copy(GuestPath(raw.value + path), destination, limits)
                } finally { withContext(NonCancellable) { remove(raw) } }
            }
            else -> error("暂不支持源类型 ${source.type}；支持 git / github / url / archive / directory / file")
        }
    }
    private suspend fun download(raw: String, limit: Long): ByteArray = runInterruptible(Dispatchers.IO) {
        val uri = URI(raw)
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")) { "下载须用 HTTPS（本机测试可用 HTTP）" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "下载失败：HTTP ${connection.responseCode}" }
            require(connection.contentLengthLong <= limit) { "下载超过大小上限" }
            connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException("下载取消")
                    val n = stream.read(buffer); if (n < 0) break
                    require(output.size().toLong() + n <= limit) { "下载超过大小上限" }; output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    private suspend fun extract(bytes: ByteArray, root: Path, limits: PluginFetchLimits) = withContext(Dispatchers.IO) {
        require(bytes.size.toLong() <= limits.downloadBytes) { "压缩包超过下载上限" }
        // ZipInputStream 不提供 Unix mode；先核中央目录，拒绝符号链接后才写文件。
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int): Long = (0..3).fold(0L) { value, n -> value or ((bytes[i + n].toLong() and 255) shl (8 * n)) }
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull { i -> i >= 0 && u32(i) == 0x06054b50L && i + 22 + u16(i + 20) == bytes.size } ?: error("无效 ZIP 中央目录")
        require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10)) { "不支持分卷 ZIP" }
        val count = u16(end + 10); require(count <= limits.files && count != 65535) { "压缩包文件数超限" }
        var pos = u32(end + 16).toInt()
        val names = mutableSetOf<String>()
        repeat(count) {
            require(pos >= 0 && pos + 46 <= end && u32(pos) == 0x02014b50L) { "无效 ZIP 条目" }
            val mode = (u32(pos + 38) ushr 16).toInt()
            require(mode and 0xf000 != 0xa000) { "压缩包符号链接被拒绝" }
            require(mode and 0xf000 in setOf(0, 0x8000, 0x4000)) { "压缩包特殊文件被拒绝" }
            val len = u16(pos + 28); require(pos + 46 + len <= end) { "ZIP 文件名越界" }
            val name = bytes.copyOfRange(pos + 46, pos + 46 + len).toString(Charsets.UTF_8)
            safeRelative(name.trimEnd('/')); require(names.add(name)) { "ZIP 重复路径" }
            require(u32(pos + 24) <= limits.expandedBytes) { "压缩包展开超限" }
            pos += 46 + len + u16(pos + 30) + u16(pos + 32)
        }
        require(pos == end && u32(end + 12) + u32(end + 16) == end.toLong()) { "ZIP 目录不一致" }
        Files.createDirectories(root)
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                safeRelative(entry.name.trimEnd('/'))
                require(names.remove(entry.name)) { "ZIP 本地条目不匹配中央目录" }
                val target = root.resolve(entry.name).normalize()
                require(target.startsWith(root)) { "ZIP 路径逃逸" }
                if (entry.isDirectory) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= limits.expandedBytes) { "压缩包展开超过大小上限" }; output.write(buffer, 0, n) }
                    }
                }
            }
        }
        require(names.isEmpty()) { "ZIP 缺少条目" }
    }
    companion object {
        fun safeRelative(path: String) {
            require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && path.split('/').none { it == ".." || it.isEmpty() }) { "路径穿越或无效路径" }
        }
        private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
