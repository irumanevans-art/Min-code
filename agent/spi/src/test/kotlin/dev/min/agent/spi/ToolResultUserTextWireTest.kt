package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolResultUserTextWireTest {
    @Test fun `old tool results have no user text and keep their JSON shape`() {
        // 旧日志里用户的话就在 content 里：读出来 userText 是 null，再编码不会多出字段
        val json = """{"content":[{"type":"text","text":"用户回答了你的问题：\n- 用哪个库？ → Room"}]}"""
        val result = AgentJson.decodeFromString(ToolResult.serializer(), json)
        assertNull(result.userText)
        assertEquals(AgentJson.parseToJsonElement(json), AgentJson.parseToJsonElement(AgentJson.encodeToString(ToolResult.serializer(), result)))
    }

    @Test fun `user text round trips inside tool_outcome and is written only when present`() {
        val outcome: SessionEvent = ToolOutcome(
            TurnId("t1"), CallId("c1"),
            ToolResult(listOf(TextPart("用户已回答。")), userText = "用户的回答：Room"),
        )
        val expected = """{"type":"tool_outcome","turnId":"t1","callId":"c1","result":{
            "content":[{"type":"text","text":"用户已回答。"}],"userText":"用户的回答：Room"}}"""
        val encoded = AgentJson.encodeToString(SessionEvent.serializer(), outcome)
        assertEquals(AgentJson.parseToJsonElement(expected), AgentJson.parseToJsonElement(encoded))
        assertEquals(outcome, AgentJson.decodeFromString(SessionEvent.serializer(), encoded))
    }

    @Test fun `display text shows the tool output followed by what the user said`() {
        assertEquals("用户已回答。\n答案：Room",ToolResult(listOf(TextPart("用户已回答。")), userText = "答案：Room").displayText())
        assertEquals("用户已回答。 答案：Room", ToolResult(listOf(TextPart("用户已回答。")), userText = "答案：Room").displayText(" "))
        assertEquals("旧结果", ToolResult(listOf(TextPart("旧结果"))).displayText())
    }
}
