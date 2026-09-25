package dev.min.code.ui.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 有序列表编号：走真的解析器（[MarkdownDoc.parse]），钉住「首项定起始数、按位置递增、分隔符沿用原文」，
 * 以及序号列按哪个序号量宽（[OrderedListNumbering.widestMarker]）。
 */
class OrderedListNumberingTest {

    private fun numbering(markdown: String): OrderedListNumbering {
        val doc = MarkdownDoc.parse(markdown)
        val list = doc.root!!.find { it.type == MarkdownElementTypes.ORDERED_LIST }!!
        return OrderedListNumbering.of(list, doc.text)
    }

    private fun ASTNode.find(pred: (ASTNode) -> Boolean): ASTNode? =
        if (pred(this)) this else children.firstNotNullOfOrNull { it.find(pred) }

    private fun items(start: Int, count: Int, delimiter: Char = '.') =
        (start until start + count).joinToString("\n") { "$it$delimiter item $it" }

    private fun markers(n: OrderedListNumbering) = (0 until n.count).map(n::marker)

    @Test
    fun `single digit list counts from one`() {
        val n = numbering(items(1, 3))
        assertEquals(OrderedListNumbering(1, '.', 3), n)
        assertEquals(listOf("1.", "2.", "3."), markers(n))
        assertEquals("3.", n.widestMarker)
    }

    @Test
    fun `two digit list is measured by its last marker`() {
        val n = numbering(items(1, 12))
        assertEquals(12, n.count)
        assertEquals("12.", n.widestMarker)
    }

    @Test
    fun `three digit list is measured by its last marker`() {
        val n = numbering(items(1, 100))
        assertEquals(100, n.count)
        assertEquals("100.", n.widestMarker)
    }

    @Test
    fun `list may start anywhere and keeps counting from there`() {
        val n = numbering(items(5, 3))
        assertEquals(listOf("5.", "6.", "7."), markers(n))
    }

    @Test
    fun `start of 98 crosses into three digits`() {
        val n = numbering(items(98, 3))
        assertEquals(listOf("98.", "99.", "100."), markers(n))
        assertEquals("100.", n.widestMarker)
    }

    @Test
    fun `only the first number counts, later ones are ignored as in CommonMark`() {
        val n = numbering("3. a\n3. b\n1. c")
        assertEquals(listOf("3.", "4.", "5."), markers(n))
    }

    @Test
    fun `paren delimiter is kept`() {
        val n = numbering(items(1, 3, ')'))
        assertEquals(OrderedListNumbering(1, ')', 3), n)
        assertEquals(listOf("1)", "2)", "3)"), markers(n))
    }

    @Test
    fun `task items still take a number`() {
        val n = numbering("1. [ ] a\n2. [x] b\n3. c")
        assertEquals(listOf("1.", "2.", "3."), markers(n))
    }

    @Test
    fun `zero is a valid start`() {
        val n = numbering(items(0, 2))
        assertEquals(listOf("0.", "1."), markers(n))
    }
}
