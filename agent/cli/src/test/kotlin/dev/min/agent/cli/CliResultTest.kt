package dev.min.agent.cli

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CliResultTest {
    private fun record(event: SessionEvent) = EventRecord(1, 0, event)
    @Test fun `a resumed failed turn cannot borrow old output tokens to report success`() {
        val result = CliResult("s", listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Failed("HTTP 401"), Usage(), 0))), Usage(100, 200, 300, 0))
        assertFalse(result.completed)
        assertEquals("HTTP 401", result.json()["error"]?.jsonPrimitive?.content)
        assertEquals(Usage(), result.turnUsage)
        assertEquals(JsonPrimitive(200L), result.json()["output_tokens"])
    }
    @Test fun `successful zero token replies still complete and missing terminal cannot succeed`() {
        assertTrue(CliResult("s",listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Completed, Usage(), 0))), Usage()).completed)
        assertFalse(CliResult("s",emptyList(),Usage(outputTokens=900)).completed)
        assertFalse(CliResult("s",listOf(record(TurnEnded(TurnId("t"),TurnOutcome.Interrupted(InterruptedBy.User),Usage(),0))),Usage(outputTokens=900)).completed)
    }
    @Test fun `JSON separates current usage from cumulative and includes actual request fingerprints`() {
        val usage = Usage(10,20,100,30)
        val events = listOf(record(ModelRequestStarted(TurnId("t"),RequestId("r"),0,ModelRef("p","m"),"fixed")),record(TurnEnded(TurnId("t"),TurnOutcome.Completed,usage,1)))
        val result = CliResult("s", events, usage + Usage(1000,2000,3000,4000))
        assertTrue(result.completed)
        assertEquals(usage,result.turnUsage)
        assertEquals(JsonPrimitive(100L),(result.json()["turn_usage"] as JsonObject)["cache_read_tokens"])
        assertEquals(JsonArray(listOf(JsonPrimitive("fixed"))),result.json()["prefix_fingerprints"])
    }
}
