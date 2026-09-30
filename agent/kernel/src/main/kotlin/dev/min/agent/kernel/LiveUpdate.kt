package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TurnId

/**
 * 瞬时流：只为界面观感，不落盘、不保证送达（docs/agent/02-总体架构.md §6 的「两条流」）。
 * 丢了也无妨——完整的东西都会以持久事件的形式出现在状态里。
 */
sealed interface LiveUpdate {
    data class Text(val turnId: TurnId, val text: String) : LiveUpdate
    data class Thinking(val turnId: TurnId, val text: String) : LiveUpdate
    data class ToolCallStarted(val turnId: TurnId, val index: Int, val name: String) : LiveUpdate
    data class ToolInput(val turnId: TurnId, val index: Int, val partialJson: String) : LiveUpdate
    data class ToolOutput(val callId: CallId, val text: String) : LiveUpdate
    data class ToolStatus(val callId: CallId, val text: String) : LiveUpdate
}
