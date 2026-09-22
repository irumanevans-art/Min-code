package dev.min.code.core.update

import dev.min.code.core.claudecode.ClaudeCodeInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun normalizeTag_strips_v_prefix_and_finds_semver() {
        assertEquals("2.1.13", AppUpdateChecker.normalizeTag("v2.1.13"))
        assertEquals("2.1.13", AppUpdateChecker.normalizeTag("2.1.13"))
        assertEquals("2.1.13", AppUpdateChecker.normalizeTag("Min-code 2.1.13"))
    }

    @Test
    fun pickApkAsset_prefers_matching_abi_then_universal() {
        val assets = listOf(
            AppUpdateChecker.ApkAsset("Min-code-2.1.13-debug.apk", "https://x/d.apk", 1),
            AppUpdateChecker.ApkAsset("Min-code-2.1.13-x86_64.apk", "https://x/x.apk", 2),
            AppUpdateChecker.ApkAsset("Min-code-2.1.13-arm64-v8a.apk", "https://x/a.apk", 3),
            AppUpdateChecker.ApkAsset("Min-code-2.1.13-universal.apk", "https://x/u.apk", 4),
        )
        assertEquals(
            "Min-code-2.1.13-arm64-v8a.apk",
            AppUpdateChecker.pickApkAsset(assets, "2.1.13", "arm64-v8a")!!.name,
        )
        assertEquals(
            "Min-code-2.1.13-x86_64.apk",
            AppUpdateChecker.pickApkAsset(assets, "2.1.13", "x86_64")!!.name,
        )
        assertEquals(
            "Min-code-2.1.13-universal.apk",
            AppUpdateChecker.pickApkAsset(
                assets.filterNot { "arm64" in it.name || "x86_64" in it.name },
                "2.1.13",
                "armeabi-v7a",
            )!!.name,
        )
    }

    @Test
    fun pickApkAsset_skips_debug_packages() {
        val onlyDebug = listOf(
            AppUpdateChecker.ApkAsset("app-debug.apk", "https://x/d.apk", 1),
        )
        assertNull(AppUpdateChecker.pickApkAsset(onlyDebug, "2.1.13", "arm64-v8a"))
    }

    @Test
    fun parseRelease_reads_github_payload() {
        val body = """
            {
              "tag_name": "v2.1.13",
              "name": "2.1.13",
              "body": "notes",
              "html_url": "https://github.com/irumanevans-art/Min-code/releases/tag/v2.1.13",
              "assets": [
                {
                  "name": "Min-code-2.1.13-arm64-v8a.apk",
                  "browser_download_url": "https://example.com/a.apk",
                  "size": 12
                },
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://example.com/n.txt",
                  "size": 3
                }
              ]
            }
        """.trimIndent()
        val release = AppUpdateChecker.parseRelease(body, "arm64-v8a")!!
        assertEquals("2.1.13", release.version)
        assertEquals("notes", release.body)
        assertEquals("Min-code-2.1.13-arm64-v8a.apk", release.apk!!.name)
        assertEquals(listOf("Min-code-2.1.13-arm64-v8a.apk"), release.apkNames)
    }

    @Test
    fun compare_against_current_uses_installer_semver() {
        assertTrue(
            ClaudeCodeInstaller.isNewerVersion("2.1.9", "2.1.10"),
        )
        assertFalse(
            ClaudeCodeInstaller.isNewerVersion("2.1.13", "2.1.13"),
        )
    }
}
