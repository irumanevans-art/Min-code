package dev.min.agent.spi

import dev.min.agent.protocol.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WebEventsTest {
    @Test fun `web event wire names and accounting fields are stable`() {
        val events = listOf(
            WebSearchUsed(SessionId("child"), CallId("c1")) to """{"type":"web_search_used","sessionId":"child","callId":"c1"}""",
            WebExtractionCompleted(TurnId("t1"), CallId("c2"), ModelRef("test", "fast"), Usage(10, 5)) to
                """{"type":"web_extraction_completed","turnId":"t1","callId":"c2","model":{"providerId":"test","modelId":"fast"},"usage":{"inputTokens":10,"outputTokens":5}}""",
        )
        for ((event, expected) in events) {
            val encoded = AgentJson.encodeToString(SessionEvent.serializer(), event)
            assertEquals(AgentJson.parseToJsonElement(expected), AgentJson.parseToJsonElement(encoded))
            assertEquals(event, AgentJson.decodeFromString(SessionEvent.serializer(), encoded))
        }
    }
}
