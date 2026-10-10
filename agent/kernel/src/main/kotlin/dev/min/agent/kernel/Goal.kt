package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookHandler
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.GoalCheckIn
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GoalVerdict
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.SessionEvent
import kotlinx.serialization.json.JsonObject

/*
 * `/goal`（CC 文档 goal 页）的内核侧：目标是会话状态，由 goal_* 事件折叠（[GoalState]）；评估借 prompt 型 Stop hook 的
 * 执行路径（[ModelHookRunner]，含计费），结论接进 Stop 收尾（TurnRunner.stopHookContinues）。
 */

/** 一个已结束的目标的记录（达成 / 判定不可能 / 用户清掉）。`/goal` 无参数时显示最近达成的那个 */
data class GoalRecord(val condition: String, val reason: GoalEndReason, val turns: Int, val lastReason: String?)

/**
 * 会话里的目标。[condition] 为 null = 没有进行中的目标。
 * [turns] 是本目标自设定（或恢复会话）以来评估过的次数；[checkIns] 是用户上一次说话之后已发的 check-in 数。
 * 两者在 [GoalResumed] 时清零（照 CC：恢复后回合数、计时、花费基线都重置），[checkIns] 在用户再说话时清零。
 */
data class GoalState(
    val condition: String? = null,
    val turns: Int = 0,
    val checkIns: Int = 0,
    val lastReason: String? = null,
    val lastEnded: GoalRecord? = null,
)

/** 折叠一条 goal 事件（非 goal 事件原样返回）。用户每说一句话都清 check-in 计数（CC：你每说一句话，又有 3 次） */
internal fun GoalState.applyGoal(event: SessionEvent): GoalState = when (event) {
    is GoalSet -> copy(condition = event.condition, turns = 0, checkIns = 0, lastReason = null)
    is GoalEvaluated -> copy(turns = turns + 1, lastReason = event.reason ?: lastReason)
    is GoalCleared -> {
        val record = condition?.let { GoalRecord(it, event.reason, turns, lastReason) }
        // 只有进行中的目标才有记录可写：清掉一个没有的目标是空操作
        if (record == null) this else GoalState(lastEnded = record)
    }
    is GoalCheckIn -> copy(checkIns = event.count)
    is GoalResumed -> copy(turns = 0, checkIns = 0)
    is InputReceived -> if (event.origin == InputOrigin.User) copy(checkIns = 0) else this
    else -> this
}

/** `/goal` 的参数解读：无参数 = 显示；清除词 = 清掉；其余整段当完成条件 */
internal sealed interface GoalCommand {
    data object Show : GoalCommand
    data object Clear : GoalCommand
    data class Set(val condition: String) : GoalCommand
}

/** CC 原文：`clear`、`stop`、`off`、`reset`、`none`、`cancel` 都能清掉目标（大小写不计） */
internal val GOAL_CLEAR_WORDS = setOf("clear", "stop", "off", "reset", "none", "cancel")

internal fun parseGoalCommand(argument: String): GoalCommand {
    val text = argument.trim()
    return when {
        text.isEmpty() -> GoalCommand.Show
        text.lowercase() in GOAL_CLEAR_WORDS -> GoalCommand.Clear
        else -> GoalCommand.Set(text)
    }
}

/**
 * 后台工作挂住目标时的 check-in 节奏（CC 原文）：第一次在挂起 [GoalCheckInPolicy.DEFAULT_FIRST_MINUTES] 分钟后，
 * 之后每次等的时间加倍、封顶为第一次的 4 倍——默认就是 30 分钟、1 小时、之后每 2 小时。
 * 用户每说一句话清零，空闲时最多发 [MAX_IDLE_CHECK_INS] 次。`CLAUDE_CODE_GOAL_CHECKIN_MINUTES=0` 关掉。
 */
internal object GoalCheckInPolicy {
    const val DEFAULT_FIRST_MINUTES = 30L
    const val MAX_IDLE_CHECK_INS = 3

    /** 第 [n] 次 check-in（n 从 1 数）之前要等的毫秒数；[firstMinutes] 为 0 或负数时返回 null（关闭） */
    fun gapMs(n: Int, firstMinutes: Long): Long? {
        if (firstMinutes <= 0L || n < 1) return null
        // 1, 2, 4, 4, … 倍：移位封顶在 2，这里的 4 就是 CC 说的「最多四倍」
        val multiplier = 1L shl minOf(n - 1, 2)
        return firstMinutes * multiplier * 60_000L
    }

    /** 还能不能再发一次：没关掉，且这段用户没说话期间发的还没到 [MAX_IDLE_CHECK_INS] 次（时间由调用方用 deps.clock 比） */
    fun mayCheckIn(checkIns: Int, firstMinutes: Long): Boolean = firstMinutes > 0L && checkIns < MAX_IDLE_CHECK_INS
}

/** 有后台工作（命令 / Monitor）在跑时不评估：这一回合还没到「真的停下来」（CC：推迟到没有后台工作的那一回合） */
internal fun goalEvaluationDeferred(backgroundRunning: Int): Boolean = backgroundRunning > 0

/**
 * 评估用的提示词（CC：条件加上到目前为止的对话；评估器不调工具，只看已经出现在对话里的内容）。
 * 结论口径沿用 prompt hook 的 {ok, reason, impossible}：ok:false 继续、impossible:true 结束（没法满足）。
 */
internal fun goalEvaluationPrompt(condition: String): String = """
你在评估一个 /goal 的完成条件是否已经满足。完成条件：
$condition
只依据下面对话里已经出现的内容判断（你不能调用工具，也不能假设没出现的结果）。
满足：{"ok": true, "reason": "…"}；还没满足：{"ok": false, "reason": "下一步该做什么"}；
判断它永远不可能满足：{"ok": false, "impossible": true, "reason": "…"}。reason 给人看，也是下一回合的指引。
${'$'}ARGUMENTS
""".trimIndent()

/** 把 [goalEvaluationPrompt] 包成一个 prompt 型 Stop 处理器，交给既有的 hook 执行路径（不另起一套模型调用） */
internal fun goalEvaluationHandler(condition: String): HookHandler = HookHandler(
    event = HookEvent.Stop,
    matcher = null,
    type = "prompt",
    command = null,
    args = null,
    timeout = null,
    condition = null,
    // 目标不来自设置层，没有对应的配置来源；这个字段只标注 hook 的来处，评估路径不读它
    source = ConfigSource.Managed,
    raw = JsonObject(emptyMap()),
    prompt = goalEvaluationPrompt(condition),
    continueOnBlock = true,
)
