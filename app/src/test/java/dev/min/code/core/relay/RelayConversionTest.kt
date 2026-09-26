package dev.min.code.core.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议转换。错一次就是「工具调用丢了」或者「流式卡死」，
 * 而且错在上游那边，界面上看不出是路由的锅。所以请求形状、事件序列、错误透传都钉死。
 */
class RelayConversionTest {

    private val anthropicRequest = buildJsonObject {
        put("model", "claude-sonnet-4")
        put("max_tokens", 1024)
        put("stream", true)
        put("system", "you are helpful")
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "hi")
                    })
                })
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_use")
                        put("id", "toolu_1")
                        put("name", "Read")
                        put("input", buildJsonObject { put("path", "/tmp/a") })
                    })
                })
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", "toolu_1")
                        put("content", "file contents")
                    })
                })
            })
        })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("name", "Read")
                put("description", "read a file")
                put("input_schema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string") })
                    })
                })
            })
        })
    }

    @Test
    fun anthropic_request_becomes_chat_completions() {
        val chat = AnthropicToOpenAI.convertRequest(anthropicRequest)
        assertEquals("claude-sonnet-4", chat.str("model"))
        assertTrue(chat.bool("stream") == true)
        assertEquals(1024, chat.int("max_tokens"))
        // 流式要 usage
        assertTrue(chat.obj("stream_options")?.bool("include_usage") == true)

        val messages = chat.arr("messages")!!
        assertEquals("system", (messages[0] as JsonObject).str("role"))
        assertEquals("you are helpful", (messages[0] as JsonObject).str("content"))
        assertEquals("user", (messages[1] as JsonObject).str("role"))

        // tool_use → assistant + tool_calls
        val assistant = messages[2] as JsonObject
        assertEquals("assistant", assistant.str("role"))
        val toolCall = assistant.arr("tool_calls")!!.first() as JsonObject
        assertEquals("toolu_1", toolCall.str("id"))
        assertEquals("Read", toolCall.obj("function")?.str("name"))
        assertTrue(toolCall.obj("function")?.str("arguments")!!.contains("/tmp/a"))

        // tool_result → role=tool
        val tool = messages[3] as JsonObject
        assertEquals("tool", tool.str("role"))
        assertEquals("toolu_1", tool.str("tool_call_id"))
        assertEquals("file contents", tool.str("content"))

        // tools.input_schema → function.parameters
        val toolDef = chat.arr("tools")!!.first() as JsonObject
        assertEquals("function", toolDef.str("type"))
        assertEquals("Read", toolDef.obj("function")?.str("name"))
        assertEquals("object", toolDef.obj("function")?.obj("parameters")?.str("type"))
    }

    @Test
    fun chat_response_becomes_anthropic_message() {
        val chat = buildJsonObject {
            put("id", "chatcmpl_1")
            put("model", "gpt-x")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", "thinking")
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("id", "call_1")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "Bash")
                                    put("arguments", """{"cmd":"ls"}""")
                                })
                            })
                        })
                    })
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", 10)
                put("completion_tokens", 5)
            })
        }
        val msg = OpenAIToAnthropic.convertResponse(chat, "claude-sonnet-4")
        assertEquals("message", msg.str("type"))
        assertEquals("assistant", msg.str("role"))
        assertEquals("tool_use", msg.str("stop_reason"))
        assertEquals(10, msg.obj("usage")?.int("input_tokens"))
        assertEquals(5, msg.obj("usage")?.int("output_tokens"))
        val content = msg.arr("content")!!
        assertEquals("text", (content[0] as JsonObject).str("type"))
        assertEquals("thinking", (content[0] as JsonObject).str("text"))
        assertEquals("tool_use", (content[1] as JsonObject).str("type"))
        assertEquals("Bash", (content[1] as JsonObject).str("name"))
        assertEquals("ls", (content[1] as JsonObject).obj("input")?.str("cmd"))
    }

    @Test
    fun stream_emits_the_anthropic_event_sequence() {
        val converter = OpenAIToAnthropic.StreamConverter("claude-sonnet-4")
        val events = ArrayList<String>()
        events += converter.onChunk(buildJsonObject {
            put("id", "chatcmpl_1")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject { put("content", "Hel") })
                })
            })
        })
        events += converter.onChunk(buildJsonObject {
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
        events += converter.finish()

        val joined = events.joinToString("")
        assertTrue(joined.contains("event: message_start"))
        assertTrue(joined.contains("event: content_block_start"))
        assertTrue(joined.contains("text_delta"))
        assertTrue(joined.contains("Hel"))
        assertTrue(joined.contains("lo"))
        assertTrue(joined.contains("event: content_block_stop"))
        assertTrue(joined.contains("event: message_delta"))
        assertTrue(joined.contains("end_turn"))
        assertTrue(joined.contains("event: message_stop"))
    }

    @Test
    fun upstream_errors_keep_the_original_message() {
        val err = OpenAIToAnthropic.convertError(
            401,
            """{"error":{"message":"invalid api key","type":"auth"}}""",
        )
        assertEquals("error", err.str("type"))
        assertEquals("authentication_error", err.obj("error")?.str("type"))
        assertEquals("invalid api key", err.obj("error")?.str("message"))
    }

    @Test
    fun responses_to_chat_and_back() {
        val responses = buildJsonObject {
            put("model", "gpt-5")
            put("instructions", "be brief")
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", "hi")
                        })
                    })
                })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "Lookup")
                    put("parameters", buildJsonObject { put("type", "object") })
                })
            })
        }
        val chat = ResponsesBridge.toChatCompletions(responses)
        assertEquals("gpt-5", chat.str("model"))
        val messages = chat.arr("messages")!!
        assertEquals("system", (messages[0] as JsonObject).str("role"))
        assertEquals("be brief", (messages[0] as JsonObject).str("content"))
        assertEquals("user", (messages[1] as JsonObject).str("role"))
        val tool = chat.arr("tools")!!.first() as JsonObject
        assertEquals("Lookup", tool.obj("function")?.str("name"))

        val chatResp = buildJsonObject {
            put("id", "chatcmpl_x")
            put("model", "gpt-5")
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", "hey")
                    })
                    put("finish_reason", "stop")
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", 1)
                put("completion_tokens", 1)
            })
        }
        val back = ResponsesBridge.fromChatCompletions(chatResp)
        assertEquals("response", back.str("object"))
        assertEquals("completed", back.str("status"))
        val output = back.arr("output")!!.first() as JsonObject
        assertEquals("message", output.str("type"))
        assertEquals("hey", (output.arr("content")!!.first() as JsonObject).str("text"))
    }

    @Test
    fun tool_choice_object_maps_to_openai_strings() {
        // Anthropic 的 tool_choice 恒为对象。any 原样透传会被严格上游 400，必须映射成 required
        fun choice(type: String) = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {})
            put("tool_choice", buildJsonObject { put("type", type) })
        }).str("tool_choice")

        assertEquals("required", choice("any"))
        assertEquals("auto", choice("auto"))
        assertEquals("none", choice("none"))
    }

    @Test
    fun stream_closes_each_tool_block_exactly_once() {
        val converter = OpenAIToAnthropic.StreamConverter("m")
        val events = ArrayList<String>()
        events += converter.onChunk(buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("index", 0)
                                put("id", "call_1")
                                put("function", buildJsonObject {
                                    put("name", "Bash")
                                    put("arguments", """{"cmd":"ls"}""")
                                })
                            })
                        })
                    })
                    put("finish_reason", "tool_calls")
                })
            })
        })
        events += converter.finish()

        val stops = events.joinToString("").split("event: content_block_stop").size - 1
        assertEquals(1, stops)
    }

    @Test
    fun count_tokens_is_estimated_locally() {
        val empty = AnthropicToOpenAI.estimateTokens(buildJsonObject {
            put("messages", buildJsonArray {})
        })
        assertEquals(0, empty)

        val some = AnthropicToOpenAI.estimateTokens(buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hello world")
                })
            })
        })
        assertTrue(some > 0)
    }

    @Test
    fun model_override_wins() {
        val chat = AnthropicToOpenAI.convertRequest(anthropicRequest, modelOverride = "glm-5")
        assertEquals("glm-5", chat.str("model"))
    }

    @Test
    fun unknown_top_level_fields_are_not_silently_required() {
        // 转换不该因为多了一个字段就炸；也不该把 Anthropic 专有的 metadata 硬塞给 OpenAI
        val withMeta = buildJsonObject {
            anthropicRequest.forEach { (k, v) -> put(k, v) }
            put("metadata", buildJsonObject { put("user_id", "u1") })
        }
        val chat = AnthropicToOpenAI.convertRequest(withMeta)
        assertFalse(chat.containsKey("metadata"))
        assertEquals("claude-sonnet-4", chat.str("model"))
    }
}
