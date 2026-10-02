package dev.min.code.core.engine

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用类型上的小纯函数：交互卡、id 拆合、状态派生、摘要投影 */
class InteractionsAndStateTest {

    // ---------------------------------------------------------------- 交互

    @Test
    fun `notification buttons only carry the safe options`() {
        val card = approval("1")
        assertEquals(listOf(ApprovalOption.ALLOW, ApprovalOption.DENY), card.notificationOptions.map { it.id })
    }

    @Test
    fun `deny in a notification keeps the turn going and takes a note, deny-and-stop does not`() {
        val byId = approval("1").options.associateBy { it.id }
        assertTrue(byId.getValue(ApprovalOption.DENY).acceptsNote)
        assertEquals(ApprovalOption.Kind.DenyAndStop, byId.getValue(ApprovalOption.DENY_AND_STOP).kind)
        assertFalse(byId.getValue(ApprovalOption.DENY_AND_STOP).notificationSafe)
    }

    @Test
    fun `kind follows the variant`() {
        assertEquals(InteractionKind.Approval, approval("1").kind)
        assertEquals(InteractionKind.Question, question("1").kind)
        assertEquals(InteractionKind.PlanReview, planReview("1").kind)
    }

    @Test
    fun `queue depth counts the cards behind each one`() {
        val stamped = listOf(approval("a"), question("b"), planReview("c")).withQueueDepth()
        assertEquals(listOf(2, 1, 0), stamped.map { it.origin.queuedAfter })
        assertEquals(listOf("a", "b", "c"), stamped.map { it.id })
        assertTrue(listOf<PendingInteraction>().withQueueDepth().isEmpty())
    }

    @Test
    fun `queue depth keeps the path of each card`() {
        val label = AgentLabel("th", "Explore", null, "d", true)
        val stamped = listOf(approval("a", origin = InteractionOrigin(listOf(label)))).withQueueDepth()
        assertEquals(listOf(label), stamped.single().origin.path)
        assertEquals(label, stamped.single().origin.agent)
        assertFalse(stamped.single().origin.isMain)
    }

    @Test
    fun `scoped interaction ids split back into session and local id`() {
        val id = InteractionIds.scoped("s_child", "ap_1")
        assertEquals("s_child/ap_1", id)
        assertEquals("s_child" to "ap_1", InteractionIds.split(id))
        assertEquals("s_1" to "a/b", InteractionIds.split("s_1/a/b"))
    }

    @Test
    fun `ids that are not scoped do not split`() {
        assertNull(InteractionIds.split("plain"))
        assertNull(InteractionIds.split("/x"))
        assertNull(InteractionIds.split("x/"))
    }

    @Test
    fun `a session id with the separator is refused`() {
        try {
            InteractionIds.scoped("a/b", "x")
            error("应该拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `question answers join the way Claude Code expects`() {
        assertEquals("A, B", QuestionAnswerInput("q", listOf("A", "B")).joined)
        assertEquals("A, 都要", QuestionAnswerInput("q", listOf("A"), other = "都要").joined)
        assertEquals("手填", QuestionAnswerInput("q", emptyList(), other = "手填").joined)
        assertEquals("", QuestionAnswerInput("q", emptyList(), other = "  ").joined)
    }

    // ---------------------------------------------------------------- 状态派生

    @Test
    fun `initial state is idle, empty and starting`() {
        val s = AgentSessionState.initial(MIN, "/workspace")
        assertEquals(SessionStatus.Starting, s.status)
        assertFalse(s.busy)
        assertNull(s.headInteraction)
        assertEquals(0, s.queuedCount)
        assertFalse(s.readOnly)
        assertEquals("/workspace", s.cwd)
    }

    @Test
    fun `busy is exactly turn non-null and queued counts only queued user messages`() {
        val items = listOf(
            ChatItem.UserText("u1", "a"),
            ChatItem.UserText("u2", "b", queued = true),
            ChatItem.UserText("u3", "c", queued = true),
            ChatItem.AssistantText("a1", "x"),
        )
        val s = AgentSessionState.initial(CC).copy(items = items, turn = turn())
        assertTrue(s.busy)
        assertEquals(2, s.queuedCount)
    }

    @Test
    fun `a removed provider makes the session read-only, a stale one does not`() {
        val base = AgentSessionState.initial(MIN)
        assertTrue(base.copy(connection = ConnectionView("p", "P", stale = false, removed = true)).readOnly)
        assertFalse(base.copy(connection = ConnectionView("p", "P", stale = true)).readOnly)
    }

    @Test
    fun `choosing a model from another provider is flagged so the UI can warn about the cold cache`() {
        val conn = ConnectionView("prof-1", "A", stale = false)
        assertTrue(Choice("m", "M", profileId = "prof-2").switchesProviderFrom(conn))
        assertFalse(Choice("m", "M", profileId = "prof-1").switchesProviderFrom(conn))
        assertFalse("CC / Codex 没有档的概念", Choice("m", "M").switchesProviderFrom(conn))
        assertFalse(Choice("m", "M", profileId = "prof-2").switchesProviderFrom(null))
    }

    @Test
    fun `live stream is empty only without any increment`() {
        assertTrue(LiveStream.Empty.isEmpty)
        assertFalse(LiveStream.Empty.copy(text = "x").isEmpty)
        assertFalse(LiveStream.Empty.copy(toolOutput = mapOf("tu" to "o")).isEmpty)
        assertFalse(LiveStream.Empty.copy(toolDrafts = listOf(ToolDraft(0, null, "Bash", "{\"co"))).isEmpty)
    }

    // ---------------------------------------------------------------- 摘要

    @Test
    fun `summary projection takes the head interaction, status text and last reply`() {
        val state = AgentSessionState.initial(MIN).copy(
            status = SessionStatus.Running,
            title = "t",
            items = listOf(ChatItem.AssistantText("a1", "first"), ChatItem.UserText("u", "q"), ChatItem.AssistantText("a2", "last")),
            turn = turn().copy(phase = "thinking", detail = "读取文件"),
            interactions = listOf(approval("ap1", tool = "Write"), question("q2")),
            resume = offer(id = "t3"),
            lastTurn = TurnReceipt("t2", 10L, 20L, TurnEnd.Completed),
            errorMessage = "e",
        )
        val s = state.toLiveSummary(keepAlive = true)
        assertEquals(MIN, s.ref)
        assertEquals("读取文件", s.statusText)
        assertEquals("last", s.lastAssistantText)
        assertEquals(InteractionBrief("ap1", InteractionKind.Approval, "Write", null), s.headInteraction)
        assertEquals(2, s.interactionCount)
        assertTrue(s.busy)
        assertTrue(s.keepAlive)
        assertEquals(ResumeBrief("t3", ResumeReason.UserStopped), s.resume)
        assertEquals(TurnEnd.Completed, s.lastTurnEnd)
        assertEquals("e", s.errorMessage)
    }

    @Test
    fun `summary status text falls back to the phase`() {
        val s = AgentSessionState.initial(CC).copy(turn = turn().copy(phase = "requesting")).toLiveSummary(true)
        assertEquals("requesting", s.statusText)
    }

    @Test
    fun `brief of a question or plan review has no tool name`() {
        assertNull(question("q").brief().toolName)
        assertNull(planReview("p").brief().toolName)
        assertEquals("Bash", approval("a").brief().toolName)
    }

    @Test
    fun `merging live summaries fills active and attached and keeps order`() {
        val a = summary(ref = CC)
        val b = summary(ref = MIN)
        val c = summary(ref = SessionRef(EngineKind.Codex, "th"))
        val merged = mergeLiveSummaries(listOf(listOf(a), listOf(b, c)), attached = setOf(MIN), active = MIN)
        assertEquals(listOf(CC, MIN, c.ref), merged.map { it.ref })
        assertEquals(listOf(false, true, false), merged.map { it.isActive })
        assertEquals(listOf(false, true, false), merged.map { it.attached })
    }
}
