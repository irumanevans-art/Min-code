package dev.min.code.core.engine

/**
 * 「继续」键（▶）的数据与出现条件（设计 §6.1）。
 *
 * 语义：回合停在半路、模型还欠一个回答时，停止键原地变成继续键，按一下用同一段上下文接着跑，用户不用打字。
 * 键怎么摆、点了发什么命令在 `ComposerKeys.kt`；这里只回答「这一刻该不该给 offer」。
 * 三个引擎的出现条件相同，所以判定只写一处（[offerResume]），适配器只负责把自己的状态翻成它的入参。
 */
data class ResumeOffer(
    /** 停下来的那一回合的 id（CC / Codex 合成等价物）。按 ▶ 时带上，防止重复续跑 */
    val offerId: String,
    val reason: ResumeReason,
    /** 停在何时（epoch 毫秒）；不知道就 null */
    val since: Long?,
    val mode: ResumeMode,
    /** 续跑时会一起交给模型的排队输入（含子 agent 报告） */
    val pendingInputs: Int,
) {
    fun brief(): ResumeBrief = ResumeBrief(offerId, reason)
}

/** 为什么停下的。无障碍文案「继续：应用被系统结束于 14:32」就按它写 */
sealed interface ResumeReason {
    data object UserStopped : ResumeReason

    /** 重试耗尽失败；[kind] 为 Auth / ContextOverflow 时说明里写「先检查供应商」/「上下文已满」，▶ 照常出现 */
    data class Failed(val kind: FailureKind?) : ResumeReason

    /** App 进程被杀 / 关机。[atMs] = 最后活动时间（Min：K6 的 `lastActivityAtMs`） */
    data class ProcessDeath(val atMs: Long?) : ResumeReason

    data object Limit : ResumeReason

    /** hook 叫停了这一轮 */
    data object HookStopped : ResumeReason

    /** CC 的 CLI / Codex 的 app-server 自己退了 */
    data object EngineExited : ResumeReason
}

/** 这一次续跑具体怎么做（[EngineCapabilities.resume] 说的是引擎有没有这个能力） */
enum class ResumeMode {
    /** 引擎自己续，不往历史里加任何消息（Min：`SessionRuntime.resume()`） */
    Native,

    /** 代发续跑提示（CC / Codex，进程还活着） */
    Prompted,

    /** 进程已经没了：先把会话接回来（CC `--resume`、Codex `thread/resume`），就绪后再代发续跑提示 */
    RelaunchThenPrompted,
}

/** 给通知与抽屉用的最小摘要：按钮要的是 offerId，文案要的是原因 */
data class ResumeBrief(val offerId: String, val reason: ResumeReason)

/**
 * 续跑提示原文，与官方 CLI 的 `CLAUDE_CODE_RESUME_PROMPT` 默认值一致：官方的「续跑」本身就是代发这一句。
 * CC / Codex 适配器认出这句原文，把它画成 `UserText(continuation = true)` 的细分隔线；
 * 用户恰好手打了这句也显示成分隔线，无害。
 */
const val CONTINUE_PROMPT = "Continue from where you left off."

/**
 * 历史末尾长什么样：模型还欠不欠一个回答（§6.1 条件 3）。由适配器从自己的历史 / transcript 判，
 * 通用层只看结论。
 */
enum class HistoryTail {
    /** 没有历史 */
    Empty,

    /** 最后一条是用户消息 */
    UserMessage,

    /** 最后一条是工具结果（所有调用都已有结果，恢复 / 收尾保证过） */
    ToolResults,

    /** 带工具调用的助手消息（这样的消息在恢复时已被补齐结果） */
    AssistantWithToolCalls,

    /**
     * 不带调用的助手消息：回答其实已经完整。进程死在「最终回答已落盘」与回合结束事件之间就是这样，
     * 这时续跑会变成 assistant prefill，许多模型直接 400，所以不出 ▶——除非收件箱里有输入（见 [offerResume]）。
     */
    AssistantFinal,
}

/**
 * 该不该出 ▶，该出就给 [ResumeOffer]。纯函数，§6.1 的四个条件逐条对应：
 *
 * 1. 当前没有回合在跑，也没有正在关闭（[turnRunning] / [stopping]）；
 * 2. 最近一个回合的结局是 用户停止 / 失败 / 进程死亡 / 引擎退出 / 达到上限（[lastEnd]）。
 *    正常完成不出；**撤回**（一个字还没出，消息已退回输入框）不出；
 * 3. 模型确实还欠一个回答（[tail]）：用户消息、工具结果、带调用的助手消息。
 *    唯一的例外：最后是完整的助手回答、但收件箱里有输入（[pendingInputs] > 0，如进程重启后补交的子 agent 报告），
 *    这时续跑等于处理排队输入，合法；
 * 4. 引擎的 [EngineCapabilities.resume] 不是 None，且会话还能跑（[canRun]：供应商档没被删、引擎可用）。
 *
 * @param offerId 停下来的回合 id（或合成的等价物）
 * @param since 停在何时
 * @param needsRelaunch 进程已经没了、续跑前得先接回会话。只对 Prompted 的引擎有意义
 */
fun offerResume(
    capabilities: EngineCapabilities,
    turnRunning: Boolean,
    stopping: Boolean,
    canRun: Boolean,
    lastEnd: TurnEnd?,
    since: Long?,
    tail: HistoryTail,
    pendingInputs: Int,
    offerId: String,
    needsRelaunch: Boolean = false,
): ResumeOffer? {
    if (turnRunning || stopping) return null
    if (capabilities.resume == ResumeSupport.None || !canRun) return null
    val reason = reasonOf(lastEnd, since) ?: return null
    val owesAnswer = when (tail) {
        HistoryTail.Empty -> false
        HistoryTail.UserMessage, HistoryTail.ToolResults, HistoryTail.AssistantWithToolCalls -> true
        HistoryTail.AssistantFinal -> pendingInputs > 0
    }
    if (!owesAnswer) return null
    val mode = when {
        capabilities.resume == ResumeSupport.Native -> ResumeMode.Native
        needsRelaunch -> ResumeMode.RelaunchThenPrompted
        else -> ResumeMode.Prompted
    }
    return ResumeOffer(offerId = offerId, reason = reason, since = since, mode = mode, pendingInputs = pendingInputs)
}

/** 结局 → 续跑原因；不该续的结局（完成 / 撤回 / 还没有结局）返回 null */
private fun reasonOf(end: TurnEnd?, since: Long?): ResumeReason? = when (end) {
    null, TurnEnd.Completed -> null
    is TurnEnd.Failed -> ResumeReason.Failed(end.kind)
    is TurnEnd.Interrupted -> when (end.cause) {
        InterruptCause.User -> ResumeReason.UserStopped
        InterruptCause.ProcessDeath -> ResumeReason.ProcessDeath(since)
        InterruptCause.EngineExited -> ResumeReason.EngineExited
        InterruptCause.Limit -> ResumeReason.Limit
        InterruptCause.Hook -> ResumeReason.HookStopped
        InterruptCause.Withdrawn -> null
    }
}
