package dev.min.code.core.engine

/**
 * 隔了很久回来的会话：重开一个又老又大的会话时「从摘要续接 / 完整续接」的建议（[EngineCapabilities.resumeAdvice]），
 * 以及会话回顾（[EngineCapabilities.recap]，一行「刚才在做什么、做到哪、下一步」）。只有 Min 有——
 * CC 的上下文与压缩在它自己的 CLI 里，App 管不到。
 */

/**
 * 重开时给人的建议。[idleMs] 会话静置了多久，[contextTokens] 此刻上下文的估算 token，
 * [rereadCost] 完整续接重读它们的估算花费（已按标价币种写成文字；目录里没有这个模型的价格就是 null——不编数字）
 */
data class ResumeAdviceView(val idleMs: Long, val contextTokens: Long, val rereadCost: String?)

/** 一次回顾的结局。失败与跳过都不影响会话，[Skipped] / [Failed] 的原因给人看 */
sealed interface RecapOutcome {
    data class Generated(val text: String) : RecapOutcome
    data class Skipped(val reason: String) : RecapOutcome
    data class Failed(val reason: String) : RecapOutcome
}
