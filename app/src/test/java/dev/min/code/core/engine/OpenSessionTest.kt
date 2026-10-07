package dev.min.code.core.engine

import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话页要不要显示它、输入框收不收字」：Claude Code / Codex 的 Running 就是进程活着；
 * Min 没有进程，空闲时 status 是 Idle 但会话是打开着的（[AgentSessionState.open]）。
 */
class OpenSessionTest {
    private val ref = SessionRef(EngineKind.MinAgent, "s_1")
    private val base = AgentSessionState.initial(ref)

    @Test fun `a running session accepts input whether or not the engine marks it open`() {
        assertTrue(base.copy(status = SessionStatus.Running).accepting)
        assertTrue(base.copy(status = SessionStatus.Running, open = true).accepting)
    }

    @Test fun `an idle session accepts input only when the engine says it is open`() {
        // 没有会话时的占位（NoSession）与 Claude 的「还没启动」都是 Idle 且没有 open：不收字，页面显示开始面板
        assertFalse(base.copy(status = SessionStatus.Idle).accepting)
        assertTrue(base.copy(status = SessionStatus.Idle, open = true).accepting)
    }

    @Test fun `a closed or failed session never accepts input even if it was open`() {
        assertFalse(base.copy(status = SessionStatus.Closed, open = true).accepting)
        assertFalse(base.copy(status = SessionStatus.Failed, open = true).accepting)
    }

    @Test fun `a starting process is not accepting yet`() {
        assertFalse(base.copy(status = SessionStatus.Starting).accepting)
    }
}
