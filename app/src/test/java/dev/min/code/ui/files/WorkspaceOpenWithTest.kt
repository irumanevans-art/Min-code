package dev.min.code.ui.files

import dev.min.code.core.settings.WorkspaceOpenMode
import dev.min.code.core.settings.formatOpenWithDefaults
import dev.min.code.core.settings.parseOpenWithDefaults
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOpenWithTest {

    private fun file(name: String, size: Long = 16, directory: Boolean = false) = WorkspaceFileEntry(
        path = name,
        name = name.substringAfterLast('/'),
        isDirectory = directory,
        sizeBytes = size,
        updatedAt = 0,
    )

    @Test
    fun `an image can be previewed in app or handed to another app`() {
        assertEquals(
            listOf(WorkspaceOpenMode.EDITOR, WorkspaceOpenMode.IMAGE, WorkspaceOpenMode.EXTERNAL),
            availableOpenModes(file("shot.png")),
        )
    }

    @Test
    fun `a text file has no image preview`() {
        assertEquals(
            listOf(WorkspaceOpenMode.EDITOR, WorkspaceOpenMode.EXTERNAL),
            availableOpenModes(file("notes.txt")),
        )
    }

    @Test
    fun `a directory is never opened with anything`() {
        assertTrue(availableOpenModes(file("src", directory = true)).isEmpty())
    }

    @Test
    fun `a file too big for the editor drops that option`() {
        val big = file("huge.txt", size = OPEN_WITH_EDITOR_MAX_BYTES + 1)
        assertEquals(OpenDisable.TOO_LARGE, editorDisabledReason(big))
        assertEquals(listOf(WorkspaceOpenMode.EXTERNAL), availableOpenModes(big))
        assertEquals("默认路由也不能把人送进一个必然报错的编辑器",
            WorkspaceOpenMode.EXTERNAL, defaultOpenMode(big))
    }

    @Test
    fun `default routing matches the file type`() {
        assertEquals(WorkspaceOpenMode.EDITOR, defaultOpenMode(file("a.md")))
        assertEquals(WorkspaceOpenMode.IMAGE, defaultOpenMode(file("a.png")))
        assertEquals(WorkspaceOpenMode.EXTERNAL, defaultOpenMode(file("a.docx")))
    }

    @Test
    fun `a remembered choice wins over the default`() {
        val overrides = mapOf("png" to WorkspaceOpenMode.EXTERNAL)
        assertEquals(WorkspaceOpenMode.EXTERNAL, resolveOpenMode(file("a.png"), overrides))
        assertEquals("没记过的扩展名不受影响", WorkspaceOpenMode.EDITOR, resolveOpenMode(file("a.md"), overrides))
    }

    @Test
    fun `a remembered choice that cannot apply falls back instead of breaking`() {
        val overrides = mapOf("txt" to WorkspaceOpenMode.EDITOR)
        val big = file("huge.txt", size = OPEN_WITH_EDITOR_MAX_BYTES + 1)
        assertEquals(WorkspaceOpenMode.EXTERNAL, resolveOpenMode(big, overrides))
    }

    @Test
    fun `defaults round trip and bad entries are dropped silently`() {
        val map = mapOf("kt" to WorkspaceOpenMode.EDITOR, "png" to WorkspaceOpenMode.EXTERNAL)
        assertEquals(map, parseOpenWithDefaults(formatOpenWithDefaults(map)))
        assertEquals(
            mapOf("png" to WorkspaceOpenMode.IMAGE),
            parseOpenWithDefaults("kt=NOPE;=;png=IMAGE;broken"),
        )
        assertEquals(emptyMap<String, WorkspaceOpenMode>(), parseOpenWithDefaults(""))
    }

    @Test
    fun `an extension-less file cannot be remembered`() {
        assertFalse(canRememberOpenMode(file("Makefile")))
        assertTrue(canRememberOpenMode(file("a.kt")))
    }
}
