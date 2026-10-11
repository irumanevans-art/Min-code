package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalPaused
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent

/*
 * `/goal` 的命令与生命周期：设、清、恢复、出错时清或暂停。纯判定（哪些错误算不可恢复）单独放在这里好测。
 */

/** `/goal <条件>` 的结果：被拒（附原因）或已设（[startedTurn] 表示空闲时开了回合，忙时只是设上、回合结束时才评估） */
sealed interface GoalSetResult {
    data class Refused(val reason: String) : GoalSetResult
    data class Set(val startedTurn: Boolean) : GoalSetResult
}

/**
 * 设目标（CC：「Setting a goal starts a turn immediately, with the condition itself as the directive」）。
 * 空闲（没有回合、没有排着的输入）时把条件本身当作用户输入开一回合；忙时只设上，当前回合结束时开始评估。
 * hooks 被整体禁用时拒绝（[goalRefusal]），不静默不做。
 */
suspend fun SessionRuntime.setGoal(condition: String): GoalSetResult {
    goalRefusal(state.value.hooksDisabledBy)?.let { return GoalSetResult.Refused(it) }
    val idle = isQuiescent()
    commit(listOf(GoalSet(condition)), Durability.Sync)
    if (!idle) return GoalSetResult.Set(startedTurn = false)
    send(ClientMsgId("goal-set:${deps.ids.next("goal")}"), listOf(TextPart(condition)))
    return GoalSetResult.Set(startedTurn = true)
}

/** 清掉进行中的目标（用户手动）。没有目标返回 false（界面说 No goal set） */
suspend fun SessionRuntime.clearGoal(): Boolean =
    commitIf(listOf(GoalCleared(GoalEndReason.User)), Durability.Sync) { it.goal.condition != null } != null

/** 重开会话时：带着进行中的目标恢复，写 [GoalResumed] 把回合与 check-in 计数清零。已达成或已清除的目标没有条件，不恢复 */
internal suspend fun SessionRuntime.resumeGoal(nowMs: Long) {
    if (state.value.goal.condition == null) return
    commit(listOf(GoalResumed(nowMs)), Durability.Sync)
}

/** 不可恢复的错误（CC 原文四类）：认证失效、额度用完、上下文溢出且压缩救不回、模型不可用。遇到就清目标，用户修完再 /goal */
internal fun unrecoverableFailure(kind: ModelFailureKind?, message: String): Boolean {
    if (kind == ModelFailureKind.Auth || kind == ModelFailureKind.ModelUnavailable || kind == ModelFailureKind.ContextOverflow) return true
    // 额度用完没有单独的失败种类（供应商多以 400 报），按供应商的原文认：Anthropic「credit balance」、OpenAI「insufficient_quota」、中文「余额不足」
    return QUOTA_MARKERS.any { message.contains(it, ignoreCase = true) }
}

private val QUOTA_MARKERS = listOf("credit balance", "insufficient_quota", "余额不足")

/**
 * 回合因失败结束时，`/goal` 的处理（CC 原文）：不可恢复的错误 → 清目标并提示 `Goal cleared after an unrecoverable error`；
 * 其它（重试用尽、速率 / 用量限制、hook 停了回合……）→ 暂停并说明，目标保留，用户下一句话恢复评估。
 * 没有目标时返回空表。
 */
internal fun goalFailureEvents(condition: String?, kind: ModelFailureKind?, message: String): List<SessionEvent> {
    if (condition == null) return emptyList()
    return if (unrecoverableFailure(kind, message)) {
        listOf(
            GoalCleared(GoalEndReason.Error, message),
            Note(NoteLevel.Warning, "Goal cleared after an unrecoverable error：$message。修好之后用 /goal <条件> 重新设定。"),
        )
    } else {
        listOf(
            GoalPaused(message),
            Note(NoteLevel.Warning, "Goal paused：$message。目标还在，你的下一句话会接着评估。"),
        )
    }
}

/**
 * `-p` 跑 `/goal`（CC：「runs the loop to completion」）要不要继续等：目标还在、没暂停，而后台工作还在跑——
 * 它结束时会以 Notification 开回合，评估照常进行；非交互没有空闲 check-in，只能等它自己收完。
 */
fun SessionRuntime.goalWaitingOnBackground(): Boolean {
    val goal = state.value.goal
    return goal.condition != null && goal.paused == null && goalBackgroundWork().isNotEmpty()
}

/**
 * 挂住 `/goal` 评估的后台工作（CC：「a subagent or a background shell command」）：后台命令 / Monitor，加上回合还在跑的后台子 agent。
 * 只数后台命令的话，主回合把活派给后台子 agent 就收尾，评估器在活还没干完时就被问（可能直接判成已达成），
 * 接着防空转把循环停下，子 agent 回报之后也不再评估。看已落盘的回合状态：子 agent 回报时 TurnEnded 已经落盘
 */
internal fun SessionRuntime.goalBackgroundWork(): List<String> =
    background.runningJobsText() + state.value.children.values
        .filter { it.background && hub?.get(it.child)?.state?.value?.turn != null }
        .map { "子 agent ${it.name ?: it.child.value}（${it.description}）" }
