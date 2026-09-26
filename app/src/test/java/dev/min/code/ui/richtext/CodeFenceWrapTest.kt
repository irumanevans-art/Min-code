package dev.min.code.ui.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 围栏块折不折行：只有明确标了散文类语言的才折。
 * 钉住「没标语言的不折」——目录树、ASCII 表格折了就错位，这条最容易被顺手改掉。
 */
class CodeFenceWrapTest {

    @Test
    fun `prose tags wrap, case insensitive`() {
        listOf("text", "txt", "plaintext", "plain", "markdown", "md", "TEXT", "Markdown", " md ").forEach {
            assertTrue(it, wrapsAsProse(it))
        }
    }

    @Test
    fun `only the first word of the info string counts`() {
        assertTrue(wrapsAsProse("text title=\"essay\""))
        assertTrue(wrapsAsProse("plain text"))
        assertFalse(wrapsAsProse("kotlin text"))
    }

    @Test
    fun `untagged blocks keep scrolling sideways`() {
        assertFalse(wrapsAsProse(""))
        assertFalse(wrapsAsProse("   "))
        assertFalse(wrapsAsProse(null))
    }

    @Test
    fun `code and math keep scrolling sideways`() {
        listOf("kotlin", "bash", "sh", "python", "json", "diff", "latex", "tex", MATH_FENCE_LANG, "texts", "mdx").forEach {
            assertFalse(it, wrapsAsProse(it))
        }
    }
}
