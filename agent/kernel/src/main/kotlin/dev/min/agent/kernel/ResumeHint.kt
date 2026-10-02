package dev.min.agent.kernel

import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.TurnOutcome

/**
 * 「可以继续」的判定结果（docs/agent/detail/M2-B-界面接入设计.md §6.1、§6.3）：界面据此把停止键变成继续键。
 * [offerId] 是停下来的那一回合：按 ▶ 时带上，界面核对它与当前提示一致才调 [SessionRuntime.resume]，防止重复续跑。
 */
data class ResumeHint(
    val offerId: TurnId,
    val outcome: TurnOutcome,
    /** 停在何时：进程被杀的回合是被杀前最后一条事件的时间 */
    val sinceMs: Long,
    /** 续跑时会一起交给模型的排队输入（含进程重启后补交的子 agent 报告） */
    val pendingInputs: Int,
)

/**
 * 模型还欠一个回答、可以原样接着跑时给出提示，否则 null。纯函数，只看折叠出的状态：
 *
 * - 没有回合在跑，且最近一个回合是被打断（用户 / 进程死亡 / 达到上限）或失败收场；
 * - 历史最后一条是用户消息，或带工具调用的助手消息（调用的结果在收尾时已经补齐）——模型确实还欠一个回答；
 * - 或者最后一条是不带调用的助手消息（回答其实已经完整），但收件箱里有输入：续跑等于处理排队的输入，也合法。
 *
 * 回答已经完整、收件箱又是空的不给：续跑会变成「助手消息之后再要一个助手回答」，许多供应商直接 400。
 * 正常完成的回合不给：没有欠什么。
 */
fun SessionState.resumeHint(): ResumeHint? {
    if (turn != null) return null
    val ended = lastTurn ?: return null
    if (ended.outcome == TurnOutcome.Completed) return null
    val owesAnswer = when (val last = history.lastOrNull()) {
        null -> inbox.isNotEmpty()
        is HistoryEntry.User -> true
        is HistoryEntry.Assistant -> last.toolCalls.isNotEmpty() || inbox.isNotEmpty()
    }
    if (!owesAnswer) return null
    return ResumeHint(ended.turnId, ended.outcome, ended.lastActivityAtMs, inbox.size)
}
