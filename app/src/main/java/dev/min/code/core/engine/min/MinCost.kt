package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.activeModel
import dev.min.agent.llm.Cost
import dev.min.agent.llm.ModelPricing
import dev.min.agent.llm.cost
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.Usage
import java.util.Locale

/**
 * 一个 Min 会话树花了多少钱（B7：目录有官方标价，花费可以显示了）。
 *
 * 价格按**模型**不同，而内核的 [SessionState.usage] 只是累计总量、不分模型——中途换过模型（含回退链换到的回退模型、
 * 子 agent 用别的模型）时，不能拿现在的价格去乘总量。所以这里**盯着每一帧状态的增量**：这一帧比上一帧多出来的用量，
 * 记到「那一刻在用的模型」头上（[activeModel]：回退链生效中是回退模型，否则是会话设置里的）。
 *
 * 局限，如实写：账本只活在进程里。重新打开的会话，历史用量不知道当年是哪个模型，整份记给此刻的模型——
 * 单一模型的会话（绝大多数）是准的，中途换过模型又重开过的会话是估算。要精确得由内核按请求记模型（缺的接口，见汇报）。
 * 网页提取（`WebExtractionCompleted`）用的提取模型也不单独记，归在当时的会话模型上。
 *
 * 一帧一帧喂，单线程用（状态流是 conflate + 单个后台调度，天然串行）。
 */
class MinCostLedger(private val pricing: (ModelRef) -> ModelPricing?) {
    private val seen = HashMap<SessionId, Usage>()
    private val byModel = LinkedHashMap<ModelRef, Usage>()

    /** 喂一帧：根与在线后代各自此刻的状态 */
    fun observe(nodes: Map<SessionId, SessionState>) {
        for ((id, state) in nodes) {
            val total = state.usage + (state.turn?.usage ?: Usage())
            val delta = total - (seen[id] ?: Usage())
            seen[id] = total
            if (delta != Usage()) byModel[state.activeModel] = (byModel[state.activeModel] ?: Usage()) + delta
        }
    }

    /**
     * 界面上那句花费，按币种原样（不换汇）；同一会话里用了两种币种标价的模型就并排写（`$0.0123 + ¥0.045`）。
     * 没有花过钱返回 null；**用过的模型里有一个没有价格也返回 null**——只显示一部分花费比不显示更误导。
     */
    fun label(): String? {
        val spent = byModel.filterValues { it != Usage() }
        if (spent.isEmpty()) return null
        val perCurrency = LinkedHashMap<String, Double>()
        for ((model, usage) in spent) {
            val price = pricing(model) ?: return null
            val c = cost(price, usage)
            perCurrency[c.currency] = (perCurrency[c.currency] ?: 0.0) + c.amount
        }
        return perCurrency.entries.joinToString(" + ") { formatCost(Cost(it.value, it.key)) }
    }
}

private operator fun Usage.minus(other: Usage) = Usage(
    inputTokens = (inputTokens - other.inputTokens).coerceAtLeast(0),
    outputTokens = (outputTokens - other.outputTokens).coerceAtLeast(0),
    cacheReadTokens = (cacheReadTokens - other.cacheReadTokens).coerceAtLeast(0),
    cacheWriteTokens = (cacheWriteTokens - other.cacheWriteTokens).coerceAtLeast(0),
)

/**
 * 金额文字：常见币种用符号（USD `$`、CNY `¥`），其余写币种代码；一块钱以内给四位小数（几分钱的会话要看得出差别），
 * 以上两位。不换汇、不按区域改格式（币种是标价的事，不是用户所在地的事）。
 */
fun formatCost(cost: Cost): String {
    val number = String.format(Locale.ROOT, if (cost.amount < 1.0) "%.4f" else "%.2f", cost.amount)
    return when (cost.currency) {
        "USD" -> "$$number"
        "CNY" -> "¥$number"
        else -> "${cost.currency} $number"
    }
}
