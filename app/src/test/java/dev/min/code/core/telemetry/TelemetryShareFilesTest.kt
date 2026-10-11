package dev.min.code.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 「导出到文件」之后的分享：把本机 JSONL 拷进缓存目录再交给 FileProvider（和工作区分享、诊断包同一条路） */
class TelemetryShareFilesTest {
    private fun tmp() = Files.createTempDirectory("min-share").toFile()

    @Test
    fun `没导出过（文件不存在或是空的）就没有可分享的`() {
        val dir = tmp()
        assertNull(TelemetryShareFiles.prepare(File(dir, "telemetry.jsonl"), File(dir, "cache"), 1L))
        val empty = File(dir, "telemetry.jsonl").apply { writeText("") }
        assertNull(TelemetryShareFiles.prepare(empty, File(dir, "cache"), 1L))
    }

    @Test
    fun `拷进缓存目录的专用子目录，原文件不动，文件名带时间`() {
        val dir = tmp()
        val source = File(dir, "telemetry.jsonl").apply { writeText("{\"a\":1}\n{\"a\":2}\n") }
        val cache = File(dir, "cache")
        val shared = TelemetryShareFiles.prepare(source, cache, 1234L)
        assertNotNull(shared)
        assertEquals("min-telemetry-1234.jsonl", shared!!.name)
        assertEquals(File(cache, TelemetryShareFiles.SHARE_DIR).canonicalPath, shared.parentFile!!.canonicalPath)
        assertEquals(source.readText(), shared.readText())
        assertTrue(source.exists())
    }

    @Test
    fun `再分享时先清空上一次的拷贝，不在缓存里越攒越多`() {
        val dir = tmp()
        val source = File(dir, "telemetry.jsonl").apply { writeText("x\n") }
        val cache = File(dir, "cache")
        val first = TelemetryShareFiles.prepare(source, cache, 1L)!!
        val second = TelemetryShareFiles.prepare(source, cache, 2L)!!
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(1, second.parentFile!!.listFiles()!!.size)
    }
}
