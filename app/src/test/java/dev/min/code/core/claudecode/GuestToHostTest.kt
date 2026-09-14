package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestToHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun workspace(): java.io.File {
        val root = temp.newFolder("ws")
        java.io.File(root, "files").mkdirs()
        java.io.File(root, "linux").mkdirs()
        return root
    }

    @Test
    fun `workspace prefix lands in files`() {
        val root = workspace()
        assertEquals(
            java.io.File(root, "files").canonicalFile,
            guestToHostFile(root, "/workspace")?.canonicalFile,
        )
        assertEquals(
            java.io.File(root, "files/src/App.kt").canonicalFile,
            guestToHostFile(root, "/workspace/src/App.kt")?.canonicalFile,
        )
    }

    @Test
    fun `other absolute paths land in linux`() {
        val root = workspace()
        assertEquals(
            java.io.File(root, "linux/etc/hosts").canonicalFile,
            guestToHostFile(root, "/etc/hosts")?.canonicalFile,
        )
    }

    @Test
    fun `relative paths are refused`() {
        val root = workspace()
        assertNull(guestToHostFile(root, "src/App.kt"))
        assertNull(guestToHostFile(root, ""))
    }

    @Test
    fun `dot-dot cannot walk out of the area`() {
        val root = workspace()
        assertNull(guestToHostFile(root, "/workspace/../linux/etc/passwd"))
        assertNull(guestToHostFile(root, "/etc/../../files/secret"))
    }
}
