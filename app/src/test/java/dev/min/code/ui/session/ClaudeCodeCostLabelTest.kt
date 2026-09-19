package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 底栏那一段金额**显示哪几个数**。要的是「单日消耗」，本次会话只是补充。
 *
 * 测的是取数逻辑而不是那句话：文案在 `costLabel` 里走 string 资源，
 * 而这些分支（今日 / 本次 / 都没有 / 两者相等）才是会出错的地方。
 */
class ClaudeCodeCostLabelTest {

    @Test
    fun `今日合计是主角，本次跟在后面`() {
        assertEquals(3.5912 to 0.42, costParts(3.5912, 0.42))
    }

    @Test
    fun `今天只有这一个会话时不重复写两遍`() {
        assertEquals(0.42 to null, costParts(0.42, 0.42))
    }

    /** 台账还没记上（这一轮的 get_session_cost 先回来了）时退回会话值 */
    @Test
    fun `台账为零时用会话值`() {
        assertEquals(0.42 to null, costParts(0.0, 0.42))
    }

    @Test
    fun `两边都没有就不显示`() {
        assertNull(costParts(0.0, null))
    }

    /** 本次比今日大（跨天、或台账刚被清）时不该把它当成两个数写出来 */
    @Test
    fun `本次不小于今日时只写一个数`() {
        assertEquals(1.0 to null, costParts(1.0, 2.0))
    }
}
