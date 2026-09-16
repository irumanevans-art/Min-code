package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceArchiveTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory("archive_$name").toFile().apply { deleteOnExit() }

    private fun File.write(path: String, text: String): File {
        val f = File(this, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun zipOf(base: File, vararg sources: File, skipDirNames: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        WorkspaceArchive.zip(base, sources.toList(), out, skipDirNames = skipDirNames)
        return out.toByteArray()
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
        }
        return names
    }

    @Test
    fun `zip then unzip round trips files and empty directories`() {
        val src = tempDir("src")
        src.write("a.txt", "a")
        src.write("sub/b.bin", "bbb")
        File(src, "sub/empty").mkdirs()

        val bytes = zipOf(src, *src.listFiles()!!)
        val dest = tempDir("dest")
        val result = WorkspaceArchive.unzip(ByteArrayInputStream(bytes), dest)

        assertEquals(2, result.entries)
        assertEquals("a", File(dest, "a.txt").readText())
        assertEquals("bbb", File(dest, "sub/b.bin").readText())
        assertTrue("空目录不留下来，round-trip 就把结构丢了", File(dest, "sub/empty").isDirectory)
    }

    @Test
    fun `entry names are relative to base and use forward slashes`() {
        val src = tempDir("names")
        src.write("deep/nested/x.txt", "x")

        val names = entryNames(zipOf(src, File(src, "deep")))
        assertEquals(listOf("deep/nested/x.txt"), names)
    }

    @Test
    fun `entry order is deterministic`() {
        val src = tempDir("order")
        listOf("m", "a", "z", "b").forEach { src.write("$it.txt", it) }
        src.write("dir/c.txt", "c")

        val first = entryNames(zipOf(src, *src.listFiles()!!))
        val second = entryNames(zipOf(src, *src.listFiles()!!))
        assertEquals(first, second)
        assertEquals(listOf("a.txt", "b.txt", "dir/c.txt", "m.txt", "z.txt"), first)
    }

    @Test
    fun `zip slip entries are rejected`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zos ->
                listOf("../evil.txt", "/etc/evil", "a/../../evil", "ok.txt").forEach { name ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write("x".toByteArray())
                    zos.closeEntry()
                }
            }
        }.toByteArray()

        val dest = tempDir("slip")
        val result = WorkspaceArchive.unzip(ByteArrayInputStream(bytes), dest)

        assertEquals(1, result.entries)
        assertEquals("ok.txt", File(dest, "ok.txt").name)
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.all { it.reason == WorkspaceArchive.SkipReason.UNSAFE_PATH })
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun `sanitize rejects the shapes that escape and keeps the ones that do not`() {
        assertNull(WorkspaceArchive.sanitizeEntryName("../x"))
        assertNull(WorkspaceArchive.sanitizeEntryName("/abs/x"))
        assertNull(WorkspaceArchive.sanitizeEntryName("C:/x"))
        assertNull(WorkspaceArchive.sanitizeEntryName("a/../../b"))
        assertNull(WorkspaceArchive.sanitizeEntryName("  "))
        assertEquals("a/b.txt", WorkspaceArchive.sanitizeEntryName("a/b.txt"))
        assertEquals("a/b.txt", WorkspaceArchive.sanitizeEntryName("./a//b.txt"))
        assertEquals("a/b.txt", WorkspaceArchive.sanitizeEntryName("a\\b.txt"))
    }

    @Test
    fun `symlinks are skipped instead of followed`() {
        val src = tempDir("links")
        src.write("real.txt", "r")
        val link = File(src, "loop")
        // Windows 上没开开发者模式就建不了符号链接；那台机器上跳过这条
        Assume.assumeTrue(
            runCatching { Files.createSymbolicLink(link.toPath(), src.toPath()) }.isSuccess,
        )

        val out = ByteArrayOutputStream()
        val result = WorkspaceArchive.zip(src, src.listFiles()!!.toList(), out)

        assertEquals(listOf("real.txt"), entryNames(out.toByteArray()))
        assertTrue(result.skipped.any { it.reason == WorkspaceArchive.SkipReason.SYMLINK })
    }

    @Test
    fun `l2s files and system dirs are pruned`() {
        val src = tempDir("prune")
        src.write("keep.txt", "k")
        src.write(".l2s.deadbeef", "junk")
        src.write("proc/1/stat", "junk")

        val out = ByteArrayOutputStream()
        val result = WorkspaceArchive.zip(
            src,
            src.listFiles()!!.toList(),
            out,
            skipDirNames = setOf("proc"),
        )

        assertEquals(listOf("keep.txt"), entryNames(out.toByteArray()))
        assertTrue(result.skipped.any { it.reason == WorkspaceArchive.SkipReason.L2S })
        assertTrue(result.skipped.any { it.reason == WorkspaceArchive.SkipReason.SYSTEM_DIR })
    }

    @Test(expected = WorkspaceArchive.ArchiveLimitException::class)
    fun `entry cap aborts the zip`() {
        val src = tempDir("cap")
        repeat(5) { src.write("f$it.txt", "x") }
        WorkspaceArchive.zip(src, src.listFiles()!!.toList(), ByteArrayOutputStream(), maxEntries = 3)
    }

    @Test(expected = WorkspaceArchive.ArchiveLimitException::class)
    fun `total byte cap aborts the unzip`() {
        val src = tempDir("bomb")
        src.write("big.txt", "x".repeat(4096))
        val bytes = zipOf(src, *src.listFiles()!!)
        WorkspaceArchive.unzip(ByteArrayInputStream(bytes), tempDir("bombout"), maxTotalBytes = 128)
    }

    @Test
    fun `unzip resolves name conflicts through the callback`() {
        val src = tempDir("conflict")
        src.write("a.txt", "new")
        val bytes = zipOf(src, *src.listFiles()!!)

        val dest = tempDir("conflictout")
        dest.write("a.txt", "old")
        WorkspaceArchive.unzip(ByteArrayInputStream(bytes), dest, onConflict = { f ->
            if (!f.exists()) f else File(f.parentFile, "${f.nameWithoutExtension} (1).${f.extension}")
        })

        assertEquals("old", File(dest, "a.txt").readText())
        assertEquals("new", File(dest, "a (1).txt").readText())
    }

    @Test
    fun `peek top level names sees the wrapper folder`() {
        val src = tempDir("peek")
        src.write("proj/a.txt", "a")
        src.write("proj/b.txt", "b")
        val bytes = zipOf(src, File(src, "proj"))

        assertEquals(setOf("proj"), WorkspaceArchive.peekTopLevelNames(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `peek top level names sees loose files`() {
        val src = tempDir("peekloose")
        src.write("a.txt", "a")
        src.write("b.txt", "b")
        val bytes = zipOf(src, *src.listFiles()!!)

        assertEquals(setOf("a.txt", "b.txt"), WorkspaceArchive.peekTopLevelNames(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `base itself as a source exports its children without a wrapper`() {
        val src = tempDir("self")
        src.write("a.txt", "a")
        src.write("sub/b.txt", "b")

        assertEquals(listOf("a.txt", "sub/b.txt"), entryNames(zipOf(src, src)))
    }

    @Test
    fun `entry name refuses a file outside the base`() {
        val base = tempDir("base")
        val outside = tempDir("outside").write("x.txt", "x")
        assertNull(WorkspaceArchive.entryName(base, outside))
    }
}
