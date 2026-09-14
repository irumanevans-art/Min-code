package dev.min.code.core.claudecode

import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CwdPathTest {

    @Test
    fun `files area maps onto workspace`() {
        assertEquals("/workspace", CwdPath.guest(WorkspaceStorageArea.FILES, ""))
        assertEquals("/workspace/src", CwdPath.guest(WorkspaceStorageArea.FILES, "src"))
        assertEquals("/workspace/src/app", CwdPath.guest(WorkspaceStorageArea.FILES, "/src/app/"))
    }

    @Test
    fun `linux area maps onto the rootfs`() {
        assertEquals("/", CwdPath.guest(WorkspaceStorageArea.LINUX, ""))
        assertEquals("/etc", CwdPath.guest(WorkspaceStorageArea.LINUX, "etc"))
        assertEquals("/home/root", CwdPath.guest(WorkspaceStorageArea.LINUX, "/home/root/"))
    }

    @Test
    fun `split undoes guest`() {
        assertEquals(WorkspaceStorageArea.FILES to "", CwdPath.split("/workspace"))
        assertEquals(WorkspaceStorageArea.FILES to "src", CwdPath.split("/workspace/src"))
        assertEquals(WorkspaceStorageArea.LINUX to "", CwdPath.split("/"))
        assertEquals(WorkspaceStorageArea.LINUX to "etc", CwdPath.split("/etc"))
    }

    @Test
    fun `normalize collapses junk into an absolute path`() {
        assertEquals("/workspace", CwdPath.normalize(""))
        assertEquals("/workspace", CwdPath.normalize("relative"))
        assertEquals("/workspace/src", CwdPath.normalize("/workspace/src/"))
        assertEquals("/workspace/src", CwdPath.normalize("/workspace//src"))
        assertEquals("/", CwdPath.normalize("/"))
    }

    @Test
    fun `parent stops at the root`() {
        assertEquals("/workspace", CwdPath.parent("/workspace/src"))
        assertEquals("/", CwdPath.parent("/workspace"))
        assertEquals("/", CwdPath.parent("/etc"))
        assertNull(CwdPath.parent("/"))
    }

    @Test
    fun `folder names reject path fragments`() {
        assertEquals("project", CwdPath.folderName(" project "))
        assertNull(CwdPath.folderName(""))
        assertNull(CwdPath.folderName("a/b"))
        assertNull(CwdPath.folderName(".."))
        assertNull(CwdPath.folderName("."))
    }
}
