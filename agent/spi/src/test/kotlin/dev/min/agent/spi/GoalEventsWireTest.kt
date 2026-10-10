package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** `/goal` 事件的线形状：判别值与字段名不落盘变动；每个变体能往返 */
class GoalEventsWireTest {
    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
        assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
    }

    @Test
    fun `goal events keep their wire shape`() {
        assertEventWire(GoalSet("测试全绿"), """{"type":"goal_set","condition":"测试全绿"}""")
        assertEventWire(GoalEvaluated(GoalVerdict.NotMet, "还有一个用例红"), """{"type":"goal_evaluated","verdict":"not_met","reason":"还有一个用例红"}""")
        assertEventWire(GoalEvaluated(GoalVerdict.Met), """{"type":"goal_evaluated","verdict":"met"}""")
        assertEventWire(GoalCleared(GoalEndReason.User), """{"type":"goal_cleared","reason":"user"}""")
        assertEventWire(GoalCheckIn(2), """{"type":"goal_check_in","count":2}""")
        assertEventWire(GoalResumed(5L), """{"type":"goal_resumed","atMs":5}""")
        assertEventWire(GoalEvaluated(GoalVerdict.NotMet, "改一下", toolUse = true), """{"type":"goal_evaluated","verdict":"not_met","reason":"改一下","toolUse":true}""")
        assertEventWire(GoalCleared(GoalEndReason.Error, "额度用完"), """{"type":"goal_cleared","reason":"error","detail":"额度用完"}""")
        assertEventWire(GoalCheckIn(3, atMs = 9L, idle = true), """{"type":"goal_check_in","count":3,"atMs":9,"idle":true}""")
        assertEventWire(GoalDeferred(7L), """{"type":"goal_deferred","atMs":7}""")
        assertEventWire(GoalPaused("速率限制"), """{"type":"goal_paused","reason":"速率限制"}""")
    }
}
