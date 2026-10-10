package dev.min.agent.llm

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 计费点折总账：金额照事件冻结的累计、跨币种分列不换算、无价格如实计数 */
class SessionCostTest {
    private val m1 = ModelRef("p", "one")
    private val m2 = ModelRef("p", "two")

    private fun charged(model: ModelRef, amount: Double?, currency: String?, usage: Usage = Usage(inputTokens = 10)) =
        ChargedCost(model, usage, amount, currency)

    @Test
    fun `multi-currency totals stay split and allUsd only when everything is USD`() {
        val totals = totalCost(listOf(charged(m1, 1.0, "USD"), charged(m2, 7.0, "CNY"), charged(m1, 0.5, "USD")))
        assertEquals(mapOf("USD" to 1.5, "CNY" to 7.0), totals.byCurrency)
        assertFalse(totals.allUsd)
        assertEquals(1.5, totals.usd, 1e-9)
        val usd = totalCost(listOf(charged(m1, 1.0, "USD")))
        assertTrue(usd.allUsd)
    }

    @Test
    fun `unpriced points are counted, not treated as zero`() {
        val totals = totalCost(listOf(charged(m1, null, null), charged(m2, 1.0, "USD")))
        assertEquals(1, totals.unpricedRequests)
        // 无价格的不进任何币种：byCurrency 只有真量得出的
        assertEquals(mapOf("USD" to 1.0), totals.byCurrency)
        assertFalse(totals.allUsd)
    }

    @Test
    fun `per-model usage aggregates requests, tokens and cache hit rate`() {
        val totals = totalCost(
            listOf(
                charged(m1, 1.0, "USD", Usage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 30)),
                charged(m1, 1.0, "USD", Usage(inputTokens = 10, cacheWriteTokens = 10)),
                charged(m2, 2.0, "USD", Usage(inputTokens = 100)),
            ),
        )
        val one = totals.perModel.getValue(m1)
        assertEquals(2, one.requests)
        assertEquals(20, one.usage.inputTokens)
        assertEquals(5, one.usage.outputTokens)
        assertEquals(30, one.usage.cacheReadTokens)
        // 缓存读 30 ÷（输入 20 + 缓存写 10 + 缓存读 30）
        assertEquals(30.0 / 60.0, one.cacheReadRatio!!, 1e-9)
        assertEquals(100, totals.perModel.getValue(m2).usage.inputTokens)
        // 输入侧为 0 时命中率没有意义
        assertNull(ModelUsage(1, Usage(outputTokens = 3)).cacheReadRatio)
    }

    @Test
    fun `small amounts keep significant digits, large ones two decimals`() {
        assertEquals("0.000432", formatCostAmount(0.0004321))
        assertEquals("0", formatCostAmount(0.0))
        assertEquals("0.01", formatCostAmount(0.01))
        assertEquals("1.23", formatCostAmount(1.234))
        assertEquals("12.35", formatCostAmount(12.3456))
    }
}
