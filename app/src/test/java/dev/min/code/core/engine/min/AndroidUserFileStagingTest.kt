package dev.min.code.core.engine.min

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SendUserFile 在手机上的落盘：文件按「哪个会话的哪次调用、第几个」存，通知和对话流里的文件卡片指着同一份。
 * 一次发多个文件（或连着发两次）时，后一个不能把前一个指着的文件删掉；缓存占用有上限。
 */
class AndroidUserFileStagingTest {
    private val root: File = Files.createTempDirectory("user_files").toFile()

    @Test
    fun `a second file does not delete the first one that an earlier notification still points to`() {
        val first = stageUserFile(root, key = "k1", index = 0, name = "report.md", bytes = byteArrayOf(1))
        val second = stageUserFile(root, key = "k2", index = 0, name = "chart.png", bytes = byteArrayOf(2))
        assertTrue("第一条通知指着的文件还得在", first.exists())
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test
    fun `the same name twice in one batch stays two files`() {
        val a = stageUserFile(root, key = "k", index = 0, name = "out.txt", bytes = byteArrayOf(1))
        val b = stageUserFile(root, key = "k", index = 1, name = "out.txt", bytes = byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), a.readBytes())
        assertArrayEquals(byteArrayOf(2), b.readBytes())
    }

    @Test
    fun `only the most recent calls are kept, so the cache cannot grow without bound`() {
        val kept = 3
        val files = (1..5).map { i ->
            stageUserFile(root, key = "k$i", index = 0, name = "f$i.txt", bytes = byteArrayOf(i.toByte()), keep = kept)
                // 修改时间在有的文件系统上只精确到秒：显式拉开，免得排序靠运气
                .also { it.parentFile!!.parentFile!!.setLastModified(1_000_000L * i) }
        }
        stageUserFile(root, key = "k6", index = 0, name = "f6.txt", bytes = byteArrayOf(6), keep = kept)
        assertFalse("最旧的那批要被清掉", files[0].exists())
        assertFalse(files[1].exists())
        assertFalse(files[2].exists())
        assertTrue(files[3].exists())
        assertTrue(files[4].exists())
    }

    @Test
    fun `the stage key is the same for the same call and differs across sessions with the same call id`() {
        assertEquals(userFileStageKey("s1", "call_0"), userFileStageKey("s1", "call_0"))
        // 有的中转站按序号起调用 id：两个会话都有 call_0，不能落进同一个目录
        assertNotEquals(userFileStageKey("s1", "call_0"), userFileStageKey("s2", "call_0"))
        assertTrue(userFileStageKey("s1", "../../x").matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `card rows give name and size for staged files, and no file for what is gone`() {
        stageUserFile(root, key = "k", index = 0, name = "a.md", bytes = ByteArray(1234))
        val rows = sentFileRows(root, "k", listOf("a.md", "b.png"))
        assertEquals(listOf("a.md", "b.png"), rows.map { it.name })
        assertEquals(1234L, rows[0].sizeBytes)
        assertTrue(rows[0].file!!.isFile)
        // 第二个没存上（缓存被系统清了、或这一批在别的设备上发的）：行还在，只是打不开
        assertNull(rows[1].sizeBytes)
        assertNull(rows[1].file)
    }

    @Test
    fun `a dot name cannot climb out of the stage directory`() {
        val f = stageUserFile(root, key = "k", index = 0, name = "..", bytes = byteArrayOf(1))
        assertTrue(f.canonicalPath.startsWith(File(root, "k").canonicalPath))
    }
}
