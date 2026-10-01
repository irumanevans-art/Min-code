package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEffectsWireTest {
    @Test fun `old tool results decode with no effects and keep their JSON shape`() {
        val json = """{"content":[{"type":"text","text":"旧结果"}]}"""
        val result = AgentJson.decodeFromString(ToolResult.serializer(), json)
        assertTrue(result.effects.isEmpty())
        assertEquals(AgentJson.parseToJsonElement(json), AgentJson.parseToJsonElement(AgentJson.encodeToString(ToolResult.serializer(), result)))
    }

    @Test fun `a foreground report and delivery marker round trip inside one ToolOutcome`() {
        val outcome: SessionEvent = ToolOutcome(
            TurnId("parent_turn"), CallId("delegate"),
            ToolResult(listOf(TextPart("报告")), effects = listOf(SessionEffect.ReportDelivered(SessionId("child"), TurnId("child_turn")))),
        )
        val expected = """{"type":"tool_outcome","turnId":"parent_turn","callId":"delegate","result":{
            "content":[{"type":"text","text":"报告"}],
            "effects":[{"type":"report_delivered","child":"child","childTurn":"child_turn"}]}}"""
        val encoded = AgentJson.encodeToString(SessionEvent.serializer(), outcome)
        assertEquals(AgentJson.parseToJsonElement(expected), AgentJson.parseToJsonElement(encoded))
        assertEquals(outcome, AgentJson.decodeFromString(SessionEvent.serializer(), encoded))
    }
}
