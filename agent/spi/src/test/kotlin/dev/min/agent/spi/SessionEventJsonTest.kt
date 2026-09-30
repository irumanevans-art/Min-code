package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 日志格式的冒烟：嵌套的 sealed 类型（事件里套内容块、审批答复里套判别）在同一个 `type` 判别键下能往返，
 * 默认值不落盘。逐个变体的往返测试另有专门的测试类。
 */
class SessionEventJsonTest {
    private val model = ModelRef("anthropic", "claude-sonnet-5-5")

    @Test
    fun `nested sealed types round-trip under the type discriminator`() {
        val record = EventRecord(
            seq = 7,
            atMs = 1_000,
            event = AssistantMessageCompleted(
                turnId = TurnId("turn_1"),
                requestId = RequestId("req_1"),
                messageId = MessageId("msg_1"),
                parts = listOf(
                    ThinkingPart("想一想", origin = model, signature = "sig"),
                    TextPart("好的"),
                    ToolCallPart(CallId("call_1"), "Read", buildJsonObject { put("file_path", JsonPrimitive("/workspace/a.kt")) }),
                    ImagePart("image/png", ImageSource.Artifact("shots/1.png")),
                ),
                usage = Usage(inputTokens = 10, outputTokens = 5, cacheReadTokens = 3),
                stop = StopReason.Other("pause_turn"),
            ),
        )
        val text = AgentJson.encodeToString(EventRecord.serializer(), record)
        assertEquals(record, AgentJson.decodeFromString(EventRecord.serializer(), text))

        val event = AgentJson.parseToJsonElement(text).jsonObject.getValue("event").jsonObject
        assertEquals("assistant_message_completed", event.getValue("type").jsonPrimitive.content)
        assertFalse("默认值不应落盘", "truncated" in event)
    }

    @Test
    fun `approval answers round-trip`() {
        val events = listOf<SessionEvent>(
            ApprovalResolved(ApprovalId("ap_1"), ApprovalChoice.Always("Bash(git status *)"), DecidedBy.User),
            ApprovalResolved(ApprovalId("ap_2"), ApprovalChoice.Deny("别 push"), DecidedBy.Timeout),
            ApprovalResolved(ApprovalId("ap_3"), ApprovalChoice.Once, DecidedBy.Rule("Read(**)", "user")),
        )
        events.forEach { e ->
            val text = AgentJson.encodeToString(SessionEvent.serializer(), e)
            assertEquals(e, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        }
    }
}
