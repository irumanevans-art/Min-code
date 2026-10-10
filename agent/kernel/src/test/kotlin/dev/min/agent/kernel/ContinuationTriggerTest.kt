package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.TurnTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuationTriggerTest {
    @Test fun `a queued input continues the turn with the same trigger it would start an idle session with`() {
        assertEquals(TurnTrigger.Notification, continuationTrigger(InputOrigin.Agent(SessionId("s_child"), AgentInputKind.Report, TurnId("turn_1"))))
        assertEquals(TurnTrigger.Notification, continuationTrigger(InputOrigin.Task("bash_1")))
        assertEquals(TurnTrigger.Hook, continuationTrigger(InputOrigin.Hook("Stop")))
        assertEquals(TurnTrigger.Cron, continuationTrigger(InputOrigin.Cron("c1")))
        assertEquals(TurnTrigger.User, continuationTrigger(InputOrigin.User))
        assertEquals(TurnTrigger.User, continuationTrigger(InputOrigin.Parent(SessionId("s_parent"))))
    }
}
