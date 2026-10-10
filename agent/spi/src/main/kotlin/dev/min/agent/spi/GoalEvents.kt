package dev.min.agent.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * `/goal` 进日志的形状（CC 文档 goal 页：`/goal` 是会话级的 prompt 型 Stop hook 封装）。
 *
 * 目标是会话状态：设置、评估结论、清除、暂停、check-in 都落成事件，[SessionState.goal] 由它们折叠出来，重开会话后目标还在。
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

/**
 * 目标为什么结束：达成、判定不可能、用户清掉、或一个需要用户修的错误（照 CC：认证失效、额度用完、上下文溢出、模型不可用）。
 * [Error] 即「不可恢复的错误」，清除时 [GoalCleared.detail] 写原因
 */
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

/**
 * 一次评估的结论；[reason] 给人看，也作为下一回合的指引交给模型。
 * [toolUse]：上一次评估之后、到这一次之间，模型有没有调过工具（照 CC 的「连续几回合没有工具调用就停下」计数用；默认 false 不落盘）
 */
@Serializable
@SerialName("goal_evaluated")
data class GoalEvaluated(val verdict: GoalVerdict, val reason: String? = null, val toolUse: Boolean = false) : SessionEvent

/** 结束当前目标。达成 / 不可能时折叠成「最近达成」的记录，供 `/goal` 无参数时显示；[detail] 是不可恢复错误的原因（其它结局为空） */
@Serializable
@SerialName("goal_cleared")
data class GoalCleared(val reason: GoalEndReason, val detail: String? = null) : SessionEvent

/**
 * 后台工作把目标挂起的时刻（第一次 check-in 的计时从这里数）。已挂起时不再写。
 * 评估、清除、check-in、恢复都会把挂起清掉
 */
@Serializable
@SerialName("goal_deferred")
data class GoalDeferred(val atMs: Long) : SessionEvent

/**
 * 一次 check-in（[GoalCheckInPolicy] 决定何时发）。[count] 是本目标第几次 check-in；[atMs] 是这一次的时刻（下一次的计时从它数）；
 * [idle] 为 true 表示它是空闲时开的一回合（这种有每目标 3 次的上限，照 CC：第三次时说明空闲 check-in 暂停到用户下一句话）。
 * 用户再说话时计数清零
 */
@Serializable
@SerialName("goal_check_in")
data class GoalCheckIn(val count: Int, val atMs: Long = 0L, val idle: Boolean = false) : SessionEvent

/**
 * 目标因错误暂停（照 CC：速率限制、用量限制，或重试 3 次仍失败）。目标还在、不再评估，用户下一句话恢复。[reason] 给人看
 */
@Serializable
@SerialName("goal_paused")
data class GoalPaused(val reason: String) : SessionEvent

/** 带着进行中的目标恢复会话：目标延续，但回合数与 check-in 计数从这里重新数（照 CC：恢复后计数、计时、花费基线都重置） */
@Serializable
@SerialName("goal_resumed")
data class GoalResumed(val atMs: Long) : SessionEvent
