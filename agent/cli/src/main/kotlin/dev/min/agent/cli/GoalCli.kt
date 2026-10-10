package dev.min.agent.cli

import dev.min.agent.kernel.GoalCommand
import dev.min.agent.kernel.goalArgumentOf
import dev.min.agent.kernel.GoalSetResult
import dev.min.agent.kernel.GoalState
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.clearGoal
import dev.min.agent.kernel.goalWaitingOnBackground
import dev.min.agent.kernel.parseGoalCommand
import dev.min.agent.kernel.setGoal
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalSet
import kotlinx.coroutines.delay
import java.io.PrintStream

/*
 * `/goal` 的命令行口径（交互与 `-p` 共用）：状态文字、清除与设定的回显、`-p` 跑到目标结束的等待与失败说明。
 * 文字照 CC：`No goal set`、`Goal cleared:`、`◎ /goal active`；这里只是拼字符串，状态机在内核。
 */

/** `-p` 跑目标时，后台工作还在跑就每隔这么久看一次（毫秒） */
internal const val GOAL_WAIT_POLL_MS = 500L

/** `/goal` 无参数时的状态（CC：条件、评估次数、最近理由；暂停、后台等待各说一句；没有进行中的就说最近结束的） */
internal fun goalStatusText(goal: GoalState): String {
    goal.condition?.let { condition ->
        return buildString {
            append("◎ /goal active：").append(condition)
            append("\n已评估 ${goal.turns} 次")
            goal.lastReason?.let { append("，最近理由：").append(it) }
            goal.paused?.let { append("\n目标已暂停：").append(it).append("。你的下一句话会接着评估。") }
            if (goal.paused == null && goal.waitSinceMs != null) append("\n后台工作在跑，评估推迟到它结束。")
        }
    }
    val ended = goal.lastEnded ?: return "No goal set"
    val how = when (ended.reason) {
        GoalEndReason.Achieved -> "已达成"
        GoalEndReason.Impossible -> "判定不可能"
        GoalEndReason.User -> "已清除"
        GoalEndReason.Error -> "因不可恢复的错误被清除"
    }
    val tail = ended.detail ?: ended.lastReason
    return "上一个目标$how：${ended.condition}（评估 ${ended.turns} 次）" + (tail?.let { "：$it" } ?: "")
}

/** 交互模式的 `/goal [条件|clear]`：状态、清除、设定都回显到 [out]；设定后的回合照常在界面里跑 */
internal suspend fun goalCommandLine(runtime: SessionRuntime, argument: String, out: PrintStream) {
    when (val command = parseGoalCommand(argument)) {
        GoalCommand.Show -> out.println(goalStatusText(runtime.state.value.goal))
        GoalCommand.Clear -> {
            val before = runtime.state.value.goal.condition
            out.println(if (runtime.clearGoal() && before != null) "Goal cleared: $before" else "No goal set")
        }
        is GoalCommand.Set -> when (val result = runtime.setGoal(command.condition)) {
            is GoalSetResult.Refused -> out.println(result.reason)
            is GoalSetResult.Set -> out.println("Goal set: ${command.condition}" + if (result.startedTurn) "" else "（当前回合结束时开始评估）")
        }
    }
}

/**
 * `-p` 跑 `/goal`：一直等到回合结束、后台工作也收完（[goalWaitingOnBackground] 为真就继续等，它结束时会自己开回合）。
 * 返回后目标可能已经达成（清掉了）、也可能还停在中途（[goalUnmetReason] 说明）。
 */
internal suspend fun awaitGoalLoop(runtime: SessionRuntime) {
    do {
        runtime.awaitIdle()
        val waiting = runtime.goalWaitingOnBackground()
        if (waiting) delay(GOAL_WAIT_POLL_MS)
    } while (waiting)
}

/**
 * 这次 `-p` 设了目标、却没有达成就结束了的说明（它就是 result 的一条错误）：暂停、防空转停下、评估没得出结论（目标还在）；
 * 或者判定不可能、不可恢复的错误（目标已清）。没有设目标、或达成了，返回 null。
 * [events] 只含本次运行之后的事件（[GoalSet] 在里面才算这次设的）
 */
internal fun goalUnmetReason(goal: GoalState, events: List<EventRecord>): String? {
    if (events.none { it.event is GoalSet }) return null
    goal.condition?.let { condition ->
        val why = goal.paused ?: goal.lastReason ?: "评估没有给出结论"
        return "目标没有达成就停下了（$condition）：$why"
    }
    val ended = goal.lastEnded ?: return null
    if (ended.reason == GoalEndReason.Achieved) return null
    return "目标结束但没有达成（${ended.condition}）：${ended.detail ?: ended.lastReason ?: "未说明"}"
}
