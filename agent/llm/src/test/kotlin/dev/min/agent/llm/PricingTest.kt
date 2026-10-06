package dev.min.agent.llm

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.Usage
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * cost(pricing, usage) 的计费口径与 ModelProfile.pricing 的读取。
 * 数据出处见 catalog/SOURCES.md「pricing」；价格数值本身不在本类里抄第二遍（随包目录加载由 CatalogTest 把关）。
 */
class PricingTest {

    /** 任务单口径：官方没单列缓存价的字段按输入价算（Pricing.kt 注释里的回退规则） */
    @Test
    fun `cache tokens without a listed price bill at the input price`() {
        val pricing = ModelPricing(currency = "USD", inputPerMTok = 2.0, outputPerMTok = 10.0, asOf = "2026-10-07")
        val c = cost(
            pricing,
            Usage(inputTokens = 1_000_000, outputTokens = 100_000, cacheReadTokens = 500_000, cacheWriteTokens = 1_000_000),
        )
        // 输入 1M×$2 + 输出 0.1M×$10 + 读 0.5M×$2（回退） + 写 1M×$2（回退）
        assertEquals(6.0, c.amount, 1e-9)
    }

    /** 分档边界与档价自洽：档是一套完整的价，省略的缓存价按本档输入价算 */
    @Test
    fun `tiers switch strictly above the boundary and carry their own prices`() {
        val pricing = ModelPricing(
            currency = "USD",
            inputPerMTok = 0.3,
            outputPerMTok = 1.2,
            cacheReadPerMTok = 0.06,
            tiers = listOf(PricingTier(aboveInputTokens = 512_000, inputPerMTok = 0.6, outputPerMTok = 2.4, cacheReadPerMTok = 0.12)),
            asOf = "2026-10-07",
        )
        // 边界值本身归低档（MiniMax 的口径是「≤512k」低价）
        assertEquals(0.1536, cost(pricing, Usage(inputTokens = 512_000)).amount, 1e-9)
        // 512 001 才进高档
        assertEquals(0.3072006, cost(pricing, Usage(inputTokens = 512_001)).amount, 1e-9)
        // 缓存 token 计入分档总数：2000 + 510000 + 1 = 512 001 → 高档；写价档里省略，按高档输入价 0.6 计
        val tiered = cost(pricing, Usage(inputTokens = 2_000, cacheReadTokens = 510_000, cacheWriteTokens = 1))
        assertEquals(0.0624006, tiered.amount, 1e-9)
    }

    /** 币种照标价原样返回，不做换算 */
    @Test
    fun `currency passes through as priced`() {
        val pricing = ModelPricing(
            currency = "CNY", inputPerMTok = 8.0, outputPerMTok = 28.0, cacheReadPerMTok = 2.0, asOf = "2026-10-07",
        )
        val c = cost(pricing, Usage(inputTokens = 1_000_000, outputTokens = 1_000_000))
        assertEquals("CNY", c.currency)
        assertEquals(36.0, c.amount, 1e-9)
    }

    /** Usage 不分 5m/1h 写入：一律按 5m 档计（cacheWrite1hPerMTok 当前只是数据） */
    @Test
    fun `the 1h cache write price stays data until usage distinguishes TTLs`() {
        val pricing = ModelPricing(
            currency = "USD", inputPerMTok = 4.0, outputPerMTok = 20.0,
            cacheWritePerMTok = 5.0, cacheWrite1hPerMTok = 8.0, asOf = "2026-10-07",
        )
        assertEquals(5.0, cost(pricing, Usage(cacheWriteTokens = 1_000_000)).amount, 1e-9)
    }

    /** 旧目录条目没有 pricing 字段照常读（为 null）；带 pricing 的条目同一个解码器也能读 */
    @Test
    fun `catalog entries without pricing still load`() {
        val old = AgentJson.decodeFromString(
            ListSerializer(ModelProfile.serializer()),
            """[{"ref":{"providerId":"anthropic","modelId":"m"},"family":"claude","contextWindow":1000,"maxOutputTokens":100}]""",
        )
        assertEquals(1, old.size)
        assertNull(old.single().pricing)

        val withPricing = AgentJson.decodeFromString(
            ListSerializer(ModelProfile.serializer()),
            """[{"ref":{"providerId":"zhipu","modelId":"m"},"family":"glm","contextWindow":1000,"maxOutputTokens":100,""" +
                """"pricing":{"currency":"CNY","inputPerMTok":8.0,"outputPerMTok":28.0,"cacheReadPerMTok":2.0,"asOf":"2026-10-07"}}]""",
        )
        assertEquals("CNY", withPricing.single().pricing?.currency)
        assertEquals(28.0, withPricing.single().pricing?.outputPerMTok)
    }
}
