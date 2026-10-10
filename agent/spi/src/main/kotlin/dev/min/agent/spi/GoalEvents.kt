package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * `/goal` 进日志的形状（CC 文档 goal 页：`/goal` 是会话级的 prompt 型 Stop hook 封装）。
 *
 * 目标是会话状态：设置、评估结论、清除都落成事件，[SessionState.goal] 由它们折叠出来，重开会话后目标还在。
 * 一个会话同时最多一个目标；新设的目标替换旧的。
 *
 * 格式纪律同 SessionEvent.kt：变体与字段只加不改不删，默认值发布后不能改。
 */

/** 评估的结论。[NotMet] 继续干；[Met] 与 [Impossible] 都结束目标（后者照原文记为失败） */
@Serializable
enum class GoalVerdict {
    @SerialName("not_met") NotMet,
    @SerialName("met") Met,
    @SerialName("impossible") Impossible,
}

/** 目标为什么结束：达成、判定不可能、用户清掉、或一个需要用户修的错误（照 CC：认证失效、额度用完、上下文溢出等） */
@Serializable
enum class GoalEndReason {
    @SerialName("achieved") Achieved,
    @SerialName("impossible") Impossible,
    @SerialName("user") User,
    @SerialName("error") Error,
}

/** 设置目标（替换已有目标）。[condition] 是用户给的完成条件原文 */
@Serializable
@SerialName("goal_set")
data class GoalSet(val condition: String) : SessionEvent

/** 一次评估的结论；[reason] 给人看，也作为下一回合的指引交给模型 */
@Serializable
@SerialName("goal_evaluated")
data class GoalEvaluated(val verdict: GoalVerdict, val reason: String? = null) : SessionEvent

/** 结束当前目标。达成 / 不可能时折叠成「最近达成」的记录，供 `/goal` 无参数时显示 */
@Serializable
@SerialName("goal_cleared")
data class GoalCleared(val reason: GoalEndReason) : SessionEvent

/** 后台工作把目标挂起、到点的一次 check-in（[GoalCheckInPolicy] 决定何时发）。计数在用户再说话时清零 */
@Serializable
@SerialName("goal_check_in")
data class GoalCheckIn(val count: Int) : SessionEvent

/** 带着进行中的目标恢复会话：目标延续，但回合数与 check-in 计数从这里重新数（照 CC：恢复后计数、计时、花费基线都重置） */
@Serializable
@SerialName("goal_resumed")
data class GoalResumed(val atMs: Long) : SessionEvent
