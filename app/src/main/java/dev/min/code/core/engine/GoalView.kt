package dev.min.code.core.engine

/**
 * 会话当前的 `/goal`（Min）：任务条上的一枚标记、点开的详情都读它。
 * 没有进行中的目标为 null（已达成的不在这里，那是历史，不占任务条）。
 *
 * @param evaluations 已评估几次（CC：turns）
 * @param lastReason 最近一次评估的理由；也是下一回合的指引，给人看
 * @param pausedReason 暂停时的原因（[GoalStatus.Paused]）
 */
data class GoalView(
    val condition: String,
    val evaluations: Int,
    val lastReason: String?,
    val status: GoalStatus,
    val pausedReason: String?,
)

/** 进行中 / 等后台工作（推迟评估）/ 已暂停（错误，下一句话恢复） */
enum class GoalStatus { Active, Deferred, Paused }
