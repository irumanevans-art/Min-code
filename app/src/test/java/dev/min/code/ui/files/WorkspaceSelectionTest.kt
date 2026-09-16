package dev.min.code.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSelectionTest {

    private val visible = listOf("a.txt", "b.txt", "dir")

    @Test
    fun `toggle adds then removes`() {
        val once = WorkspaceSelection.toggle(emptySet(), "a.txt")
        assertEquals(setOf("a.txt"), once)
        assertEquals(emptySet<String>(), WorkspaceSelection.toggle(once, "a.txt"))
    }

    @Test
    fun `select all and invert work on what is visible`() {
        assertEquals(visible.toSet(), WorkspaceSelection.selectAll(emptySet(), visible))
        assertEquals(
            setOf("b.txt", "dir"),
            WorkspaceSelection.invert(setOf("a.txt"), visible),
        )
    }

    @Test
    fun `prune drops paths that are gone but keeps the rest`() {
        val selected = setOf("a.txt", "deleted.txt")
        assertEquals(setOf("a.txt"), WorkspaceSelection.prune(selected, visible))
    }

    @Test
    fun `all selected is false for an empty listing`() {
        assertFalse(WorkspaceSelection.allSelected(emptySet(), emptyList()))
        assertTrue(WorkspaceSelection.allSelected(visible.toSet(), visible))
    }

    @Test
    fun `archive base is the area root while searching`() {
        assertEquals("", archiveBase(inSearch = true, currentPath = "src/main"))
        assertEquals("src/main", archiveBase(inSearch = false, currentPath = "src/main"))
    }

    @Test
    fun `a lone folder keeps its own name`() {
        assertEquals(
            "docs.zip",
            archiveName(listOf("docs"), true, "proj", "workspace", "2026-09-16"),
        )
    }

    @Test
    fun `multi selection is named after the folder it came from`() {
        assertEquals(
            "proj-2026-09-16.zip",
            archiveName(listOf("a.txt", "b.txt"), false, "proj", "workspace", "2026-09-16"),
        )
    }

    @Test
    fun `at the area root the area name stands in`() {
        assertEquals(
            "workspace-2026-09-16.zip",
            archiveName(listOf("a.txt", "b.txt"), false, null, "workspace", "2026-09-16"),
        )
        assertEquals(
            "rootfs-2026-09-16.zip",
            archiveName(listOf("a.txt", "b.txt"), false, "", "rootfs", "2026-09-16"),
        )
    }

    @Test
    fun `illegal characters are replaced not dropped`() {
        assertEquals("a_b_c.zip", archiveName(listOf("""a/b:c"""), true, null, "workspace", "2026-09-16"))
    }

    @Test
    fun `long names are capped`() {
        val long = "x".repeat(200)
        val name = archiveName(listOf(long), true, null, "workspace", "2026-09-16")
        assertEquals(80 + ".zip".length, name.length)
    }

    @Test
    fun `a name made only of illegal characters still produces something openable`() {
        assertEquals("_.zip", archiveName(listOf("/"), true, null, "workspace", "2026-09-16"))
    }
}
