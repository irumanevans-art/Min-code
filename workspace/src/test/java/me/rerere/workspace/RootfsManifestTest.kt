package me.rerere.workspace

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * rootfs 签名清单（H1/H2）：SHA256SUMS 解析、point release 文件名选择、
 * 下载后 SHA-256 / Content-Length 校验的行为测试。
 */
class RootfsManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- parseSha256Sums ----------

    @Test
    fun `parse reads gnu format with star and space separators`() {
        val sums = parseSha256Sums(
            """
            ${"a".repeat(64)} *ubuntu-base-24.04.3-base-amd64.tar.gz
            ${"b".repeat(64)}  ubuntu-base-24.04.3-base-arm64.tar.gz

            """.trimIndent(),
        )
        assertEquals(2, sums.size)
        assertEquals("a".repeat(64), sums["ubuntu-base-24.04.3-base-amd64.tar.gz"])
        assertEquals("b".repeat(64), sums["ubuntu-base-24.04.3-base-arm64.tar.gz"])
    }

    @Test
    fun `parse skips malformed lines`() {
        val valid = "c".repeat(64)
        val sums = parseSha256Sums(
            """
            not-a-hash *too-short.tar.gz
            ${"g".repeat(64)} *non-hex.tar.gz
            nospaceline
            $valid
            $valid *valid.tar.gz
            """.trimIndent(),
        )
        // "not-a-hash" 长度不够、"g"*64 非十六进制、无空格行、空文件名行全部被跳过
        assertEquals(mapOf("valid.tar.gz" to valid), sums)
    }

    @Test
    fun `parse normalizes uppercase hashes`() {
        val sums = parseSha256Sums("${"A".repeat(64)} *file.tar.gz")
        assertEquals("a".repeat(64), sums["file.tar.gz"])
    }

    @Test
    fun `missing entry is not in the parsed map`() {
        val sums = parseSha256Sums("${"a".repeat(64)} *ubuntu-base-24.04.3-base-amd64.tar.gz")
        assertNull(sums["ubuntu-base-24.04.3-base-arm64.tar.gz"])
        assertNull(selectRootfsEntry("custom-rootfs.tar.gz", sums))
    }

    // ---------- selectRootfsEntry ----------

    @Test
    fun `exact file name match is used as-is`() {
        val hash = "d".repeat(64)
        val sums = mapOf("ubuntu-base-24.04.3-base-arm64.tar.gz" to hash)
        val entry = selectRootfsEntry("ubuntu-base-24.04.3-base-arm64.tar.gz", sums)
        assertEquals(RootfsManifestEntry("ubuntu-base-24.04.3-base-arm64.tar.gz", hash, substituted = false), entry)
    }

    @Test
    fun `stale point release is substituted with the current one of the same arch`() {
        val old = "ubuntu-base-24.04.3-base-arm64.tar.gz"
        val sums = mapOf(
            "ubuntu-base-24.04.4-base-arm64.tar.gz" to "1".repeat(64),
            "ubuntu-base-24.04.4-base-amd64.tar.gz" to "2".repeat(64),
        )
        val entry = selectRootfsEntry(old, sums)
        assertEquals(
            RootfsManifestEntry("ubuntu-base-24.04.4-base-arm64.tar.gz", "1".repeat(64), substituted = true),
            entry,
        )
    }

    @Test
    fun `substitution picks the highest patch version numerically`() {
        // 字符串排序会把 24.04.10 排在 24.04.4 前面，必须按数值比
        val sums = mapOf(
            "ubuntu-base-24.04.4-base-arm64.tar.gz" to "1".repeat(64),
            "ubuntu-base-24.04.10-base-arm64.tar.gz" to "2".repeat(64),
        )
        val entry = selectRootfsEntry("ubuntu-base-24.04.3-base-arm64.tar.gz", sums)
        assertEquals("ubuntu-base-24.04.10-base-arm64.tar.gz", entry?.fileName)
    }

    @Test
    fun `substitution refuses a different arch or series`() {
        val sums = mapOf(
            "ubuntu-base-24.04.4-base-arm64.tar.gz" to "1".repeat(64),
            "ubuntu-base-22.04.5-base-amd64.tar.gz" to "2".repeat(64),
        )
        // 请求 amd64：清单里 24.04 只有 arm64 → null；22.04 的 amd64 不算（series 不同）
        assertNull(selectRootfsEntry("ubuntu-base-24.04.3-base-amd64.tar.gz", sums))
    }

    @Test
    fun `non ubuntu file names are not resolved`() {
        val sums = mapOf("ubuntu-base-24.04.4-base-arm64.tar.gz" to "1".repeat(64))
        assertNull(selectRootfsEntry("alpine-minirootfs.tar.gz", sums))
    }

    // ---------- 安装行为（本地 HTTP 集成） ----------

    @Test
    fun `install substitutes stale point release and verifies sha256`() {
        val archive = rootfsTarGz()
        val hash = sha256Hex(archive)
        val server = httpServer {
            it.createContext("/SHA256SUMS") { exchange ->
                exchange.respondText("$hash *ubuntu-base-24.04.4-base-arm64.tar.gz\n")
            }
            it.createContext("/ubuntu-base-24.04.4-base-arm64.tar.gz") { exchange ->
                exchange.respondBytes(archive)
            }
            // 24.04.3 不存在：上游已发新版，写死的旧名 404 —— 必须靠清单顶替装上
        }
        try {
            val manager = WorkspaceManager(tmp.newFolder())
            val root = "test-workspace"
            RootfsInstaller(manager).install(
                root,
                "http://127.0.0.1:${server.address.port}/ubuntu-base-24.04.3-base-arm64.tar.gz",
            )
            assertEquals("echo hello\n", File(manager.linuxDir(root), "bin/hello").readText())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `install fails on sha256 mismatch and does not keep the rootfs`() {
        val archive = rootfsTarGz()
        val server = httpServer {
            it.createContext("/SHA256SUMS") { exchange ->
                exchange.respondText("${"0".repeat(64)} *ubuntu-base-24.04.3-base-arm64.tar.gz\n")
            }
            it.createContext("/ubuntu-base-24.04.3-base-arm64.tar.gz") { exchange ->
                exchange.respondBytes(archive)
            }
        }
        try {
            val manager = WorkspaceManager(tmp.newFolder())
            val root = "test-workspace"
            val installer = RootfsInstaller(manager)
            assertThrows(RootfsChecksumException::class.java) {
                installer.install(
                    root,
                    "http://127.0.0.1:${server.address.port}/ubuntu-base-24.04.3-base-arm64.tar.gz",
                )
            }
            assertFalse(manager.hasRootfs(root))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `install fails when the download is truncated`() {
        val archive = rootfsTarGz()
        val server = httpServer {
            it.createContext("/rootfs.tar.gz") { exchange ->
                // 声明的长度比实际 body 大：Content-Length 对账必须判失败
                exchange.sendResponseHeaders(200, archive.size + 100L)
                exchange.responseBody.use { out -> out.write(archive) }
            }
        }
        try {
            val manager = WorkspaceManager(tmp.newFolder())
            val installer = RootfsInstaller(manager)
            assertThrows(IOException::class.java) {
                installer.install("test-workspace", "http://127.0.0.1:${server.address.port}/rootfs.tar.gz")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `install refuses an unverifiable ubuntu base download`() {
        val archive = rootfsTarGz()
        val server = httpServer {
            // 只发包，不发清单：ubuntu-base 包拿不到 SHA256SUMS 必须失败，不静默降级
            it.createContext("/ubuntu-base-24.04.3-base-arm64.tar.gz") { exchange ->
                exchange.respondBytes(archive)
            }
        }
        try {
            val manager = WorkspaceManager(tmp.newFolder())
            val installer = RootfsInstaller(manager)
            assertThrows(IllegalArgumentException::class.java) {
                installer.install(
                    "test-workspace",
                    "http://127.0.0.1:${server.address.port}/ubuntu-base-24.04.3-base-arm64.tar.gz",
                )
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `mirror download is verified against the official manifest`() {
        val archive = rootfsTarGz()
        val hash = sha256Hex(archive)
        val official = httpServer {
            it.createContext("/SHA256SUMS") { exchange ->
                exchange.respondText("$hash *ubuntu-base-24.04.3-base-arm64.tar.gz\n")
            }
        }
        val mirror = httpServer {
            it.createContext("/ubuntu-base-24.04.3-base-arm64.tar.gz") { exchange ->
                exchange.respondBytes(archive)
            }
        }
        try {
            val manager = WorkspaceManager(tmp.newFolder())
            val root = "test-workspace"
            RootfsInstaller(manager).install(
                root,
                "http://127.0.0.1:${mirror.address.port}/ubuntu-base-24.04.3-base-arm64.tar.gz",
                manifestUrl = "http://127.0.0.1:${official.address.port}/SHA256SUMS",
            )
            assertEquals("echo hello\n", File(manager.linuxDir(root), "bin/hello").readText())
        } finally {
            official.stop(0)
            mirror.stop(0)
        }
    }

    // ---------- helpers ----------

    private fun httpServer(configure: (HttpServer) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        configure(server)
        server.start()
        return server
    }

    private fun com.sun.net.httpserver.HttpExchange.respondBytes(body: ByteArray) {
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun com.sun.net.httpserver.HttpExchange.respondText(text: String) = respondBytes(text.toByteArray())

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rootfsTarGz(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        GZIPOutputStream(output).use { out ->
            out.writeTarEntry("bin/", '5', ByteArray(0))
            out.writeTarEntry("bin/sh", '0', "#!/bin/sh\n".toByteArray())
            out.writeTarEntry("bin/hello", '0', "echo hello\n".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }
        return output.toByteArray()
    }

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }
}
