package dev.min.code.core.service

import dev.min.code.core.engine.*
import dev.min.code.core.session.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Min 的真实生命周期是 Running → Idle，不能用 CLI 的「进程仍 Running」前提吞掉结束边沿。 */
class MinSupervisorLifecycleTest {
    private val running = LiveSessionSummary(SessionRef(EngineKind.MinAgent, "s_test"), null,
        SessionStatus.Running, keepAlive = true, busy = true)

    @Test fun `a completed kernel turn notifies after keep alive falls`() {
        val idle = running.copy(status = SessionStatus.Idle, keepAlive = false, busy = false, lastTurnEnd = TurnEnd.Completed)
        assertEquals(listOf(SupervisorEvent.TurnDone), supervisorDiff(running, idle, false))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(running, idle, true))
    }

    @Test fun `an incomplete kernel turn carries its resume offer`() {
        val end = TurnEnd.Interrupted(InterruptCause.Limit)
        val offer = ResumeBrief("turn-1", ResumeReason.Limit)
        val idle = running.copy(status = SessionStatus.Idle, keepAlive = false, busy = false, lastTurnEnd = end, resume = offer)
        assertEquals(listOf(SupervisorEvent.TurnIncomplete(end, offer)), supervisorDiff(running, idle, false))
    }

    @Test fun `a user stopped kernel turn stays quiet`() {
        val idle = running.copy(status = SessionStatus.Idle, keepAlive = false, busy = false,
            lastTurnEnd = TurnEnd.Interrupted(InterruptCause.User))
        assertEquals(emptyList<SupervisorEvent>(), supervisorDiff(running, idle, false))
    }
}
