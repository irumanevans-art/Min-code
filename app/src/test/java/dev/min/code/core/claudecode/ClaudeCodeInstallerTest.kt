package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安装器里那几个纯函数。
 *
 * 值得单测的原因集中在版本比较上：CLI 是用 `@latest` 装的，「有没有新版本」是这个功能
 * 的全部意义。一旦比较写错，用户看到的永远是"已是最新"——而这个错误在真机上极难发现，
 * 因为只有跨到 `x.y.9 → x.y.10` 那一步才暴露。
 */
class ClaudeCodeInstallerTest {

    // --- 版本比较 ---

    @Test
    fun `two digit patch is newer than one digit`() {
        // 字符串比较会判反："2.1.9" > "2.1.10" 在字典序下成立
        assertTrue(ClaudeCodeInstaller.isNewerVersion("2.1.9", "2.1.10"))
        assertFalse(ClaudeCodeInstaller.isNewerVersion("2.1.10", "2.1.9"))
    }

    @Test
    fun `same version is not newer`() {
        assertFalse(ClaudeCodeInstaller.isNewerVersion("2.1.258", "2.1.258"))
    }

    @Test
    fun `major and minor take precedence`() {
        assertTrue(ClaudeCodeInstaller.isNewerVersion("2.1.999", "3.0.0"))
        assertTrue(ClaudeCodeInstaller.isNewerVersion("2.1.999", "2.2.0"))
        assertFalse(ClaudeCodeInstaller.isNewerVersion("3.0.0", "2.9.9"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertFalse(ClaudeCodeInstaller.isNewerVersion("2.1.0", "2.1"))
        assertTrue(ClaudeCodeInstaller.isNewerVersion("2.1", "2.1.1"))
    }

    @Test
    fun `cli version suffix is ignored`() {
        // `claude --version` 输出的是 "2.1.258 (Claude Code)"。虽然 UI 比较时用的是
        // package.json 里的纯 semver，但解析必须对带后缀的输入也稳
        assertEquals(0, ClaudeCodeInstaller.compareVersions("2.1.258 (Claude Code)", "2.1.258"))
        assertEquals(0, ClaudeCodeInstaller.compareVersions("2.1.0-beta.3", "2.1.0"))
    }

    @Test
    fun `unknown versions degrade safely`() {
        // 查不到最新版时不要谎报有更新
        assertFalse(ClaudeCodeInstaller.isNewerVersion("2.1.258", null))
        assertFalse(ClaudeCodeInstaller.isNewerVersion("2.1.258", ""))
        // 本地读不到版本号时,有远端版本就当作可更新
        assertTrue(ClaudeCodeInstaller.isNewerVersion(null, "2.1.258"))
    }

    // --- os-release ---

    @Test
    fun `pretty name is unquoted`() {
        val content = """
            NAME="Ubuntu"
            VERSION_ID="24.04"
            PRETTY_NAME="Ubuntu 24.04.3 LTS"
            ID=ubuntu
        """.trimIndent()
        assertEquals("Ubuntu 24.04.3 LTS", ClaudeCodeInstaller.parseOsPrettyName(content))
    }

    @Test
    fun `pretty name without quotes`() {
        assertEquals("Debian GNU/Linux 12", ClaudeCodeInstaller.parseOsPrettyName("PRETTY_NAME=Debian GNU/Linux 12"))
    }

    @Test
    fun `missing pretty name is null`() {
        assertNull(ClaudeCodeInstaller.parseOsPrettyName("NAME=\"Ubuntu\"\nID=ubuntu"))
        assertNull(ClaudeCodeInstaller.parseOsPrettyName(""))
        // 空值不该被当成发行版名字显示出来
        assertNull(ClaudeCodeInstaller.parseOsPrettyName("PRETTY_NAME=\"\""))
    }

    // --- npm registry 元数据 ---

    @Test
    fun `version field is extracted`() {
        val json = """{"name":"@anthropic-ai/claude-code","version":"2.1.258","dist":{"tarball":"x"}}"""
        assertEquals("2.1.258", ClaudeCodeInstaller.parseVersionField(json))
    }

    @Test
    fun `malformed metadata is null`() {
        assertNull(ClaudeCodeInstaller.parseVersionField("not json"))
        assertNull(ClaudeCodeInstaller.parseVersionField("""{"name":"x"}"""))
        assertNull(ClaudeCodeInstaller.parseVersionField("""{"version":""}"""))
    }

    // --- apt 输出摘要 ---

    @Test
    fun `apt summary picks the count line`() {
        val stdout = """
            Reading package lists...
            Building dependency tree...
            The following packages will be upgraded:
              curl libcurl4
            2 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.
            Setting up curl ...
        """.trimIndent()
        assertEquals(
            "2 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.",
            ClaudeCodeInstaller.aptSummary(stdout),
        )
    }

    @Test
    fun `apt summary falls back to tail`() {
        assertTrue(ClaudeCodeInstaller.aptSummary("a\nb\nc\nd").contains("d"))
        assertEquals("已完成", ClaudeCodeInstaller.aptSummary("   \n\n"))
    }

    // --- 下载地址 ---

    @Test
    fun `node tarball urls put official first`() {
        val urls = ClaudeCodeInstaller.nodeTarballUrls("linux-arm64")
        assertTrue(urls.first().startsWith("https://nodejs.org/"))
        assertEquals(2, urls.size)
    }

    // --- 平台原生二进制包 ---

    @Test
    fun `native tarball url follows npm layout`() {
        // registry 的固定格式：<registry>/<scope>/<name>/-/<name>-<version>.tgz，
        // 名字部分不带 scope。拼错一个字符就是 404，而且两个源都会 404
        val urls = ClaudeCodeInstaller.nativeTarballUrls(
            pkg = "@anthropic-ai/claude-code-linux-arm64",
            version = "2.1.258",
            mirrorFirst = false,
        )
        assertEquals(
            "https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64/-/claude-code-linux-arm64-2.1.258.tgz",
            urls.first(),
        )
        assertEquals(2, urls.size)
        assertTrue(urls.last().startsWith("https://registry.npmmirror.com/"))
    }

    @Test
    fun `mirror first flips the order but keeps both`() {
        val urls = ClaudeCodeInstaller.nativeTarballUrls("@anthropic-ai/claude-code-linux-arm64", "2.1.258", mirrorFirst = true)
        assertTrue(urls.first().startsWith("https://registry.npmmirror.com/"))
        assertTrue(urls.last().startsWith("https://registry.npmjs.org/"))
    }

    @Test
    fun `declared native version comes from optionalDependencies`() {
        val packageJson = """
            {
              "name": "@anthropic-ai/claude-code",
              "version": "2.1.258",
              "optionalDependencies": {
                "@anthropic-ai/claude-code-linux-x64": "2.1.258",
                "@anthropic-ai/claude-code-linux-arm64": "2.1.258"
              }
            }
        """.trimIndent()
        assertEquals(
            "2.1.258",
            ClaudeCodeInstaller.parseDeclaredNativeVersion(packageJson, "@anthropic-ai/claude-code-linux-arm64"),
        )
        // 没声明这个平台（未来布局变了 / 老版本）→ null，调用方据此跳过原生二进制这一步
        assertNull(ClaudeCodeInstaller.parseDeclaredNativeVersion(packageJson, "@anthropic-ai/claude-code-linux-arm64-musl"))
        assertNull(ClaudeCodeInstaller.parseDeclaredNativeVersion("""{"version":"2.0.0"}""", "@anthropic-ai/claude-code-linux-arm64"))
        assertNull(ClaudeCodeInstaller.parseDeclaredNativeVersion("garbage", "@anthropic-ai/claude-code-linux-arm64"))
    }

    @Test
    fun `sri integrity is decoded to hex sha512`() {
        // 真实的 2.1.258 linux-arm64 元数据里的值；base64 → hex 是 MessageDigest 比对要的形态
        val json = """
            {"dist":{"shasum":"066528b078e2a1525f0f3b02d486b84c4888396b",
              "integrity":"sha512-cbzsJZ4gMDp30eOlhA6qM+s0plv0TTKLhWX/y4CwNRspSQI8wPO5T1UafhimU4fTeznpPxH38m3k3vYkA9ypHw=="}}
        """.trimIndent()
        val digest = ClaudeCodeInstaller.parseIntegrity(json)!!
        assertEquals("SHA-512", digest.algorithm)
        assertEquals(128, digest.hex.length)
        assertTrue(digest.hex.startsWith("71bcec259e20303a77d1e3a5840eaa33"))
    }

    @Test
    fun `integrity falls back to sha1 shasum and rejects garbage`() {
        val onlyShasum = """{"dist":{"shasum":"066528b078e2a1525f0f3b02d486b84c4888396b"}}"""
        val digest = ClaudeCodeInstaller.parseIntegrity(onlyShasum)!!
        assertEquals("SHA-1", digest.algorithm)
        assertEquals("066528b078e2a1525f0f3b02d486b84c4888396b", digest.hex)

        // 不认识的算法不能被当成校验和用掉——那等于没校验
        assertNull(ClaudeCodeInstaller.parseIntegrity("""{"dist":{"integrity":"sha256-abc"}}"""))
        assertNull(ClaudeCodeInstaller.parseIntegrity("""{"name":"x"}"""))
        assertNull(ClaudeCodeInstaller.parseIntegrity("not json"))
    }

    @Test
    fun `native binary threshold sits between stub and real binary`() {
        // postinstall 之前 bin/claude.exe 是不到 4KB 的 stub，之后是 200MB+ 的二进制。
        // 阈值必须落在两者之间，否则要么把 stub 当成装好了（ENOENT），要么永远判成没装
        assertTrue(ClaudeCodeInstaller.NATIVE_BIN_MIN_BYTES > 4096)
        assertTrue(ClaudeCodeInstaller.NATIVE_BIN_MIN_BYTES < 100L * 1024 * 1024)
    }

    /**
     * 原生二进制是拷到 bin/claude.exe 再删掉平台包目录。
     * wrapper 换了声明版本时，旧拷贝还在也必须重下。
     */
    @Test
    fun `update refreshes native when the declared version changed`() {
        assertTrue(
            ClaudeCodeInstaller.shouldRefreshNativeBinary(
                executablePresent = true,
                declaredVersionBefore = "2.1.258",
                declaredVersionAfter = "2.1.267",
            ),
        )
    }

    @Test
    fun `update skips native when the copy already matches the wrapper`() {
        assertFalse(
            ClaudeCodeInstaller.shouldRefreshNativeBinary(
                executablePresent = true,
                declaredVersionBefore = "2.1.267",
                declaredVersionAfter = "2.1.267",
            ),
        )
    }

    @Test
    fun `update fetches native when the executable is missing but the wrapper declares it`() {
        assertTrue(
            ClaudeCodeInstaller.shouldRefreshNativeBinary(
                executablePresent = false,
                declaredVersionBefore = "2.1.258",
                declaredVersionAfter = "2.1.267",
            ),
        )
        // stub / 遗留 cli.js：文件不够大，但 wrapper 声明了平台包
        assertTrue(
            ClaudeCodeInstaller.shouldRefreshNativeBinary(
                executablePresent = false,
                declaredVersionBefore = null,
                declaredVersionAfter = "2.1.267",
            ),
        )
    }

    @Test
    fun `update leaves a JS-only layout alone`() {
        // 老布局没有 optionalDependencies 平台包，不应去下根本不存在的原生包
        assertFalse(
            ClaudeCodeInstaller.shouldRefreshNativeBinary(
                executablePresent = false,
                declaredVersionBefore = null,
                declaredVersionAfter = null,
            ),
        )
    }

    @Test
    fun `a file under the threshold is not a native executable`() {
        val linuxDir = kotlin.io.path.createTempDirectory("cli-linux").toFile()
        val native = File(
            linuxDir,
            "opt/node/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
        )
        native.parentFile!!.mkdirs()
        native.writeBytes(ByteArray(4096))
        assertFalse(ClaudeCodeInstaller.nativeExecutablePresent(linuxDir))
        native.writeBytes(ByteArray((ClaudeCodeInstaller.NATIVE_BIN_MIN_BYTES + 1).toInt()))
        assertTrue(ClaudeCodeInstaller.nativeExecutablePresent(linuxDir))
    }
}
