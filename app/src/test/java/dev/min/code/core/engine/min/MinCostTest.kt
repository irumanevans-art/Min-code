package dev.min.code.core.engine.min

import dev.min.agent.llm.Cost
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelCharged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 花费：内核按请求记在每个会话的 modelCosts 里，Min 对整棵树求和，币种原样，有请求没价格就不显示。 */
class MinCostTest {
    private val root = SessionId("s_root")
    private val child = SessionId("s_child")
    private val grandchild = SessionId("s_grand")
    private val other = ModelRef("test", "other")
    private val tokens = Usage(inputTokens = 100)

    private fun usd(amount: Double) = ModelCharged(T_MODEL, tokens, amount, "USD")
    private fun cny(amount: Double) = ModelCharged(other, tokens, amount, "CNY")
    private fun unpriced() = ModelCharged(other, tokens)

    @Test fun `no spend means no cost`() {
        val cost = treeCostOf(root, mapOf(root to foldOf()))
        assertNull(cost.totalLabel())
        assertNull(cost.subagentLabel())
    }

    @Test fun `the root's own spend is the total and there is no sub-agent line`() {
        val cost = treeCostOf(root, mapOf(root to foldOf(usd(0.0123), usd(0.01))))
        assertEquals("$0.0223", cost.totalLabel())
        assertNull("子 agent 没花钱就没有这一行", cost.subagentLabel())
    }

    @Test fun `the whole tree adds up and the sub-agents' share is shown on its own`() {
        val cost = treeCostOf(
            root,
            mapOf(
                root to foldOf(usd(0.01)),
                child to foldOf(usd(0.02), id = "s_child"),
                grandchild to foldOf(usd(0.03), id = "s_grand"),
            ),
        )
        assertEquals("$0.0600", cost.totalLabel())
        assertEquals("孙会话也算子 agent 的一份", "$0.0500", cost.subagentLabel())
    }

    @Test fun `two currencies are written side by side and never converted`() {
        val cost = treeCostOf(root, mapOf(root to foldOf(usd(0.5)), child to foldOf(cny(2.0), id = "s_child")))
        assertEquals("$0.5000 + ¥2.00", cost.totalLabel())
        assertEquals("¥2.00", cost.subagentLabel())
    }

    @Test fun `a request without a price hides the figure rather than showing part of it`() {
        val cost = treeCostOf(root, mapOf(root to foldOf(usd(0.5)), child to foldOf(unpriced(), id = "s_child")))
        assertNull(cost.totalLabel())
        assertNull(cost.subagentLabel())
    }

    @Test fun `a sub-agent that is not online contributes nothing instead of failing`() {
        // 映射器只收到在线节点；卸下的后代不在表里（它们的钱要等重开才看得到）
        val cost = treeCostOf(root, mapOf(root to foldOf(usd(0.25))))
        assertEquals("$0.2500", cost.totalLabel())
    }

    @Test fun `cost text keeps the currency as priced and widens the decimals below one unit`() {
        assertEquals("$0.0123", formatCost(Cost(0.0123, "USD")))
        assertEquals("¥12.35", formatCost(Cost(12.345, "CNY")))
        assertEquals("EUR 0.5000", formatCost(Cost(0.5, "EUR")))
        assertEquals("$1.00", formatCost(Cost(1.0, "USD")))
    }
}
