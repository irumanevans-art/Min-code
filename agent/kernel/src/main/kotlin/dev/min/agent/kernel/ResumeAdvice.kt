package dev.min.agent.kernel

import dev.min.agent.llm.Cost
import dev.min.agent.llm.cost
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.catchingNonCancellation

/**
 * 「从摘要续接」的触发阈值。照 CC：会话空闲超过约 1 小时**并且**上下文超过 100,000 token 才建议——
 * 提示缓存的存活时间远短于 1 小时，缓存早过期了，完整续接要把整段对话按全价重读一遍；两个条件缺一不可，
 * 隔得久但对话很短、或对话很长但刚离开，重读都不贵
 */
data class ResumeThresholds(
    val idleMs: Long = 60 * 60 * 1000L,
    val contextTokens: Long = 100_000,
)

/**
 * 重开会话时给宿主的建议：[idleMs] 是会话已经静置多久，[contextTokens] 是此刻模型视图的估算 token，
 * [rereadCost] 是完整续接这一次要重读它们的估算花费（目录里有这个模型的价格才有，没价格就是 null——不编数字）。
 * 宿主让人选「从摘要续接」（[continueFromSummary]）还是「完整续接」（什么都不做）
 */
data class ResumeAdvice(val idleMs: Long, val contextTokens: Long, val rereadCost: Cost?)

/** 重开那一刻的基线：之后会话有了新动静（新回合、新输入）建议就过期了 */
internal data class ResumeBasis(val quietSinceMs: Long, val lastTurn: TurnId?)

/**
 * 重开（[SessionRuntime.open]）后问一次：这个会话值不值得先压成摘要再续。没有建议返回 null：
 * 不是重开出来的（新建的）、子 agent、已经有了新动静、或没同时过两道阈值。
 *
 * 空闲时间取重开前日志最后一条事件的时刻（重开时写的恢复事件不算「动静」）；
 * 花费按「全部重新写入缓存」估（缓存写价，没单列写价的模型回退到输入价）——是保守的上限，不是账单。
 * 上下文 token 取内核自己的估算（[CompactionPlanner.estimate]）；没有上一次请求的用量可参考时只算对话本身，不含系统提示与工具表，会略低。
 */
fun SessionRuntime.resumeAdvice(): ResumeAdvice? {
    val basis = resumeBasis ?: return null
    val s = state.value
    if (s.origin != null || s.turn != null || s.lastTurn?.turnId != basis.lastTurn) return null
    val limits = deps.resumeThresholds
    val idle = deps.clock.nowMs() - basis.quietSinceMs
    if (idle <= limits.idleMs) return null
    val tokens = CompactionPlanner.estimate(s, 0)
    if (tokens <= limits.contextTokens) return null
    val pricing = catchingNonCancellation { deps.models.resolve(s.settings.effectiveModel).profile.pricing }.getOrNull()
    return ResumeAdvice(idle, tokens, pricing?.let { cost(it, Usage(cacheWriteTokens = tokens)) })
}

/**
 * 选了「从摘要续接」：跑一次现有的压缩（与 `/compact` 同一条路），等它结束。返回 true = 真的压缩成了；
 * 有回合在跑、没有可压缩的对话、模型失败、被 PreCompact hook 拦下都返回 false（会话照原样完整续接，什么都没坏）
 */
suspend fun SessionRuntime.continueFromSummary(): Boolean {
    if (!compact()) return false
    awaitIdle()
    val ended = state.value.lastTurn
    return ended?.trigger == TurnTrigger.Compact && ended.outcome == TurnOutcome.Completed &&
        state.value.compaction != null
}
