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
    fun `denied approvals land in permission_denials with the SDK field names tool_name, tool_use_id and tool_input`() {
        val events = listOf(
            record(ToolPlanned(TurnId("t"), CallId("c1"), "Bash", buildJsonObject { put("command", "rm -rf /") }, ResumePolicy.Replay)),
            record(ApprovalRequested(TurnId("t"), ApprovalId("a1"), CallId("c1"), PermissionAsk("Bash", "跑命令"))),
            record(ApprovalResolved(ApprovalId("a1"), ApprovalChoice.Deny("不批"), DecidedBy.User)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Interrupted(InterruptedBy.User), Usage(), 0)),
        )
        val denials = CliResult("s", events).json()["permission_denials"]!!.jsonArray
        assertEquals(1, denials.size)
        val d = denials.single().jsonObject
        assertEquals("Bash", d["tool_name"]!!.jsonPrimitive.content)
        assertEquals("c1", d["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("rm -rf /", d["tool_input"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        // SDK 的 result 两个臂都必带 permission_denials：没有被拒的审批时是空数组，不省略
        assertEquals(JsonArray(emptyList()), CliResult("s", emptyList()).json()["permission_denials"])
    }

    @Test
    fun `a budget cap run reports error_max_budget_usd and its error text names the flag`() {
        val events = listOf(
            record(TurnEnded(TurnId("t"), TurnOutcome.Interrupted(InterruptedBy.Limit), Usage(), 0, limitCause = dev.min.agent.spi.LIMIT_CAUSE_MAX_BUDGET_USD)),
        )
        val result = CliResult("s", events)
        assertEquals("error_max_budget_usd", result.subtype)
        assertEquals(listOf("到达 --max-budget-usd 的花费上限"), result.errors)
        // 没写原因的 Limit 依旧是回合数到限
        assertEquals("error_max_turns", CliResult("s", listOf(record(TurnEnded(TurnId("t"), TurnOutcome.Interrupted(InterruptedBy.Limit), Usage(), 0)))).subtype)
    }

    @Test
    fun `cost totals follow the run ledger across the tree, multi-currency stays split`() {
        val usd = dev.min.agent.spi.ModelCharged(ModelRef("test", "m"), Usage(inputTokens = 10), 1.0, "USD")
        val cny = dev.min.agent.spi.ModelCharged(ModelRef("test", "cn"), Usage(inputTokens = 5), 7.0, "CNY")
        val compactionFirst = dev.min.agent.spi.ModelCharged(ModelRef("test", "m"), Usage(inputTokens = 3), 0.5, "USD")

        // 全 USD（含首个请求之前的压缩那笔，M5）：CC 的 total_cost_usd
        val allUsd = CliResult("s", listOf(record(compactionFirst), record(usd))).json()
        assertEquals(1.5, allUsd["total_cost_usd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertNull(allUsd["total_cost"])

        // 跨币种：Min 自己的 total_cost 按币种分列，不冒充换算过
        val mixed = CliResult("s", listOf(record(usd)), treeCost = dev.min.agent.llm.CostTotals(
            mapOf("USD" to 1.0, "CNY" to 7.0), 0, emptyMap(),
        )).json()
        assertNull(mixed["total_cost_usd"])
        assertEquals(setOf("USD", "CNY"), mixed["total_cost"]!!.jsonObject.keys)
        assertEquals(7.0, mixed["total_cost"]!!.jsonObject["CNY"]!!.jsonPrimitive.content.toDouble(), 1e-9)

        // 树级总账优先于根会话事件：子 agent 的花费也算进这次运行
        val tree = CliResult("s", listOf(record(usd)), treeCost = dev.min.agent.llm.CostTotals(
            mapOf("USD" to 3.0), 0, emptyMap(),
        )).json()
        assertEquals(3.0, tree["total_cost_usd"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `the result carries a uuid, the last stop reason and the terminal reason`() {
        val usage = Usage(1, 2, 0, 0)
        val events = listOf(
            record(AssistantMessageCompleted(TurnId("t"), RequestId("r"), MessageId("m1"), listOf(TextPart("答案")), usage, StopReason.EndTurn)),
            record(TurnEnded(TurnId("t"), TurnOutcome.Completed, usage, 1)),
        )
        val json = CliResult("s", events, uuid = "fixed-uuid").json()
        assertEquals(JsonPrimitive("fixed-uuid"), json["uuid"])
        assertEquals(JsonPrimitive("end_turn"), json["stop_reason"])
        assertEquals(JsonPrimitive("completed"), json["terminal_reason"])
        // 没有任何模型请求：stop_reason 是 null（SDK 类型 string | null），不省略
        assertEquals(kotlinx.serialization.json.JsonNull, CliResult("s", emptyList()).json()["stop_reason"])
        // 两次生成的 uuid 不同（默认每个结果新生成）
        assertFalse(CliResult("s", events).json()["uuid"] == CliResult("s", events).json()["uuid"])
    }

    @Test
    fun `terminal_reason is only filled where Min's verdict maps cleanly onto CC's table`() {
        fun terminal(outcome: TurnOutcome, limitCause: String? = null) =
            CliResult("s", listOf(record(TurnEnded(TurnId("t"), outcome, Usage(), 0, limitCause = limitCause)))).json()["terminal_reason"]
        assertEquals(JsonPrimitive("max_turns"), terminal(TurnOutcome.Interrupted(InterruptedBy.Limit)))
        assertEquals(JsonPrimitive("budget_exhausted"), terminal(TurnOutcome.Interrupted(InterruptedBy.Limit), dev.min.agent.spi.LIMIT_CAUSE_MAX_BUDGET_USD))
        assertEquals(JsonPrimitive("hook_stopped"), terminal(TurnOutcome.Interrupted(InterruptedBy.Hook)))
        assertEquals(
            JsonPrimitive("structured_output_retry_exhausted"),
            terminal(TurnOutcome.Failed("用尽", cause = FAILURE_CAUSE_STRUCTURED_OUTPUT_RETRIES)),
        )
        // 对不上取值表的（笼统的失败、用户中断）省略，不编
        assertNull(terminal(TurnOutcome.Failed("HTTP 401")))
        assertNull(terminal(TurnOutcome.Interrupted(InterruptedBy.User)))
    }

    @Test
    fun `a protocol error without any turn is an error_during_execution result with the reason in errors`() {
        val result = CliResult("s", emptyList(), protocolError = "stdin 第 1 行不是合法的 JSON")
        assertFalse(result.completed)
        assertEquals("error_during_execution", result.subtype)
        val json = result.json()
        assertEquals(JsonPrimitive(true), json["is_error"])
        assertNull(json["result"])
        assertEquals(JsonArray(listOf(JsonPrimitive("stdin 第 1 行不是合法的 JSON"))), json["errors"])
        assertNull("对不上取值表，省略", json["terminal_reason"])
    }

    @Test
    fun `a local command is a success without turn events and names the command`() {
        val json = CliResult("s", emptyList(), localCommand = "advisor").json()
        assertEquals(JsonPrimitive("success"), json["subtype"])
        assertEquals(JsonPrimitive(false), json["is_error"])
        assertEquals(JsonPrimitive("advisor"), json["local_command"])
        assertEquals(JsonPrimitive(0), json["num_turns"])
        assertNull(CliResult("s", emptyList()).json()["local_command"])
    }
}
