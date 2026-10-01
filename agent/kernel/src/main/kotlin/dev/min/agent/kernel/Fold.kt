package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnStarted

/**
 * 日志 → 状态。纯函数：同一段日志永远折叠出同一个状态，这是恢复测试和确定性回归的基础。
 *
 * 严格：事件顺序不合法（例如给不存在的调用写结果）直接抛 [IllegalStateException]。
 * 日志只由内核自己写，顺序不对说明内核有 bug，宁可大声失败，也不要带着错的状态继续跑。
 */
fun fold(header: LogHeader, records: List<EventRecord>): SessionState {
    val first = records.firstOrNull()?.event
    check(first is SessionCreated) { "日志第一条事件必须是 SessionCreated，实际是 $first" }
    val initial = SessionState(
        header = header,
        created = first,
        lastSeq = 0,
        settings = SessionSettings(first.cwd, first.model, first.mode, first.effort),
        inbox = emptyList(),
        seenInputs = emptySet(),
        history = emptyList(),
        toolResults = emptyMap(),
        turn = null,
        usage = Usage(),
    )
    return records.fold(initial) { state, record -> state.apply(record) }
}

fun SessionState.apply(record: EventRecord): SessionState {
    check(record.seq == lastSeq + 1) { "seq 不连续：当前 $lastSeq，收到 ${record.seq}" }
    val next = when (val e = record.event) {
        is SessionCreated -> {
            check(lastSeq == 0L) { "SessionCreated 只能是第一条事件" }
            this
        }
        // 取最后一条：以后显式刷新记忆时再追加一条，整份替换前一份快照
        is MemoryLoaded -> copy(memory = e.files)
        is SettingsChanged -> copy(
            settings = settings.copy(
                cwd = e.cwd ?: settings.cwd,
                model = e.model ?: settings.model,
                mode = e.mode ?: settings.mode,
                effort = e.effort ?: settings.effort,
            ),
        )
        is InputReceived -> {
            check(e.clientMsgId !in seenInputs) { "重复的输入 ${e.clientMsgId}" }
            copy(inbox = inbox + PendingInput(e.clientMsgId, e.parts, e.delivery), seenInputs = seenInputs + e.clientMsgId)
        }
        is InputPromoted -> {
            val input = inbox.firstOrNull { it.clientMsgId == e.clientMsgId }
                ?: error("提升了不在收件箱里的输入 ${e.clientMsgId}")
            copy(
                inbox = inbox - input,
                history = history + HistoryEntry.User(e.messageId, input.parts),
            )
        }
        is InputWithdrawn -> copy(inbox = inbox.filterNot { it.clientMsgId == e.clientMsgId })
        is TurnStarted -> {
            check(turn == null) { "上一回合 ${turn?.turnId} 还没结束就开始了 ${e.turnId}" }
            copy(turn = TurnState(e.turnId, e.trigger, record.atMs, openRequest = null, calls = emptyMap(), usage = Usage()))
        }
        is ModelRequestStarted -> updateTurn(e.turnId) {
            it.copy(openRequest = OpenRequest(e.requestId, e.attempt, messageId = null, segments = emptyList()))
        }
        is AssistantSegment -> updateTurn(e.turnId) { t ->
            val open = checkNotNull(t.openRequest) { "没有进行中的请求却收到了片段" }
            check(open.requestId == e.requestId) { "片段属于 ${e.requestId}，当前请求是 ${open.requestId}" }
            t.copy(openRequest = open.copy(messageId = e.messageId, segments = open.segments + e.parts))
        }
        is AssistantMessageCompleted -> updateTurn(e.turnId) { t ->
            check(t.openRequest?.requestId == e.requestId) { "完成的是 ${e.requestId}，当前请求是 ${t.openRequest?.requestId}" }
            t.copy(openRequest = null, usage = t.usage + e.usage)
        }.copy(history = history + HistoryEntry.Assistant(e.messageId, e.parts))
        is ModelRequestFailed -> updateTurn(e.turnId) { t ->
            check(t.openRequest?.requestId == e.requestId) { "失败的是 ${e.requestId}，当前请求是 ${t.openRequest?.requestId}" }
            t.copy(openRequest = null)
        }
        is ToolPlanned -> updateTurn(e.turnId) { t ->
            check(e.callId !in t.calls) { "重复规划调用 ${e.callId}" }
            t.copy(calls = t.calls + (e.callId to CallState(e.callId, e.tool, e.input, e.resumePolicy, CallPhase.Planned)))
        }
        is ApprovalRequested -> updateCall(e.turnId, e.callId) { phase ->
            check(phase == CallPhase.Planned) { "只有刚规划的调用才能请求审批，${e.callId} 处于 $phase" }
            CallPhase.AwaitingApproval(e.approvalId, e.ask)
        }
        is ApprovalResolved -> {
            val t = checkNotNull(turn) { "没有进行中的回合却收到审批结果 ${e.approvalId}" }
            val call = t.calls.values.firstOrNull { (it.phase as? CallPhase.AwaitingApproval)?.approvalId == e.approvalId }
                ?: error("没有在等 ${e.approvalId} 的调用")
            val resolved = when (val choice = e.choice) {
                is ApprovalChoice.Deny -> CallPhase.Denied(choice.note)
                ApprovalChoice.Once, is ApprovalChoice.Always -> CallPhase.Approved
            }
            updateCall(t.turnId, call.callId) { resolved }
        }
        is ToolEffectPending -> updateCall(e.turnId, e.callId) { phase ->
            check(phase == CallPhase.Planned || phase == CallPhase.Approved) {
                "${e.callId} 处于 $phase，不能开始副作用"
            }
            CallPhase.EffectPending(e.handle)
        }
        is ToolOutcome -> updateCall(e.turnId, e.callId) { phase ->
            check(phase !is CallPhase.Done) { "${e.callId} 已经有结果了" }
            CallPhase.Done(e.result)
        }.copy(toolResults = toolResults + (e.callId to e.result))
        is TurnEnded -> {
            val t = checkNotNull(turn) { "没有进行中的回合却收到了结束 ${e.turnId}" }
            check(t.turnId == e.turnId) { "结束的是 ${e.turnId}，当前回合是 ${t.turnId}" }
            copy(turn = null, usage = usage + t.usage, presentation = presentation + SessionPresentation(
                record, history.lastOrNull()?.messageId, t.openRequest?.messageId, t.openRequest?.segments.orEmpty(),
            ))
        }
        is Note -> copy(presentation = presentation + SessionPresentation(record, history.lastOrNull()?.messageId))
    }
    return next.copy(lastSeq = record.seq)
}

private fun SessionState.updateTurn(turnId: TurnId, change: (TurnState) -> TurnState): SessionState {
    val t = checkNotNull(turn) { "没有进行中的回合，事件却属于 $turnId" }
    check(t.turnId == turnId) { "事件属于 $turnId，当前回合是 ${t.turnId}" }
    return copy(turn = change(t))
}

private fun SessionState.updateCall(
    turnId: TurnId,
    callId: CallId,
    change: (CallPhase) -> CallPhase,
): SessionState = updateTurn(turnId) { t ->
    val call = t.calls[callId] ?: error("回合 $turnId 里没有调用 $callId")
    t.copy(calls = t.calls + (callId to call.copy(phase = change(call.phase))))
}
