package dev.min.code.core.engine.codex

import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.offerResume
import dev.min.code.core.engine.provesUnfinished
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/**
 * Codex 的「继续」键（▶）判定里属于 Codex 的那部分（设计 §6.5）。出现条件是通用的（[offerResume]）。
 *
 * B8 起停止键是 `turn/interrupt`（进程不死），结局来自 manager 的只读 `lastTurnEnd`（见 `CodexTurnEnd`）。
 */
internal object CodexResume {

    fun tailOf(items: List<ChatItem>): HistoryTail {
        val last = items.lastOrNull { it !is ChatItem.Note && it !is ChatItem.ProcessOutput && !(it is ChatItem.UserText && it.queued) }
            ?: return HistoryTail.Empty
        return when (last) {
            is ChatItem.UserText, is ChatItem.Thinking -> HistoryTail.UserMessage
            is ChatItem.AssistantText -> HistoryTail.AssistantFinal
            is ChatItem.ToolCall ->
                if (last.status == ChatItem.ToolCall.Status.Running) HistoryTail.AssistantWithToolCalls else HistoryTail.ToolResults
            else -> HistoryTail.Empty
        }
    }

    /**
     * @param lastEnd 上一回合怎么结束的（manager 的 `lastTurnEnd` 映射来的）；null = 不知道，不出 ▶
     */
    fun offer(
        state: CodexAppServerManager.State,
        lastEnd: TurnEnd?,
        capabilities: EngineCapabilities = EngineCapabilities.Codex,
    ): ResumeOffer? {
        if (lastEnd == null) return null
        val exited = state.status == SessionStatus.Closed || state.status == SessionStatus.Failed
        return offerResume(
            capabilities = capabilities,
            turnRunning = state.busy,
            stopping = false,
            // 要有 threadId 才能 thread/resume；只读回放（Idle，没进程）也算能跑：▶ 会先接回进程
            canRun = state.threadId != null,
            lastEnd = lastEnd,
            since = null,
            tail = if (lastEnd.provesUnfinished()) HistoryTail.UserMessage else tailOf(state.items),
            pendingInputs = state.queued.size,
            offerId = "${state.threadId}@${state.turnId ?: "none"}",
            needsRelaunch = exited || state.status == SessionStatus.Idle,
        )
    }

    /**
     * 旧会话的 rollout 最后一个回合有没有收尾：App 被杀后重开用（§6.5 表最后一行）。
     * 判定在 `core/codex/CodexSessionFile.kt`（读文件时一遍就带出来），这里转发，已有的调用点不变。
     */
    fun rolloutEndedMidTurn(lines: Sequence<String>): Boolean = dev.min.code.core.codex.rolloutEndedMidTurn(lines)
}
