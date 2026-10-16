package dev.min.agent.spi

import dev.min.agent.protocol.*
import org.junit.Assert.*
import org.junit.Test

class Mcp2EventsWireTest {
    @Test fun `new search and URL events and external questions roundtrip`() {
        val events: List<SessionEvent> = listOf(
            McpToolSearchConfigured(true, 32, 100, 10.0), McpToolsLoaded(listOf("mcp__s__t")),
            McpUrlElicitationAsked(QuestionId("q"), "s", "https://example.com/a", "example.com", "open", "flow"),
            McpUrlElicitationResolved(QuestionId("q"), "cancel"),
            QuestionAsked(TurnId("mcp-idle"), QuestionId("q"), CallId("mcp-idle"), listOf(UserQuestion("server s", "field", emptyList(), inputType="integer")), "s", true),
            QuestionAnswered(QuestionId("q"), declined=true, cancelled=true),
            ToolOutcome(TurnId("t"), CallId("c"), ToolResult(listOf(TextPart("tools")), effects=listOf(SessionEffect.McpLoadTools(listOf("mcp__s__t"))))),
            McpToolSchemaRejected("mcp__s__t", "tools.1.custom.input_schema: JSON schema is invalid"),
        )
        events.forEach { assertEquals(it, AgentJson.decodeFromString(SessionEvent.serializer(), AgentJson.encodeToString(SessionEvent.serializer(), it))) }
    }
    @Test fun `old snapshots and question fields retain defaults`() {
        val snapshot = AgentJson.decodeFromString(SessionEvent.serializer(), """{"type":"mcp_tools_snapshot","tools":[]}""") as McpToolsSnapshot
        assertTrue(snapshot.resourceServers.isEmpty())
        val question = AgentJson.decodeFromString(SessionEvent.serializer(), """{"type":"question_answered","questionId":"q","declined":true}""") as QuestionAnswered
        assertFalse(question.cancelled)
    }
}
