package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/**
 * rootfs 的 SHA-256 与清单不符。调用方**不得**据此静默换源重试：
 * 换源下载的包也要过同一份清单，校验失败说明内容不可信，再换源没有意义。
 */
class RootfsChecksumException(message: String) : IOException(message)

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    /**
     * @param manifestUrl 官方 SHA256SUMS 清单地址（信任锚）。非空时先取清单：
     * 动态解析当前 point release 的文件名（上游发新版后写死的旧名 404 由此免疫），
     * 下载完按清单校验 SHA-256。镜像源下载的包也用这份官方清单校验（内容逐字节相同）。
     */
    fun install(
        root: String,
        url: String,
        manifestUrl: String? = null,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(url)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            val plan = resolveDownload(url, manifestUrl)
            download(plan.url, plan.expectedSha256, archive, onProgress)
            extractTar(archive, stagingDir, format, onProgress)
            linuxDir.deleteRecursively()
            require(stagingDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            stagingDir.deleteRecursively()
        }
    }

    /**
     * 解析出实际要下的地址和期望的 SHA-256。
     *
     * - ubuntu-base 包：必须拿到清单（官方 [manifestUrl] 优先，同站 SHA256SUMS 兜底），
     *   拿不到就直接失败 —— 不静默退回无校验下载；文件名以清单为准（point release 漂移免疫）
     * - 用户手填的非 ubuntu-base 地址：清单里没有它的条目，保持原有行为（无哈希校验，
     *   靠 tar 能解开 + `bin/sh` 存在判定），但 Content-Length 对账仍然生效
     */
    private fun resolveDownload(url: String, manifestUrl: String?): DownloadPlan {
        val requestedName = url.stripQuery().substringAfterLast('/')
        val isUbuntuBase = UBUNTU_BASE_NAME_REGEX.matches(requestedName)
        val manifest = manifestCandidates(url, manifestUrl)
            .firstNotNullOfOrNull { fetchManifest(it) }
        if (manifest == null) {
            require(!isUbuntuBase) {
                "无法获取 rootfs 的 SHA256SUMS 校验清单（官方与镜像均不可达），拒绝未校验安装"
            }
            return DownloadPlan(url, expectedSha256 = null, resolvedFileName = requestedName)
        }
        val entry = selectRootfsEntry(requestedName, manifest)
        require(entry != null || !isUbuntuBase) {
            "SHA256SUMS 清单中找不到 $requestedName（也没有同架构的当前版本），拒绝未校验安装"
        }
        if (entry == null) return DownloadPlan(url, expectedSha256 = null, resolvedFileName = requestedName)
        val resolvedUrl = if (entry.substituted) url.stripQuery().replaceLastSegment(entry.fileName) else url
        return DownloadPlan(resolvedUrl, entry.sha256, entry.fileName)
    }

    /** 官方清单优先；同站 SHA256SUMS 兜底（官方被墙时镜像自己的清单仍能做完整性对账） */
    private fun manifestCandidates(url: String, manifestUrl: String?): List<String> {
        val sibling = url.stripQuery().replaceLastSegment(SUMS_FILE_NAME)
        return listOfNotNull(manifestUrl?.trim()?.takeIf { it.isNotEmpty() }, sibling).distinct()
    }

    private fun fetchManifest(url: String): Map<String, String>? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = MANIFEST_CONNECT_TIMEOUT_MS
        connection.readTimeout = MANIFEST_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) return@runCatching null
            val declared = connection.contentLengthLong
            if (declared > MANIFEST_MAX_BYTES) return@runCatching null
            val text = connection.inputStream.use { it.readUtf8Capped(MANIFEST_MAX_BYTES) } ?: return@runCatching null
            parseSha256Sums(text).takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun InputStream.readUtf8Capped(maxBytes: Int): String? {
        val buffer = ByteArray(maxBytes + 1)
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        if (offset > maxBytes) return null
        return buffer.copyOf(offset).toString(Charsets.UTF_8)
    }

    private fun String.stripQuery(): String = substringBefore('?').substringBefore('#')

    private fun String.replaceLastSegment(name: String): String {
        val index = lastIndexOf('/')
        return if (index < 0) name else substring(0, index + 1) + name
    }

    private data class DownloadPlan(
        val url: String,
        val expectedSha256: String?,
        val resolvedFileName: String,
    )

    private fun download(
        url: String,
        expectedSha256: String?,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Rootfs download failed: HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    val declared = connection.contentLengthLong
                    if (declared > 0 && bytesRead != declared) {
                        throw IOException("Rootfs 下载不完整：服务端声明 $declared 字节，实际收到 $bytesRead 字节")
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw RootfsChecksumException(
                        "rootfs 校验失败（SHA-256 不符）：\n" +
                            "期望（官方清单）: $expectedSha256\n" +
                            "实际（下载内容）: $actual\n" +
                            "下载源内容不可信（被篡改或镜像同步异常），请更换网络环境或稍后再试"
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                entries++
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            val rootFile = root.canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun normalizeTarPath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
        require(normalized.isNotBlank()) { "Rootfs entry path is blank" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        /** 清单只有几 KB，超时/体量都按小文件给：官方被墙时快速让位给同站清单 */
        private const val SUMS_FILE_NAME = "SHA256SUMS"
        private const val MANIFEST_CONNECT_TIMEOUT_MS = 10_000
        private const val MANIFEST_READ_TIMEOUT_MS = 15_000
        private const val MANIFEST_MAX_BYTES = 256 * 1024
    }
}

/**
 * 解析 SHA256SUMS（GNU 格式：每行 `"<64 hex> [*]<文件名>"`，`*` 表示 binary mode）。
 * 畸形行（缺文件名、哈希不是 64 位十六进制等）直接跳过；同名条目后写覆盖先写。
 * 纯函数，单测不碰网络。
 */
internal fun parseSha256Sums(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val splitAt = line.indexOf(' ')
        if (splitAt < 0) continue
        val hash = line.substring(0, splitAt).lowercase(Locale.US)
        val name = line.substring(splitAt + 1).trimStart(' ', '*').trim()
        if (hash.length != 64 || hash.any { it !in '0'..'9' && it !in 'a'..'f' }) continue
        if (name.isEmpty()) continue
        result[name] = hash
    }
    return result
}

/** 从清单里选中的条目；[substituted] = 请求的文件名已过时，用清单里的当前 point release 顶替 */
internal data class RootfsManifestEntry(
    val fileName: String,
    val sha256: String,
    val substituted: Boolean,
)

/** `ubuntu-base-24.04.3-base-arm64.tar.gz`：组 = 主版本、次版本、patch（可空）、架构 */
internal val UBUNTU_BASE_NAME_REGEX =
    Regex("""^ubuntu-base-(\d+)\.(\d+)(?:\.(\d+))?-base-([a-z0-9]+)\.tar\.gz$""")

/**
 * 按请求的文件名在清单里找要下的包：
 *
 * 1. 清单里有同名条目 → 直接用；
 * 2. 没有，但请求名是 ubuntu-base 包（如写死的 `24.04.3` 已随上游发版 404）→
 *    在同一 series（主.次版本一致）里挑**同架构**、patch 最大的条目顶替；
 * 3. 都不是（用户手填的自定义 rootfs）→ null，调用方按无清单处理。
 */
internal fun selectRootfsEntry(
    requestedFileName: String,
    sums: Map<String, String>,
): RootfsManifestEntry? {
    sums[requestedFileName]?.let {
        return RootfsManifestEntry(requestedFileName, it, substituted = false)
    }
    val requested = UBUNTU_BASE_NAME_REGEX.matchEntire(requestedFileName) ?: return null
    val series = "${requested.groupValues[1]}.${requested.groupValues[2]}"
    val arch = requested.groupValues[4]
    val best = sums.keys
        .mapNotNull { name ->
            val m = UBUNTU_BASE_NAME_REGEX.matchEntire(name) ?: return@mapNotNull null
            if ("${m.groupValues[1]}.${m.groupValues[2]}" != series) return@mapNotNull null
            if (m.groupValues[4] != arch) return@mapNotNull null
            name to (m.groupValues[3].toIntOrNull() ?: 0)
        }
        .maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        ?: return null
    return RootfsManifestEntry(best.first, sums.getValue(best.first), substituted = true)
}
