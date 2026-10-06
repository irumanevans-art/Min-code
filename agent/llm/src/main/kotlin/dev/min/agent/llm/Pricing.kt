package dev.min.agent.llm

import dev.min.agent.protocol.Usage
import kotlinx.serialization.Serializable

/**
 * 模型标价（每百万 token）。数值与 [currency] 都照供应商官方定价页**原样**转录（国内厂商多为 CNY），
 * 不做汇率换算——花费只在同币种内比较；逐条出处见 `agent/llm/src/main/resources/dev/min/agent/llm/catalog/SOURCES.md`
 * 的「pricing」节。目录里查不到可靠价格的模型不写这个字段（ModelProfile.pricing 为 null）。
 *
 * **省略 = 按输入价算**：官方没有单独标价的缓存字段就省略（不要猜价），[cost] 计费时回退用输入价。
 * 两种情况落到这条回退：隐式缓存的供应商（zhipu / deepseek）根本没有写计费，写 token 恒为 0，回退不触发；
 * 有缓存机制但定价页没写价的（MiniMax-M3 的写），回退值可能与实收有差——差多少记录在 SOURCES.md「拿不准」。
 */
@Serializable
data class ModelPricing(
    /** 标价币种，照官方定价页原样（"USD" / "CNY"），不换算 */
    val currency: String,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    /** 缓存写入价（Anthropic 的 5 分钟档，1.25x）。省略 = 官方未单列，按 [inputPerMTok] 计 */
    val cacheWritePerMTok: Double? = null,
    /**
     * 缓存写入 1 小时 TTL 档（Anthropic 的 2x）。Min 的 Usage 不区分两种 TTL，[cost] 一律按 5 分钟档计
     * （Min 发的 cache_control 默认 TTL 也是 5m）；这个字段只是把官方价留住，供 Usage 细分后或人工对账用
     */
    val cacheWrite1hPerMTok: Double? = null,
    /** 缓存命中价。省略 = 官方未单列（命中部分按普通输入收），按 [inputPerMTok] 计 */
    val cacheReadPerMTok: Double? = null,
    /** 按输入长度分档的长上下文价（如 MiniMax-M3 的 >512k 档）。没有就空 */
    val tiers: List<PricingTier> = emptyList(),
    /** 最近一次在官方页核对价格的日期（ISO 格式） */
    val asOf: String,
)

/**
 * [ModelPricing.tiers] 的一档：本次请求的输入 token 总数**严格大于** [aboveInputTokens] 时，
 * 整条请求（含输出与缓存）都用这一档的价。档是一套自洽的价目：省略的缓存字段按**本档**输入价算，
 * 不跨档借用基础价的缓存价（MiniMax 的长上下文档是全线涨价）。
 */
@Serializable
data class PricingTier(
    val aboveInputTokens: Long,
    val inputPerMTok: Double,
    val outputPerMTok: Double,
    val cacheWritePerMTok: Double? = null,
    val cacheWrite1hPerMTok: Double? = null,
    val cacheReadPerMTok: Double? = null,
)

/** 一次请求的花费：[amount] 以 [currency] 计（照标价币种原样，不换算） */
data class Cost(val amount: Double, val currency: String)

/**
 * 按一次请求的用量与标价算花费（纯函数，供 total_cost 与预算截停用）。
 *
 * 分档按「本次请求的输入 token 总数」选（input + cacheRead + cacheWrite，任务单口径）；各家定价页口径
 * 不全一致：MiniMax 只写「≤512k / >512k input」，缓存 token 计不计入文档未写。边界值归低档
 * （512 000 整按低价档计，512 001 才进高档，照 MiniMax「≤512k」的字面）。多档时取满足条件的最大边界。
 */
fun cost(pricing: ModelPricing, usage: Usage): Cost {
    val totalInput = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
    val tier = pricing.tiers.filter { totalInput > it.aboveInputTokens }.maxByOrNull { it.aboveInputTokens }
    val input = tier?.inputPerMTok ?: pricing.inputPerMTok
    val output = tier?.outputPerMTok ?: pricing.outputPerMTok
    val write = if (tier == null) pricing.cacheWritePerMTok else tier.cacheWritePerMTok
    val read = if (tier == null) pricing.cacheReadPerMTok else tier.cacheReadPerMTok
    val mTok = 1_000_000.0
    return Cost(
        amount = usage.inputTokens / mTok * input +
            usage.outputTokens / mTok * output +
            usage.cacheWriteTokens / mTok * (write ?: input) +
            usage.cacheReadTokens / mTok * (read ?: input),
        currency = pricing.currency,
    )
}
