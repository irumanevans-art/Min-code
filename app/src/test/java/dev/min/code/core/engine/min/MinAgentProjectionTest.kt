package dev.min.code.core.engine.min

import dev.min.agent.kernel.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.MAX_TOOL_RESULT_CHARS
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class MinAgentProjectionTest {
    @Test fun handbackReportUsesExistingCardWithReviewLabelAndOriginalBody() {
        val report = """<subagent-report id="child" type="Explore" fork="false" status="completed" review="approved" handback="true" tools="2" tokens="10" duration-ms="20">
safe report
</subagent-report>"""
        val s = events().copy(history = listOf(HistoryEntry.User(MessageId("report"), listOf(TextPart(report)), InputOrigin.Agent(SessionId("child"), AgentInputKind.Report, tid))))
        val card = projectMinItems(s, FakeText()).filterIsInstance<ChatItem.AgentReport>().single()
        assertEquals("已经过审查\n\nsafe report", card.body)
        assertFalse(card.isError); assertEquals(2, card.toolCalls)
        val manual = s.copy(history = listOf(HistoryEntry.User(MessageId("report"), listOf(TextPart(report.replace("approved", "manual"))), InputOrigin.Agent(SessionId("child"), AgentInputKind.Report, tid))))
        assertEquals("safe report", projectMinItems(manual, FakeText()).filterIsInstance<ChatItem.AgentReport>().single().body)
    }
    private val model = ModelRef("provider", "model")
    private val tid = TurnId("turn")
    private val rid = RequestId("request")
    private val mid = MessageId("assistant")
    private val cid = CallId("call")
    private val input = JsonObject(emptyMap())
    private fun events(vararg rest: SessionEvent): SessionState = fold(
        LogHeader(1, SessionId("s"), 100),
        (listOf(SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default)) + rest)
            .mapIndexed { index, event -> EventRecord(index + 1L, 100 + index.toLong(), event) },
    )
    private fun state(phase: CallPhase? = null, result: ToolResult? = null): SessionState {
        val base = events().copy(history = listOf(
            HistoryEntry.User(MessageId("user"), listOf(TextPart("question"))),
            HistoryEntry.Assistant(mid, listOf(ThinkingPart("thought", model), TextPart("answer"), ToolCallPart(cid, "Write", input))),
        ), toolResults = if (result == null) emptyMap() else mapOf(cid to result))
        return if (phase == null) base else base.copy(turn = TurnState(tid, TurnTrigger.User, 100, null,
            mapOf(cid to CallState(cid, "Write", input, ResumePolicy.Unknown, phase)), Usage()))
    }
    private fun card(s: SessionState) = projectMinAgent(s).items.filterIsInstance<ChatItem.ToolCall>().single()

    @Test fun historyTextThinkingAndStableToolIdentity() {
        val view = projectMinAgent(state())
        assertEquals("question", (view.items[0] as ChatItem.UserText).text)
        assertEquals("thought", (view.items[1] as ChatItem.Thinking).text)
        assertEquals("answer", (view.items[2] as ChatItem.AssistantText).text)
        assertEquals("call", card(state()).toolUseId)
        assertEquals(input, card(state()).input)
        assertEquals(view, projectMinAgent(state()))
    }
    @Test fun plannedApprovedAndEffectPendingAreRunning() {
        listOf(CallPhase.Planned, CallPhase.Approved, CallPhase.EffectPending(null)).forEach {
            assertEquals(ChatItem.ToolCall.Status.Running, card(state(it)).status)
            assertEquals(SessionStatus.Running, projectMinAgent(state(it)).status)
        }
    }
    @Test fun awaitingApprovalProvidesDescriptionAndRunningCard() {
        val ask = PermissionAsk("Write", "write a file", paths = listOf(PathAccess(GuestPath("/sdcard/a"), AccessKind.Write, PathScope.Device)))
        val phase = CallPhase.AwaitingApproval(ApprovalId("approval"), ask)
        val view = projectMinAgent(state(phase))
        assertEquals(ChatItem.ToolCall.Status.Running, card(state(phase)).status)
        assertEquals(listOf(MinPendingApproval(phase.approvalId, cid, input, ask)), view.pendingApprovals)
    }
    @Test fun completeAndErrorUseResultInsteadOfCurrentPhase() {
        val success = ToolResult(listOf(TextPart("written")))
        assertEquals(ChatItem.ToolCall.Status.Done, card(state(CallPhase.Done(success))).status)
        assertEquals("written", card(state(result = success)).result)
        val error = ToolResult(listOf(TextPart("failed")), isError = true)
        assertEquals(ChatItem.ToolCall.Status.Error, card(state(result = error)).status)
        assertTrue(card(state(result = error)).isError)
    }
    @Test fun denialIsErrorBeforeOutcomeIsCommitted() {
        val card = card(state(CallPhase.Denied("no")))
        assertEquals(ChatItem.ToolCall.Status.Error, card.status)
        assertEquals("no", card.result); assertTrue(card.isError)
    }
    @Test fun toolResultUsesSharedClippingWithoutBreakingSurrogatePair() {
        val text = "x".repeat(MAX_TOOL_RESULT_CHARS - 1) + "\uD83D\uDE00" + "tail"
        val card = card(state(result = ToolResult(listOf(TextPart(text)))))
        assertEquals(MAX_TOOL_RESULT_CHARS - 1, card.result!!.length)
        assertEquals(text.length.toLong(), card.resultTotalChars)
        val alreadyClipped = card(state(result = ToolResult(listOf(TextPart("preview")), fullChars = 100000)))
        assertEquals(100000L, alreadyClipped.resultTotalChars)
    }
    @Test fun interruptedAndFailedTurnsSurviveFoldAndUseCorrectColor() {
        val interrupted = events(TurnStarted(tid, TurnTrigger.User), TurnEnded(tid, TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), Usage(), 0))
        val note = projectMinAgent(interrupted).items.single() as ChatItem.Note
        assertFalse(note.isError); assertTrue(note.text.contains("ProcessDeath"))
        assertEquals(SessionStatus.Idle, projectMinAgent(interrupted).status)
        val failed = events(TurnStarted(tid, TurnTrigger.User), TurnEnded(tid, TurnOutcome.Failed("network failed"), Usage(), 0))
        assertTrue((projectMinAgent(failed).items.single() as ChatItem.Note).isError)
        assertEquals("network failed", (projectMinAgent(failed).items.single() as ChatItem.Note).text)
        assertEquals(SessionStatus.Failed, projectMinAgent(failed).status)
    }
    @Test fun notesKeepTimelinePositionAndDoNotEnterModelHistory() {
        val s = events(Note(NoteLevel.Info, "catalog fallback"), InputReceived(ClientMsgId("u"), listOf(TextPart("q")), Delivery.Queue),
            InputPromoted(ClientMsgId("u"), MessageId("u")), Note(NoteLevel.Error, "error"))
        val view = projectMinAgent(s)
        assertEquals("catalog fallback", (view.items[0] as ChatItem.Note).text)
        assertTrue(view.items[1] is ChatItem.UserText)
        assertTrue((view.items[2] as ChatItem.Note).isError)
        assertEquals(1, s.history.size)
        assertEquals(2, s.presentation.size)
    }
    @Test fun queuedInputsRemainMarkedAndOrdered() {
        val s = events().copy(inbox = listOf(
            PendingInput(ClientMsgId("q1"), listOf(TextPart("first")), Delivery.Queue),
            PendingInput(ClientMsgId("q2"), listOf(TextPart("second")), Delivery.Steer)))
        val items = projectMinAgent(s).items.filterIsInstance<ChatItem.UserText>()
        assertEquals(listOf("first", "second"), items.map { it.text }); assertTrue(items.all { it.queued })
    }
    @Test fun liveBodyOverridesPersistedPartialWithoutDuplicationAndClearsOnCompletion() {
        val s = events(TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, model, "fp"),
            AssistantSegment(tid, rid, mid, listOf(ThinkingPart("p", model), TextPart("partial"))))
        val view = projectMinAgent(s, "partial plus live", "thinking live")
        assertEquals(listOf("thinking live"), view.items.filterIsInstance<ChatItem.Thinking>().map { it.text })
        assertEquals(listOf("partial plus live"), view.items.filterIsInstance<ChatItem.AssistantText>().map { it.text })
        val completed = s.apply(EventRecord(5, 110, AssistantMessageCompleted(tid, rid, mid,
            listOf(TextPart("final")), Usage(), StopReason.EndTurn)))
        assertEquals(listOf("final"), projectMinAgent(completed, "stale live").items.filterIsInstance<ChatItem.AssistantText>().map { it.text })
    }
    @Test fun interruptedPartialStillAppearsAfterRecoveryClosesRequest() {
        val s = events(TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, model, "fp"),
            AssistantSegment(tid, rid, mid, listOf(TextPart("persisted partial"))),
            TurnEnded(tid, TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), Usage(), 0))
        assertEquals("persisted partial", (projectMinAgent(s).items[0] as ChatItem.AssistantText).text)
        assertTrue(projectMinAgent(s).items[1] is ChatItem.Note)
        assertTrue(s.history.isEmpty())
    }
    @Test fun successfulNewTurnClearsOldFailedStatus() {
        val s = events(TurnStarted(tid, TurnTrigger.User), TurnEnded(tid, TurnOutcome.Failed("old"), Usage(), 0),
            TurnStarted(TurnId("new"), TurnTrigger.Resume), TurnEnded(TurnId("new"), TurnOutcome.Completed, Usage(), 20))
        assertEquals(SessionStatus.Idle, projectMinAgent(s).status)
        assertEquals(1, projectMinAgent(s).items.size)
    }
}
