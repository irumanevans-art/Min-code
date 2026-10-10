package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookHandler
import dev.min.agent.spi.HooksDisabledBy
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.GoalCheckIn
import dev.min.agent.spi.GoalCleared
import dev.min.agent.spi.GoalDeferred
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalEvaluated
import dev.min.agent.spi.GoalPaused
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.SessionEvent
import kotlinx.serialization.json.JsonObject

/*
 * `/goal`（CC 文档 goal 页）的内核侧：目标是会话状态，由 goal_* 事件折叠（[GoalState]）；评估借 prompt 型 Stop hook 的
 * 执行路径（[ModelHookRunner]，含计费），结论接进 Stop 收尾（TurnRunner.stopHookContinues）。
 */

/** 一个已结束的目标的记录（达成 / 判定不可能 / 用户清掉 / 不可恢复的错误）。`/goal` 无参数时显示最近达成的那个 */
data class GoalRecord(val condition: String, val reason: GoalEndReason, val turns: Int, val lastReason: String?, val detail: String? = null)

/**
 * 会话里的目标。[condition] 为 null = 没有进行中的目标。
 * [turns] 是本目标自设定（或恢复会话）以来评估过的次数；[checkIns] / [idleCheckIns] 是用户上一次说话之后已发的 check-in 数（总数 / 空闲时开的）；
 * [idleRounds] 是连续几回合没有工具调用（照 CC 的防空转）；[waitSinceMs] 是后台工作挂起的时刻（check-in 从它数，null = 没挂起）；
 * [paused] 非空 = 因错误暂停，用户下一句话恢复评估。
 * 回合数、check-in 计数在 [GoalResumed] 时清零（照 CC：恢复后计数、计时、花费基线都重置）；check-in 与空闲轮数在用户再说话时清零。
 */
data class GoalState(
    val condition: String? = null,
    val turns: Int = 0,
    val checkIns: Int = 0,
    val idleCheckIns: Int = 0,
    val idleRounds: Int = 0,
    val waitSinceMs: Long? = null,
    val paused: String? = null,
    val lastReason: String? = null,
    val lastEnded: GoalRecord? = null,
)

/** 折叠一条 goal 事件（非 goal 事件原样返回）。用户每说一句话都清 check-in 与空闲轮数，并恢复暂停的目标（CC：你每说一句话，又有 3 次） */
internal fun GoalState.applyGoal(event: SessionEvent): GoalState = when (event) {
    is GoalSet -> GoalState(condition = event.condition, lastEnded = lastEnded)
    is GoalEvaluated -> copy(
        turns = turns + 1,
        // 评估只发生在模型没调工具的那一轮：之前调过工具就从这一轮重新数，否则接着数
        idleRounds = if (event.toolUse) 1 else idleRounds + 1,
        waitSinceMs = null,
        paused = null,
        lastReason = event.reason ?: lastReason,
    )
    is GoalCleared -> {
        val record = condition?.let { GoalRecord(it, event.reason, turns, lastReason, event.detail) }
        // 只有进行中的目标才有记录可写：清掉一个没有的目标是空操作
        if (record == null) this else GoalState(lastEnded = record)
    }
    is GoalDeferred -> copy(waitSinceMs = waitSinceMs ?: event.atMs)
    is GoalCheckIn -> copy(
        checkIns = event.count,
        idleCheckIns = if (event.idle) idleCheckIns + 1 else idleCheckIns,
        waitSinceMs = event.atMs,
    )
    is GoalPaused -> copy(paused = event.reason, waitSinceMs = null)
    is GoalResumed -> copy(turns = 0, checkIns = 0, idleCheckIns = 0, idleRounds = 0, waitSinceMs = null)
    is InputReceived -> if (event.origin == InputOrigin.User) copy(checkIns = 0, idleCheckIns = 0, idleRounds = 0, paused = null) else this
    else -> this
}

/** `/goal` 的参数解读：无参数 = 显示；清除词 = 清掉；其余整段当完成条件 */
sealed interface GoalCommand {
    data object Show : GoalCommand
    data object Clear : GoalCommand
    data class Set(val condition: String) : GoalCommand
}

/** CC 原文：`clear`、`stop`、`off`、`reset`、`none`、`cancel` 都能清掉目标（大小写不计） */
internal val GOAL_CLEAR_WORDS = setOf("clear", "stop", "off", "reset", "none", "cancel")

fun parseGoalCommand(argument: String): GoalCommand {
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

    /** 下一次 check-in 到点的时刻；没挂起或已关掉为 null */
    fun dueAtMs(state: GoalState, firstMinutes: Long): Long? {
        val since = state.waitSinceMs ?: return null
        return since + (gapMs(state.checkIns + 1, firstMinutes) ?: return null)
    }

    /** 到点了没有：挂起满了这一次的间隔（[dueAtMs]）；没挂起或已关掉为 false */
    fun isDue(state: GoalState, firstMinutes: Long, nowMs: Long): Boolean = dueAtMs(state, firstMinutes)?.let { nowMs >= it } == true

    /** 空闲时还能不能再开一回合做 check-in：没关掉，且这段用户没说话期间空闲开的还没到 [MAX_IDLE_CHECK_INS] 次 */
    fun mayIdleCheckIn(state: GoalState, firstMinutes: Long): Boolean = firstMinutes > 0L && state.idleCheckIns < MAX_IDLE_CHECK_INS
}

/**
 * 连续几回合没有工具调用就停下（CC：「no tool use for several turns in a row」，原文没给数字，取 3）。
 * 停下时只给一条警告、目标保留；用户下一句话重新数。替代了借来的 Stop hook 每回合 8 次上限（那个数的是评估次数，会误伤长任务）
 */
internal const val GOAL_NO_TOOL_ROUNDS_LIMIT = 3

/** 自动重试的次数（CC：其它错误自动重试，三次之后暂停）；`CLAUDE_CODE_GOAL_CHECKIN_MINUTES=0` 时不重试 */
internal const val GOAL_AUTO_RETRIES = 3

/** 有后台工作（命令 / Monitor）在跑时不评估：这一回合还没到「真的停下来」（CC：推迟到没有后台工作的那一回合） */
internal fun goalEvaluationDeferred(backgroundRunning: Int): Boolean = backgroundRunning > 0

/**
 * 评估用的提示词（CC：条件加上到目前为止的对话；评估器不调工具，只看已经出现在对话里的内容）。
 * 对话放在输入 JSON 的 conversation 字段里（[goalTranscript] 渲染）。
 * 结论口径沿用 prompt hook 的 {ok, reason, impossible}：ok:false 继续、impossible:true 结束（没法满足）。
 */
internal fun goalEvaluationPrompt(condition: String): String = """
你在评估一个 /goal 的完成条件是否已经满足。完成条件：
$condition
只依据下面 JSON 的 conversation 字段（到目前为止的整段对话）里已经出现的内容判断：你不能调用工具，也不能假设没出现的结果。
工具结果在对话里只留了开头；需要的结论没在里面时，就当它没出现。
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

/**
 * `/goal` 不可用的原因（hooks 被整体禁用）。CC：`disableAllHooks` 为 true 或 managed 的 `allowManagedHooksOnly` 时 `/goal` 不运行，
 * 并说明原因（不静默不做）。没有禁用返回 null
 */
internal fun goalRefusal(disabledBy: HooksDisabledBy?): String? = when (disabledBy) {
    null -> null
    HooksDisabledBy.DisableAllHooks -> "/goal 不可用：hooks 被 disableAllHooks 关掉了（/goal 是 prompt 型 Stop hook，hooks 关着它就跑不起来）。打开 hooks 后再试。"
    HooksDisabledBy.ManagedOnly -> "/goal 不可用：托管设置里有 allowManagedHooksOnly，只留托管层的 hooks，/goal 不在其内。"
}
