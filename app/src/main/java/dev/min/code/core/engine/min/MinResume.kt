package dev.min.code.core.engine.min

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.resumeHint
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.offerResume

/**
 * 这个会话该不该出 ▶（设计 6.1、6.3）。
 *
 * 判定分两层，各管各的：内核的 [resumeHint]（K8）只看日志折叠出的状态，回答「模型还欠不欠一个回答」
 * （上一回合被打断 / 失败、历史末尾对不对）；通用的 [offerResume] 再叠上引擎层面的条件
 * （正在关闭、供应商档被删了不能跑、引擎有没有 ▶ 能力）。两层的前三条条件重复是有意的：
 * 内核那一层让「可以继续」在没有 App 的桌面 CLI 里也成立，通用这一层让三个引擎共用一份出现规则。
 *
 * @param canRun 会话还能跑：供应商档没被删
 * @param stopping 正在关闭
 */
fun minResumeOffer(
    state: SessionState,
    canRun: Boolean,
    stopping: Boolean = false,
    capabilities: EngineCapabilities = EngineCapabilities.MinAgent,
): ResumeOffer? {
    val hint = state.resumeHint()
    // 内核说不欠回答（完成 / 回答已完整且收件箱空）就不出；这时不必再算通用层
    if (hint == null) return null
    return offerResume(
        capabilities = capabilities,
        turnRunning = state.turn != null,
        stopping = stopping,
        canRun = canRun,
        lastEnd = turnEndOf(hint.outcome),
        since = hint.sinceMs.takeIf { it > 0 },
        tail = tailOf(state),
        pendingInputs = hint.pendingInputs,
        offerId = hint.offerId.value,
    )
}

/** 历史末尾长什么样。内核的历史里没有单独的「工具结果」条目（结果在 toolResults 里），所以带调用的助手消息就是 [HistoryTail.AssistantWithToolCalls] */
internal fun tailOf(state: SessionState): HistoryTail = when (val last = state.history.lastOrNull()) {
    null -> HistoryTail.Empty
    is HistoryEntry.User -> HistoryTail.UserMessage
    is HistoryEntry.Assistant -> if (last.toolCalls.isNotEmpty()) HistoryTail.AssistantWithToolCalls else HistoryTail.AssistantFinal
}
