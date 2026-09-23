package dev.min.code.core.service

import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude 会话管家的边沿判定。和 [CodexSupervisorDiffTest] 同一张矩阵，外加 Claude 这边
 * 独有的一格：待批请求从 A 直接换成 B（中间没有 null）也必须重新提醒。
 */
class ClaudeSupervisorDiffTest {

    private fun seen(
        busy: Boolean = false,
        pendingId: String? = null,
        pendingTool: String? = null,
        status: SessionStatus = SessionStatus.Running,
        isLive: Boolean = true,
        errorMessage: String? = null,
    ) = ClaudeCodeSessionSupervisor.ClaudeSeen(busy, pendingId, pendingTool, status, isLive, errorMessage)

    private fun diff(
        previous: ClaudeCodeSessionSupervisor.ClaudeSeen?,
        current: ClaudeCodeSessionSupervisor.ClaudeSeen,
        foreground: Boolean = false,
    ) = claudeSupervisorDiff(previous, current, foreground)

    @Test
    fun `first observation of a quiet session says nothing`() {
        assertEquals(emptyList<ClaudeSupervisorEvent>(), diff(null, seen(busy = true)))
    }

    @Test
    fun `a new permission request notifies in the background only`() {
        val asking = seen(busy = true, pendingId = "r1", pendingTool = "Bash")
        assertEquals(listOf(ClaudeSupervisorEvent.Permission("Bash", "r1")), diff(seen(busy = true), asking))
        assertEquals(emptyList<ClaudeSupervisorEvent>(), diff(seen(busy = true), asking, foreground = true))
    }

    @Test
    fun `a request swapped for another without a gap notifies again`() {
        val a = seen(busy = true, pendingId = "r1", pendingTool = "Read")
        val b = seen(busy = true, pendingId = "r2", pendingTool = "Bash")
        // 以前只看「有没有」，这里什么都不发：状态栏停在 Read，按钮应答的却是 Bash
        assertEquals(listOf(ClaudeSupervisorEvent.Permission("Bash", "r2")), diff(a, b))
    }

    @Test
    fun `the same request seen twice does not notify twice`() {
        val a = seen(busy = true, pendingId = "r1", pendingTool = "Read")
        assertEquals(emptyList<ClaudeSupervisorEvent>(), diff(a, a))
    }

    @Test
    fun `an answered request is cleared even in the foreground`() {
        val a = seen(busy = true, pendingId = "r1", pendingTool = "Read")
        assertEquals(listOf(ClaudeSupervisorEvent.PermissionCleared), diff(a, seen(busy = true), foreground = true))
    }

    @Test
    fun `a finished turn notifies in the background, but not for a dead session`() {
        assertEquals(listOf(ClaudeSupervisorEvent.TurnDone), diff(seen(busy = true), seen(busy = false)))
        assertEquals(emptyList<ClaudeSupervisorEvent>(), diff(seen(busy = true), seen(busy = false), foreground = true))
        val dead = seen(busy = false, status = SessionStatus.Closed, isLive = false)
        assertEquals(listOf(ClaudeSupervisorEvent.Died(null)), diff(seen(busy = true), dead))
    }

    @Test
    fun `a session that dies while asking clears the prompt and reports the death`() {
        val asking = seen(busy = true, pendingId = "r1", pendingTool = "Bash")
        val failed = seen(status = SessionStatus.Failed, isLive = false, errorMessage = "boom")
        val events = diff(asking, failed)
        assertTrue(ClaudeSupervisorEvent.PermissionCleared in events)
        assertTrue(ClaudeSupervisorEvent.Died("boom") in events)
    }
}
