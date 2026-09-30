package dev.min.code.core.relay

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * OpenAIToAnthropic.convertResponse / convertError 的特征测试：
 * 钉住「现在实际输出什么」，包括已知缺陷。M1 迁移修缺陷时由人改对应断言。
 */
class RelayResponseCharacterizationTest {

    private fun chatResponse(
        finish: String?,
        message: JsonObject,
        usage: JsonObject? = null,
        id: String = "chatcmpl_1",
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("model", "gpt-x")
        put("choices", buildJsonArray {
            add(buildJsonObject {
                finish?.let { put("finish_reason", it) }
                put("message", message)
            })
        })
        usage?.let { put("usage", it) }
    }

    private fun toolCall(id: String? = null, name: String, arguments: String) = buildJsonObject {
        id?.let { put("id", it) }
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
    }

    // ---- convertResponse ----

    @Test
    fun plain_text_response() {
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "stop",
            message = buildJsonObject { put("role", "assistant"); put("content", "hello") },
            usage = buildJsonObject {
                put("prompt_tokens", 10)
                put("completion_tokens", 5)
            },
        ), "claude-sonnet-4")
        assertEquals(setOf("id", "type", "role", "model", "content", "stop_reason", "stop_sequence", "usage"), msg.keys)
        assertEquals("chatcmpl_1", msg.str("id"))
        assertEquals("message", msg.str("type"))
        assertEquals("assistant", msg.str("role"))
        assertEquals("claude-sonnet-4", msg.str("model"))
        val content = msg.arr("content")!!
        assertEquals(1, content.size)
        assertEquals(
            buildJsonObject { put("type", "text"); put("text", "hello") },
            content[0],
        )
        assertEquals("end_turn", msg.str("stop_reason"))
        assertEquals(JsonNull, msg["stop_sequence"])
        assertEquals(
            buildJsonObject {
                put("input_tokens", 10)
                put("output_tokens", 5)
            },
            msg["usage"],
        )
    }

    @Test
    fun single_tool_call() {
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = """{"cmd":"ls"}"""))
                })
            },
        ), "m")
        val content = msg.arr("content")!!
        assertEquals(1, content.size)
        assertEquals(
            buildJsonObject {
                put("type", "tool_use")
                put("id", "call_1")
                put("name", "Bash")
                put("input", buildJsonObject { put("cmd", "ls") })
            },
            content[0],
        )
        assertEquals("tool_use", msg.str("stop_reason"))
    }

    @Test
    fun multiple_tool_calls_keep_order_and_id_fallback() {
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "A", arguments = "{}"))
                    add(toolCall(id = null, name = "B", arguments = "{}"))
                })
            },
        ), "m")
        val content = msg.arr("content")!!
        assertEquals(2, content.size)
        assertEquals("call_1", content[0].jsonObject.str("id"))
        assertEquals("A", content[0].jsonObject.str("name"))
        // 没有 id 的 tool_call 用数组下标合成 toolu_<i>
        assertEquals("toolu_1", content[1].jsonObject.str("id"))
        assertEquals("B", content[1].jsonObject.str("name"))
    }

    @Test
    fun text_plus_tool_calls() {
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("content", "thinking")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = "{}"))
                })
            },
        ), "m")
        val content = msg.arr("content")!!
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject.str("type"))
        assertEquals("thinking", content[0].jsonObject.str("text"))
        assertEquals("tool_use", content[1].jsonObject.str("type"))
    }

    @Test
    fun null_content_and_missing_content_produce_no_text_block() {
        val explicitNull = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("content", JsonNull)
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = "{}"))
                })
            },
        ), "m")
        assertEquals(1, explicitNull.arr("content")!!.size)
        assertEquals("tool_use", explicitNull.arr("content")!![0].jsonObject.str("type"))

        val absent = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = "{}"))
                })
            },
        ), "m")
        assertEquals(1, absent.arr("content")!!.size)
        assertEquals("tool_use", absent.arr("content")!![0].jsonObject.str("type"))
    }

    @Test
    fun finish_reason_mapping() {
        fun stopOf(finish: String?, tools: Boolean = false): String {
            val message = if (tools) buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = "{}"))
                })
            } else buildJsonObject { put("role", "assistant"); put("content", "hi") }
            return OpenAIToAnthropic.convertResponse(chatResponse(finish, message), "m")
                .str("stop_reason")!!
        }

        assertEquals("end_turn", stopOf("stop"))
        assertEquals("max_tokens", stopOf("length"))
        assertEquals("tool_use", stopOf("tool_calls"))
        assertEquals("refusal", stopOf("content_filter"))
        // 未知 finish_reason 一律 end_turn
        assertEquals("end_turn", stopOf("function_call"))
        // finish_reason 缺失：没有工具 end_turn，有工具 tool_use
        assertEquals("end_turn", stopOf(null, tools = false))
        assertEquals("tool_use", stopOf(null, tools = true))
    }

    @Test
    fun cached_tokens_are_not_surfaced() {
        // 现状：usage 只映射 prompt/completion_tokens，cached_tokens 被丢掉。见 GLM 汇报
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "stop",
            message = buildJsonObject { put("role", "assistant"); put("content", "hi") },
            usage = buildJsonObject {
                put("prompt_tokens", 20)
                put("completion_tokens", 3)
                put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 12) })
            },
        ), "m")
        assertEquals(
            buildJsonObject {
                put("input_tokens", 20)
                put("output_tokens", 3)
            },
            msg["usage"],
        )
        assertFalse(msg.toString().contains("cached"))
    }

    @Test
    fun reasoning_content_is_dropped() {
        // 已知缺陷 1：上游 reasoning_content 被整个丢掉，Anthropic 输出里无 thinking 块。见 GLM 汇报
        val withTools = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("content", JsonNull)
                put("reasoning_content", "I should look at the file")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Read", arguments = "{}"))
                })
            },
        ), "m")
        assertEquals(1, withTools.arr("content")!!.size)
        assertEquals("tool_use", withTools.arr("content")!![0].jsonObject.str("type"))
        assertFalse(withTools.toString().contains("reasoning"))
        assertFalse(withTools.toString().contains("thinking"))

        val withText = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "stop",
            message = buildJsonObject {
                put("role", "assistant")
                put("content", "answer")
                put("reasoning_content", "hmm")
            },
        ), "m")
        val content = withText.arr("content")!!
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject.str("type"))
        assertFalse(withText.toString().contains("hmm"))
    }

    @Test
    fun tool_arguments_invalid_json_becomes_raw() {
        // 现状：arguments 不是合法 JSON 时包成 {"_raw": <原文>}，不丢弃也不报错。见 GLM 汇报
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = "not json"))
                })
            },
        ), "m")
        val block = msg.arr("content")!![0].jsonObject
        assertEquals(
            buildJsonObject { put("_raw", "not json") },
            block["input"],
        )
    }

    @Test
    fun tool_arguments_blank_becomes_empty_object() {
        val msg = OpenAIToAnthropic.convertResponse(chatResponse(
            finish = "tool_calls",
            message = buildJsonObject {
                put("role", "assistant")
                put("tool_calls", buildJsonArray {
                    add(toolCall(id = "call_1", name = "Bash", arguments = ""))
                    add(buildJsonObject {
                        put("id", "call_2")
                        put("type", "function")
                        put("function", buildJsonObject { put("name", "NoArgs") })
                    })
                })
            },
        ), "m")
        // 空串给 {}；没有 arguments 字段的也给 {}
        assertEquals(buildJsonObject { }, msg.arr("content")!![0].jsonObject["input"])
        assertEquals(buildJsonObject { }, msg.arr("content")!![1].jsonObject["input"])
    }

    @Test
    fun missing_choices_and_id_fallback() {
        val msg = OpenAIToAnthropic.convertResponse(buildJsonObject {
            put("model", "gpt-x")
        }, "m")
        assertEquals("msg_relay", msg.str("id"))
        assertEquals(0, msg.arr("content")!!.size)
        assertEquals("end_turn", msg.str("stop_reason"))
        assertEquals(
            buildJsonObject { put("input_tokens", 0); put("output_tokens", 0) },
            msg["usage"],
        )
    }

    // ---- convertError ----

    @Test
    fun error_status_type_mapping() {
        fun errorOf(status: Int) = OpenAIToAnthropic.convertError(
            status,
            """{"error":{"message":"upstream says"}}""",
        )

        assertEquals("invalid_request_error", errorOf(400).obj("error")?.str("type"))
        assertEquals("authentication_error", errorOf(401).obj("error")?.str("type"))
        assertEquals("authentication_error", errorOf(403).obj("error")?.str("type"))
        assertEquals("rate_limit_error", errorOf(429).obj("error")?.str("type"))
        assertEquals("api_error", errorOf(500).obj("error")?.str("type"))
        // 529 不是 4xx，落到 api_error
        assertEquals("api_error", errorOf(529).obj("error")?.str("type"))

        for (status in listOf(400, 401, 403, 429, 500, 529)) {
            val err = errorOf(status)
            assertEquals("error", err.str("type"))
            assertEquals("upstream says", err.obj("error")?.str("message"))
            assertEquals(listOf("type", "message"), err.obj("error")!!.keys.toList())
        }
    }

    @Test
    fun error_non_json_body_keeps_original_text() {
        val err = OpenAIToAnthropic.convertError(502, "Bad Gateway")
        assertEquals(
            buildJsonObject {
                put("type", "error")
                put("error", buildJsonObject {
                    put("type", "api_error")
                    put("message", "Bad Gateway")
                })
            },
            err,
        )
    }

    @Test
    fun error_blank_body_says_http_status() {
        val err = OpenAIToAnthropic.convertError(500, "")
        assertEquals("HTTP 500", err.obj("error")?.str("message"))
    }

    @Test
    fun error_message_fallback_chain() {
        // error.message 优先
        assertEquals(
            "deep",
            OpenAIToAnthropic.convertError(400, """{"error":{"message":"deep"},"message":"shallow"}""")
                .obj("error")?.str("message"),
        )
        // 顶层 message 次之
        assertEquals(
            "shallow",
            OpenAIToAnthropic.convertError(400, """{"message":"shallow"}""").obj("error")?.str("message"),
        )
        // error 是字符串再次之
        assertEquals(
            "quota",
            OpenAIToAnthropic.convertError(400, """{"error":"quota"}""").obj("error")?.str("message"),
        )
    }
}
