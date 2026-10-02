package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.engine.EngineCapabilities
import dev.min.code.core.engine.HistoryTail
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.offerResume
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus

/**
 * Claude Code 的「继续」键（▶）判定里属于 CC 的那部分（设计 §6.4）。出现条件本身是通用的（[offerResume]），
 * 这里只负责把 CC 的状态翻成它的入参。
 *
 * **这一单只做映射，不接线**：manager 还没有 `lastTurnEnd`（§6.4 说的「对 manager 的唯一改动」，属 B8），
 * 所以 [offer] 的 [lastEnd] 由调用方传；传 null（现在的 manager 就是这样）就永远不出 ▶，而不是靠猜。
 */
internal object ClaudeResume {

    /**
     * 回放 transcript 之后，这个会话是不是停在回合中途：App 被杀 / 关机后重开会话时用（§6.4 表最后一行）。
     *
     * 规则：忽略标题、系统提示、子 agent 的事件，看最后一条**对话事件**：
     * - 用户消息 / 工具结果 / 带工具调用的助手消息（[ClaudeCodeEvent.ToolUse]）= 模型还欠一个回答 -> true；
     * - 助手正文 / 思考 = 回答已落盘 -> false（续跑会变成 assistant prefill，许多模型直接 400）。
     *
     * **未与真 CLI 校准**（设计标「待确认」，B8 用 `probe_frames` 对拍）。已知缺口：transcript 里的 API 错误记录
     * （`isApiErrorMessage` 的助手行）现在的解析器根本不产出事件，这里看不到它，需要 B8 在解析器里加一个事件。
     */
    fun endedMidTurn(events: List<ClaudeCodeEvent>): Boolean =
        when (events.lastOrNull { it.isConversationEvent() }) {
            is ClaudeCodeEvent.UserMessage, is ClaudeCodeEvent.ToolResult, is ClaudeCodeEvent.ToolUse -> true
            else -> false
        }

    private fun ClaudeCodeEvent.isConversationEvent(): Boolean = when (this) {
        is ClaudeCodeEvent.UserMessage, is ClaudeCodeEvent.AssistantText, is ClaudeCodeEvent.Thinking,
        is ClaudeCodeEvent.ToolUse, is ClaudeCodeEvent.ToolResult,
        -> true
        else -> false // CustomTitle / SystemNote / Subagent(...) 等：不是主线的对话
    }

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
     * @param lastEnd 上一回合怎么结束的（manager 的 `lastTurnEnd`，B8 才有）；null = 不知道，不出 ▶
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
            tail = tailOf(state.items),
            pendingInputs = state.items.count { it is ChatItem.UserText && it.queued },
            offerId = "${state.sessionId}@${since ?: 0L}",
            needsRelaunch = exited,
        )
    }
}
