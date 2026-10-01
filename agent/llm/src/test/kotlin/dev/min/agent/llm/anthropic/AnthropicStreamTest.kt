package dev.min.agent.llm.anthropic

import dev.min.agent.testkit.FakeHttpTransport
import dev.min.agent.testkit.FakeHttpTransport.Reply
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.testkit.parseSse
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnthropicStreamTest {
    /** 夹具在源码里带缩进，逐行 trim 后再拆（parseSse 要求 event:/data: 顶格） */
    private fun stream(sse: String, failAfter: IOException? = null) =
        Reply.Sse(parseSse(sse.lines().joinToString("\n") { it.trim() }), failAfter)

    private fun start(id: String = "msg_1", usage: String = """{"input_tokens":25,"output_tokens":1}""") =
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"$id","type":"message","role":"assistant","content":[],"usage":$usage}}
        """

    private fun finish(stop: String, outputTokens: Int = 15) =
        """
        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"$stop","stop_sequence":null},"usage":{"output_tokens":$outputTokens}}

        event: message_stop
        data: {"type":"message_stop"}
        """

    @Test
    fun `plain text stream`() {
        val sse = start(usage = """{"input_tokens":25,"cache_creation_input_tokens":5,"cache_read_input_tokens":10,"output_tokens":1}""") +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type":"ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            """ + finish("end_turn")
        val out = run(FakeHttpTransport(stream(sse)))
        assertNull(out.error)
        assertEquals(
            listOf(
                ModelStreamEvent.Started("msg_1"),
                ModelStreamEvent.TextDelta("你"),
                ModelStreamEvent.TextDelta("好"),
                ModelStreamEvent.Completed(
                    ModelMessage(Role.Assistant, listOf(TextPart("你好"))),
                    Usage(inputTokens = 25, outputTokens = 15, cacheReadTokens = 10, cacheWriteTokens = 5),
                    StopReason.EndTurn,
                    truncated = false,
                ),
            ),
            out.events,
        )
    }

    @Test
    fun `thinking with signature comes before the text`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"让我想"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想。"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQB"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"xyz"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"ENC"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"答案"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            """ + finish("end_turn")
        val out = run(FakeHttpTransport(stream(sse)))
        assertNull(out.error)
        assertEquals(
            listOf(
                ModelStreamEvent.Started("msg_1"),
                ModelStreamEvent.ThinkingDelta("让我想"),
                ModelStreamEvent.ThinkingDelta("想。"),
                ModelStreamEvent.TextDelta("答案"),
            ),
            out.events.dropLast(1),
        )
        assertEquals(
            listOf(
                ThinkingPart("让我想想。", claudeRef, "EqQBxyz"),
                RedactedThinkingPart("ENC", claudeRef),
                TextPart("答案"),
            ),
            out.completed.message.parts,
        )
    }

    @Test
    fun `several tool calls with interleaved argument fragments`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"我来读两个文件"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_A","name":"Read","input":{}}}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_B","name":"Grep","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\": "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"pattern\":\"to"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"do\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            """ + finish("tool_use")
        val out = run(FakeHttpTransport(stream(sse)))
        assertNull(out.error)
        assertEquals(
            listOf(
                ModelStreamEvent.Started("msg_1"),
                ModelStreamEvent.TextDelta("我来读两个文件"),
                ModelStreamEvent.ToolCallStarted(0, "toolu_A", "Read"),
                ModelStreamEvent.ToolCallStarted(1, "toolu_B", "Grep"),
                ModelStreamEvent.ToolInputDelta(0, "{\"path\": "),
                ModelStreamEvent.ToolInputDelta(1, "{\"pattern\":\"to"),
                ModelStreamEvent.ToolInputDelta(0, "\"a.txt\"}"),
                ModelStreamEvent.ToolInputDelta(1, "do\"}"),
            ),
            out.events.dropLast(1),
        )
        val done = out.completed
        assertEquals(StopReason.ToolUse, done.stop)
        assertFalse(done.truncated)
        assertEquals(
            listOf(
                TextPart("我来读两个文件"),
                ToolCallPart(CallId("toolu_A"), "Read", json("""{"path":"a.txt"}""") as JsonObject),
                ToolCallPart(CallId("toolu_B"), "Grep", json("""{"pattern":"todo"}""") as JsonObject),
            ),
            done.message.parts,
        )
    }

    @Test
    fun `empty arguments become an empty object and broken ones are preserved`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0","name":"Now","input":{}}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"Read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"t2","name":"Read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"[1,2]"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            """ + finish("tool_use")
        val parts = run(FakeHttpTransport(stream(sse))).completed.message.parts
        assertEquals(JsonObject(emptyMap()), (parts[0] as ToolCallPart).input)
        assertEquals(
            JsonObject(mapOf("__invalid_json" to JsonPrimitive("{\"path\":\"a"))),
            (parts[1] as ToolCallPart).input,
        )
        // 合法 JSON 但不是对象，同样不能悄悄当成参数
        assertEquals(
            JsonObject(mapOf("__invalid_json" to JsonPrimitive("[1,2]"))),
            (parts[2] as ToolCallPart).input,
        )
    }

    @Test
    fun `tool input given whole in the start event is kept when no deltas follow`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0","name":"Read","input":{"path":"x"}}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            """ + finish("tool_use")
        val call = run(FakeHttpTransport(stream(sse))).completed.message.parts.single() as ToolCallPart
        assertEquals(json("""{"path":"x"}"""), call.input)
    }

    @Test
    fun `hitting the output cap marks the message truncated`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t0","name":"Write","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"content\":\"很长很"}}

            """ + finish("max_tokens", outputTokens = 8000)
        val done = run(FakeHttpTransport(stream(sse))).completed
        assertEquals(StopReason.MaxTokens, done.stop)
        assertTrue(done.truncated)
        assertEquals(8000L, done.usage.outputTokens)
        val call = done.message.parts.single() as ToolCallPart
        assertTrue(call.input.containsKey("__invalid_json"))
    }

    @Test
    fun `stop reasons map and unknown ones are kept`() {
        fun stopOf(raw: String) = run(FakeHttpTransport(stream(start() + "\n\n" + finish(raw)))).completed
        assertEquals(StopReason.EndTurn, stopOf("end_turn").stop)
        assertEquals(StopReason.ToolUse, stopOf("tool_use").stop)
        assertEquals(StopReason.MaxTokens, stopOf("max_tokens").stop)
        assertEquals(StopReason.StopSequence, stopOf("stop_sequence").stop)
        assertEquals(StopReason.Refusal, stopOf("refusal").stop)
        val pause = stopOf("pause_turn")
        assertEquals(StopReason.Other("pause_turn"), pause.stop)
        assertFalse(pause.truncated)
        val window = stopOf("model_context_window_exceeded")
        assertEquals(StopReason.Other("model_context_window_exceeded"), window.stop)
        assertTrue(window.truncated)
    }

    @Test
    fun `usage reported only at the end by compatible endpoints is picked up`() {
        val sse = start(usage = """{"input_tokens":0,"output_tokens":0}""") + "\n\n" +
            """
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":120,"cache_read_input_tokens":80,"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}
            """
        assertEquals(Usage(120, 7, 80, 0), run(FakeHttpTransport(stream(sse))).completed.usage)
    }

    @Test
    fun `later zeros do not wipe usage from the start event`() {
        val sse = start(usage = """{"input_tokens":30,"output_tokens":1}""") + "\n\n" +
            """
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":0,"output_tokens":9}}

            event: message_stop
            data: {"type":"message_stop"}
            """
        assertEquals(Usage(30, 9, 0, 0), run(FakeHttpTransport(stream(sse))).completed.usage)
    }

    @Test
    fun `unknown block types and events are ignored`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":1}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: something_new
            data: {"type":"something_new"}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":"直接给出的"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            """ + finish("end_turn")
        val out = run(FakeHttpTransport(stream(sse)))
        assertNull(out.error)
        assertEquals(
            listOf(ModelStreamEvent.Started("msg_1"), ModelStreamEvent.TextDelta("直接给出的")),
            out.events.dropLast(1),
        )
        assertEquals(listOf(TextPart("直接给出的")), out.completed.message.parts)
    }

    @Test
    fun `empty text and empty thinking blocks are left out of the message`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            """ + finish("end_turn")
        assertEquals(emptyList<Any>(), run(FakeHttpTransport(stream(sse))).completed.message.parts)
    }

    // ---- 流中失败 ----

    @Test
    fun `error event after content maps by type and is not before the first byte`() {
        val sse = start() +
            """

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
            """
        val out = run(FakeHttpTransport(stream(sse)))
        val err = out.modelError()
        assertEquals(ModelFailureKind.Overloaded, err.kind)
        assertFalse(err.beforeFirstByte)
        assertTrue(err.retryable)
        assertEquals(listOf(ModelStreamEvent.Started("msg_1")), out.events)
    }

    @Test
    fun `error event as the very first event counts as before the first byte`() {
        val sse = """
            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
            """
        val err = run(FakeHttpTransport(stream(sse))).modelError()
        assertEquals(ModelFailureKind.Overloaded, err.kind)
        assertTrue(err.beforeFirstByte)
    }

    @Test
    fun `stream error types map to failure kinds`() {
        fun kindOf(type: String, message: String = "x"): ModelFailureKind {
            val sse = """
                event: error
                data: {"type":"error","error":{"type":"$type","message":"$message"}}
                """
            return run(FakeHttpTransport(stream(sse))).modelError().kind
        }
        assertEquals(ModelFailureKind.Server, kindOf("api_error"))
        assertEquals(ModelFailureKind.BadRequest, kindOf("invalid_request_error"))
        assertEquals(ModelFailureKind.BadRequest, kindOf("something_else"))
        assertEquals(ModelFailureKind.RateLimited, kindOf("rate_limit_error"))
        assertEquals(ModelFailureKind.Auth, kindOf("authentication_error"))
        assertEquals(ModelFailureKind.ContextOverflow, kindOf("invalid_request_error", "prompt is too long: 250000 tokens > 200000 maximum"))
    }

    @Test
    fun `connection dropped after the first event is a network failure after the first byte`() {
        val sse = start() + "\n\n" +
            """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
            """
        val out = run(FakeHttpTransport(stream(sse, failAfter = IOException("reset"))))
        val err = out.modelError()
        assertEquals(ModelFailureKind.Network, err.kind)
        assertFalse(err.beforeFirstByte)
        assertEquals(listOf(ModelStreamEvent.Started("msg_1")), out.events)
    }

    @Test
    fun `stream that ends without message_stop counts as a dropped connection`() {
        val err = run(FakeHttpTransport(stream(start()))).modelError()
        assertEquals(ModelFailureKind.Network, err.kind)
        assertFalse(err.beforeFirstByte)
    }

    @Test
    fun `disconnect before any event is before the first byte`() {
        val err = run(FakeHttpTransport(Reply.Sse(emptyList(), failAfter = IOException("eof")))).modelError()
        assertEquals(ModelFailureKind.Network, err.kind)
        assertTrue(err.beforeFirstByte)
        val empty = run(FakeHttpTransport(Reply.Sse(emptyList()))).modelError()
        assertEquals(ModelFailureKind.Network, empty.kind)
        assertTrue(empty.beforeFirstByte)
    }

    @Test
    fun `garbage in an event is reported instead of ignored`() {
        val err = run(FakeHttpTransport(Reply.Sse(parseSse("event: message_start\ndata: <html>oops</html>")))).modelError()
        assertEquals(ModelFailureKind.Other, err.kind)
        assertTrue(err.beforeFirstByte)
    }
}
