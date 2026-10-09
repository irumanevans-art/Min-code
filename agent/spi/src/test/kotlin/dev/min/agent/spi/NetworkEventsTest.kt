package dev.min.agent.spi

import dev.min.agent.protocol.*
import org.junit.Assert.*
import org.junit.Test

class NetworkEventsTest {
    @Test fun wireNamesAndDefaultSettingsAreStable() {
        val events = listOf(
            NetworkSettingsLoaded(NetworkSettings()) to """{"type":"network_settings_loaded","settings":{}}""",
            NetworkApprovalRequested(ApprovalId("n"), CallId("c"), "example.com", 443, "curl", 12) to
                """{"type":"network_approval_requested","approvalId":"n","callId":"c","domain":"example.com","port":443,"commandSummary":"curl","atMs":12}""",
            NetworkDecision(CallId("c"), "example.com", 443, NetworkOutcome.Allow, NetworkSource.User, "allowed", ApprovalId("n")) to
                """{"type":"network_decision","callId":"c","domain":"example.com","port":443,"outcome":"allow","source":"user","reason":"allowed","approvalId":"n"}""",
        )
        for ((event, expected) in events) {
            val encoded = AgentJson.encodeToString(SessionEvent.serializer(), event)
            assertEquals(AgentJson.parseToJsonElement(expected), AgentJson.parseToJsonElement(encoded))
            assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), encoded))
        }
    }
}
