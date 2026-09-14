package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长段粘贴折叠。要害是**不能误伤正常打字** —— 折叠一旦发生，用户刚写的东西就被
 * 换成了一行占位符，比不折叠糟得多。
 */
class ClaudeCodePasteTest {

    private val long = (1..40).joinToString("\n") { "第 $it 行内容" }

    @Test
    fun `逐字输入不折叠`() {
        assertNull(collapsePaste("你好", "你好世", 1))
    }

    @Test
    fun `短粘贴留在框里`() {
        assertNull(collapsePaste("", "https://example.com/a/b/c", 1))
    }

    @Test
    fun `贴进大段文本换成占位符`() {
        val result = collapsePaste("", long, 1)!!
        assertEquals(long, result.pasted)
        assertTrue(result.text.startsWith("[粘贴文本 #1 · 40 行 · "))
        assertTrue(result.text.endsWith("]"))
    }

    @Test
    fun `占位符插在光标处，前后原有文本保留`() {
        val typed = "看这段：\n收到了吗"
        val pasted = "看这段：\n$long\n收到了吗"
        val result = collapsePaste(typed, pasted, 2)!!
        assertTrue(result.text.startsWith("看这段：\n[粘贴文本 #2 "))
        assertTrue(result.text.endsWith("收到了吗"))
        // 不去断言"被折叠的正好是 long"：插入内容和边界共用了那个换行符，
        // 前后缀的切分点本身就有歧义（结尾的 \n 划给谁都说得通）。真正要守住的
        // 不变量是**还原之后逐字等于用户贴出来的样子**
        assertEquals(pasted, expandPastes(result.text, mapOf(2 to result.pasted)))
    }

    /** 有删改就不是粘贴。全选后替换整段内容会走到这里 —— 那多半是用户在改字 */
    @Test
    fun `不是纯插入时不折叠`() {
        assertNull(collapsePaste("abcdef", "a$long", 1))
    }

    @Test
    fun `变短时不折叠`() {
        assertNull(collapsePaste(long, "短", 1))
    }

    @Test
    fun `字数少但行数多也折叠`() {
        val manyLines = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj"
        assertTrue(shouldCollapsePaste(manyLines))
        assertTrue(collapsePaste("", manyLines, 1) != null)
    }

    // --- 还原 ---

    @Test
    fun `发送前把占位符换回原文`() {
        val pastes = mapOf(1 to long)
        val text = "看这段：\n${pastePlaceholder(1, long)}\n收到了吗"
        assertEquals("看这段：\n$long\n收到了吗", expandPastes(text, pastes))
    }

    /** 摘要被用户误删几个字也要能认出来：只按 id 认领 */
    @Test
    fun `摘要被改过仍按 id 还原`() {
        assertEquals(long, expandPastes("[粘贴文本 #1 · 乱七八糟]", mapOf(1 to long)))
    }

    /** 占位符被整段删掉 = 取消这次粘贴，正文不该偷偷跟着发出去 */
    @Test
    fun `删掉占位符就等于取消粘贴`() {
        assertEquals("算了", expandPastes("算了", mapOf(1 to long)))
    }

    @Test
    fun `认不出 id 的占位符原样留着`() {
        assertEquals("[粘贴文本 #9 · 3 行]", expandPastes("[粘贴文本 #9 · 3 行]", mapOf(1 to long)))
    }

    /** 原文里带 `$1` 这类反向引用时，替换必须是字面量 */
    @Test
    fun `原文里的美元反向引用不被当成替换模式`() {
        val raw = "echo \$1 \$0\n" + "x\n".repeat(20)
        assertEquals(raw, expandPastes(pastePlaceholder(3, raw), mapOf(3 to raw)))
    }

    @Test
    fun `占位符带上行数和字数便于核对`() {
        // "x\n" × 39 再加一个 x：39 个换行 = 40 行，79 个字符
        assertEquals("[粘贴文本 #1 · 40 行 · 79 字]", pastePlaceholder(1, "x\n".repeat(39) + "x"))
        assertTrue(pastePlaceholder(1, "y".repeat(3400)).endsWith("· 3.4k 字]"))
    }
}
