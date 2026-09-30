package dev.min.agent.kernel

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome

/**
 * 进程被杀后，打开会话时要补的事件。纯函数：输入折叠好的状态，输出要追加的事件；空列表 = 不用恢复。
 *
 * ## 策略：把没完成的回合合法地收尾，而不是接着跑
 *
 * 每个没有结果的工具调用都补一条**说清楚发生了什么**的结果，再补一条「回合被进程死亡中断」。
 * 这样历史永远合法（每个 tool_call 都有 tool_result），下一次请求的前缀与上次一致、缓存不断；
 * 用户在界面上点「继续」时开一个新回合（trigger = Resume），模型看得到哪些做完了、哪些结果未知，自己决定怎么接。
 *
 * 不自动继续，是手机上的安全默认（docs/agent/02-总体架构.md §5）：进程被杀往往意味着用户不在场。
 * 「只读工具直接重跑」「后台进程重新挂上」这类优化，等有数据证明值得再加，不影响本函数的形状。
 *
 * 半截回答（已落盘的 AssistantSegment）只给界面看，不进模型历史：它可能停在一个没说完的工具调用中间。
 */
fun planRecovery(state: SessionState): List<SessionEvent> {
    val turn = state.turn ?: return emptyList()
    val outcomes = turn.calls.values
        .filter { it.phase !is CallPhase.Done }
        .map { call -> ToolOutcome(turn.turnId, call.callId, interruptedResult(call)) }
    // 进程死在「回复已落盘、调用还没规划」之间：这些调用在 turn.calls 里根本不存在。
    // 先补一条规划再补结果，而不是放松折叠的校验——日志如实记下「规划了、没执行」
    val unplanned = state.history
        .filterIsInstance<HistoryEntry.Assistant>()
        .flatMap { it.toolCalls }
        .filter { it.callId !in state.toolResults && it.callId !in turn.calls }
        .flatMap { call ->
            listOf(
                ToolPlanned(turn.turnId, call.callId, call.name, call.input, ResumePolicy.Unknown),
                ToolOutcome(turn.turnId, call.callId, notStartedResult()),
            )
        }
    return outcomes + unplanned + TurnEnded(
        turnId = turn.turnId,
        outcome = TurnOutcome.Interrupted(InterruptedBy.ProcessDeath),
        usage = turn.usage,
        // 进程死了多久不可知（纯函数也不该去读时钟），记 0；界面用事件时间戳算「中断于何时」
        durationMs = 0,
    )
}

/**
 * 给模型看的话要让它能做对下一步：没执行的就说没执行（可以放心重来），执行了一半的要明说结果未知，
 * 并提醒别盲目重试有副作用的操作（DeepSeek Harness / pi 的同类做法）。
 */
internal fun interruptedResult(call: CallState): ToolResult {
    val text = when (val phase = call.phase) {
        CallPhase.Planned, is CallPhase.AwaitingApproval, CallPhase.Approved -> return notStartedResult()
        is CallPhase.Denied ->
            "用户拒绝了这次调用。" + (phase.note?.let { "用户的说明：$it" } ?: "")
        is CallPhase.EffectPending -> when (call.resumePolicy) {
            ResumePolicy.Replay ->
                "这次调用执行到一半时会话被中断，没有拿到结果。它是只读的，可以直接重新调用。"
            ResumePolicy.Reattach, ResumePolicy.Unknown -> buildString {
                append("这次调用执行到一半时会话被中断，结果未知：它可能已经部分或全部生效。")
                append("不要盲目重试有副作用的操作，先检查当前状态再决定。")
                phase.handle?.logPath?.let { append("它的输出日志在 $it。") }
            }
        }
        is CallPhase.Done -> error("已完成的调用不需要恢复：${call.callId}")
    }
    return ToolResult(content = listOf(TextPart(text)), isError = true)
}

private fun notStartedResult() = ToolResult(
    content = listOf(TextPart("这次调用没有执行：会话在它开始之前被中断了。需要的话可以重新调用。")),
    isError = true,
)
