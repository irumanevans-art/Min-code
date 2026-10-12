package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.transcriptEndedMidTurn
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.offerResume
import dev.min.code.core.engine.provesUnfinished
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/**
 * Claude Code 的「继续」键（▶）判定里属于 CC 的那部分（设计 §6.4）。出现条件本身是通用的（[offerResume]），
 * 这里只负责把 CC 的状态翻成它的入参。
 *
 * 结局来自 manager 的只读字段 `lastTurnEnd`（B8，见 `ClaudeTurnEnd`）；[offer] 的 [lastEnd] 为 null（从没跑过一轮、
 * 或重开的 transcript 不在半路）就不出 ▶，而不是靠猜。
 */
internal object ClaudeResume {

    /**
     * 回放 transcript 之后，这个会话是不是停在回合中途（设计 §6.4 表最后一行）。判定本身在
     * `core/claudecode/ClaudeTranscriptTail.kt`（manager 打开会话时自己要用），这里只转发。
     */
    fun endedMidTurn(events: List<ClaudeCodeEvent>): Boolean = transcriptEndedMidTurn(events)

    /**
     * 历史末尾长什么样（§6.1 条件 3）。从会话条目判：提示条与 stderr 不算对话，跳过。
     * 思考块单独在末尾说明回答没写完（模型还欠一个回答），按用户消息算。
     */
    fun tailOf(items: List<ChatItem>): HistoryTail {
        val last = items.lastOrNull { it !is ChatItem.Note && it !is ChatItem.ProcessOutput } ?: return HistoryTail.Empty
        return when (last) {
            is ChatItem.UserText -> HistoryTail.UserMessage
            is ChatItem.Thinking -> HistoryTail.UserMessage
            is ChatItem.AssistantText -> HistoryTail.AssistantFinal
            is ChatItem.ToolCall ->
                if (last.status == ChatItem.ToolCall.Status.Running) HistoryTail.AssistantWithToolCalls
                else HistoryTail.ToolResults
            else -> HistoryTail.Empty
        }
    }

    /**
     * ▶ 的 offer。
     *
     * @param lastEnd 上一回合怎么结束的（manager 的 `lastTurnEnd` 映射来的）；null = 不知道，不出 ▶
     * @param since 停在何时（毫秒）
     */
    fun offer(state: ClaudeCodeManager.SessionState, lastEnd: TurnEnd?, since: Long?): ResumeOffer? {
        if (lastEnd == null) return null
        val exited = state.status == SessionStatus.Closed || state.status == SessionStatus.Failed
        return offerResume(
            capabilities = EngineCapabilities.ClaudeCode,
            turnRunning = state.busy,
            stopping = state.stopping,
            // CC 进程死了也能接回来（--resume），所以 Closed / Failed 不是「不能跑」；
            // 能不能跑只取决于有没有会话 id 可以 --resume
            canRun = state.sessionId != null,
            lastEnd = lastEnd,
            since = since,
            tail = if (lastEnd.provesUnfinished()) HistoryTail.UserMessage else tailOf(state.items),
            pendingInputs = state.items.count { it is ChatItem.UserText && it.queued },
            offerId = "${state.sessionId}@${since ?: 0L}",
            needsRelaunch = exited,
        )
    }
}
