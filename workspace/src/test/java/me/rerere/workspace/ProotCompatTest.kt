package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 假 /proc 文件的形状。真机上这些文件顶替的是 App 读不到的宿主条目，
 * 格式错了 `nproc` / `uptime` 会直接报错或算出 0 核。
 */
class ProotCompatTest {
    private fun tempDir(): File = Files.createTempDirectory("proot-compat").toFile().apply { deleteOnExit() }

    @Test
    fun `shm bind points at a directory under tmp`() {
        val tmp = tempDir()
        val (host, guest) = ProotCompat.shmBind(tmp)!!
        assertEquals("/dev/shm", guest)
        assertTrue(host.isDirectory)
        assertEquals(tmp, host.parentFile)
    }

    @Test
    fun `fake proc files are generated for every unreadable entry`() {
        val tmp = tempDir()
        val binds = ProotCompat.procBinds(tmp)
        // 宿主机（跑单测的机器）读得到的条目不会出现在列表里，读不到的必须每个都有非空假文件
        binds.forEach { (host, guest) ->
            assertTrue("$guest 必须落在 /proc 下", guest.startsWith("/proc/"))
            assertTrue("$guest 的假文件必须非空", host.isFile && host.length() > 0)
        }
        val stat = binds.firstOrNull { it.second == "/proc/stat" }?.first
        if (stat != null) {
            val lines = stat.readLines()
            val cores = Runtime.getRuntime().availableProcessors()
            assertTrue(lines.first().startsWith("cpu  "))
            // nproc 数的是 cpuN 行，必须和真实核数一致
            assertEquals(cores, lines.count { Regex("^cpu[0-9]+ ").containsMatchIn(it) })
            assertTrue(lines.any { it.startsWith("btime ") })
        }
    }

    @Test
    fun `extra binds are shm plus proc`() {
        val tmp = tempDir()
        val binds = ProotCompat.extraBinds(tmp)
        assertEquals("/dev/shm", binds.first().second)
        assertTrue(binds.drop(1).all { it.second.startsWith("/proc/") })
    }
}
