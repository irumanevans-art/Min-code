package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.TurnId

/**
 * 瞬时流：只为界面观感，不落盘、不保证送达（docs/agent/02-总体架构.md §6 的「两条流」）。
 * 丢了也无妨——完整的东西都会以持久事件的形式出现在状态里。
 */
sealed interface LiveUpdate {
    /*
     * 正文类增量带上 [RequestId]：一个回合里有好几次请求，落盘的完成事件与最后几个增量走两条流、先后没有保证，
     * 界面见到新的 requestId 就清空缓冲，旧增量不会混进下一次请求的显示里
     */
    data class Text(val turnId: TurnId, val requestId: RequestId, val text: String) : LiveUpdate
    data class Thinking(val turnId: TurnId, val requestId: RequestId, val text: String) : LiveUpdate
    data class ToolCallStarted(val turnId: TurnId, val requestId: RequestId, val index: Int, val name: String) : LiveUpdate
    data class ToolInput(val turnId: TurnId, val requestId: RequestId, val index: Int, val partialJson: String) : LiveUpdate
    data class ToolOutput(val callId: CallId, val text: String) : LiveUpdate
    data class ToolStatus(val callId: CallId, val text: String) : LiveUpdate
}
