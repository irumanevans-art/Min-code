package dev.min.agent.llm

import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Usage

/**
 * 一笔已结算的计费点：`model_charged` 事件的镜像。金额与币种在**请求当时**冻结进日志（价格不重算），
 * [amount] / [currency] 缺失 = 那个请求的模型没有可靠价格，花费未知（不是 0）
 */
data class ChargedCost(val model: ModelRef, val usage: Usage, val amount: Double?, val currency: String?)

/** 一组计费点折出的总账 */
data class CostTotals(
    /** 按币种累计的花费：跨币种分列，不做汇率换算 */
    val byCurrency: Map<String, Double>,
    /** 花费未知的请求次数：对不上账，界面与 -p 结果都要说明 */
    val unpricedRequests: Int,
    /** 每个模型的累计用量与请求次数（/cost 与 App 的「各模型 token 用量」） */
    val perModel: Map<ModelRef, ModelUsage>,
) {
    /** 全部花费都是 USD 计价（且没有未知请求）时才真：CC 的 total_cost_usd 只在这种情况可输出 */
    val allUsd: Boolean get() = unpricedRequests == 0 && byCurrency.keys.all { it == "USD" }

    /** USD 部分的合计；没花过 USD 就是 0 */
    val usd: Double get() = byCurrency["USD"] ?: 0.0
}

/** 一个模型的累计用量（计费点按模型聚合而来） */
data class ModelUsage(
    val requests: Int,
    val usage: Usage,
    /** 这个模型按币种累计的花费（跨币种分列，不换算）；没有价格的请求不进来，记在 [unpriced] */
    val amounts: Map<String, Double> = emptyMap(),
    /** 花费未知的请求次数 */
    val unpriced: Int = 0,
) {
    /** 缓存命中率：缓存读 ÷ 输入侧总量（输入 + 缓存读写）。输入侧为 0 时没有意义，返回 null */
    val cacheReadRatio: Double?
        get() {
            val inputs = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
            return if (inputs > 0) usage.cacheReadTokens.toDouble() / inputs else null
        }
}

/**
 * 把计费点折成总账：金额照事件里冻结的累计、按模型聚合用量。纯函数，供 /cost、`-p` 结果与
 * `--max-budget-usd` 的运行账本（[dev.min.agent.kernel.RunBudget]）共用，谁算都是同一份。
 */
fun totalCost(points: List<ChargedCost>): CostTotals {
    val byCurrency = LinkedHashMap<String, Double>()
    var unpriced = 0
    val perModel = LinkedHashMap<ModelRef, ModelUsage>()
    for (point in points) {
        if (point.amount != null && point.currency != null) {
            byCurrency[point.currency] = (byCurrency[point.currency] ?: 0.0) + point.amount
        } else {
            unpriced++
        }
        val prev = perModel[point.model]
        val priced = point.amount != null && point.currency != null
        perModel[point.model] = ModelUsage(
            requests = (prev?.requests ?: 0) + 1,
            usage = (prev?.usage ?: Usage()) + point.usage,
            amounts = if (priced) (prev?.amounts.orEmpty() + (point.currency!! to ((prev?.amounts?.get(point.currency) ?: 0.0) + point.amount!!))) else prev?.amounts.orEmpty(),
            unpriced = (prev?.unpriced ?: 0) + if (priced) 0 else 1,
        )
    }
    return CostTotals(byCurrency, unpriced, perModel)
}

/** 花费数字的展示粒度：小额多留几位有效数字（hook 一次几分钱也要看得清），大额两位小数够用 */
fun formatCostAmount(value: Double): String = when {
    value in 1.0E-4..0.01 ->
        // 0.0004321 → "0.000432"：六位小数去掉尾零，够小钱看清楚
        String.format(java.util.Locale.ROOT, "%.6f", value).trimEnd('0')
    value in 0.0..0.01 -> "0"
    else -> String.format(java.util.Locale.ROOT, "%.2f", value)
}
