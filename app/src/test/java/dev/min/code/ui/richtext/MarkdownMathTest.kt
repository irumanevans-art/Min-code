package dev.min.code.ui.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公式保护：钉的是「原文一个字符不变地进了公式块 / 行内公式」和「不该碰的一个都不碰」。
 * 除了看改写后的字符串，还走一遍真的解析器（[MarkdownDoc.parse]），确认块里的 `-` / `#` 没被当成列表和标题。
 */
class MarkdownMathTest {

    // ---- display 块 ----

    @Test
    fun `dollar block gets a math fence around it and stays byte for byte`() {
        val input = "Before\n$$\na_1 * b_2\n- c\n$$\nAfter"
        assertEquals("Before\n```math\n$$\na_1 * b_2\n- c\n$$\n```\nAfter", protectMath(input))
    }

    @Test
    fun `bracket block is protected the same way`() {
        val input = "\\[\n\\int_0^1 f(x)\\,dx\n\\]"
        assertEquals("```math\n\\[\n\\int_0^1 f(x)\\,dx\n\\]\n```", protectMath(input))
    }

    @Test
    fun `delimiter lines may carry surrounding spaces`() {
        // 围栏跟着开头行缩进（列表项里的公式靠这个留在列表项里）
        assertEquals("  ```math\n  $$  \nx\n $$\n  ```", protectMath("  $$  \nx\n $$"))
        // CRLF：行尾的 \r 随原行保留，插进去的围栏行用 \n
        assertEquals("```math\n$$\r\nx\r\n$$\r\n```\nEnd", protectMath("$$\r\nx\r\n$$\r\nEnd"))
    }

    @Test
    fun `single line dollar block counts`() {
        assertEquals("```math\n$$ E = mc^2 $$\n```", protectMath("$$ E = mc^2 $$"))
        // 两个公式夹着字，不是一个块
        assertUnchanged("\$\$a\$\$ and \$\$b\$\$")
        assertUnchanged("$$ $$")
        // 单行 \[ … \] 是 Markdown 转义方括号的正常写法
        assertUnchanged("see \\[1\\]")
        assertUnchanged("\\[1\\]")
    }

    @Test
    fun `empty blocks are not math`() {
        assertUnchanged("$$\n$$")
        assertUnchanged("$$\n\n   \n$$")
        assertUnchanged("\\[\n\n\\]")
    }

    @Test
    fun `unclosed or mismatched blocks are left alone`() {
        assertUnchanged("$$\na = b")
        assertUnchanged("\\[\na = b\n$$")
        assertUnchanged("$$\na = b\n\\]")
        assertUnchanged("[\na\n]")
    }

    @Test
    fun `markdown markers inside a block reach the renderer verbatim`() {
        val block = "$$\n- a_1 * b_2 * c\n# not a heading\n1. not a list\n\\\\ x_i^2 \\_ \\*\n\n> no quote\n$$"
        val doc = MarkdownDoc.parse("Text\n$block\nMore")
        val fences = doc.root!!.all(MarkdownElementTypes.CODE_FENCE)
        assertEquals(1, fences.size)
        assertEquals(MATH_FENCE_LANG, fenceLanguage(fences[0], doc.text))
        assertEquals(block, fenceCode(fences[0], doc.text))
        for (t in listOf(MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST, MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.EMPH, MarkdownElementTypes.BLOCK_QUOTE)) {
            assertTrue("unexpected $t", doc.root!!.all(t).isEmpty())
        }
    }

    @Test
    fun `math inside a list item stays in the list item`() {
        val doc = MarkdownDoc.parse("- item\n\n  $$\n  - x\n  $$\n- next")
        val lists = doc.root!!.all(MarkdownElementTypes.UNORDERED_LIST)
        assertEquals("only the outer list", 1, lists.size)
        val fence = doc.root!!.all(MarkdownElementTypes.CODE_FENCE).single()
        assertEquals(MATH_FENCE_LANG, fenceLanguage(fence, doc.text))
        assertEquals("$$\n- x\n$$", fenceCode(fence, doc.text))
    }

    @Test
    fun `fence grows past the longest backtick run inside the block`() {
        val input = "$$\n\\text{a ``` b}\n$$"
        assertEquals("````math\n$$\n\\text{a ``` b}\n$$\n````", protectMath(input))
        val doc = MarkdownDoc.parse(input)
        val fence = doc.root!!.all(MarkdownElementTypes.CODE_FENCE).single()
        assertEquals(input, fenceCode(fence, doc.text))
    }

    // ---- 代码里的不碰 ----

    @Test
    fun `dollars inside fenced code are untouched`() {
        assertUnchanged("```\n$$\nx\n$$\n```")
        assertUnchanged("~~~~\n$$\nx\n$$\n~~~~")
        assertUnchanged("  ```bash\n  $$\n  echo \\(x\\)\n  $$\n  ```")
        // 更长的围栏里一行短的 ``` 不算收尾
        assertUnchanged("````md\n```\n$$\nx\n$$\n```\n````")
        assertUnchanged("> ```\n> \\(x\\)\n> ```")
        assertUnchanged("- ```\n  \\(x\\)\n  ```")
        // 没闭合的围栏一直到文末都是代码
        assertUnchanged("```\n$$\nx\n$$")
    }

    @Test
    fun `a closing line that lives inside a later code block does not pair`() {
        assertUnchanged("$$\nstray\n```sh\n$$\n```")
    }

    @Test
    fun `math after a closed fence is protected again`() {
        assertEquals("```\ncode\n```\n```math\n$$\nx\n$$\n```", protectMath("```\ncode\n```\n$$\nx\n$$"))
    }

    // ---- 行内 ----

    @Test
    fun `inline paren math becomes a code span the renderer recognises`() {
        val input = "where \\(a*b*c\\) holds"
        assertEquals("where `\\(a*b*c\\)` holds", protectMath(input))
        val doc = MarkdownDoc.parse(input)
        val span = doc.root!!.all(MarkdownElementTypes.CODE_SPAN).single()
        assertEquals("\\(a*b*c\\)", inlineMathSource(doc.text.substring(span.startOffset, span.endOffset)))
        assertTrue(doc.root!!.all(MarkdownElementTypes.EMPH).isEmpty())
    }

    @Test
    fun `inline math with backticks gets a longer tick run`() {
        assertEquals("x ``\\(a`b\\)`` y", protectMath("x \\(a`b\\) y"))
        assertEquals("\\(a`b\\)", inlineMathSource("``\\(a`b\\)``"))
    }

    @Test
    fun `inline code and escapes are left alone`() {
        assertUnchanged("run `\\(x\\)` here")
        assertUnchanged("run ``a ` \\(x\\)`` here")
        assertUnchanged("escaped \\\\(x\\)")
        assertUnchanged("blank \\( \\)")
        assertUnchanged("open \\(x only")
        // 一对在代码里、一对在外面
        assertEquals("`\\(a\\)` and `\\(b\\)`", protectMath("`\\(a\\)` and \\(b\\)"))
    }

    @Test
    fun `single dollars are never math`() {
        assertUnchanged("echo \$HOME and \$1")
        assertUnchanged("costs \$5 and \$10")
        assertUnchanged("pid is $$ in bash")
    }

    @Test
    fun `inline math source`() {
        assertEquals("\\(x\\)", inlineMathSource("`\\(x\\)`"))
        assertEquals("\\(x\\)", inlineMathSource("` \\(x\\) `"))
        assertNull(inlineMathSource("`x`"))
        assertNull(inlineMathSource("`\\(\\)`"))
        assertNull(inlineMathSource("`\\(x`"))
    }

    // ---- 流式 ----

    @Test
    fun `streaming prefixes never flicker`() {
        val full = "Intro\n$$\nx = 1\n- y\n$$\nOutro \\(z\\) end"
        val closedAt = full.indexOf("$$\nOutro") + 2
        val settled = protectMath(full.substring(0, closedAt))
        for (end in 0..full.length) {
            val prefix = full.substring(0, end)
            val out = protectMath(prefix)
            if (end < closedAt) {
                // 只有开头的 `$$`、块还没闭合：原样显示，不先变成公式再变回来
                assertEquals(prefix, out)
            } else {
                assertTrue("prefix $end", out.startsWith(settled))
            }
        }
    }

    @Test(timeout = 5_000)
    fun `many unclosed openers stay linear`() {
        val input = buildString { repeat(50_000) { append("\\[\nx \\( y\n") } }
        assertEquals(input, protectMath(input))
    }

    private fun assertUnchanged(input: String) = assertEquals(input, protectMath(input))

    private fun ASTNode.all(type: org.intellij.markdown.IElementType): List<ASTNode> =
        (if (this.type == type) listOf(this) else emptyList()) + children.flatMap { it.all(type) }
}
