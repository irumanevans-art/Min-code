package dev.min.code.core.relay

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAIToAnthropic.StreamConverter 的特征测试：把 onChunk / finish 返回的 SSE 字符串解析成
 * (event 名, data JsonObject) 序列，断言完整事件顺序与关键字段。钉住「现在实际输出什么」，包括已知缺陷。
 */
class RelayStreamCharacterizationTest {

    private fun parse(outputs: List<String>): List<Pair<String, JsonObject>> =
        outputs.filter { it.isNotBlank() }.map { s ->
            val lines = s.lineSequence().toList()
            val name = lines.first { it.startsWith("event: ") }.removePrefix("event: ")
            val data = lines.first { it.startsWith("data: ") }.removePrefix("data: ")
            name to relayJson.parseToJsonElement(data).jsonObject
        }

    private fun names(events: List<Pair<String, JsonObject>>) = events.map { it.first }

    private fun textChunk(content: String, finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("delta", buildJsonObject { put("content", content) })
                finish?.let { put("finish_reason", it) }
            })
        })
    }

    private fun emptyDeltaChunk(finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("delta", buildJsonObject { })
                finish?.let { put("finish_reason", it) }
            })
        })
    }

    private fun toolCall(index: Int, id: String? = null, name: String? = null, args: String? = null) =
        buildJsonObject {
            put("index", index)
            id?.let { put("id", it) }
            if (name != null || args != null) {
                put("function", buildJsonObject {
                    name?.let { put("name", it) }
                    args?.let { put("arguments", it) }
                })
            }
        }

    private fun toolChunk(calls: List<JsonObject>, finish: String? = null) = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("delta", buildJsonObject {
                    put("tool_calls", buildJsonArray { calls.forEach { add(it) } })
                })
                finish?.let { put("finish_reason", it) }
            })
        })
    }

    private fun messageStartOf(events: List<Pair<String, JsonObject>>) =
        events.first { it.first == "message_start" }.second.obj("message")!!

    private fun messageDeltaOf(events: List<Pair<String, JsonObject>>) =
        events.first { it.first == "message_delta" }.second

    @Test
    fun plain_text_multi_chunk_full_sequence() {
        val conv = OpenAIToAnthropic.StreamConverter("claude-sonnet-4")
        val events = parse(
            conv.onChunk(buildJsonObject {
                put("id", "chatcmpl_1")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject { put("content", "Hel") })
                    })
                })
            })
                + conv.onChunk(buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("delta", buildJsonObject { put("content", "lo") })
                            put("finish_reason", "stop")
                        })
                    })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 3)
                        put("completion_tokens", 2)
                    })
                })
                + conv.finish()
        )
        assertEquals(
            listOf(
                "message_start",
                "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop",
                "message_delta", "message_stop",
            ),
            names(events),
        )
        assertEquals(
            buildJsonObject {
                put("id", "chatcmpl_1")
                put("type", "message")
                put("role", "assistant")
                put("model", "claude-sonnet-4")
                put("content", buildJsonArray { })
                put("stop_reason", JsonNull)
                put("stop_sequence", JsonNull)
                put("usage", buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) })
            },
            messageStartOf(events),
        )
        assertEquals(
            buildJsonObject {
                put("type", "content_block_start")
                put("index", 0)
                put("content_block", buildJsonObject { put("type", "text"); put("text", "") })
            },
            events[1].second,
        )
        assertEquals(
            buildJsonObject {
                put("type", "content_block_delta")
                put("index", 0)
                put("delta", buildJsonObject { put("type", "text_delta"); put("text", "Hel") })
            },
            events[2].second,
        )
        assertEquals("lo", events[3].second.obj("delta")?.str("text"))
        assertEquals(
            buildJsonObject { put("type", "content_block_stop"); put("index", 0) },
            events[4].second,
        )
        // message_delta 只带 output_tokens；stop_reason 来自 finish_reason=stop
        val delta = messageDeltaOf(events)
        assertEquals(
            buildJsonObject { put("stop_reason", "end_turn"); put("stop_sequence", JsonNull) },
            delta["delta"],
        )
        assertEquals(listOf("output_tokens"), delta.obj("usage")!!.keys.toList())
        assertEquals(2, delta.obj("usage")?.int("output_tokens"))
        assertEquals(
            buildJsonObject { put("type", "message_stop") },
            events[6].second,
        )
    }

    @Test
    fun first_chunk_with_only_role_emits_only_message_start() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("delta", buildJsonObject { put("role", "assistant") })
                    })
                })
            })
                + conv.finish()
        )
        assertEquals(listOf("message_start", "message_delta", "message_stop"), names(events))
    }

    @Test
    fun text_then_tool_args_in_multiple_segments() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(textChunk("Let me check"))
                + conv.onChunk(toolChunk(listOf(toolCall(0, id = "call_1", name = "Bash", args = "{\"cmd\":\"ls -"))))
                + conv.onChunk(toolChunk(listOf(toolCall(0, args = "la\"}"))))
                + conv.finish()
        )
        assertEquals(
            listOf(
                "message_start",
                "content_block_start", "content_block_delta",
                "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "content_block_stop",
                "message_delta", "message_stop",
            ),
            names(events),
        )
        // 文本块 index 0，工具块 index 1：OpenAI 的 tool index 0 映射到 anthropic 的 block index 1
        assertEquals("text", events[1].second.obj("content_block")?.str("type"))
        assertEquals(0, events[1].second.int("index"))
        assertEquals(
            buildJsonObject {
                put("type", "tool_use")
                put("id", "call_1")
                put("name", "Bash")
                put("input", buildJsonObject { })
            },
            events[3].second["content_block"],
        )
        assertEquals(1, events[3].second.int("index"))
        assertEquals(
            buildJsonObject {
                put("type", "input_json_delta")
                put("partial_json", "{\"cmd\":\"ls -")
            },
            events[4].second["delta"],
        )
        assertEquals(1, events[4].second.int("index"))
        assertEquals("la\"}", events[5].second.obj("delta")?.str("partial_json"))
        assertEquals(0, events[6].second.int("index"))
        assertEquals(1, events[7].second.int("index"))
        // 全程没有 finish_reason：即使有工具调用，stop_reason 也落到默认 end_turn。见 GLM 汇报
        assertEquals("end_turn", messageDeltaOf(events).obj("delta")?.str("stop_reason"))
        assertEquals(0, messageDeltaOf(events).obj("usage")?.int("output_tokens"))
    }

    @Test
    fun interleaved_tool_calls_separated_by_index() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(toolChunk(listOf(
                toolCall(0, id = "call_1", name = "A", args = "{\"x\":"),
                toolCall(1, id = "call_2", name = "B", args = "{\"y\":2}"),
            )))
                + conv.onChunk(toolChunk(listOf(toolCall(0, args = "1}")), finish = "tool_calls"))
                + conv.finish()
        )
        assertEquals(
            listOf(
                "message_start",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta",
                "content_block_delta",
                "content_block_stop", "content_block_stop",
                "message_delta", "message_stop",
            ),
            names(events),
        )
        // 工具 1 开块时把还开着的工具 0 收掉
        assertEquals(0, events[1].second.int("index"))
        assertEquals("A", events[1].second.obj("content_block")?.str("name"))
        assertEquals(0, events[2].second.int("index"))
        assertEquals(0, events[3].second.int("index")) // 工具 0 的 stop（中途）
        assertEquals(1, events[4].second.int("index"))
        assertEquals("B", events[4].second.obj("content_block")?.str("name"))
        assertEquals(1, events[5].second.int("index"))
        assertEquals(0, events[6].second.int("index")) // 工具 0 的后续参数接着流
        // 现状：finish 里先收工具 1（openToolIndex），随后循环把中途已收过的工具 0 又收一遍 ——
        // 重复的 content_block_stop（closed 集合漏记中途 closeTool 过的块）。见 GLM 汇报
        assertEquals(1, events[7].second.int("index"))
        assertEquals(0, events[8].second.int("index")) // 工具 0 的重复 stop
        assertEquals("tool_use", messageDeltaOf(events).obj("delta")?.str("stop_reason"))
    }

    @Test
    fun tool_then_text_closes_text_block_before_tool_block() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(toolChunk(listOf(toolCall(0, id = "call_1", name = "A", args = "{}"))))
                + conv.onChunk(textChunk("done", finish = "tool_calls"))
                + conv.finish()
        )
        assertEquals(
            listOf(
                "message_start",
                "content_block_start", "content_block_delta",
                "content_block_start", "content_block_delta",
                "content_block_stop", "content_block_stop",
                "message_delta", "message_stop",
            ),
            names(events),
        )
        // 工具块 index 0，文本块 index 1；finish 里文本块先收、工具块后收（与 index 顺序相反）。见 GLM 汇报
        assertEquals(1, events[5].second.int("index"))
        assertEquals(0, events[6].second.int("index"))
        assertEquals("tool_use", messageDeltaOf(events).obj("delta")?.str("stop_reason"))
    }

    @Test
    fun finish_reason_mapping() {
        val cases = mapOf(
            "stop" to "end_turn",
            "length" to "max_tokens",
            "tool_calls" to "tool_use",
            "content_filter" to "refusal",
            "function_call" to "end_turn",
        )
        for ((openai, anthropic) in cases) {
            val conv = OpenAIToAnthropic.StreamConverter("m")
            val events = parse(conv.onChunk(emptyDeltaChunk(finish = openai)) + conv.finish())
            assertEquals(anthropic, messageDeltaOf(events).obj("delta")?.str("stop_reason"))
        }
        // 没有 finish_reason 且没有工具：end_turn
        val noFinish = OpenAIToAnthropic.StreamConverter("m")
        assertEquals("end_turn", messageDeltaOf(parse(noFinish.onChunk(emptyDeltaChunk()) + noFinish.finish()))
            .obj("delta")?.str("stop_reason"))
        // 没有 finish_reason 但有工具：也是 end_turn（mapStopReason 只在 finish 到达时才调用）。见 GLM 汇报
        val noFinishWithTools = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            noFinishWithTools.onChunk(toolChunk(listOf(toolCall(0, id = "call_1", name = "A"))))
                + noFinishWithTools.finish()
        )
        assertEquals("end_turn", messageDeltaOf(events).obj("delta")?.str("stop_reason"))
    }

    @Test
    fun usage_only_chunk_never_surfaces_input_tokens() {
        // 已知缺陷 2：message_start 的 input_tokens 恒为 0，即使最后一个 chunk 带了 prompt_tokens；
        // 且 message_delta 的 usage 只有 output_tokens —— 流式全程拿不到 input_tokens。见 GLM 汇报
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(textChunk("hi"))
                + conv.onChunk(buildJsonObject {
                    put("choices", buildJsonArray { })
                    put("usage", buildJsonObject {
                        put("prompt_tokens", 7)
                        put("completion_tokens", 2)
                    })
                })
                + conv.finish()
        )
        assertEquals(0, messageStartOf(events).obj("usage")?.int("input_tokens"))
        assertEquals(0, messageStartOf(events).obj("usage")?.int("output_tokens"))
        val delta = messageDeltaOf(events)
        assertEquals(listOf("output_tokens"), delta.obj("usage")!!.keys.toList())
        assertEquals(2, delta.obj("usage")?.int("output_tokens"))
    }

    @Test
    fun reasoning_content_delta_is_dropped() {
        // 已知缺陷 1：流式 delta.reasoning_content 被整个丢掉，没有 thinking delta，也没有任何块。见 GLM 汇报
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val reasoningOnly = parse(conv.onChunk(buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject { put("reasoning_content", "hmm") })
                })
            })
        }))
        assertEquals(listOf("message_start"), names(reasoningOnly))

        val events = parse(
            conv.onChunk(textChunk("ans"))
                + conv.finish()
        )
        assertTrue(events.none { it.second.toString().contains("thinking") })
        assertFalse(events.joinToString("").contains("hmm"))
    }

    @Test
    fun empty_choices_chunks() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        // 首个 chunk 没有 choices：只发 message_start，id 用兜底 msg_relay
        val first = parse(conv.onChunk(buildJsonObject { put("choices", buildJsonArray { }) }))
        assertEquals(listOf("message_start"), names(first))
        assertEquals("msg_relay", messageStartOf(first).str("id"))

        // 流中再来的空 choices chunk：什么都不发
        val afterText = parse(
            conv.onChunk(textChunk("hi"))
                + conv.onChunk(buildJsonObject { put("choices", buildJsonArray { }) })
        )
        assertEquals(listOf("content_block_start", "content_block_delta"), names(afterText))

        val events = parse(conv.finish())
        assertEquals(listOf("content_block_stop", "message_delta", "message_stop"), names(events))
    }

    @Test
    fun finish_without_any_chunks() {
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(conv.finish())
        assertEquals(listOf("message_start", "message_delta", "message_stop"), names(events))
        assertEquals("msg_relay", messageStartOf(events).str("id"))
        assertEquals(
            buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) },
            messageStartOf(events)["usage"],
        )
        assertEquals("end_turn", messageDeltaOf(events).obj("delta")?.str("stop_reason"))
        assertEquals(0, messageDeltaOf(events).obj("usage")?.int("output_tokens"))
    }

    @Test
    fun tool_closed_midstream_gets_a_second_stop_in_finish() {
        // 现状：源码注释声称「每个工具块只发一次 stop」，但 closed 集合漏记中途 closeTool 收掉的块 ——
        // 工具 0 在工具 1 开块时收到第一次 stop，finish 的循环又给它发第二次。见 GLM 汇报
        val conv = OpenAIToAnthropic.StreamConverter("m")
        val events = parse(
            conv.onChunk(toolChunk(listOf(toolCall(0, id = "call_1", name = "A", args = "{"))))
                + conv.onChunk(toolChunk(listOf(toolCall(1, id = "call_2", name = "B", args = "{}"))))
                + conv.finish()
        )
        val stops = events.filter { it.first == "content_block_stop" }
        assertEquals(3, stops.size)
        assertEquals(0, stops[0].second.int("index"))
        assertEquals(1, stops[1].second.int("index"))
        assertEquals(0, stops[2].second.int("index"))
    }
}
