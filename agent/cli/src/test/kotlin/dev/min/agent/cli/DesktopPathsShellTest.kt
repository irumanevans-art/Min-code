package dev.min.agent.cli

import dev.min.agent.spi.GuestPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class DesktopPathsShellTest {
    private val temp = Files.createTempDirectory("paths-shell")
    // DesktopPaths 的两种模式各自只能在对应的宿主上构造（盘符枚举与 guest 路径形态取决于本机）
    private val onWindows = System.getProperty("os.name").startsWith("Windows")

    private fun paths(windows: Boolean) = DesktopPaths(temp, temp.resolve("scratch"), windows)

    @Test fun `posix hosts pass shell paths through`() {
        assumeFalse(onWindows)
        assertEquals(GuestPath("/home/u/work"), paths(false).guestOfShell("/home/u/work"))
        assertEquals(GuestPath("/"), paths(false).guestOfShell("/"))
    }

    @Test fun `Git Bash drive paths map to the drive-style guest path and msys internals pass through unchanged (they can never be inside the project)`() {
        assumeTrue(onWindows)
        val p = paths(true)
        assertEquals(GuestPath("/d/kimi/中文 dir"), p.guestOfShell("/d/kimi/中文 dir"))
        // pwd -W 的 Windows 写法
        assertEquals(GuestPath("/d/kimi/中文 dir"), p.guestOfShell("D:/kimi/中文 dir"))
        assertEquals(GuestPath("/c/Users/x y"), p.guestOfShell("C:\\Users\\x y"))
        assertEquals(GuestPath("/c"), p.guestOfShell("C:/"))
        assertEquals(GuestPath("/c"), p.guestOfShell("c:"))
        assertEquals(GuestPath("//server/share"), p.guestOfShell("//server/share"))
        assertEquals("盘符统一小写", GuestPath("/c/Users/x"), p.guestOfShell("/C/Users/x"))
        assertEquals(GuestPath("/d"), p.guestOfShell("/d"))
        assertEquals(GuestPath("/d"), p.guestOfShell("/d/"))
        assertNull("不是绝对路径", p.guestOfShell("relative"))
        for (internal in listOf("/usr/bin", "/tmp", "/", "/home/me")) assertEquals(GuestPath(internal), p.guestOfShell(internal))
    }
}
