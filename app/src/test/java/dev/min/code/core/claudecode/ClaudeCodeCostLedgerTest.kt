package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单日花费台账的纯逻辑。真正的坑全在 [costDelta] 上：
 * `get_session_cost` 给的是**会话累计值**，当成增量直接加会翻好几倍。
 */
class ClaudeCodeCostLedgerTest {

    @Test
    fun `第一次见到的会话整段计入`() {
        assertEquals(0.42, costDelta(null, 0.42), 1e-9)
    }

    @Test
    fun `同一个会话只加涨的部分`() {
        assertEquals(0.08, costDelta(0.42, 0.50), 1e-9)
    }

    @Test
    fun `重复轮询同一个读数不重复计费`() {
        assertEquals(0.0, costDelta(0.42, 0.42), 1e-9)
    }

    /** CLI 重启后会话累计值可能从头算起 —— 倒扣会把今天已经花掉的钱抹掉 */
    @Test
    fun `读数倒退时不倒扣`() {
        assertEquals(0.0, costDelta(0.42, 0.10), 1e-9)
    }

    @Test
    fun `非法读数一律记零`() {
        assertEquals(0.0, costDelta(null, Double.NaN), 1e-9)
        assertEquals(0.0, costDelta(null, Double.POSITIVE_INFINITY), 1e-9)
        assertEquals(0.0, costDelta(null, -1.0), 1e-9)
        assertEquals(0.0, costDelta(0.5, 0.0), 1e-9)
    }

    // --- get_session_cost 的文本解析 ---

    @Test
    fun `从预格式化文本里摘出金额`() {
        val text = "Total cost:            \$0.4218\nTotal duration (API):  1m 2.3s"
        assertEquals(0.4218, parseSessionCostUsd(text)!!, 1e-9)
    }

    @Test
    fun `整数金额也认`() {
        assertEquals(3.0, parseSessionCostUsd("Total cost: \$3")!!, 1e-9)
    }

    @Test
    fun `没有金额时返回 null`() {
        assertNull(parseSessionCostUsd("Total duration: 1m"))
    }

    // --- 展示格式 ---

    @Test
    fun `小额保留四位小数，上了一美元收成两位`() {
        assertEquals("\$0.0134", formatUsd(0.0134))
        assertEquals("\$3.59", formatUsd(3.5912))
        assertEquals("\$1.00", formatUsd(1.0))
    }
}
