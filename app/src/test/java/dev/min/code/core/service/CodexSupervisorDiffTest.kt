package dev.min.code.core.service

import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codex 管家的边沿判定。通知本身要真机看，这里的矩阵是：什么状态翻转换来什么事件，
 * 前台怎么抑制——发不发错、发不发重，全在这层钉死。
 */
class CodexSupervisorDiffTest {

    private val idle = ClaudeCodeSessionSupervisor.CodexSeen(
        busy = false, approvalKey = null, status = SessionStatus.Idle, errorMessage = null,
    )

    private fun seen(
        busy: Boolean = false,
        approvalKey: String? = null,
        status: SessionStatus = SessionStatus.Running,
        errorMessage: String? = null,
    ) = ClaudeCodeSessionSupervisor.CodexSeen(busy, approvalKey, status, errorMessage)

    @Test
    fun `first observation never notifies`() {
        assertNull(codexSupervisorDiff(null, seen(busy = true), isForeground = false))
        assertNull(codexSupervisorDiff(null, seen(), isForeground = false))
    }

    @Test
    fun `approval pending in background raises the approval event`() {
        val event = codexSupervisorDiff(idle, seen(approvalKey = "42"), isForeground = false)
        assertEquals(CodexSupervisorEvent.Approval, event)
    }

    /** 前台不提醒：App 内的审批 sheet 才是主界面 */
    @Test
    fun `approval pending in foreground stays silent`() {
        assertNull(codexSupervisorDiff(idle, seen(approvalKey = "42"), isForeground = true))
    }

    /** 审批被清掉要撤通知——这条在前台也得执行，状态栏还挂着那条 ongoing */
    @Test
    fun `approval cleared cancels the notification even in foreground`() {
        val previous = seen(approvalKey = "42")
        val event = codexSupervisorDiff(previous, seen(), isForeground = true)
        assertEquals(CodexSupervisorEvent.ApprovalCleared, event)
    }

    @Test
    fun `busy falling with an error message is a failed turn, not a dead session`() {
        val previous = seen(busy = true)
        val current = seen(busy = false, errorMessage = "401 Incorrect API key")
        assertEquals(
            CodexSupervisorEvent.TurnFailed("401 Incorrect API key"),
            codexSupervisorDiff(previous, current, isForeground = false),
        )
    }

    @Test
    fun `busy falling without an error is a completed turn`() {
        val previous = seen(busy = true)
        assertEquals(
            CodexSupervisorEvent.TurnDone,
            codexSupervisorDiff(previous, seen(busy = false), isForeground = false),
        )
    }

    /** 进程退出（Failed）优先于一轮收尾：会话都没了，谈不上"完成" */
    @Test
    fun `process death reports died instead of turn done`() {
        val previous = seen(busy = true)
        val current = seen(busy = false, status = SessionStatus.Failed, errorMessage = "退出码 1")
        assertEquals(
            CodexSupervisorEvent.Died("退出码 1"),
            codexSupervisorDiff(previous, current, isForeground = false),
        )
    }

    /** 用户主动停（Closed）同 Claude 一并提醒——否则回到 App 才发现停了 */
    @Test
    fun `user stop also reports died`() {
        val previous = seen()
        val event = codexSupervisorDiff(previous, seen(status = SessionStatus.Closed), isForeground = false)
        assertEquals(CodexSupervisorEvent.Died(null), event)
    }

    /** Foreground 抑制完成 / 失败 / 退出：人在屏幕前，流里本来就看得到 */
    @Test
    fun `foreground suppresses turn and death events`() {
        assertNull(
            codexSupervisorDiff(
                seen(busy = true),
                seen(busy = false, errorMessage = "401"),
                isForeground = true,
            ),
        )
        assertNull(
            codexSupervisorDiff(seen(), seen(status = SessionStatus.Failed), isForeground = true),
        )
    }

    /** turn 失败后 errorMessage 还挂着，下一帧不能再报一次失败 */
    @Test
    fun `a failed turn does not re-notify while the error message lingers`() {
        val previous = seen(busy = false, errorMessage = "401 Incorrect API key")
        assertNull(
            codexSupervisorDiff(previous, seen(busy = false, errorMessage = "401 Incorrect API key"), isForeground = false),
        )
    }
}
