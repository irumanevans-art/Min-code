package dev.min.agent.llm.openai

import dev.min.agent.llm.FakeHttpTransport
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.OpenAiChatQuirks
import dev.min.agent.llm.StreamUsage
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAIChatStreamTest {
    @Test
    fun `plain text and final usage only chunk complete after DONE`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"id":"msg_1","choices":[{"delta":{"role":"assistant","content":""}}],"usage":null}

            data: {"choices":[{"delta":{"content":"你"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":15,"prompt_tokens_details":{"cached_tokens":30}}}

            data: [DONE]
        """)))
        assertNull(out.error)
        assertEquals(listOf(ModelStreamEvent.Started("msg_1"), ModelStreamEvent.TextDelta("你"), ModelStreamEvent.TextDelta("好"),
            ModelStreamEvent.Completed(ModelMessage(Role.Assistant, listOf(TextPart("你好"))), Usage(70, 15, 30, 0), StopReason.EndTurn, false)), out.events)
    }

    @Test
    fun `thinking is concatenated before text and chat does not invent signatures`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"id":"msg_2","choices":[{"delta":{"reasoning_content":"先","signature":"sig"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"想","content":"答"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"后想","content":"案"},"finish_reason":"stop"}]}

            data: [DONE]
        """)), provider = provider(OpenAiChatQuirks(reasoningField = "reasoning_content")))
        assertNull(out.error)
        assertEquals(listOf(ModelStreamEvent.Started("msg_2"), ModelStreamEvent.ThinkingDelta("先"), ModelStreamEvent.ThinkingDelta("想"),
            ModelStreamEvent.TextDelta("答"), ModelStreamEvent.ThinkingDelta("后想"), ModelStreamEvent.TextDelta("案")), out.events.dropLast(1))
        assertEquals(listOf(ThinkingPart("先想后想", modelRef), TextPart("答案")), out.completed.message.parts)
    }

    @Test
    fun `several tools preserve interleaved parameters and ordinal indices`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"id":"msg_1","choices":[{"delta":{"content":"读取"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":3,"id":"call-A","function":{"name":"Read","arguments":"{\"path\":"}},{"index":9,"id":"call-B","function":{"name":"Grep","arguments":"{\"pattern\":\"to"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":9,"function":{"arguments":"do\"}"}},{"index":3,"function":{"arguments":"\"a.txt\"}"}}]}}]}

            data: {"choices":[{"delta":{"content":"结束"},"finish_reason":"tool_calls"}]}

            data: [DONE]
        """)))
        assertNull(out.error)
        assertEquals(listOf(ModelStreamEvent.Started("msg_1"), ModelStreamEvent.TextDelta("读取"),
            ModelStreamEvent.ToolCallStarted(0, "call-A", "Read"), ModelStreamEvent.ToolInputDelta(0, "{\"path\":"),
            ModelStreamEvent.ToolCallStarted(1, "call-B", "Grep"), ModelStreamEvent.ToolInputDelta(1, "{\"pattern\":\"to"),
            ModelStreamEvent.ToolInputDelta(1, "do\"}"), ModelStreamEvent.ToolInputDelta(0, "\"a.txt\"}"), ModelStreamEvent.TextDelta("结束")), out.events.dropLast(1))
        assertEquals(listOf(TextPart("读取"), ToolCallPart(CallId("call-A"), "Read", obj("""{"path":"a.txt"}""")),
            ToolCallPart(CallId("call-B"), "Grep", obj("""{"pattern":"todo"}""")), TextPart("结束")), out.completed.message.parts)
        assertEquals(StopReason.ToolUse, out.completed.stop)
    }

    @Test
    fun `tool identity may arrive after arguments without losing deltas`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"x\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"Read","arguments":"1}"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"Read","arguments":""}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]
        """)))
        assertNull(out.error)
        assertEquals(listOf(ModelStreamEvent.Started(null), ModelStreamEvent.ToolCallStarted(0, "c", "Read"), ModelStreamEvent.ToolInputDelta(0, "{\"x\":1}")), out.events.dropLast(1))
        assertEquals(listOf(ToolCallPart(CallId("c"), "Read", obj("""{"x":1}"""))), out.completed.message.parts)
    }

    @Test
    fun `empty arguments are object and malformed or nonobject JSON is preserved`() {
        for ((raw, expected) in listOf("" to "{}", "[1]" to """{"__invalid_json":"[1]"}""", "broken" to """{"__invalid_json":"broken"}""", " " to """{"__invalid_json":" "}""")) {
            val outcome = run(FakeHttpTransport(stream("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"Read","arguments":"$raw"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]
            """)))
            assertEquals(obj(expected), (outcome.completed.message.parts.single() as ToolCallPart).input)
        }
    }

    @Test
    fun `length truncates and preserves partial tool arguments`() {
        val done = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"Write","arguments":"{\"content\":\"abc"}}]},"finish_reason":"length"}],"usage":{"completion_tokens":8000}}

            data: [DONE]
        """))).completed
        assertTrue(done.truncated)
        assertEquals(StopReason.MaxTokens, done.stop)
        assertEquals(8000L, done.usage.outputTokens)
        assertEquals(obj("""{"__invalid_json":"{\"content\":\"abc"}"""), (done.message.parts.single() as ToolCallPart).input)
    }

    @Test
    fun `finish reasons preserve vendor and missing values`() {
        for ((raw, expected) in listOf("stop" to StopReason.EndTurn, "tool_calls" to StopReason.ToolUse, "function_call" to StopReason.ToolUse,
            "length" to StopReason.MaxTokens, "content_filter" to StopReason.Refusal, "sensitive" to StopReason.Other("sensitive"),
            "aborted" to StopReason.Other("aborted"), "network_error" to StopReason.Other("network_error"),
            "insufficient_system_resource" to StopReason.Other("insufficient_system_resource"), "model_context_window_exceeded" to StopReason.Other("model_context_window_exceeded"))) {
            val done = run(FakeHttpTransport(stream("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$raw\"}]}\n\ndata: [DONE]"))).completed
            assertEquals(expected, done.stop)
            assertEquals(raw == "length" || raw == "model_context_window_exceeded", done.truncated)
        }
        assertEquals(StopReason.Other("missing"), run(FakeHttpTransport(stream("data: [DONE]"))).completed.stop)
    }

    @Test
    fun `DeepSeek cache hit miss fields determine uncached input`() {
        val done = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":7,"prompt_cache_hit_tokens":60,"prompt_cache_miss_tokens":40,"prompt_tokens_details":{"cached_tokens":9}}}

            data: [DONE]
        """))).completed
        assertEquals(Usage(40, 7, 60, 0), done.usage)
    }

    @Test
    fun `DeepSeek miss can be derived and usage need not include prompt total`() {
        for ((usage, expected) in listOf("""{"prompt_tokens":100,"prompt_cache_hit_tokens":60,"completion_tokens":4}""" to Usage(40, 4, 60, 0),
            """{"prompt_cache_miss_tokens":20,"prompt_cache_hit_tokens":80,"completion_tokens":5}""" to Usage(20, 5, 80, 0))) {
            val done = run(FakeHttpTransport(stream("data: {\"choices\":[],\"usage\":$usage}\n\ndata: [DONE]"))).completed
            assertEquals(expected, done.usage)
        }
    }

    @Test
    fun `usage snapshots overwrite not sum and missing fields preserve earlier totals`() {
        val done = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":30,"cache_write_tokens":10}}}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"completion_tokens":9}}

            data: [DONE]
        """))).completed
        assertEquals(Usage(60, 9, 30, 10), done.usage)
    }

    @Test
    fun `usage options never prevent consuming reported usage`() {
        for (usageMode in StreamUsage.entries) {
            val done = run(FakeHttpTransport(stream("""
                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: [DONE]
            """)), provider = provider(OpenAiChatQuirks(streamUsage = usageMode))).completed
            assertEquals(Usage(4, 2, 0, 0), done.usage)
        }
        assertEquals(Usage(), run(FakeHttpTransport(okStream)).completed.usage)
    }

    @Test
    fun `empty or unsupported reasoning fields never create empty parts`() {
        val raw = """
            data: {"choices":[{"delta":{"content":"","reasoning_content":"","other_reason":"ignored","signature":"sig"},"finish_reason":"stop"}]}

            data: [DONE]
        """
        assertEquals(emptyList<Any>(), run(FakeHttpTransport(stream(raw)), provider = provider(OpenAiChatQuirks(reasoningField = "reasoning_content"))).completed.message.parts)
        val q = OpenAiChatQuirks(reasoningField = "other_reason")
        assertEquals(listOf(ThinkingPart("ignored", modelRef)), run(FakeHttpTransport(stream(raw)), provider = provider(q)).completed.message.parts)
    }

    @Test
    fun `encrypted thinking fragments survive as opaque with model origin`() {
        val done = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{"reasoning_content":"摘要","encrypted_content":"EN"}}]}

            data: {"choices":[{"delta":{"encrypted_content":"C","content":"答"},"finish_reason":"stop"}]}

            data: [DONE]
        """)), provider = provider(OpenAiChatQuirks(reasoningField = "reasoning_content", encryptedReasoningField = "encrypted_content"))).completed
        assertEquals(listOf(ThinkingPart("摘要", modelRef), OpaquePart(modelRef, obj("""{"encrypted_content":"ENC"}""")), TextPart("答")), done.message.parts)
    }

    @Test
    fun `DONE stops upstream and yields exactly one completion`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            data: [DONE]
        """, IOException("tail closed"))))
        assertNull(out.error)
        assertEquals(2, out.events.size)
        assertEquals(1, out.events.filterIsInstance<ModelStreamEvent.Completed>().size)
    }

    @Test
    fun `finish reason alone is not completion when DONE is missing`() {
        val out = run(FakeHttpTransport(stream("data: {\"id\":\"m\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")))
        assertEquals(ModelFailureKind.Network, out.modelError().kind)
        assertFalse(out.modelError().beforeFirstByte)
        assertEquals(listOf(ModelStreamEvent.Started("m")), out.events)
    }

    @Test
    fun `empty stream and IOException before any event are network before first byte`() {
        for (reply in listOf(FakeHttpTransport.Reply.Sse(emptyList()), FakeHttpTransport.Reply.Sse(emptyList(), IOException("eof")))) {
            val out = run(FakeHttpTransport(reply))
            assertEquals(ModelFailureKind.Network, out.modelError().kind)
            assertTrue(out.modelError().beforeFirstByte)
            assertTrue(out.events.isEmpty())
        }
    }

    @Test
    fun `IOException after text keeps deltas but does not complete`() {
        val out = run(FakeHttpTransport(stream("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}", IOException("reset"))))
        assertEquals(ModelFailureKind.Network, out.modelError().kind)
        assertFalse(out.modelError().beforeFirstByte)
        assertEquals(listOf(ModelStreamEvent.Started(null), ModelStreamEvent.TextDelta("半句")), out.events)
    }

    @Test
    fun `ping and empty events count as received before disconnect`() {
        for (raw in listOf("event: ping\ndata: ping", "event: keepalive\ndata: ")) {
            val out = run(FakeHttpTransport(stream(raw, IOException("eof"))))
            assertEquals(ModelFailureKind.Network, out.modelError().kind)
            assertFalse(out.modelError().beforeFirstByte)
            assertTrue(out.events.isEmpty())
        }
    }

    @Test
    fun `incomplete tool identity or missing index fails instead of inventing a call`() {
        for (raw in listOf("""{"index":0,"function":{"name":"Read"}}""", """{"id":"c","function":{"name":"Read"}}""")) {
            val out = run(FakeHttpTransport(stream("data: {\"choices\":[{\"delta\":{\"tool_calls\":[$raw]}}]}\n\ndata: [DONE]")))
            assertEquals(ModelFailureKind.Other, out.modelError().kind)
            assertTrue(out.events.none { it is ModelStreamEvent.Completed })
        }
    }

    @Test
    fun `null error and usage plus extra choices do not corrupt the first choice`() {
        val out = run(FakeHttpTransport(stream("""
            data: {"error":null,"id":"m","choices":[{"delta":{"content":"主答案"},"finish_reason":"stop"},{"delta":{"content":"备选答案"},"finish_reason":"length"}],"usage":null}

            data: [DONE]
        """)))
        assertNull(out.error)
        assertEquals(listOf(TextPart("主答案")), out.completed.message.parts)
        assertEquals(StopReason.EndTurn, out.completed.stop)
        assertFalse(out.completed.truncated)
    }
}
