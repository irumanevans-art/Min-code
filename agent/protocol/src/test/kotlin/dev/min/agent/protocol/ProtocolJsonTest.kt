package dev.min.agent.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 契约类型的线形状测试：这些类型原样写进会话日志，日志格式一旦发布就要永远可读
 * （agent/store/FORMAT.md 的格式纪律）。谁改了字段名或判别值，这里的期望 JSON 立刻红。
 *
 * 期望 JSON 一律按源码里的 @SerialName 与属性名手写；比较前两边都过 AgentJson.parseToJsonElement，
 * 避免空白差异。
 */
class ProtocolJsonTest {

    private val model = ModelRef(providerId = "anthropic", modelId = "claude-sonnet-5-5")

    private fun <T> roundTrips(serializer: KSerializer<T>, value: T) {
        val text = AgentJson.encodeToString(serializer, value)
        assertEquals("往返不相等：$text", value, AgentJson.decodeFromString(serializer, text))
    }

    private fun <T> assertWire(serializer: KSerializer<T>, value: T, expectedJson: String) {
        val actual = AgentJson.encodeToString(serializer, value)
        assertEquals(
            AgentJson.parseToJsonElement(expectedJson),
            AgentJson.parseToJsonElement(actual),
        )
    }

    // ---- Ids：value class 编码成裸字符串 ----

    @Test
    fun `value class ids encode as bare strings`() {
        // 裸值，不是 {"value":"s1"}
        assertEquals("\"s1\"", AgentJson.encodeToString(SessionId.serializer(), SessionId("s1")))

        val ids: List<Triple<KSerializer<*>, Any, String>> = listOf(
            Triple(SessionId.serializer(), SessionId("s1"), "s1"),
            Triple(TurnId.serializer(), TurnId("turn_1"), "turn_1"),
            Triple(MessageId.serializer(), MessageId("msg_1"), "msg_1"),
            Triple(CallId.serializer(), CallId("call_1"), "call_1"),
            Triple(ApprovalId.serializer(), ApprovalId("ap_1"), "ap_1"),
            Triple(RequestId.serializer(), RequestId("req_1"), "req_1"),
            Triple(ClientMsgId.serializer(), ClientMsgId("cm_1"), "cm_1"),
        )
        for ((serializer, value, raw) in ids) {
            @Suppress("UNCHECKED_CAST")
            val typed = serializer as KSerializer<Any>
            assertEquals("\"$raw\"", AgentJson.encodeToString(typed, value))
            assertEquals(value, AgentJson.decodeFromString(typed, "\"$raw\""))
        }
    }

    // ---- Content ----

    @Test
    fun `model ref keeps its field names`() {
        roundTrips(ModelRef.serializer(), model)
        assertWire(ModelRef.serializer(), model, """{"providerId":"anthropic","modelId":"claude-sonnet-5-5"}""")
    }

    @Test
    fun `every part variant keeps its wire shape and round trips`() {
        val cases: List<Pair<Part, String>> = listOf(
            TextPart("你好") to """{"type":"text","text":"你好"}""",
            ImagePart("image/png", ImageSource.Base64("QUJD")) to
                """{"type":"image","mediaType":"image/png","source":{"type":"base64","data":"QUJD"}}""",
            DocumentPart("application/pdf", ImageSource.Base64("JVBERi0=")) to
                """{"type":"document","mediaType":"application/pdf","source":{"type":"base64","data":"JVBERi0="}}""",
            ThinkingPart("想一想", origin = model) to
                """{"type":"thinking","text":"想一想","origin":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"}}""",
            ThinkingPart("想一想", origin = model, signature = "sig") to
                """{"type":"thinking","text":"想一想","origin":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"signature":"sig"}""",
            RedactedThinkingPart("DATA", origin = model) to
                """{"type":"redacted_thinking","data":"DATA","origin":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"}}""",
            ToolCallPart(CallId("call_1"), "Read", buildJsonObject { put("path", "/workspace/a.kt") }) to
                """{"type":"tool_call","callId":"call_1","name":"Read","input":{"path":"/workspace/a.kt"}}""",
            ToolResultPart(CallId("call_1"), listOf(TextPart("out"))) to
                """{"type":"tool_result","callId":"call_1","content":[{"type":"text","text":"out"}]}""",
            ToolResultPart(CallId("call_1"), listOf(TextPart("boom")), isError = true) to
                """{"type":"tool_result","callId":"call_1","content":[{"type":"text","text":"boom"}],"isError":true}""",
            OpaquePart(model, buildJsonObject { put("reasoning_id", "r1") }) to
                """{"type":"opaque","origin":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"json":{"reasoning_id":"r1"}}""",
        )
        for ((value, expected) in cases) {
            roundTrips(Part.serializer(), value)
            assertWire(Part.serializer(), value, expected)
        }
    }

    @Test
    fun `image source variants keep their wire shape`() {
        val cases: List<Pair<ImageSource, String>> = listOf(
            ImageSource.Base64("QUJD") to """{"type":"base64","data":"QUJD"}""",
            ImageSource.Artifact("shots/1.png") to """{"type":"artifact","path":"shots/1.png"}""",
        )
        for ((value, expected) in cases) {
            roundTrips(ImageSource.serializer(), value)
            assertWire(ImageSource.serializer(), value, expected)
        }
    }

    @Test
    fun `defaults are not written to the wire`() {
        // ThinkingPart.signature = null 不落盘
        val thinking = AgentJson.encodeToString(
            Part.serializer(),
            ThinkingPart("想一想", origin = model),
        )
        assertFalse("signature 不应出现：$thinking", "signature" in thinking)
        // 显式带上 signature 能解码回来
        assertEquals(
            ThinkingPart("想一想", origin = model, signature = "sig"),
            AgentJson.decodeFromString(
                Part.serializer(),
                """{"type":"thinking","text":"想一想","origin":{"providerId":"anthropic","modelId":"claude-sonnet-5-5"},"signature":"sig"}""",
            ),
        )
        // ToolResultPart.isError = false 不落盘
        val toolResult = AgentJson.encodeToString(
            Part.serializer(),
            ToolResultPart(CallId("call_1"), listOf(TextPart("out"))),
        )
        assertFalse("isError 不应出现：$toolResult", "isError" in toolResult)
    }

    // ---- Values ----

    @Test
    fun `usage omits zero fields`() {
        // 四项全 0 → 空 JSON 对象
        assertEquals("{}", AgentJson.encodeToString(Usage.serializer(), Usage()))
        roundTrips(Usage.serializer(), Usage())

        val full = Usage(inputTokens = 1, outputTokens = 2, cacheReadTokens = 3, cacheWriteTokens = 4)
        assertWire(Usage.serializer(), full, """{"inputTokens":1,"outputTokens":2,"cacheReadTokens":3,"cacheWriteTokens":4}""")
        roundTrips(Usage.serializer(), full)

        // 部分字段用默认值补齐
        assertEquals(
            Usage(inputTokens = 5),
            AgentJson.decodeFromString(Usage.serializer(), """{"inputTokens":5}"""),
        )
    }

    @Test
    fun `usage plus adds field wise`() {
        assertEquals(
            Usage(inputTokens = 11, outputTokens = 22, cacheReadTokens = 33, cacheWriteTokens = 44),
            Usage(1, 2, 3, 4) + Usage(10, 20, 30, 40),
        )
    }

    @Test
    fun `stop reason variants keep their serial names`() {
        val cases: List<Pair<StopReason, String>> = listOf(
            StopReason.EndTurn to """{"type":"end_turn"}""",
            StopReason.ToolUse to """{"type":"tool_use"}""",
            StopReason.MaxTokens to """{"type":"max_tokens"}""",
            StopReason.StopSequence to """{"type":"stop_sequence"}""",
            StopReason.Refusal to """{"type":"refusal"}""",
            StopReason.Other("pause_turn") to """{"type":"other","raw":"pause_turn"}""",
        )
        for ((value, expected) in cases) {
            roundTrips(StopReason.serializer(), value)
            assertWire(StopReason.serializer(), value, expected)
        }
    }

    @Test
    fun `permission mode enum values keep their serial names`() {
        val cases = mapOf(
            PermissionMode.Default to "default",
            PermissionMode.AcceptEdits to "acceptEdits",
            PermissionMode.Plan to "plan",
            PermissionMode.Auto to "auto",
            PermissionMode.DontAsk to "dontAsk",
            PermissionMode.BypassPermissions to "bypassPermissions",
        )
        for ((value, wire) in cases) {
            assertEquals("\"$wire\"", AgentJson.encodeToString(PermissionMode.serializer(), value))
            assertEquals(value, AgentJson.decodeFromString(PermissionMode.serializer(), "\"$wire\""))
        }
    }

    @Test
    fun `effort enum values keep their serial names`() {
        val cases = mapOf(
            Effort.Off to "off",
            Effort.Minimal to "minimal",
            Effort.Low to "low",
            Effort.Medium to "medium",
            Effort.High to "high",
            Effort.XHigh to "xhigh",
            Effort.Max to "max",
        )
        for ((value, wire) in cases) {
            assertEquals("\"$wire\"", AgentJson.encodeToString(Effort.serializer(), value))
            assertEquals(value, AgentJson.decodeFromString(Effort.serializer(), "\"$wire\""))
        }
    }
}
