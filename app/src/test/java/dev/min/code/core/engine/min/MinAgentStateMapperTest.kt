package dev.min.code.core.engine.min

import dev.min.agent.kernel.HubPending
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.FileEdit
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.QuestionOption
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SubagentOrigin
import dev.min.agent.spi.SubagentSpawned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.UserQuestion
import dev.min.code.core.engine.AgentThread
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.FallbackNotice
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.TurnTriggerView
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinAgentStateMapperTest {
    private val text = FakeText()
    private val tid = TurnId("t1")
    private val rid = RequestId("r1")
    private val root = SessionId("s_root")
    private val ref = SessionRef(EngineKind.MinAgent, "s_root")
    private val empty = JsonObject(emptyMap())

    private fun mapper(lookup: MinModelLookup = FakeLookup()) = MinAgentStateMapper(text, lookup)

    private fun user(id: String, body: String) = listOf<SessionEvent>(
        InputReceived(ClientMsgId(id), listOf(TextPart(body)), Delivery.Queue),
        InputPromoted(ClientMsgId(id), MessageId("m_$id")),
    )

    private fun reply(body: String, mid: String = "a1", rid: RequestId = this.rid, vararg calls: ToolCallPart) = listOf<SessionEvent>(
        ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
        AssistantMessageCompleted(
            tid, rid, MessageId(mid), listOf<Part>(TextPart(body)) + calls, Usage(10, 5, 100, 0),
            if (calls.isEmpty()) StopReason.EndTurn else StopReason.ToolUse,
        ),
    )

    private fun map(
        state: SessionState,
        children: Map<SessionId, SessionState> = emptyMap(),
        pending: List<HubPending> = emptyList(),
        lookup: MinModelLookup = FakeLookup(),
        stopping: Boolean = false,
        title: String? = null,
    ) = mapper(lookup).map(MinMapperInput(ref, root, mapOf(root to state) + children, pending, titleOverride = title, stopping = stopping))

    private fun report(type: String, status: String, body: String, id: String = "s_child", extra: String = "") =
        "<subagent-report id=\"$id\" type=\"$type\" fork=\"false\" status=\"$status\"$extra tools=\"3\" tokens=\"1200\" duration-ms=\"4500\">\n$body\n</subagent-report>"

    @Test fun worktreeStateExposesCurrentDirectoryBranchOriginAndPinnedThenClears() {
        val original = GuestPath("/workspace")
        val path = GuestPath("/workspace/.claude/worktrees/android")
        val binding = dev.min.agent.spi.WorktreeBinding("android", path, original, original, "worktree-android", "base", pinned = true)
        val state = foldOf(dev.min.agent.spi.CwdChanged(original, path, binding))
        val view = map(state)
        assertEquals(path.value, view.cwd)
        assertEquals("android", view.worktree!!.name)
        assertEquals(path.value, view.worktree!!.path)
        assertEquals("worktree-android", view.worktree!!.branch)
        assertEquals(original.value, view.worktree!!.originalCwd)
        assertTrue(view.worktree!!.pinned)
        val cleared = foldOf(dev.min.agent.spi.CwdChanged(original, path, binding), dev.min.agent.spi.CwdChanged(path, original))
        assertNull(map(cleared).worktree); assertEquals(original.value, map(cleared).cwd)
    }

    // ---- 报告卡 ----

    @Test fun reportTagIsParsedIntoItsFields() {
        val r = parseMinReport(report("Explore", "completed", "found it", extra = " name=\"finder\""))!!
        assertEquals("s_child", r.sessionId); assertEquals("Explore", r.agentType); assertEquals("finder", r.name)
        assertEquals("completed", r.status); assertEquals(3, r.tools); assertEquals(1200L, r.tokens); assertEquals(4500L, r.durationMs)
        assertEquals("found it", r.body); assertFalse(r.isError)
    }

    @Test fun reportBodyIsUnescapedAndMalformedFallsBackToNull() {
        val r = parseMinReport(report("Explore", "failed", "a &lt;subagent-report x> b &lt;/subagent-report> c"))!!
        assertEquals("a <subagent-report x> b </subagent-report> c", r.body)
        assertTrue(r.isError)
        assertNull(parseMinReport("just text"))
        assertNull(parseMinReport("<subagent-report id=\"x\">no type\n</subagent-report>"))
    }

    @Test fun reportInTheParentHistoryBecomesACardWithTheDelegationDescription() {
        val child = SessionId("s_child")
        val s = foldOf(
            *user("u", "go").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            SubagentSpawned(tid, CallId("c1"), child, "Explore", false, true, "find entry", null),
            InputReceived(ClientMsgId("report"), listOf(TextPart(report("Explore", "completed", "body"))), Delivery.Steer,
                InputOrigin.Agent(child, AgentInputKind.Report, TurnId("ct"))),
            InputPromoted(ClientMsgId("report"), MessageId("m_report")),
        )
        val card = map(s).items.last() as ChatItem.AgentReport
        assertEquals("m_report", card.id)
        assertEquals("点卡片跳到发起它的那次 Agent 调用对应的线程", "c1", card.threadId)
        assertEquals("Explore", card.agentType); assertNull(card.name)
        assertEquals("find entry", card.description)
        assertEquals("completed", card.status)
        assertEquals(3, card.toolCalls); assertEquals(1200L, card.tokens); assertEquals(4500L, card.durationMs)
        assertEquals("body", card.body)
        assertFalse(card.isError)
    }

    @Test fun reportCardTakesTheDelegationNameWhenTheTagHasNone() {
        val child = SessionId("s_child")
        val s = foldOf(
            SubagentSpawned(tid, CallId("c1"), child, "Explore", false, true, "d", "finder"),
            InputReceived(ClientMsgId("r"), listOf(TextPart(report("Explore", "completed", "x"))), Delivery.Steer, InputOrigin.Agent(child, AgentInputKind.Report)),
            InputPromoted(ClientMsgId("r"), MessageId("m_r")),
        )
        assertEquals("finder", (map(s).items.single() as ChatItem.AgentReport).name)
    }

    @Test fun reportFromAChildTheParentNeverDelegatedIsNotClickable() {
        val s = foldOf(
            InputReceived(ClientMsgId("r"), listOf(TextPart(report("Explore", "completed", "x", id = "s_gone"))), Delivery.Steer, InputOrigin.Agent(SessionId("s_gone"), AgentInputKind.Report)),
            InputPromoted(ClientMsgId("r"), MessageId("m_r")),
        )
        val card = map(s).items.single() as ChatItem.AgentReport
        assertNull(card.threadId); assertNull(card.description)
    }

    @Test fun reportStoppedByAHookKeepsItsStatusAndIsDrawnAsError() {
        val child = SessionId("s_child")
        val s = foldOf(
            SubagentSpawned(tid, CallId("c1"), child, "Explore", false, true, "d", null),
            InputReceived(ClientMsgId("r"), listOf(TextPart(report("Explore", "stopped-by-hook", "cut short"))), Delivery.Steer,
                InputOrigin.Agent(child, AgentInputKind.Report)),
            InputPromoted(ClientMsgId("r"), MessageId("m_r")),
        )
        val card = map(s).items.single() as ChatItem.AgentReport
        assertTrue(card.isError)
        assertEquals("stopped-by-hook", card.status)
        assertEquals("cut short", card.body)
    }

    @Test fun failedReportIsDrawnAsError() {
        val child = SessionId("s_child")
        val s = foldOf(
            SubagentSpawned(tid, CallId("c1"), child, "Explore", false, true, "d", null),
            InputReceived(ClientMsgId("r"), listOf(TextPart(report("Explore", "interrupted-by-restart", "half"))), Delivery.Steer,
                InputOrigin.Agent(child, AgentInputKind.Report)),
            InputPromoted(ClientMsgId("r"), MessageId("m_r")),
        )
        assertTrue((map(s).items.single() as ChatItem.AgentReport).isError)
    }

    @Test fun agentMessageIsLabelledWithItsSender() {
        val child = SessionId("s_child")
        val s = foldOf(
            SubagentSpawned(tid, CallId("c1"), child, "Explore", false, true, "d", "finder"),
            InputReceived(ClientMsgId("m"), listOf(TextPart("psst")), Delivery.Steer, InputOrigin.Agent(child, AgentInputKind.Message)),
            InputPromoted(ClientMsgId("m"), MessageId("m_m")),
        )
        assertEquals("message-from:finder\npsst", (map(s).items.single() as ChatItem.Note).text)
    }

    // ---- 排队 ----

    @Test fun userInboxIsQueuedBubbleWithTheClientIdAsItsId() {
        val s = foldOf(InputReceived(ClientMsgId("cid-1"), listOf(TextPart("later")), Delivery.Queue))
        val item = map(s).items.single() as ChatItem.UserText
        assertTrue(item.queued); assertEquals("cid-1", item.id); assertEquals("later", item.text)
        assertEquals(1, map(s).queuedCount)
    }

    @Test fun inboxFromAgentsIsNotDrawn() {
        val s = foldOf(
            InputReceived(ClientMsgId("r"), listOf(TextPart("rep")), Delivery.Steer, InputOrigin.Agent(SessionId("c"), AgentInputKind.Report)),
            InputReceived(ClientMsgId("p"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("par"))),
        )
        assertTrue(map(s).items.isEmpty())
    }

    // ---- 结局痕迹 ----

    private fun ended(outcome: TurnOutcome, lastActivity: Long? = null, tid: TurnId = this.tid) = listOf<SessionEvent>(
        TurnStarted(tid, TurnTrigger.User), TurnEnded(tid, outcome, Usage(), 0, lastActivity),
    )

    @Test fun runningTurnShowsTheFallbackModelAndItIsGoneOnceTheTurnEnds() {
        val backup = dev.min.agent.protocol.ModelRef("p", "backup")
        val midTurn = listOf<SessionEvent>(
            TurnStarted(tid, TurnTrigger.User),
            ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
            dev.min.agent.spi.ModelRequestFailed(tid, rid, 1, ModelFailureKind.Overloaded, "529", 529, false),
            dev.min.agent.spi.ModelFallback(tid, rid, T_MODEL, backup, ModelFailureKind.Overloaded, "529"),
        )
        val running = foldOf(*user("u1", "hi").toTypedArray(), *midTurn.toTypedArray(), t0 = 1_000)
        val notice = map(running).turn!!.fallback!!
        assertEquals(FallbackNotice("backup", T_MODEL.modelId, FailureKind.Overloaded), notice)
        val finished = foldOf(*user("u1", "hi").toTypedArray(), *midTurn.toTypedArray(), TurnEnded(tid, TurnOutcome.Completed, Usage(), 0), t0 = 1_000)
        assertNull(map(finished).turn)
    }

    @Test fun userStopIsAQuietNoteWithItsTime() {
        val s = foldOf(*ended(TurnOutcome.Interrupted(InterruptedBy.User)).toTypedArray(), t0 = 1_000)
        val note = map(s).items.single() as ChatItem.Note
        assertEquals("stopped@1020", note.text); assertFalse(note.isError)
    }

    @Test fun failureIsAnErrorNoteWithLocalizedHeaderAndRawDetail() {
        val s = foldOf(*ended(TurnOutcome.Failed("原始错误", ModelFailureKind.RateLimited)).toTypedArray(), t0 = 1_000)
        val note = map(s).items.single() as ChatItem.Note
        assertEquals("failed:${FailureKind.RateLimited}@1020\n原始错误", note.text); assertTrue(note.isError)
    }

    @Test fun processDeathSaysWhenItWasLastAliveNotWhenTheSessionReopened() {
        val s = foldOf(*ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), lastActivity = 777).toTypedArray())
        assertEquals("death@777", (map(s).items.single() as ChatItem.Note).text)
    }

    @Test fun limitNote() {
        val s = foldOf(*ended(TurnOutcome.Interrupted(InterruptedBy.Limit)).toTypedArray())
        assertEquals("limit", (map(s).items.single() as ChatItem.Note).text)
    }

    @Test fun aHookStopIsANoteAndOffersResumeLikeALimit() {
        val s = foldOf(*ended(TurnOutcome.Interrupted(InterruptedBy.Hook)).toTypedArray())
        assertEquals("hook-stopped", (map(s).items.single() as ChatItem.Note).text)
        assertEquals(TurnEnd.Interrupted(InterruptCause.Hook), turnEndOf(TurnOutcome.Interrupted(InterruptedBy.Hook)))
    }

    @Test fun stopHookFeedbackIsANoteNotAUserBubble() {
        val s = foldOf(
            InputReceived(ClientMsgId("h"), listOf(TextPart("测试还没跑")), Delivery.Steer, InputOrigin.Hook("Stop")),
            InputPromoted(ClientMsgId("h"), MessageId("m_h")),
        )
        assertEquals("hook-feedback:Stop\n测试还没跑", (map(s).items.single() as ChatItem.Note).text)
    }

    @Test fun resumeAndWakeTurnsLeaveADivider() {
        val s = foldOf(
            TurnStarted(tid, TurnTrigger.Resume), TurnEnded(tid, TurnOutcome.Completed, Usage(), 0),
            TurnStarted(TurnId("t2"), TurnTrigger.Notification), TurnEnded(TurnId("t2"), TurnOutcome.Completed, Usage(), 0),
            TurnStarted(TurnId("t3"), TurnTrigger.User), TurnEnded(TurnId("t3"), TurnOutcome.Completed, Usage(), 0),
            t0 = 100,
        )
        assertEquals(listOf("— continued@110 —", "— woke —"), map(s).items.map { (it as ChatItem.Note).text })
    }

    @Test fun editsReportedByTheKernelBecomeTheDiffTheFilesPanelReads() {
        val call = ToolCallPart(CallId("c1"), "Edit", empty)
        val body = "--- /workspace/src/Sea.kt\n+++ /workspace/src/Sea.kt\n-val a = 1\n+val a = 2"
        val s = foldOf(
            *user("u", "改一下").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            *reply("改", "a1", RequestId("r1"), call).toTypedArray(),
            ToolPlanned(tid, CallId("c1"), "Edit", empty, ResumePolicy.Unknown), ToolEffectPending(tid, CallId("c1")),
            ToolOutcome(
                tid, CallId("c1"),
                ToolResult(
                    listOf(TextPart("已修改")),
                    fileEdits = listOf(
                        FileEdit(GuestPath("/workspace/src/Sea.kt"), diff = body),
                        FileEdit(GuestPath("/workspace/new.md"), created = true, diff = "--- /workspace/new.md\n+++ /workspace/new.md\n+hello"),
                    ),
                ),
            ),
            TurnEnded(tid, TurnOutcome.Completed, Usage(), 0),
        )
        val card = map(s).items.filterIsInstance<ChatItem.ToolCall>().single()
        val diff = card.editDiff!!
        assertEquals("两个改动按顺序接起来，空行隔开", 2, diff.split("\n\n").size)
        assertTrue(diff, diff.contains("--- /workspace/src/Sea.kt"))
        assertTrue("新建的补上 new file mode，面板据此给图标", diff.contains("new file mode"))
        // 界面侧的 ChangedFiles 按 +++ 头认文件；这里只钉住两边约定的形状
        assertEquals(
            listOf("/workspace/src/Sea.kt", "/workspace/new.md"),
            diff.lineSequence().filter { it.startsWith("+++ ") }.map { it.removePrefix("+++ ") }.toList(),
        )
    }

    @Test fun aToolResultWithoutFileEditsLeavesTheDiffAlone() {
        val call = ToolCallPart(CallId("c1"), "Read", empty)
        val s = foldOf(
            *user("u", "读一下").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            *reply("读", "a1", RequestId("r1"), call).toTypedArray(),
            ToolPlanned(tid, CallId("c1"), "Read", empty, ResumePolicy.Replay), ToolEffectPending(tid, CallId("c1")),
            ToolOutcome(tid, CallId("c1"), ToolResult(listOf(TextPart("内容")))),
            TurnEnded(tid, TurnOutcome.Completed, Usage(), 0),
        )
        assertNull(map(s).items.filterIsInstance<ChatItem.ToolCall>().single().editDiff)
    }

    @Test fun completedTurnStampsOnlyTheLastAnswerOfThatTurn() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            *reply("first", "a1", RequestId("r1"), ToolCallPart(CallId("c"), "Read", empty)).toTypedArray(),
            *reply("second", "a2", RequestId("r2")).toTypedArray(),
            TurnEnded(tid, TurnOutcome.Completed, Usage(10, 7), 500),
        )
        val answers = map(s).items.filterIsInstance<ChatItem.AssistantText>()
        assertNull(answers[0].finishedAt)
        assertEquals(500L, answers[1].durationMs); assertEquals(7, answers[1].outputTokens); assertNotNull(answers[1].finishedAt)
    }

    @Test fun halfAnswerSurvivesProcessDeathAndSitsBeforeTheMarker() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
            AssistantSegment(tid, rid, MessageId("a1"), listOf(TextPart("half an ans"))),
            TurnEnded(tid, TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), Usage(), 0),
        )
        val items = map(s).items
        assertEquals("half an ans", (items[1] as ChatItem.AssistantText).text)
        assertTrue((items[2] as ChatItem.Note).text.startsWith("death@"))
    }

    @Test fun theOpenRequestIsNotInItemsItBelongsToTheLiveStream() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
            AssistantSegment(tid, rid, MessageId("a1"), listOf(TextPart("streaming"))),
        )
        val mapped = map(s)
        assertEquals(listOf("q"), mapped.items.map { (it as ChatItem.UserText).text })
        assertEquals(SessionStatus.Running, mapped.status)
        assertEquals(TurnTriggerView.User, mapped.turn!!.trigger)
    }

    @Test fun infoNoteFromTheLogKeepsItsPlace() {
        val s = foldOf(Note(NoteLevel.Info, "catalog fallback"), *user("u", "q").toTypedArray(), Note(NoteLevel.Error, "boom"))
        val items = map(s).items.map { it as? ChatItem.Note }
        assertEquals("catalog fallback", items[0]!!.text); assertTrue(items[2]!!.isError)
    }

    @Test fun retryingRequestShowsTheAttempt() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 2, T_MODEL, "fp"),
        )
        val retry = map(s).turn!!.retry!!
        assertEquals(2, retry.attempt); assertEquals(5, retry.maxAttempts)
    }

    // ---- 子 agent ----

    private fun child(events: List<SessionEvent>, id: String = "s_child", parent: String = "s_root", depth: Int = 1, call: String = "c1", type: String = "Explore") =
        foldOf(
            SubagentOrigin(SessionId(parent), root, CallId(call), type, false, depth, cacheKey = "k"),
            InputReceived(ClientMsgId("task"), listOf(TextPart("find the entry")), Delivery.Queue, InputOrigin.Parent(SessionId(parent))),
            InputPromoted(ClientMsgId("task"), MessageId("m_task")),
            *events.toTypedArray(), id = id, parent = parent,
        )

    private val spawn = SubagentSpawned(tid, CallId("c1"), SessionId("s_child"), "Explore", false, true, "find entry", "finder")

    @Test fun threadPhaseFollowsTheChildsLastTurn() {
        fun phase(events: List<SessionEvent>) = map(foldOf(spawn), mapOf(SessionId("s_child") to child(events))).threads.single().phase
        assertEquals(AgentThread.Phase.Running, phase(listOf(TurnStarted(TurnId("ct"), TurnTrigger.Delegated))))
        assertEquals(AgentThread.Phase.Done, phase(ended(TurnOutcome.Completed, tid = TurnId("ct"))))
        assertEquals(AgentThread.Phase.Failed, phase(ended(TurnOutcome.Failed("x"), tid = TurnId("ct"))))
        assertEquals(AgentThread.Phase.Stopped, phase(ended(TurnOutcome.Interrupted(InterruptedBy.User), tid = TurnId("ct"))))
        assertEquals(AgentThread.Phase.Stopped, phase(ended(TurnOutcome.Interrupted(InterruptedBy.Limit), tid = TurnId("ct"))))
        assertEquals(AgentThread.Phase.Interrupted, phase(ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), tid = TurnId("ct"))))
    }

    @Test fun threadCarriesIdentityPromptAndItsOwnTranscript() {
        val thread = map(foldOf(spawn), mapOf(SessionId("s_child") to child(listOf(TurnStarted(TurnId("ct"), TurnTrigger.Delegated))))).threads.single()
        assertEquals("c1", thread.threadId); assertNull(thread.parentThreadId); assertEquals(1, thread.depth)
        assertEquals("Explore", thread.agentType); assertEquals("finder", thread.name); assertEquals("find entry", thread.description)
        assertEquals("find the entry", thread.prompt); assertTrue(thread.background)
        assertEquals(SessionRef(EngineKind.MinAgent, "s_child"), thread.childRef)
        assertEquals("find the entry", (thread.items.single() as ChatItem.UserText).text)
    }

    @Test fun agentToolCardCarriesTheChildsTranscriptAsSubItems() {
        val s = foldOf(
            *user("u", "go").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            *reply("delegating", "a1", rid, ToolCallPart(CallId("c1"), "Agent", empty)).toTypedArray(), spawn,
        )
        val card = map(s, mapOf(SessionId("s_child") to child(emptyList()))).items.filterIsInstance<ChatItem.ToolCall>().single()
        assertEquals("find the entry", (card.subItems.single() as ChatItem.UserText).text)
    }

    @Test fun nestedThreadsKeepTheirParentChainAndDepth() {
        val grand = SubagentSpawned(tid, CallId("c2"), SessionId("s_grand"), "general-purpose", false, false, "deep", null)
        val mid = foldOf(
            SubagentOrigin(root, root, CallId("c1"), "Explore", false, 1, cacheKey = "k"), grand, id = "s_child", parent = "s_root",
        )
        val low = child(emptyList(), id = "s_grand", parent = "s_child", depth = 2, call = "c2", type = "general-purpose")
        val threads = map(foldOf(spawn), mapOf(SessionId("s_child") to mid, SessionId("s_grand") to low)).threads
        assertEquals(listOf("c1", "c2"), threads.map { it.threadId })
        assertEquals("c1", threads[1].parentThreadId); assertEquals(2, threads[1].depth)
    }

    @Test fun finishedAndDeliveredThreadDropsOffTheSwitcherButAnInterruptedOneStays() {
        val delivered = foldOf(spawn)
        val done = child(ended(TurnOutcome.Completed, tid = TurnId("ct")))
        val undelivered = map(delivered, mapOf(SessionId("s_child") to done)).threads.single()
        assertTrue("报告还没交付，切换条上留着", undelivered.tracked)
        val withReport = foldOf(
            spawn, InputReceived(ClientMsgId("r"), listOf(TextPart(report("Explore", "completed", "b"))), Delivery.Steer,
                InputOrigin.Agent(SessionId("s_child"), AgentInputKind.Report, TurnId("ct"))),
        )
        assertFalse(map(withReport, mapOf(SessionId("s_child") to done)).threads.single().tracked)
        val died = child(ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), tid = TurnId("ct")))
        val t = map(withReport, mapOf(SessionId("s_child") to died)).threads.single()
        assertTrue("被重启打断的可以接着做", t.tracked); assertNotNull(t.resume)
    }

    // ---- 交互 ----

    private fun approvalEvents(callId: String, apId: String, tool: String, input: JsonObject, ask: PermissionAsk, floor: String? = null) = listOf(
        TurnStarted(tid, TurnTrigger.User), ModelRequestStarted(tid, rid, 1, T_MODEL, "fp"),
        AssistantMessageCompleted(tid, rid, MessageId("a1"), listOf(ToolCallPart(CallId(callId), tool, input)), Usage(), StopReason.ToolUse),
        ToolPlanned(tid, CallId(callId), tool, input, ResumePolicy.Unknown),
        ApprovalRequested(tid, ApprovalId(apId), CallId(callId), ask, null, floor),
    )

    private fun pending(session: String, atMs: Long, what: HubPending.What, parent: String? = null, type: String? = null) =
        HubPending(SessionId(session), root, parent?.let(::SessionId), type, atMs, what)

    @Test fun rootApprovalBecomesACardWithScopedIdAndThreeOptions() {
        val ask = PermissionAsk("Write", "write a file", paths = listOf(PathAccess(GuestPath("/sdcard/a"), AccessKind.Write, PathScope.Device)))
        val input = JsonObject(mapOf("file_path" to JsonPrimitive("/sdcard/a")))
        val s = foldOf(*approvalEvents("c9", "ap1", "Write", input, ask, floor = "device").toTypedArray())
        val what = HubPending.What.Approval(ApprovalId("ap1"), CallId("c9"), "Write", ask, null, "device")
        val card = map(s, pending = listOf(pending("s_root", 5, what))).interactions.single() as PendingInteraction.Approval
        assertEquals("s_root/ap1", card.id); assertEquals(input, card.input); assertEquals("write a file", card.summary)
        assertEquals(dev.min.code.core.session.PathScope.Device, card.paths.single().scope)
        assertEquals("device", card.reason); assertTrue(card.origin.isMain)
        assertEquals(listOf("allow", "deny", "deny-stop"), card.options.map { it.id })
        assertEquals(listOf("allow", "deny"), card.notificationOptions.map { it.id })
        assertTrue(card.options.first { it.id == ApprovalOption.DENY }.acceptsNote)
    }

    @Test fun onlyPathsOutsideTheWorkspaceReachTheCard() {
        val ask = PermissionAsk(
            "Write", "write a file",
            paths = listOf(
                PathAccess(GuestPath("/workspace/in.txt"), AccessKind.Write, PathScope.Workspace),
                PathAccess(GuestPath("/etc/out"), AccessKind.Write, PathScope.Rootfs),
            ),
        )
        val input = JsonObject(mapOf("file_path" to JsonPrimitive("/workspace/in.txt")))
        val s = foldOf(*approvalEvents("c8", "ap8", "Write", input, ask, floor = null).toTypedArray())
        val what = HubPending.What.Approval(ApprovalId("ap8"), CallId("c8"), "Write", ask, null, null)
        val card = map(s, pending = listOf(pending("s_root", 5, what))).interactions.single() as PendingInteraction.Approval
        // 界面把这一行读成「在 /workspace 之外」：工作区之内的路径进来就是谎话（模拟器验收抓到的）
        assertEquals(listOf("/etc/out"), card.paths.map { it.path })
    }

    @Test fun approvalFromAChildFloatsUpWithItsOriginAndAUniqueId() {
        val ask = PermissionAsk("Bash", "run it")
        val input = JsonObject(mapOf("command" to JsonPrimitive("ls")))
        val c = child(approvalEvents("cb", "ap1", "Bash", input, ask))
        val what = HubPending.What.Approval(ApprovalId("ap1"), CallId("cb"), "Bash", ask, null, null)
        val mapped = map(foldOf(spawn), mapOf(SessionId("s_child") to c), pending = listOf(pending("s_child", 5, what, "s_root", "Explore")))
        val card = mapped.interactions.single() as PendingInteraction.Approval
        assertEquals("s_child/ap1", card.id)
        assertEquals("Explore", card.origin.agent!!.agentType); assertTrue(card.origin.agent!!.background)
        assertEquals(input, card.input)
        assertEquals(1, mapped.threads.single().pendingInteractions)
    }

    @Test fun sameApprovalIdInTwoSessionsDoesNotCollide() {
        val ask = PermissionAsk("Bash", "run")
        val c = child(approvalEvents("cb", "ap1", "Bash", empty, ask))
        val s = foldOf(*approvalEvents("c9", "ap1", "Bash", empty, ask).toTypedArray(), spawn)
        val pend = listOf(
            pending("s_root", 1, HubPending.What.Approval(ApprovalId("ap1"), CallId("c9"), "Bash", ask, null, null)),
            pending("s_child", 2, HubPending.What.Approval(ApprovalId("ap1"), CallId("cb"), "Bash", ask, null, null), "s_root", "Explore"),
        )
        val ids = map(s, mapOf(SessionId("s_child") to c), pending = pend).interactions.map { it.id }
        assertEquals(listOf("s_root/ap1", "s_child/ap1"), ids)
    }

    @Test fun queueIsOrderedByTimeAndEachCardKnowsHowManyFollow() {
        val ask = PermissionAsk("Bash", "run")
        val s = foldOf(*approvalEvents("c9", "ap1", "Bash", empty, ask).toTypedArray())
        val pend = listOf(
            pending("s_root", 30, HubPending.What.Approval(ApprovalId("late"), CallId("c9"), "Bash", ask, null, null)),
            pending("s_root", 10, HubPending.What.Approval(ApprovalId("early"), CallId("c9"), "Bash", ask, null, null)),
            pending("s_root", 20, HubPending.What.Question(QuestionId("q1"), CallId("c9"), listOf(UserQuestion("which?", "h", listOf(QuestionOption("a", "A"), QuestionOption("b", "B")))))),
        )
        val cards = map(s, pending = pend).interactions
        assertEquals(listOf("s_root/early", "s_root/q1", "s_root/late"), cards.map { it.id })
        assertEquals(listOf(2, 1, 0), cards.map { it.origin.queuedAfter })
        val q = cards[1] as PendingInteraction.Question
        assertEquals(listOf("a", "b"), q.questions.single().options.map { it.label })
    }

    @Test fun exitPlanModeApprovalIsAPlanReviewWithTheSummaryAsPlan() {
        val ask = PermissionAsk("ExitPlanMode", "# Plan\n1. do it")
        val s = foldOf(*approvalEvents("c9", "ap1", "ExitPlanMode", JsonObject(mapOf("plan" to JsonPrimitive("# Plan\n1. do it"))), ask).toTypedArray())
        val card = map(s, pending = listOf(pending("s_root", 1, HubPending.What.Approval(ApprovalId("ap1"), CallId("c9"), "ExitPlanMode", ask, null, null))))
            .interactions.single() as PendingInteraction.PlanReview
        assertEquals("# Plan\n1. do it", card.plan)
        assertEquals(listOf("approve-plan", "revise-plan"), card.options.map { it.label })
        assertTrue(card.options.none { it.notificationSafe })
    }

    @Test fun delegationApprovalSaysWhoAndWhat() {
        val ask = PermissionAsk("Agent", "委派子任务：x", specifiers = listOf("Explore"))
        val input = JsonObject(mapOf("description" to JsonPrimitive("find entry"), "prompt" to JsonPrimitive("long")))
        val s = foldOf(*approvalEvents("c9", "ap1", "Agent", input, ask).toTypedArray())
        val card = map(s, pending = listOf(pending("s_root", 1, HubPending.What.Approval(ApprovalId("ap1"), CallId("c9"), "Agent", ask, null, null))))
            .interactions.single() as PendingInteraction.Approval
        assertEquals("delegate:Explore:find entry", card.summary)
    }

    // ---- 设置 / 用量 / 连接 ----

    @Test fun settingsViewListsEffortsInOrderAndCrossProviderModels() {
        val patch = JsonObject(emptyMap())
        val lookup = FakeLookup(
            profile = { T_PROFILE.copy(ref = it, effortPatches = mapOf(Effort.High to patch, Effort.Off to patch, Effort.Low to patch)) },
            others = listOf(Choice("m2", "m2@P-p2", profileId = "p2")),
        )
        val s = foldOf(dev.min.agent.spi.SettingsChanged(effort = Effort.Low))
        val view = map(s, lookup = lookup).settings
        // 表头多一项「跟随默认」（id 为空）
        assertEquals(listOf("", "off", "low", "high"), view.efforts.map { it.id })
        assertEquals("low", view.effort!!.id)
        assertEquals(listOf("m1", "m2"), view.models.map { it.id })
        assertTrue(view.models[1].switchesProviderFrom(map(s, lookup = lookup).connection))
        assertFalse(view.models[0].switchesProviderFrom(map(s, lookup = lookup).connection))
        assertEquals("mode:Default", view.mode.label)
        assertEquals("warn", view.modes.first { it.id == "bypassPermissions" }.warning)
        assertTrue("审查模型有了，Auto 列在模式表里", view.modes.any { it.id == "auto" })
    }

    @Test fun autoReviewShowsTheDefaultReviewerAndAnExplicitOne() {
        val lookup = object : MinModelLookup by FakeLookup(others = listOf(Choice("small", "small@P-p2", profileId = "p2"))) {
            override fun fastModelOf(providerId: String) = dev.min.agent.protocol.ModelRef(providerId, "p-fast")
        }
        val defaulted = map(foldOf(), lookup = lookup).settings.autoReview!!
        assertNull(defaulted.reviewer)
        assertEquals("默认 = 与会话同供应商目录给的小模型", "p-fast", defaulted.defaultModel)

        val explicit = foldOf(dev.min.agent.spi.AutoReviewSettingsLoaded(reviewer = dev.min.agent.spi.AutoModeReviewer(dev.min.agent.protocol.ModelRef("p2", "small"))))
        val view = map(explicit, lookup = lookup).settings.autoReview!!
        assertEquals("small", view.reviewer!!.id); assertEquals("p2", view.reviewer!!.profileId)
        assertEquals("选单里已有的就用它的标签（带供应商名）", "small@P-p2", view.reviewer!!.label)
    }

    @Test fun withoutACatalogFastModelTheDefaultReviewerIsTheSessionModelItself() {
        assertEquals(T_MODEL.modelId, map(foldOf()).settings.autoReview!!.defaultModel)
    }

    @Test fun aModelWithoutEffortLevelsHasAnEmptyEffortTable() {
        val view = map(foldOf(), lookup = FakeLookup(profile = { T_PROFILE.copy(ref = it, effortPatches = emptyMap()) })).settings
        assertTrue("没有强度档就不给「跟随默认」，面板据此隐藏整段", view.efforts.isEmpty())
    }

    @Test fun usageIsLastRequestOverWindowAndCacheRatioIsCumulative() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User), *reply("a").toTypedArray(),
            TurnEnded(tid, TurnOutcome.Completed, Usage(10, 5, 100, 0), 10),
        )
        val u = map(s).usage
        assertEquals(110, u.contextTokens); assertEquals(100_000, u.contextLimit)
        assertEquals(100.0 / 110.0, u.cacheReadRatio!!, 1e-9)
        assertNull(u.costUsd)
    }

    @Test fun deletedProviderMakesTheSessionReadOnlyAndHidesResume() {
        val gone = ModelRef("gone", "m1")
        val s = foldOf(*user("u", "q").toTypedArray(), *ended(TurnOutcome.Interrupted(InterruptedBy.User)).toTypedArray(), model = gone)
        val mapped = map(s)
        assertTrue(mapped.readOnly); assertEquals("gone", mapped.connection!!.profileId); assertNull(mapped.connection!!.profileLabel)
        assertNull(mapped.resume)
        val ok = map(foldOf(*user("u", "q").toTypedArray(), *ended(TurnOutcome.Interrupted(InterruptedBy.User)).toTypedArray()))
        assertFalse(ok.readOnly); assertNotNull(ok.resume)
        assertEquals("P-p1", ok.connection!!.profileLabel)
    }

    @Test fun titleIsFirstUserLineClippedAndOverrideWins() {
        val long = "x".repeat(200)
        val s = foldOf(*user("u1", "\n  $long\nsecond").toTypedArray())
        assertEquals(80, map(s).title!!.length)
        assertEquals("renamed", map(s, title = "renamed").title)
    }

    @Test fun planIsTheLastExitPlanModeInput() {
        val s = foldOf(
            *user("u", "q").toTypedArray(), TurnStarted(tid, TurnTrigger.User),
            *reply("p", "a1", rid, ToolCallPart(CallId("c1"), "ExitPlanMode", JsonObject(mapOf("plan" to JsonPrimitive("the plan"))))).toTypedArray(),
        )
        assertEquals("the plan", map(s).plan)
    }

    @Test fun stoppingFlagAndStatusPassThrough() {
        assertTrue(map(foldOf(), stopping = true).stopping)
        assertEquals(SessionStatus.Idle, map(foldOf()).status)
    }

    @Test fun anIdleMinSessionIsStillOpenSoThePageKeepsItAndTheComposerAcceptsInput() {
        // 设计 §3.4：有回合在跑 → Running，否则 Idle。空闲的 Min 会话没有进程，页面靠 open 才不会把它当成「没有会话」
        val idle = map(foldOf())
        assertEquals(SessionStatus.Idle, idle.status)
        assertTrue(idle.open)
        assertTrue(idle.accepting)
    }

    @Test fun lastTurnReceiptCarriesTheEnd() {
        val s = foldOf(*ended(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), lastActivity = 55).toTypedArray())
        val r = map(s).lastTurn!!
        assertEquals(TurnEnd.Interrupted(InterruptCause.ProcessDeath), r.end); assertEquals(55L, r.finishedAt)
    }
}
