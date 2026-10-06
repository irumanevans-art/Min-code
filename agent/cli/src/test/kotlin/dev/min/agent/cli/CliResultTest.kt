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
        assertTrue(CliResult("s",listOf(record(TurnEnded(TurnId("t"),TurnOutcome.Completed,Usage(),0))),Usage()).completed)
        assertFalse(CliResult("s",emptyList(),Usage(outputTokens=900)).completed)
        assertFalse(CliResult("s",listOf(record(TurnEnded(TurnId("t"),TurnOutcome.Interrupted(InterruptedBy.User),Usage(),0))),Usage(outputTokens=900)).completed)
    }
    @Test fun `subtype follows CC naming - success, error_max_turns on the request limit, error_during_execution`() {
        fun subtypeOf(outcome: TurnOutcome) =
            CliResult("s", listOf(record(TurnEnded(TurnId("t"), outcome, Usage(), 0))), Usage()).subtype
        assertEquals("success", subtypeOf(TurnOutcome.Completed))
        assertEquals("error_max_turns", subtypeOf(TurnOutcome.Interrupted(InterruptedBy.Limit)))
        assertEquals("error_during_execution", subtypeOf(TurnOutcome.Failed("HTTP 401")))
        assertEquals("error_during_execution", subtypeOf(TurnOutcome.Interrupted(InterruptedBy.User)))
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
    @Test fun `structured_output appears only when a validated answer was delivered`() {
        val answer = buildJsonObject { put("name", "Min") }
        val delivered = listOf(
            record(StructuredOutputDelivered(TurnId("t"), answer)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Completed, Usage(), 0)),
        )
        assertEquals(answer, CliResult("s", delivered, Usage()).json()["structured_output"])
        val failed = listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Failed("两次都没过"), Usage(), 0)))
        assertNull(CliResult("s", failed, Usage()).json()["structured_output"])
        assertEquals("两次都没过", CliResult("s", failed, Usage()).json()["error"]?.jsonPrimitive?.content)
    }
}
