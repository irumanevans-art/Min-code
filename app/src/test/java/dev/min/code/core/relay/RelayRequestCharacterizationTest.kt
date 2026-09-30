package dev.min.code.core.relay

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * AnthropicToOpenAI.convertRequest / estimateTokens 的特征测试：
 * 钉住「现在实际输出什么」，包括已知缺陷。M1 迁移修缺陷时由人改对应断言。
 */
class RelayRequestCharacterizationTest {

    // ---- 消息形状 ----

    @Test
    fun plain_text_single_turn() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "k2")
            put("max_tokens", 100)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        })
        // 没提 stream / temperature / tools 就一个键都不多
        assertEquals(setOf("model", "messages", "max_tokens"), chat.keys)
        assertEquals("k2", chat.str("model"))
        val messages = chat.arr("messages")!!
        assertEquals(1, messages.size)
        val only = messages[0].jsonObject
        assertEquals("user", only.str("role"))
        assertEquals("hi", only.str("content"))
        assertFalse(chat.containsKey("stream"))
        assertFalse(chat.containsKey("stream_options"))
    }

    @Test
    fun system_string_becomes_first_system_message() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("system", "be nice")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            })
        })
        val messages = chat.arr("messages")!!
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject.str("role"))
        assertEquals("be nice", messages[0].jsonObject.str("content"))
        assertEquals("user", messages[1].jsonObject.str("role"))
    }

    @Test
    fun system_block_array_joins_text_lines_and_drops_other_blocks() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("system", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "line1") })
                add(buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject { put("type", "base64"); put("data", "QUJD") })
                })
                add(buildJsonObject { put("type", "text"); put("text", "line2") })
            })
            put("messages", buildJsonArray {})
        })
        val system = chat.arr("messages")!!.first().jsonObject
        assertEquals("system", system.str("role"))
        assertEquals("line1\nline2", system.str("content"))
    }

    @Test
    fun blank_system_array_produces_no_system_message() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("system", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "   ") })
            })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            })
        })
        val messages = chat.arr("messages")!!
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject.str("role"))
    }

    @Test
    fun tool_use_and_tool_result_string_roundtrip() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "find the file") })
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
        })
        val messages = chat.arr("messages")!!
        assertEquals(3, messages.size)

        // 现状：纯 tool_use 的 assistant 消息 content 是显式 null（OpenAI 允许）。见 GLM 汇报
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant.str("role"))
        assertEquals(JsonNull, assistant["content"])
        assertEquals(listOf("role", "content", "tool_calls"), assistant.keys.toList())
        val call = assistant.arr("tool_calls")!![0].jsonObject
        assertEquals("toolu_1", call.str("id"))
        assertEquals("function", call.str("type"))
        assertEquals("Read", call.obj("function")?.str("name"))
        assertEquals("{\"path\":\"/tmp/a\"}", call.obj("function")?.str("arguments"))

        val tool = messages[2].jsonObject
        assertEquals("tool", tool.str("role"))
        assertEquals("toolu_1", tool.str("tool_call_id"))
        assertEquals("file contents", tool.str("content"))
        assertEquals(listOf("role", "tool_call_id", "content"), tool.keys.toList())
    }

    @Test
    fun tool_result_block_array_joins_text_and_missing_content_is_empty() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", "toolu_1")
                            put("name", "Read")
                            put("input", buildJsonObject { })
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "toolu_1")
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", "line1") })
                                add(buildJsonObject { put("type", "text"); put("text", "line2") })
                            })
                        })
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "toolu_1")
                        })
                    })
                })
            })
        })
        val messages = chat.arr("messages")!!
        assertEquals(3, messages.size)
        assertEquals("line1\nline2", messages[1].jsonObject.str("content"))
        // 没写 content 的 tool_result 现在给空串。见 GLM 汇报
        assertEquals("", messages[2].jsonObject.str("content"))
    }

    @Test
    fun tool_result_is_error_flag_is_ignored() {
        // 现状：is_error 被忽略，tool 消息里没有任何错误标记。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "toolu_1")
                            put("content", "boom")
                            put("is_error", true)
                        })
                    })
                })
            })
        })
        val tool = chat.arr("messages")!!.first().jsonObject
        assertEquals("tool", tool.str("role"))
        assertEquals("boom", tool.str("content"))
        assertEquals(listOf("role", "tool_call_id", "content"), tool.keys.toList())
    }

    @Test
    fun tool_result_image_block_content_is_lost() {
        // 现状：tool_result 内的图片块被整个丢掉，只剩 text 块的文本。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", "toolu_1")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", "image/png")
                                        put("data", "QUJD")
                                    })
                                })
                                add(buildJsonObject { put("type", "text"); put("text", "caption") })
                            })
                        })
                    })
                })
            })
        })
        assertEquals("caption", chat.arr("messages")!!.first().jsonObject.str("content"))
    }

    @Test
    fun assistant_text_with_multiple_tool_use_stays_one_message() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "running") })
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", "call_a")
                            put("name", "A")
                            put("input", buildJsonObject { put("a", 1) })
                        })
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", "call_b")
                            put("name", "B")
                        })
                    })
                })
            })
        })
        val assistant = chat.arr("messages")!!.single().jsonObject
        assertEquals("assistant", assistant.str("role"))
        // 单个 text 块压成纯字符串
        assertEquals("running", assistant.str("content"))
        val calls = assistant.arr("tool_calls")!!
        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].jsonObject.str("id"))
        assertEquals("A", calls[0].jsonObject.obj("function")?.str("name"))
        assertEquals("{\"a\":1}", calls[0].jsonObject.obj("function")?.str("arguments"))
        // 没有 input 的 tool_use 给空对象串
        assertEquals("call_b", calls[1].jsonObject.str("id"))
        assertEquals("{}", calls[1].jsonObject.obj("function")?.str("arguments"))
    }

    @Test
    fun thinking_block_is_dropped() {
        // 已知缺陷 3：请求侧 thinking 内容块被整个丢弃，签名一起丢。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "thinking")
                            put("thinking", "hmm")
                            put("signature", "sig")
                        })
                        add(buildJsonObject { put("type", "text"); put("text", "answer") })
                    })
                })
            })
        })
        val messages = chat.arr("messages")!!
        assertEquals(1, messages.size)
        assertEquals("answer", messages[0].jsonObject.str("content"))
        assertFalse(chat.toString().contains("hmm"))
        assertFalse(chat.toString().contains("sig"))
    }

    @Test
    fun message_with_only_a_thinking_block_disappears() {
        // 现状：只有 thinking 块的消息一条 OpenAI 消息都不产生。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "thinking"); put("thinking", "hmm") })
                    })
                })
                add(buildJsonObject { put("role", "user"); put("content", "go") })
            })
        })
        val messages = chat.arr("messages")!!
        assertEquals(2, messages.size)
        assertEquals("hi", messages[0].jsonObject.str("content"))
        assertEquals("go", messages[1].jsonObject.str("content"))
    }

    // ---- 顶层字段 ----

    @Test
    fun tool_choice_object_variants() {
        fun choice(type: String, name: String? = null) =
            AnthropicToOpenAI.convertRequest(buildJsonObject {
                put("model", "m")
                put("messages", buildJsonArray { })
                put("tool_choice", buildJsonObject {
                    put("type", type)
                    name?.let { put("name", it) }
                })
            })["tool_choice"]

        assertEquals(JsonPrimitive("auto"), choice("auto"))
        // Anthropic 的 any 在 OpenAI 侧对应 required
        assertEquals(JsonPrimitive("required"), choice("any"))
        assertEquals(JsonPrimitive("none"), choice("none"))
        assertEquals(
            buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "Read") })
            },
            choice("tool", name = "Read"),
        )
    }

    @Test
    fun stop_sequences_become_stop() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray { })
            put("stop_sequences", buildJsonArray {
                add(JsonPrimitive("END"))
                add(JsonPrimitive("STOP"))
            })
        })
        assertEquals(
            buildJsonArray {
                add(JsonPrimitive("END"))
                add(JsonPrimitive("STOP"))
            },
            chat["stop"],
        )
        assertFalse(chat.containsKey("stop_sequences"))
    }

    @Test
    fun cache_control_marker_is_dropped() {
        // 现状：内容块上的 cache_control 被丢弃（Chat Completions 无对应物）。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "hi")
                            put("cache_control", buildJsonObject { put("type", "ephemeral") })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", "there") })
                    })
                })
            })
        })
        val content = chat.arr("messages")!!.first().jsonObject.arr("content")!!
        assertEquals(2, content.size)
        assertEquals(
            buildJsonObject {
                put("type", "text")
                put("text", "hi")
            },
            content[0],
        )
        assertEquals(
            buildJsonObject {
                put("type", "text")
                put("text", "there")
            },
            content[1],
        )
    }

    @Test
    fun sampling_params_pass_through() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray { })
            put("max_tokens", 1024)
            put("temperature", 0.7)
            put("top_p", 0.9)
            put("stream", false)
        })
        assertEquals(JsonPrimitive(1024), chat["max_tokens"])
        assertEquals(JsonPrimitive(0.7), chat["temperature"])
        assertEquals(JsonPrimitive(0.9), chat["top_p"])
        assertEquals(JsonPrimitive(false), chat["stream"])
        // stream=false 不加 include_usage
        assertFalse(chat.containsKey("stream_options"))
    }

    @Test
    fun stream_true_adds_include_usage() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray { })
            put("stream", true)
        })
        assertEquals(JsonPrimitive(true), chat["stream"])
        assertEquals(
            buildJsonObject { put("include_usage", true) },
            chat["stream_options"],
        )
    }

    @Test
    fun unknown_top_level_fields_pass_through_but_anthropic_extras_are_dropped() {
        // 已知缺陷 3（现状）：顶层 thinking / top_k 原样透传给 OpenAI 上游（严格上游会 400）；
        // metadata / anthropic_version 在 HANDLED 里被丢弃。见 GLM 汇报
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray { })
            put("top_k", 40)
            put("thinking", buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", 1024)
            })
            put("metadata", buildJsonObject { put("user_id", "u1") })
            put("anthropic_version", "2023-06-01")
        })
        assertEquals(JsonPrimitive(40), chat["top_k"])
        assertEquals(
            buildJsonObject {
                put("type", "enabled")
                put("budget_tokens", 1024)
            },
            chat["thinking"],
        )
        assertFalse(chat.containsKey("metadata"))
        assertFalse(chat.containsKey("anthropic_version"))
    }

    @Test
    fun model_override_replaces_and_missing_model_becomes_empty() {
        val overridden = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "claude-x")
            put("messages", buildJsonArray { })
        }, modelOverride = "glm-5")
        assertEquals("glm-5", overridden.str("model"))

        val noModel = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("messages", buildJsonArray { })
        })
        assertEquals("", noModel.str("model"))
    }

    // ---- 图片 ----

    @Test
    fun user_image_base64_becomes_data_url_and_url_stays_url() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "look") })
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/png")
                                put("data", "QUJD")
                            })
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "url")
                                put("url", "https://example.com/x.png")
                            })
                        })
                    })
                })
            })
        })
        val messages = chat.arr("messages")!!
        val first = messages[0].jsonObject.arr("content")!!
        assertEquals(2, first.size)
        assertEquals(
            buildJsonObject {
                put("type", "text")
                put("text", "look")
            },
            first[0],
        )
        assertEquals(
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/png;base64,QUJD") })
            },
            first[1],
        )
        // 单张 url 图也走 image_url，url 原样
        val second = messages[1].jsonObject.arr("content")!!
        assertEquals(1, second.size)
        assertEquals(
            buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "https://example.com/x.png") })
            },
            second[0],
        )
    }

    @Test
    fun lone_image_stays_a_content_array() {
        val chat = AnthropicToOpenAI.convertRequest(buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", "QUJD")
                            })
                        })
                    })
                })
            })
        })
        // 单个非 text 块不压成字符串，保持数组
        val content = chat.arr("messages")!!.first().jsonObject.arr("content")!!
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject.str("type"))
    }

    // ---- estimateTokens ----

    @Test
    fun estimate_tokens_edge_cases() {
        fun of(messages: kotlinx.serialization.json.JsonArray) =
            AnthropicToOpenAI.estimateTokens(buildJsonObject {
                put("messages", messages)
            })

        assertEquals(0, of(buildJsonArray { }))
        // 纯 ASCII 约 4 字符一个 token；太短保底 1；CJK 一字一个
        assertEquals(2, of(buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "hello world") })
        }))
        assertEquals(1, of(buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "hi") })
        }))
        assertEquals(4, of(buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "你好世界") })
        }))
        assertEquals(2, of(buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "hi你好") })
        }))
    }

    @Test
    fun estimate_tokens_counts_system_names_tool_input_and_tools() {
        // system(3) + 文本(2) + 工具名 Read(4) + input 的 JSON 串(17) + tools 元素 JSON 串(15) = 41 → 41/4 = 10
        val estimate = AnthropicToOpenAI.estimateTokens(buildJsonObject {
            put("system", "abc")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "de") })
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
            })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", "Read") })
            })
        })
        assertEquals(10, estimate)
    }
}
