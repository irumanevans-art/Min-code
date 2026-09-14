package dev.min.code.ui.files

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileKindTest {

    private fun file(name: String, directory: Boolean = false) = WorkspaceFileEntry(
        path = name,
        name = name.substringAfterLast('/'),
        isDirectory = directory,
        sizeBytes = 1,
        updatedAt = 0,
    )

    @Test
    fun `directories are folders regardless of extension`() {
        assertEquals(WorkspaceFileKind.FOLDER, file("src.md", directory = true).detectFileKind())
    }

    @Test
    fun `markdown is not the same as a word document`() {
        assertEquals(WorkspaceFileKind.MARKDOWN, file("README.md").detectFileKind())
        assertEquals(WorkspaceFileKind.DOCUMENT, file("notes.docx").detectFileKind())
        assertEquals(WorkspaceFileType.TEXT, file("README.md").detectFileType())
        assertEquals(WorkspaceFileType.OTHER, file("notes.docx").detectFileType())
    }

    @Test
    fun `code images archives and pdfs have their own kind`() {
        assertEquals(WorkspaceFileKind.CODE, file("Main.kt").detectFileKind())
        assertEquals(WorkspaceFileKind.IMAGE, file("cover.png").detectFileKind())
        assertEquals(WorkspaceFileKind.ARCHIVE, file("src.zip").detectFileKind())
        assertEquals(WorkspaceFileKind.PDF, file("paper.pdf").detectFileKind())
        assertEquals(WorkspaceFileKind.AUDIO, file("clip.mp3").detectFileKind())
        assertEquals(WorkspaceFileKind.VIDEO, file("demo.mp4").detectFileKind())
        assertEquals(WorkspaceFileKind.UNKNOWN, file("blob.bin").detectFileKind())
    }
}
