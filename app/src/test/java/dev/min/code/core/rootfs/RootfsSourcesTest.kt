package dev.min.code.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Test

class RootfsSourcesTest {

    private val official = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    private val mirror = "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

    @Test
    fun `sums url points at the official cdimage manifest`() {
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
            RootfsSources.sumsUrl(),
        )
    }

    @Test
    fun `blank uses official then mirror`() {
        assertEquals(listOf(official, mirror), resolveUrls(null, official, mirror))
        assertEquals(listOf(official, mirror), resolveUrls("  ", official, mirror))
    }

    @Test
    fun `the official prefill still falls back to the mirror`() {
        assertEquals(listOf(official, mirror), resolveUrls(official, official, mirror))
        assertEquals(listOf(official, mirror), resolveUrls(" $official ", official, mirror))
    }

    @Test
    fun `a custom url is used alone`() {
        val custom = "https://example.com/ubuntu.tar.gz"
        assertEquals(listOf(custom), resolveUrls(custom, official, mirror))
    }
}
