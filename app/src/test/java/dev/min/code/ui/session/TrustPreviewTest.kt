package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TrustPreviewTest {
    @Test fun `a short list is shown whole`() {
        assertEquals("a, b" to 0, previewItems(listOf("a", "b")))
        assertEquals("" to 0, previewItems(emptyList()))
    }

    @Test fun `a long list shows the first few and counts the rest`() {
        val items = (1..9).map { "Bash(cmd$it)" }
        val (shown, hidden) = previewItems(items, max = 3)
        assertEquals("Bash(cmd1), Bash(cmd2), Bash(cmd3)", shown)
        assertEquals(6, hidden)
        assertEquals("exactly at the limit nothing is hidden", 0, previewItems(items.take(5)).second)
    }
}
