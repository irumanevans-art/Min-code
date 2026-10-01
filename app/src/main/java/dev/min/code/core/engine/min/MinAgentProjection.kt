package dev.min.code.core.engine.min

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.session.withClippedResult
import kotlinx.serialization.json.JsonObject

data class MinPendingApproval(val id: ApprovalId, val callId: CallId, val input: JsonObject, val ask: PermissionAsk)
data class MinAgentView(val items: List<ChatItem>, val pendingApprovals: List<MinPendingApproval>, val status: SessionStatus)

/** Min 填 ChatItem 的唯一入口；展示信息与模型历史分开，恢复后仍能解释旧回合的结局。 */
fun projectMinAgent(state: SessionState, streamingText: String = "", streamingThinking: String = ""): MinAgentView {
    val items = mutableListOf<ChatItem>()
    fun parts(messageId: String, content: List<Part>) {
        content.forEachIndexed { index, part ->
            val id = "$messageId:$index"
            when (part) {
                is TextPart -> if (part.text.isNotEmpty()) items += ChatItem.AssistantText(id, part.text)
                is ThinkingPart -> items += ChatItem.Thinking(id, part.text)
                is RedactedThinkingPart -> items += ChatItem.Thinking(id, "[加密思考]")
                is ToolCallPart -> {
                    val phase = state.turn?.calls?.get(part.callId)?.phase
                    val result = state.toolResults[part.callId] ?: (phase as? CallPhase.Done)?.result
                    val denied = phase is CallPhase.Denied
                    val error = result?.isError == true || denied
                    val status = when { error -> ChatItem.ToolCall.Status.Error; result != null -> ChatItem.ToolCall.Status.Done
                        else -> ChatItem.ToolCall.Status.Running }
                    val text = result?.content?.filterIsInstance<TextPart>()?.joinToString("\n") { it.text }
                        ?: (phase as? CallPhase.Denied)?.note
                    val card = ChatItem.ToolCall(id, part.callId.value, part.name, part.input, status, isError = error)
                        .withClippedResult(text)
                    items += card.copy(resultTotalChars = maxOf(card.resultTotalChars ?: 0, result?.fullChars ?: 0).takeIf { it > 0 })
                }
                else -> Unit
            }
        }
    }
    fun notices(after: MessageId?) {
        state.presentation.filter { it.afterMessageId == after }.forEach { entry ->
            val id = "notice:${entry.record.seq}"
            if (entry.partialParts.isNotEmpty()) parts(entry.partialMessageId?.value ?: "$id:partial", entry.partialParts)
            when (val event = entry.record.event) {
                is Note -> items += ChatItem.Note(id, event.text, event.level == NoteLevel.Error)
                is TurnEnded -> when (val outcome = event.outcome) {
                    is TurnOutcome.Failed -> items += ChatItem.Note(id, outcome.message, isError = true)
                    is TurnOutcome.Interrupted -> items += ChatItem.Note(id, "回合已中断（${outcome.by}）")
                    TurnOutcome.Completed -> Unit
                }
                else -> Unit
            }
        }
    }
    notices(null)
    state.history.forEach { entry ->
        when (entry) {
            is HistoryEntry.User -> items += ChatItem.UserText(entry.messageId.value,
                entry.parts.joinToString("\n") { if (it is TextPart) it.text else "[附件]" })
            is HistoryEntry.Assistant -> parts(entry.messageId.value, entry.parts)
        }
        notices(entry.messageId)
    }
    state.turn?.openRequest?.let { request ->
        val id = request.messageId?.value ?: "stream:${request.requestId.value}"
        val persistedText = request.segments.filterIsInstance<TextPart>().joinToString("") { it.text }
        val persistedThinking = request.segments.filterIsInstance<ThinkingPart>().joinToString("") { it.text }
        val thinking = streamingThinking.ifEmpty { persistedThinking }
        val text = streamingText.ifEmpty { persistedText }
        if (thinking.isNotEmpty()) items += ChatItem.Thinking("$id:thinking", thinking)
        if (text.isNotEmpty()) items += ChatItem.AssistantText("$id:text", text)
    }
    state.inbox.forEach { input -> items += ChatItem.UserText("queued:${input.clientMsgId.value}",
        input.parts.filterIsInstance<TextPart>().joinToString("\n") { it.text }, queued = true) }
    val approvals = state.turn?.calls?.values.orEmpty().mapNotNull { call ->
        (call.phase as? CallPhase.AwaitingApproval)?.let { MinPendingApproval(it.approvalId, call.callId, call.input, it.ask) }
    }
    val lastOutcome = (state.presentation.lastOrNull { it.record.event is TurnEnded }?.record?.event as? TurnEnded)?.outcome
    val status = when { state.turn != null -> SessionStatus.Running; lastOutcome is TurnOutcome.Failed -> SessionStatus.Failed
        else -> SessionStatus.Idle }
    return MinAgentView(items, approvals, status)
}
