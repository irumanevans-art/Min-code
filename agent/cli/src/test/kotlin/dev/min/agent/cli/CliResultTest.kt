package dev.min.agent.cli

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.StructuredOutputDelivered
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `-p` 的结果对象：字段名与出现条件照 CC 的 result 消息（04-sessions-sdk-automation.md :218–219） */
class CliResultTest {
    private fun record(event: SessionEvent) = EventRecord(1, 0, event)

    @Test
    fun `a failed run reports the errors array and CC field names`() {
        val result = CliResult("s", listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Failed("HTTP 401"), Usage(), 0))))
        assertFalse(result.completed)
        assertEquals("error_during_execution", result.subtype)
        assertEquals(JsonPrimitive(true), result.json()["is_error"])
        assertEquals(JsonArray(listOf(JsonPrimitive("HTTP 401"))), result.json()["errors"])
        assertNull(result.json()["result"])
        assertNull("旧键直接改名、不双写", result.json()["session"])
        assertNull(result.json()["completed"])
    }

    @Test
    fun `successful runs carry the final reply text and this run's usage`() {
        val usage = Usage(10, 20, 100, 30)
        val events = listOf(
            record(ModelRequestStarted(TurnId("t"), RequestId("r"), 0, ModelRef("p", "m"), "fixed")),
            record(AssistantMessageCompleted(TurnId("t"), RequestId("r"), MessageId("m1"), listOf(TextPart("第一段")), usage, StopReason.ToolUse)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Completed, usage, 1)),
            record(ModelRequestStarted(TurnId("t"), RequestId("r2"), 1, ModelRef("p", "m"), "fixed2")),
            record(AssistantMessageCompleted(TurnId("t"), RequestId("r2"), MessageId("m2"), listOf(TextPart("答案")), usage, StopReason.EndTurn)),
            record(TurnEnded(TurnId("t2"), TurnOutcome.Completed, usage, 7)),
        )
        val result = CliResult("s", events)
        assertTrue(result.completed)
        assertEquals(JsonPrimitive("result"), result.json()["type"])
        assertEquals(JsonPrimitive("success"), result.json()["subtype"])
        assertEquals(JsonPrimitive(false), result.json()["is_error"])
        assertEquals("最终回复取最后一条带文字的助手消息", JsonPrimitive("答案"), result.json()["result"])
        assertEquals(JsonPrimitive("s"), result.json()["session_id"])
        assertEquals(JsonPrimitive(2), result.json()["num_turns"])
        assertEquals(JsonPrimitive(8L), result.json()["duration_ms"])
        val usageObj = result.json()["usage"] as JsonObject
        // 两个回合各带一份 usage：结果对象里是本次运行合计（20/40/200/60），不是会话累计
        assertEquals(JsonPrimitive(20L), usageObj["input_tokens"])
        assertEquals(JsonPrimitive(40L), usageObj["output_tokens"])
        assertEquals("cache 写入按 CC 键名叫 cache_creation_input_tokens", JsonPrimitive(60L), usageObj["cache_creation_input_tokens"])
        assertEquals(JsonPrimitive(200L), usageObj["cache_read_input_tokens"])
        assertNull("没有价格数据，不输出 total_cost_usd（输出 0 会误导）", result.json()["total_cost_usd"])
    }

    @Test
    fun `successful zero token replies still complete and missing terminal cannot succeed`() {
        assertTrue(CliResult("s", listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Completed, Usage(), 0)))).completed)
        assertFalse(CliResult("s", emptyList()).completed)
        assertFalse(CliResult("s", listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Interrupted(InterruptedBy.User), Usage(), 0)))).completed)
    }

    @Test
    fun `subtype follows CC naming including structured-output retries`() {
        fun subtypeOf(outcome: TurnOutcome) =
            CliResult("s", listOf(record(TurnEnded(TurnId("t"), outcome, Usage(), 0)))).subtype
        assertEquals("success", subtypeOf(TurnOutcome.Completed))
        assertEquals("error_max_turns", subtypeOf(TurnOutcome.Interrupted(InterruptedBy.Limit)))
        assertEquals("error_during_execution", subtypeOf(TurnOutcome.Failed("HTTP 401")))
        assertEquals("error_during_execution", subtypeOf(TurnOutcome.Interrupted(InterruptedBy.User)))
        assertEquals(
            "error_max_structured_output_retries",
            subtypeOf(TurnOutcome.Failed("重试用尽", cause = FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES)),
        )
    }

    @Test
    fun `structured_output appears only when a validated answer was delivered`() {
        val answer = buildJsonObject { put("name", "Min") }
        val delivered = listOf(
            record(StructuredOutputDelivered(TurnId("t"), answer)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Completed, Usage(), 0)),
        )
        assertEquals(answer, CliResult("s", delivered).json()["structured_output"])
        val failed = listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Failed("两次都没过"), Usage(), 0)))
        assertNull(CliResult("s", failed).json()["structured_output"])
        assertEquals(JsonArray(listOf(JsonPrimitive("两次都没过"))), CliResult("s", failed).json()["errors"])
    }

    @Test
    fun `denied approvals land in permission_denials with tool, call id and input`() {
        val events = listOf(
            record(ToolPlanned(TurnId("t"), CallId("c1"), "Bash", buildJsonObject { put("command", "rm -rf /") }, ResumePolicy.Replay)),
            record(ApprovalRequested(TurnId("t"), ApprovalId("a1"), CallId("c1"), PermissionAsk("Bash", "跑命令"))),
            record(ApprovalResolved(ApprovalId("a1"), ApprovalChoice.Deny("不批"), DecidedBy.User)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Interrupted(InterruptedBy.User), Usage(), 0)),
        )
        val denials = CliResult("s", events).json()["permission_denials"]!!.jsonArray
        assertEquals(1, denials.size)
        val d = denials.single().jsonObject
        assertEquals("Bash", d["tool"]!!.jsonPrimitive.content)
        assertEquals("c1", d["call_id"]!!.jsonPrimitive.content)
        assertEquals("rm -rf /", d["input"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        // 没有被拒的审批时整个键不出现
        assertNull(CliResult("s", emptyList()).json()["permission_denials"])
    }
}
