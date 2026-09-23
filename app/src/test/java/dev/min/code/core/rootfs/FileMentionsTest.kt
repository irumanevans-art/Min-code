package dev.min.code.core.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileMentionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, vararg paths: String) = paths.forEach { p ->
        File(root, p).apply { parentFile!!.mkdirs() }.writeText("x")
    }

    @Test
    fun `a bare at lists files straight away`() {
        val root = tmp.newFolder("files")
        touch(root, "README.md", "src/App.kt")
        assertEquals(
            setOf("/workspace/README.md", "/workspace/src/App.kt"),
            findMentionFiles(root, "/workspace", "").toSet(),
        )
    }

    @Test
    fun `the query matches the relative path, not just the name`() {
        val root = tmp.newFolder("files")
        touch(root, "src/app/Main.kt", "docs/app.md", "lib/Other.kt")
        assertEquals(
            listOf("/workspace/src/app/Main.kt"),
            findMentionFiles(root, "/workspace", "SRC/APP"),
        )
    }

    @Test
    fun `folders are not offered, and dependency folders are not walked`() {
        val root = tmp.newFolder("files")
        touch(root, "node_modules/pkg/index.js", "web/index.js")
        File(root, "index-dir").mkdirs()
        assertEquals(listOf("/workspace/web/index.js"), findMentionFiles(root, "/workspace", "index"))
    }

    @Test
    fun `a working directory that is itself named like a skipped folder is still searched`() {
        val root = tmp.newFolder("build")
        touch(root, "out.txt")
        assertEquals(listOf("/workspace/build/out.txt"), findMentionFiles(root, "/workspace/build/", "out"))
    }

    @Test
    fun `results stop at the limit`() {
        val root = tmp.newFolder("files")
        touch(root, *Array(30) { "f$it.txt" })
        assertEquals(MENTION_LIMIT, findMentionFiles(root, "/workspace", "").size)
    }

    @Test
    fun `a missing root gives nothing`() {
        assertTrue(findMentionFiles(File(tmp.root, "nope"), "/workspace", "").isEmpty())
    }
}
