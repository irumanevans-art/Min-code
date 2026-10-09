package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionState
import dev.min.agent.llm.Cost
import dev.min.agent.protocol.SessionId
import java.util.Locale

/**
 * 一棵 Min 会话树花了多少钱（B7 起目录有官方标价，花费可以显示；B10 起整棵树求和）。
 *
 * 钱是内核在每个模型请求完成时按**那个请求用的模型**的标价记下的（`ModelCharged`，折叠进 [SessionState.modelCosts]：币种 → 金额），
 * 子 agent、团队成员、auto 审查、网页提取的请求各记在各自会话上。这里不再自己猜价格，只对树求和——以前的账本盯着用量增量、
 * 按「此刻在用的模型」估价，中途换过模型又重开的会话是估算；现在是内核的账，重开后也一样。
 *
 * 币种原样、不换汇：同一棵树里用了两种币种标价的模型就并排写（`$0.0123 + ¥0.045`）。
 */
data class TreeCost(
    /** 整棵树（根 + 在线后代）按币种合计 */
    val total: Map<String, Double>,
    /** 其中子 agent（含团队成员）的部分 */
    val subagents: Map<String, Double>,
    /** 树里有请求没有价格：花费只知道一部分 */
    val unpriced: Boolean,
) {
    /** 顶栏与设置里那句总花费。没花过钱返回 null；**有请求没有价格也返回 null**——只显示一部分花费比不显示更误导 */
    fun totalLabel(): String? = costLabelOf(total).takeUnless { unpriced }

    /** 其中子 agent 花的（设置里的用量分行）。没有子 agent 花钱、或总数不全时返回 null */
    fun subagentLabel(): String? = costLabelOf(subagents).takeUnless { unpriced }
}

/** 对树求和。[nodes] 是根与它在线的全部后代的状态（映射器的输入）；不在线的后代看不到，它们的钱不在里面 */
fun treeCostOf(root: SessionId, nodes: Map<SessionId, SessionState>): TreeCost {
    val total = LinkedHashMap<String, Double>()
    val subagents = LinkedHashMap<String, Double>()
    var unpriced = false
    for ((id, state) in nodes) {
        if (state.unpricedModelCalls > 0) unpriced = true
        for ((currency, amount) in state.modelCosts) {
            total[currency] = (total[currency] ?: 0.0) + amount
            if (id != root) subagents[currency] = (subagents[currency] ?: 0.0) + amount
        }
    }
    return TreeCost(total, subagents, unpriced)
}

/** 按币种写成一句：`$0.0123`、`$0.0123 + ¥0.045`；全是 0 / 空返回 null */
fun costLabelOf(byCurrency: Map<String, Double>): String? =
    byCurrency.filterValues { it > 0.0 }.entries.joinToString(" + ") { formatCost(Cost(it.value, it.key)) }.ifEmpty { null }

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
