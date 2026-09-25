package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestBrowserBridgeTest {
    private fun rootfs(): File = Files.createTempDirectory("rootfs").toFile().apply { deleteOnExit() }

    private fun File.bin(name: String) = File(this, "usr/local/bin/$name")

    @Test
    fun `installs the script and every shim with the same content`() {
        val linux = rootfs()
        GuestBrowserBridge.install(linux)

        (listOf("min-open") + GuestBrowserBridge.SHIM_NAMES).forEach { name ->
            val file = linux.bin(name)
            assertEquals(name, GuestBrowserBridge.SCRIPT, file.readText())
        }
        assertTrue(GuestBrowserBridge.inboxDir(linux).isDirectory)
        assertEquals(File(linux, "root/.min/open"), GuestBrowserBridge.inboxDir(linux))
    }

    @Test
    fun `leaves an xdg-open the user put there alone`() {
        val linux = rootfs()
        val own = linux.bin("xdg-open").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexec firefox \"$@\"\n")
        }
        GuestBrowserBridge.install(linux)
        assertEquals("#!/bin/sh\nexec firefox \"$@\"\n", own.readText())
        // 别的名字照装
        assertEquals(GuestBrowserBridge.SCRIPT, linux.bin("min-open").readText())
    }

    @Test
    fun `replaces an older copy of our own script`() {
        val linux = rootfs()
        val stale = linux.bin("min-open").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n# ${GuestBrowserBridge.MARKER}: old\nexit 0\n")
        }
        GuestBrowserBridge.install(linux)
        assertEquals(GuestBrowserBridge.SCRIPT, stale.readText())
    }

    @Test
    fun `the script follows the drop protocol`() {
        val script = GuestBrowserBridge.SCRIPT
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue(GuestBrowserBridge.MARKER in script)
        // 写临时文件再 rename 到 .req：Min 只看 .req，读不到半截
        assertTrue("mv -f \"\$tmp\" \"\$dir/\$\$${GuestBrowserBridge.REQUEST_SUFFIX}\"" in script)
        assertTrue("dir=${GuestBrowserBridge.INBOX_GUEST}" in script)
        assertTrue(script.trimEnd().endsWith("exit 0"))
    }
}
