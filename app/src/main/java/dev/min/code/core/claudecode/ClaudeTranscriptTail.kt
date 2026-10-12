package dev.min.code.core.claudecode

/**
 * 回放 transcript 之后，这个会话是不是停在回合中途：App 被杀 / 关机后重开会话时用（设计 §6.4 表最后一行）。
 * 放在 `core/claudecode` 是因为 [ClaudeCodeManager.openSession] 自己要在回放完之后记一条结局；
 * 引擎层的 `ClaudeResume.endedMidTurn` 只是转发它。
 *
 * 规则与真 CLI 的 `CLAUDE_CODE_RESUME_INTERRUPTED_TURN` 判定对拍过（2.1.295，12 份自然 transcript 加 39 份手工变体，
 * 隔离 home + 本机 mock，见 B8 汇报）。忽略标题、系统提示、内部记录、子 agent 的事件，看最后一条**对话事件**：
 * - 用户消息 / 工具结果 / 带工具调用的助手消息 / 仅有思考的助手消息 = 模型还欠一个回答 -> true；
 * - **API 报错说明**（[ClaudeCodeEvent.AssistantText.apiError]）= 那一轮的请求失败了，没有回答 -> true；
 * - **中断标记**（用户按 ■ 时 CLI 写下的 `[Request interrupted by user]` / `... for tool use]`）= 用户有意停的，
 *   CLI 不会替人续 -> false（Min 的 ▶ 在会话还开着时由 manager 的 `lastTurnEnd` 给，重开后与 CLI 一致不再出）；
 * - 助手正文 = 回答已落盘 -> false（续跑会变成 assistant prefill，许多模型直接 400）。
 *
 * 已知与 CLI 的细差（宁可多给一个 ▶）：思考后紧跟 CLI 自己插的「无可见输出」提示又只出思考的罕见情形，CLI 不续、这里续。
 */
internal fun transcriptEndedMidTurn(events: List<ClaudeCodeEvent>): Boolean =
    when (val last = events.lastOrNull { it.isConversationEvent() }) {
        is ClaudeCodeEvent.UserMessage -> !last.text.trimStart().startsWith(INTERRUPT_MARKER_PREFIX)
        is ClaudeCodeEvent.AssistantText -> last.apiError
        is ClaudeCodeEvent.ToolResult, is ClaudeCodeEvent.ToolUse, is ClaudeCodeEvent.Thinking -> true
        else -> false
    }

/** CLI 在用户打断后写进 transcript 的那条用户消息（带 `for tool use` 的变体共用这个前缀） */
private const val INTERRUPT_MARKER_PREFIX = "[Request interrupted by user"

private fun ClaudeCodeEvent.isConversationEvent(): Boolean = when (this) {
    is ClaudeCodeEvent.UserMessage, is ClaudeCodeEvent.AssistantText, is ClaudeCodeEvent.Thinking,
    is ClaudeCodeEvent.ToolUse, is ClaudeCodeEvent.ToolResult,
    -> true
    else -> false // CustomTitle / SystemNote / Subagent(...) 等：不是主线的对话
}
