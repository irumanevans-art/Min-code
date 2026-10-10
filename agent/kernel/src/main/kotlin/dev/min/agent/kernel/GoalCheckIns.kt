package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GoalCheckIn
import dev.min.agent.spi.GoalDeferred
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.TurnTrigger

/*
 * `/goal` 的 check-in：后台工作挂住目标满了间隔，就送一条说明进收件箱（与后台任务的通知同一条路，`TurnTrigger.Notification` 开回合）。
 * 计时用 deps.clock，由宿主的心跳（[SessionHub.startScheduling]）驱动，与定时任务同一个 Scheduler，不另起计时器。
 */

/** 环境变量：第一次 check-in 的分钟数（CC 原文），`0` 关掉 check-in 与自动重试 */
internal const val GOAL_CHECKIN_MINUTES_ENV = "CLAUDE_CODE_GOAL_CHECKIN_MINUTES"

/** `/goal` 送来的输入的 hook 来源标签。Stop hook 的次数不数它（它有自己的上限，见 [GOAL_NO_TOOL_ROUNDS_LIMIT]） */
internal const val GOAL_ORIGIN = "goal"

/** 第一次 check-in 的分钟数：环境变量没设、不是整数时取默认 30；负数按 0（关掉） */
internal fun goalCheckInMinutes(env: (String) -> String?): Long =
    env(GOAL_CHECKIN_MINUTES_ENV)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: GoalCheckInPolicy.DEFAULT_FIRST_MINUTES

/**
 * 心跳到来时检查每个在线根会话：目标挂着、check-in 到点、会话空闲、还没到空闲上限，就开一回合做 check-in。
 * 空闲判定与定时任务一致（[SessionRuntime.isQuiescent]）：会话正忙时不打扰，回合结束时由 [goalCheckInAtTurnEnd] 接着送。
 */
internal class GoalCheckIns(private val deps: KernelDeps) {
    suspend fun tick(sessions: Collection<SessionRuntime>) {
        val now = deps.clock.nowMs()
        val minutes = goalCheckInMinutes(deps.env)
        for (rt in sessions) {
            if (rt.state.value.origin != null || !rt.isQuiescent()) continue
            val goal = rt.state.value.goal
            if (goal.paused != null || !GoalCheckInPolicy.isDue(goal, minutes, now)) continue
            if (!GoalCheckInPolicy.mayIdleCheckIn(goal, minutes)) continue
            val count = goal.checkIns + 1
            val lastIdle = goal.idleCheckIns + 1 >= GoalCheckInPolicy.MAX_IDLE_CHECK_INS
            rt.receive(
                ClientMsgId("goal-check-in:$count:$now"),
                listOf(TextPart(goalCheckInText(rt, lastIdle))),
                Delivery.Queue,
                InputOrigin.Hook(GOAL_ORIGIN),
                startTurn = TurnTrigger.Notification,
                extra = listOfNotNull(GoalCheckIn(count, now, idle = true), if (lastIdle) idleLimitNote() else null),
                // 提交的锁里再判一次：仍然到点、仍然空闲、没被用户抢先（用户说话会清零计数，这时就不该再送）
                precondition = { s ->
                    val g = s.goal
                    g.condition != null && g.paused == null && s.turn == null && s.inbox.isEmpty() &&
                        GoalCheckInPolicy.isDue(g, minutes, now) && GoalCheckInPolicy.mayIdleCheckIn(g, minutes)
                },
            )
        }
    }
}

/**
 * 回合结束时后台仍在跑、check-in 到点：在这一回合结束时送（CC 原文：非交互的 `-p` 只有这一条路）。
 * 挂起时刻在第一次挂起时补记（[GoalDeferred]）。返回送没送成（送成了调用方照常继续回合）。
 */
internal suspend fun SessionRuntime.goalCheckInAtTurnEnd(nowMs: Long, minutes: Long): Boolean {
    if (state.value.goal.waitSinceMs == null) commit(listOf(GoalDeferred(nowMs)), Durability.Batched)
    val goal = state.value.goal
    if (!GoalCheckInPolicy.isDue(goal, minutes, nowMs)) return false
    val count = goal.checkIns + 1
    return receive(
        ClientMsgId("goal-check-in:$count:$nowMs"),
        listOf(TextPart(goalCheckInText(this, lastIdle = false))),
        Delivery.Steer,
        InputOrigin.Hook(GOAL_ORIGIN),
        startTurn = null,
        extra = listOf(GoalCheckIn(count, nowMs, idle = false)),
    )
}

/** 送给模型的 check-in 话（CC：列出在跑的任务，请它读输出；还在推进就继续等，卡住的修掉或停掉；已停没报告的就接着朝目标干） */
internal fun goalCheckInText(rt: SessionRuntime, lastIdle: Boolean): String {
    val running = rt.background.runningJobsText()
    val head = if (running.isEmpty()) {
        "/goal 还没达成，后台工作已经停了但没有报告结果：接着朝目标干。"
    } else {
        "/goal 还没达成，后台还有在跑的工作：" + running.joinToString("、") + "。请读它们的输出：还在推进就继续等；卡住的修掉或停掉；做完了就接着朝目标干。"
    }
    return if (lastIdle) "$head 这是空闲 check-in 的最后一次，之后会暂停到用户下一句话。" else head
}

private fun idleLimitNote() = Note(NoteLevel.Info, "/goal 空闲 check-in 已经发满 ${GoalCheckInPolicy.MAX_IDLE_CHECK_INS} 次，暂停到你下一句话")
