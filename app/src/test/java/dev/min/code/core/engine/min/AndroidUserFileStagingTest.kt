package dev.min.code.core.engine.min

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendUserFile 在手机上的落盘：每个文件一条通知，通知点开时文件必须还在。
 * 一次发多个文件（或连着发两次）时，后一个不能把前一个通知指着的文件删掉。
 */
class AndroidUserFileStagingTest {
    private val root: File = Files.createTempDirectory("user_files").toFile()

    @Test
    fun `a second file does not delete the first one that an earlier notification still points to`() {
        val first = stageUserFile(root, slot = 0, name = "report.md", bytes = byteArrayOf(1))
        val second = stageUserFile(root, slot = 1, name = "chart.png", bytes = byteArrayOf(2))
        assertTrue("第一条通知指着的文件还得在", first.exists())
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test
    fun `the same name in two slots stays two files`() {
        val a = stageUserFile(root, slot = 0, name = "out.txt", bytes = byteArrayOf(1))
        val b = stageUserFile(root, slot = 1, name = "out.txt", bytes = byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), a.readBytes())
        assertArrayEquals(byteArrayOf(2), b.readBytes())
    }

    @Test
    fun `reusing a slot replaces only what that slot held, because its notification is replaced too`() {
        val old = stageUserFile(root, slot = 3, name = "old.txt", bytes = byteArrayOf(1))
        val other = stageUserFile(root, slot = 4, name = "keep.txt", bytes = byteArrayOf(2))
        stageUserFile(root, slot = 3, name = "new.txt", bytes = byteArrayOf(3))
        assertFalse("同一个槽位的旧文件随旧通知一起作废", old.exists())
        assertTrue(other.exists())
    }
}
