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
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

/** 文件副作用限制在 plugins 根下；新包拒绝所有链接，防止解压、复制和清理越过安装边界。 */
class FilePluginSources(private val fs: RootfsFileSystem, private val exec: Exec, private val pluginsRoot: GuestPath,
    private val privateProcess: PluginProcess? = null,
    private val processPath: (GuestPath) -> String = { it.value },
    private val outputPath: (String) -> GuestPath = { GuestPath(it) },
    private val sessionCwd: GuestPath? = null,
    private val storedHeaders: suspend (String) -> Map<String, String> = { emptyMap() },
) : PluginSourceHost {
    private val home = GuestPath(pluginsRoot.value.substringBeforeLast("/.claude/plugins"))
    private val helperCache = mutableMapOf<String, Pair<Long, Map<String, String>>>()
    private suspend fun privateRun(script: String, cwd: GuestPath, timeoutMs: Long, env: Map<String, String> = emptyMap(), scrub: Boolean = false) =
        requireNotNull(privateProcess) { "宿主未提供无日志插件进程端口" }.run(script, cwd, timeoutMs, env, scrub)
    private fun owned(path: GuestPath): Path {
        val root = fs.hostPath(pluginsRoot).toAbsolutePath().normalize()
        val result = fs.hostPath(path).toAbsolutePath().normalize()
        require(result.startsWith(root) && result != root) { "插件副作用超出安装目录" }
        // Android 的 /data/user/0 是宿主别名；两端都用真实根比较，插件树内部的链接仍逐级拒绝。
        val physicalRoot = root.toRealPath()
        var cursor: Path? = result
        while (cursor != null && cursor != root.parent) {
            require(!Files.isSymbolicLink(cursor)) { "安装目录包含符号链接" }
            if (Files.exists(cursor, NOFOLLOW_LINKS)) require(cursor.toRealPath().startsWith(physicalRoot)) { "安装目录链接逃逸" }
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
    private suspend fun gitEnvironment(source: PluginFetchSource): Map<String, String> {
        // Windows 的凭据管理器通常注册在系统配置；受信任源应复用系统和用户的既有登录。
        val base = mapOf("GIT_TERMINAL_PROMPT" to "0", "GCM_INTERACTIVE" to "never")
        val location = if (source.type == "github") "https://github.com/${source.location}.git" else source.location
        if (!location.startsWith("https://") && !location.startsWith("http://")) return base
        val credentialHeaders = headers(source.copy(location = location)).toMutableMap()
        if (sourceUri(location).host == "github.com" && credentialHeaders.keys.none { it.equals("Authorization", true) }) {
            System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { token ->
                credentialHeaders["Authorization"] = "Basic " + java.util.Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
            }
        }
        // git 的 argv 和日志里不携带认证值，extraHeader 经私有端口的环境传入。
        return base + buildMap {
            put("GIT_CONFIG_COUNT", credentialHeaders.size.toString())
            credentialHeaders.entries.forEachIndexed { i, (key, value) ->
                put("GIT_CONFIG_KEY_$i", "http.$location.extraHeader"); put("GIT_CONFIG_VALUE_$i", "$key: $value")
            }
        }
    }
    override suspend fun tags(source: PluginFetchSource, pluginName: String): List<PluginSourceTag> {
        if (privateProcess == null || source.type !in setOf("git", "github", "directory")) return emptyList()
        require(pluginName.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")))
        val location = when(source.type) { "github" -> "https://github.com/${source.location}.git"; "directory" -> processPath(GuestPath(source.location)); else -> source.location }
        if (source.type == "directory" && !Files.exists(fs.hostPath(GuestPath(source.location)).resolve(".git"))) return emptyList()
        val output = privateRun("git -c protocol.ext.allow=never ls-remote --tags --refs -- ${processQuote(location)} ${processQuote("refs/tags/$pluginName--v*")}", home, 120_000, gitEnvironment(source))
        return output.lineSequence().mapNotNull { line ->
            val parts = line.split(Regex("\\s+")); if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-f]{40}"))) null
            else parts[1].removePrefix("refs/tags/").takeIf { it.startsWith("$pluginName--v") }?.let { PluginSourceTag(it, it.substringAfter("--v"), parts[0]) }
        }.toList()
    }
    override suspend fun fetch(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits) {
        when (source.type) {
            "directory" -> copy(GuestPath(source.location), destination, limits)
            "file" -> {
                val file = fs.hostPath(GuestPath(source.location))
                require(Files.size(file) <= limits.downloadBytes && !Files.isSymbolicLink(file)) { "清单过大或是链接" }
                fs.writeAtomic(GuestPath(destination.value + "/.claude-plugin/marketplace.json"), Files.readAllBytes(file))
            }
            "url" -> fs.writeAtomic(GuestPath(destination.value + "/.claude-plugin/marketplace.json"), download(source.location, limits.downloadBytes, headers(source)))
            "archive" -> {
                val bytes = if (source.location.startsWith("/")) withContext(Dispatchers.IO) {
                    val file = fs.hostPath(GuestPath(source.location)); require(Files.size(file) <= limits.downloadBytes) { "压缩包超过下载上限" }; Files.readAllBytes(file)
                } else download(source.location, limits.downloadBytes, headers(source))
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
                val flags = source.ref?.let { " --branch ${ShellQuote.posix(it)}" }.orEmpty()
                try {
                    val env = gitEnvironment(source)
                    if (privateProcess != null) {
                        privateRun("git -c core.hooksPath=/dev/null -c protocol.ext.allow=never clone --no-recurse-submodules" +
                            source.ref?.let { " --branch ${processQuote(it)}" }.orEmpty() + " -- ${processQuote(location)} ${processQuote(processPath(raw))}" +
                            source.sha?.let { " && git -C ${processQuote(processPath(raw))} -c core.hooksPath=/dev/null checkout --detach ${processQuote(it)}" }.orEmpty(),
                            home, 120_000, env)
                    } else {
                        val result = exec.run(ShellCommand("git -c core.hooksPath=/dev/null -c protocol.ext.allow=never clone --no-recurse-submodules$flags -- ${ShellQuote.posix(location)} ${ShellQuote.posix(raw.value)}" +
                            source.sha?.let { " && git -C ${ShellQuote.posix(raw.value)} -c core.hooksPath=/dev/null checkout --detach ${ShellQuote.posix(it)}" }.orEmpty(), pluginsRoot, env), ExecOptions(120_000))
                        require(result is ExecResult.Exited && result.exitCode == 0) { "git 拉取失败或超时" }
                    }
                    val path = source.path?.let { safeRelative(it); "/$it" }.orEmpty()
                    copy(GuestPath(raw.value + path), destination, limits)
                } finally { withContext(NonCancellable) { remove(raw) } }
            }
            "command" -> {
                PrivatePluginProcess.validateCommand(source.location)
                require(source.commandTimeout in 1..600 && source.mode in setOf("copy", "link")) { "无效 command timeout/mode" }
                require(source.mode == "copy") { "此宿主不支持 command link 模式；Windows 原文要求 copy" }
                val printed = privateRun(source.location, home, source.commandTimeout * 1000L, scrub = true).trimEnd('\r', '\n')
                require(printed.isNotBlank() && '\n' !in printed && '\r' !in printed && !printed.startsWith("\\\\") && !printed.startsWith("//")) { "command 必须只输出一行绝对插件目录，不能是 UNC" }
                val plugin = outputPath(printed)
                val physical = fs.hostPath(plugin).toRealPath()
                sessionCwd?.let { require(!fs.hostPath(it).toRealPath().startsWith(physical)) { "command 输出不能是会话目录或其父目录" } }
                require(Files.isDirectory(physical) && listOf(".claude-plugin", "skills", "commands", "agents", "hooks").any { Files.isDirectory(physical.resolve(it)) }) { "command 输出目录没有插件内容" }
                copy(plugin, destination, limits.copy(expandedBytes = minOf(limits.expandedBytes, 256L * 1024 * 1024), files = minOf(limits.files, 20_000)))
            }
            "npm" -> fetchNpm(source, destination, limits)
            else -> error("暂不支持源类型 ${source.type}；支持 git / github / url / archive / directory / file")
        }
    }
    private suspend fun headers(source: PluginFetchSource): Map<String, String> {
        val inherited = source.inheritedAuthentication?.let { headers(it) }.orEmpty()
        val dynamic = source.headersHelper?.let helper@ { command ->
            PrivatePluginProcess.validateCommand(command)
            val cacheKey = command + source.helperEnvironment.toString() + source.scrubCredentials
            helperCache[cacheKey]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }?.let { return@helper it.second }
            val text = privateRun(command, GuestPath(home.value + "/.claude"), 10_000, source.helperEnvironment, source.scrubCredentials)
            try {
                val obj = Json.parseToJsonElement(text).jsonObject
                require(obj.values.all { it is JsonPrimitive && it.isString })
                obj.mapValues { it.value.jsonPrimitive.content }.also { helperCache[cacheKey] = System.nanoTime() to it }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { error("headersHelper 必须只输出字符串值的 JSON 对象") }
        }.orEmpty()
        val entry = (source.headers + dynamic).filterKeys { source.type != "archive" || !it.equals("Host", true) && !it.equals("Cookie", true) && !it.startsWith("X-Forwarded-", true) && it.lowercase() !in setOf("connection", "content-length", "transfer-encoding") }
        val result = storedHeaders(source.location) + inherited + entry
        require(result.all { (key, value) -> key.matches(Regex("[A-Za-z0-9-]+")) && value.none { it == '\r' || it == '\n' || it == '\u0000' } }) { "认证头格式无效" }
        return result
    }
    private suspend fun download(raw: String, limit: Long, headers: Map<String, String> = emptyMap()): ByteArray = runInterruptible(Dispatchers.IO) {
        val uri = sourceUri(raw)
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")) { "下载须用 HTTPS（本机测试可用 HTTP）" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
        headers.forEach(connection::setRequestProperty)
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
    private fun sourceUri(value: String): URI = try { URI(value) } catch (_: Exception) { error("插件源 URL 格式无效") }
    private fun processQuote(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) { "无效进程参数" }
        if (!HostOs.isWindows) return ShellQuote.posix(value)
        require(value.none { it in "\"%!" }) { "进程参数包含 shell 元字符" }
        return "\"$value\""
    }
    private suspend fun fetchNpm(source: PluginFetchSource, destination: GuestPath, limits: PluginFetchLimits) {
        val defaultRegistry = privateRun("npm config get registry", home, 10_000).trim().trimEnd('/')
        val registry = (source.registry ?: defaultRegistry).trimEnd('/')
        fun validRegistry(value: String) = sourceUri(value).let { it.scheme == "https" || it.scheme == "http" && value.trimEnd('/') == defaultRegistry }
        require(validRegistry(registry)) { "npm registry 必须为 HTTPS 或用户默认 registry" }
        val packageName = source.location
        require(".." !in packageName) { "npm package 不能含 .." }
        val uri = if (packageName.startsWith("https://") || packageName.startsWith("http://")) sourceUri(packageName) else null
        if (uri != null) {
            require(uri.scheme == "https" || packageName.startsWith(defaultRegistry + "/")) { "npm tarball HTTP 仅限默认 registry" }
            require(uri.host !in setOf("github.com", "gist.github.com", "bitbucket.org", "git.sr.ht") && (uri.host != "gitlab.com" || uri.path.startsWith("/api/v4/"))) { "npm package 不能是 git 托管 tarball" }
        } else require(Regex("(?:@[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+(?:@[a-zA-Z0-9_.~^*+<>=| -]+)?").matches(packageName)) { "npm package 必须是 registry 包名，不能是路径、git 或 alias" }
        val spec = if (uri == null && packageName.substringAfter('/').indexOf('@') < 0) packageName + "@" + (source.version ?: "latest") else packageName
        val staging = GuestPath(destination.value + "-npm")
        Files.createDirectories(owned(staging))
        try {
            val output = privateRun("npm pack --ignore-scripts --json --registry ${processQuote(registry)} --pack-destination ${processQuote(processPath(staging))} -- ${processQuote(spec)}", home, 120_000)
            val filename = try { Json.parseToJsonElement(output).jsonArray.single().jsonObject.getValue("filename").jsonPrimitive.content }
                catch(e: CancellationException) { throw e } catch(_: Exception) { error("npm pack 返回格式无效") }
            require(filename.matches(Regex("[A-Za-z0-9_.-]+\\.tgz"))) { "npm tarball 名称无效" }
            val archive = owned(staging).resolve(filename)
            require(Files.size(archive) <= limits.downloadBytes) { "npm 下载超限" }
            extractTar(Files.readAllBytes(archive), owned(destination), limits)
        } finally { withContext(NonCancellable) { remove(staging) } }
    }
    // API 26 的 InputStream 没有 readNBytes；有界读取也让截断压缩包不能伪装完整。
    private fun readBounded(input: java.io.InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size); var offset = 0
        while (offset < size) { val n = input.read(buffer, offset, size-offset); if (n < 0) break; offset += n }
        return buffer.copyOf(offset)
    }
    private suspend fun extractTar(bytes: ByteArray, root: Path, limits: PluginFetchLimits) = withContext(Dispatchers.IO) {
        var total = 0L; var count = 0
        val seen = mutableSetOf<String>()
        GZIPInputStream(bytes.inputStream()).use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val header = readBounded(input, 512)
                if (header.isEmpty() || header.all { it == 0.toByte() }) break
                require(header.size == 512) { "npm tar 头不完整" }
                fun text(start: Int, length: Int) = header.copyOfRange(start, start + length).toString(Charsets.UTF_8).trimEnd('\u0000', ' ')
                val checksum = text(148, 8).trim().toLongOrNull(8) ?: error("npm tar 校验值无效")
                require(header.indices.sumOf { if (it in 148..155) 32L else (header[it].toInt() and 255).toLong() } == checksum) { "npm tar 校验失败" }
                val prefix = text(345, 155)
                val raw = (if (prefix.isEmpty()) "" else "$prefix/") + text(0, 100)
                require(raw.startsWith("package/")) { "npm tar 必须在 package 根下" }
                val name = raw.removePrefix("package/").trimEnd('/')
                if (name.isNotEmpty()) safeRelative(name)
                require(seen.add(raw) && ++count <= limits.files) { "npm tar 重复路径或文件数超限" }
                val size = text(124, 12).trim().toLongOrNull(8) ?: error("npm tar 大小无效")
                require(size >= 0 && size <= limits.expandedBytes && total + size <= limits.expandedBytes) { "npm tar 展开超限" }; total += size
                val target = root.resolve(name).normalize(); require(target.startsWith(root))
                when(header[156].toInt().toChar()) {
                    '5' -> { require(size == 0L); Files.createDirectories(target) }
                    '0', '\u0000' -> { require(name.isNotEmpty()); Files.createDirectories(target.parent); Files.newOutputStream(target, CREATE_NEW).use { out ->
                        var remaining = size; val buffer = ByteArray(8192)
                        while (remaining > 0) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); require(n > 0) { "npm tar 内容截断" }; out.write(buffer, 0, n); remaining -= n }
                    } }
                    else -> error("npm tar 链接或特殊条目被拒绝")
                }
                val padding = ((512 - size % 512) % 512).toInt(); require(readBounded(input, padding).size == padding) { "npm tar 截断" }
            }
        }
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
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
