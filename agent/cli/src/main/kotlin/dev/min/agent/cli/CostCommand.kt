package dev.min.agent.cli

import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.llm.ChargedCost
import dev.min.agent.llm.formatCostAmount
import dev.min.agent.llm.totalCost
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.ModelCharged

/**
 * `/cost`（交互 CLI）：本会话的花费——按币种分列（不换算汇率）、各模型的 token 用量、缓存命中率。
 * 计费点从日志的 `model_charged` 折出（金额在请求当时冻结），覆盖会话一生；树上在线的子会话
 * （子 agent / hook agent 留下的日志 / 团队成员）各算各的，按根归到本会话。不在树上的不算。
 */
internal suspend fun costReport(app: DesktopApp, hub: SessionHub, runtime: SessionRuntime): String {
    val rootId: SessionId = runtime.state.value.origin?.root ?: runtime.sessionId
    val children = hub.online.value.asSequence()
        .filter { it != rootId }
        .mapNotNull { hub.get(it) }
        .filter { it.state.value.origin?.root == rootId }
    // sequence 的 lambda 不挂起：先收齐会话再逐个读日志（peek，只读不打扰写者）
    val points = mutableListOf<ChargedCost>()
    for (rt in listOf(runtime) + children.toList()) {
        for (record in app.records(rt.sessionId, 1)) {
            (record.event as? ModelCharged)?.let { points += ChargedCost(it.model, it.usage, it.amount, it.currency) }
        }
    }
    if (points.isEmpty()) return "本会话还没有模型请求，没有花费。"
    val totals = totalCost(points)
    val lines = mutableListOf<String>()
    if (totals.byCurrency.isEmpty()) {
        lines += "本会话的花费算不出来：${totals.unpricedRequests} 次请求的模型在目录里没有价格。"
    } else {
        val currencies = totals.byCurrency.entries.joinToString("、") { (c, v) -> "$c ${formatCostAmount(v)}" }
        lines += "本会话花费：$currencies"
        if (totals.unpricedRequests > 0) {
            lines += "另有 ${totals.unpricedRequests} 次请求的模型没有价格，量不出花费（不是 0）。"
        }
    }
    lines += "各模型用量："
    for ((ref, usage) in totals.perModel) {
        val u = usage.usage
        val hit = usage.cacheReadRatio?.let { "，缓存命中 ${"%.0f".format(it * 100)}%" } ?: ""
        lines += "  ${ref.providerId}/${ref.modelId}：请求 ${usage.requests} 次，" +
            "输入 ${u.inputTokens}、输出 ${u.outputTokens}、缓存写 ${u.cacheWriteTokens} / 读 ${u.cacheReadTokens} tok$hit"
    }
    return lines.joinToString("\n")
}
