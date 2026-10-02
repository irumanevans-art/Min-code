package dev.min.code.core.engine

import dev.min.code.core.engine.AgentThread.Phase as P
import dev.min.code.core.session.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通用子 agent 线程的转换规则：阶段怎么定、切换条列谁、标签与路径怎么起 */
class AgentThreadTest {

    // ---------------------------------------------------------------- Min 的规则：按回合状态

    @Test
    fun `a running turn is Running whatever happened before`() {
        assertEquals(P.Running, threadPhaseOfTurn(true, TurnEnd.Completed))
        assertEquals(P.Running, threadPhaseOfTurn(true, TurnEnd.Failed(null, "x")))
        assertEquals(P.Running, threadPhaseOfTurn(true, null))
    }

    @Test
    fun `last turn outcome decides the phase of an idle thread`() {
        assertEquals(P.Done, threadPhaseOfTurn(false, TurnEnd.Completed))
        assertEquals(P.Failed, threadPhaseOfTurn(false, TurnEnd.Failed(FailureKind.Auth, "401")))
        assertEquals(P.Stopped, threadPhaseOfTurn(false, TurnEnd.Interrupted(InterruptCause.User)))
        assertEquals(P.Stopped, threadPhaseOfTurn(false, TurnEnd.Interrupted(InterruptCause.Limit)))
        assertEquals(P.Stopped, threadPhaseOfTurn(false, TurnEnd.Interrupted(InterruptCause.Withdrawn)))
        assertEquals(P.Interrupted, threadPhaseOfTurn(false, TurnEnd.Interrupted(InterruptCause.ProcessDeath)))
        assertEquals(P.Interrupted, threadPhaseOfTurn(false, TurnEnd.Interrupted(InterruptCause.EngineExited)))
    }

    @Test
    fun `a thread that has not finished any turn yet is not reported as done`() {
        assertEquals(P.Running, threadPhaseOfTurn(false, null))
    }

    // ---------------------------------------------------------------- CC 的规则：按任务表（搬自 SubagentThreads.threadOf）

    private fun task(running: Boolean = false, status: String? = null, error: Boolean = false) =
        ThreadTaskSignal(running, status, error)

    @Test
    fun `a task in the table decides the phase`() {
        val card = ChatItem.ToolCall.Status.Done
        assertEquals(P.Running, threadPhaseOfTask(task(running = true), card))
        assertEquals(P.Stopped, threadPhaseOfTask(task(status = "killed"), card))
        assertEquals(P.Stopped, threadPhaseOfTask(task(status = "stopped"), card))
        assertEquals(P.Failed, threadPhaseOfTask(task(error = true), card))
        assertEquals(P.Done, threadPhaseOfTask(task(status = "completed"), card))
    }

    @Test
    fun `a running task wins over an error flag and a killed task is not a failure`() {
        assertEquals(P.Running, threadPhaseOfTask(task(running = true, error = true), ChatItem.ToolCall.Status.Error))
        // task_notification 把 killed 报成 stopped；被停掉的不是它自己出错，即使同时带了 error
        assertEquals(P.Stopped, threadPhaseOfTask(task(status = "killed", error = true), ChatItem.ToolCall.Status.Error))
    }

    @Test
    fun `without a task the card decides - history sessions have no task table`() {
        assertEquals(P.Running, threadPhaseOfTask(null, ChatItem.ToolCall.Status.Running))
        assertEquals(P.Failed, threadPhaseOfTask(null, ChatItem.ToolCall.Status.Error))
        assertEquals(P.Done, threadPhaseOfTask(null, ChatItem.ToolCall.Status.Done))
    }

    // ---------------------------------------------------------------- 切换条

    @Test
    fun `the switcher lists tracked threads plus the one being viewed`() {
        val a = thread("a", tracked = true)
        val b = thread("b", tracked = false, phase = P.Done)
        val c = thread("c", tracked = false, phase = P.Done)
        assertEquals(listOf("a"), switcherThreads(listOf(a, b, c), null).map { it.threadId })
        assertEquals("正看着的被清出任务表时不能消失", listOf("a", "b"), switcherThreads(listOf(a, b, c), "b").map { it.threadId })
        assertEquals(emptyList<AgentThread>(), switcherThreads(listOf(b, c), null))
    }

    @Test
    fun `labels are the type and only get numbered when a type repeats`() {
        val ts = listOf(thread("1", "Explore"), thread("2", "general-purpose"), thread("3", "general-purpose"), thread("4", "general-purpose"))
        assertEquals(listOf("Explore", "general-purpose 1", "general-purpose 2", "general-purpose 3"), switcherLabels(ts))
    }

    @Test
    fun `numbering counts only what the strip shows and goes by the raw type`() {
        val ts = listOf(thread("1", "fork"), thread("2", "fork"))
        assertEquals(listOf("分叉 1", "分叉 2"), switcherLabels(ts) { if (it == "fork") "分叉" else it })
        assertEquals(listOf("fork"), switcherLabels(listOf(thread("1", "fork"))))
        assertEquals(emptyList<String>(), switcherLabels(emptyList()))
    }

    // ---------------------------------------------------------------- 树与来源

    private val tree = listOf(
        thread("a", "Explore", parent = null, depth = 0),
        thread("b", "general-purpose", parent = "a", depth = 1),
        thread("c", "Plan", parent = "b", depth = 2),
        thread("x", "Explore", parent = null, depth = 0),
    )

    @Test
    fun `path runs from the root down to the thread and includes it`() {
        assertEquals(listOf("a", "b", "c"), threadPath(tree, "c").map { it.threadId })
        assertEquals(listOf("a"), threadPath(tree, "a").map { it.threadId })
        assertEquals(emptyList<AgentThread>(), threadPath(tree, "nope"))
    }

    @Test
    fun `a broken parent chain starts at the break instead of failing`() {
        val orphan = listOf(thread("b", parent = "gone", depth = 1))
        assertEquals(listOf("b"), threadPath(orphan, "b").map { it.threadId })
    }

    @Test
    fun `a parent cycle terminates`() {
        val cyc = listOf(thread("a", parent = "b"), thread("b", parent = "a"))
        assertEquals(2, threadPath(cyc, "a").size)
    }

    @Test
    fun `nested subagents read as a breadcrumb`() {
        assertEquals("Explore › general-purpose › Plan", threadBreadcrumb(tree, "c"))
        assertEquals("Explore", threadBreadcrumb(tree, "x"))
        assertEquals("分叉 › Plan", threadBreadcrumb(listOf(thread("a", "fork"), thread("c", "Plan", parent = "a")), "c") { if (it == "fork") "分叉" else it })
    }

    @Test
    fun `an interaction origin lists every level and the main session has an empty path`() {
        assertTrue(originOf(tree, null).isMain)
        val origin = originOf(tree, "c")
        assertEquals(listOf("a", "b", "c"), origin.path.map { it.threadId })
        assertEquals("Plan", origin.agent?.agentType)
        assertEquals("desc-c", origin.agent?.description)
    }

    @Test
    fun `labels carry the background flag and display name falls back to the type`() {
        val t = thread("a", "Explore", background = true)
        assertTrue(t.toLabel().background)
        assertEquals("Explore", t.displayName)
        assertEquals("查找入口", thread("a", name = "查找入口").displayName)
        assertEquals("Explore", thread("a", name = "  ").displayName)
    }

    @Test
    fun `pending counts attribute each card to the thread that raised it`() {
        val fromB = originOf(tree, "b")
        val cards = listOf(approval("1"), approval("2", origin = fromB), question("3", origin = fromB), approval("4", origin = originOf(tree, "c")))
        val counted = tree.withPendingCounts(cards).associate { it.threadId to it.pendingInteractions }
        assertEquals(mapOf("a" to 0, "b" to 2, "c" to 1, "x" to 0), counted)
    }

    @Test
    fun `running only for the Running phase`() {
        assertTrue(thread("a", phase = P.Running).running)
        for (p in listOf(P.Done, P.Failed, P.Stopped, P.Interrupted)) assertEquals(false, thread("a", phase = p).running)
    }
}
