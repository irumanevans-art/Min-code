package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 底栏那一段金额。要的是「单日消耗」，本次会话只是补充 */
class ClaudeCodeCostLabelTest {

    @Test
    fun `今日合计是主角，本次跟在后面`() {
        assertEquals("今日 \$3.59 · 本次 \$0.4200", costLabel(3.5912, 0.42))
    }

    @Test
    fun `今天只有这一个会话时不重复写两遍`() {
        assertEquals("今日 \$0.4200", costLabel(0.42, 0.42))
    }

    /** 台账还没记上（这一轮的 get_session_cost 先回来了）时退回会话值 */
    @Test
    fun `台账为零时用会话值`() {
        assertEquals("今日 \$0.4200", costLabel(0.0, 0.42))
    }

    @Test
    fun `两边都没有就不显示`() {
        assertNull(costLabel(0.0, null))
    }
}
