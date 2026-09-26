package dev.min.code.core.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Codex 的 Responses API ↔ Chat Completions 双向转换。
 *
 * 不少中转只提供 `/chat/completions`，而 Codex 默认走 `wire_api = responses`。
 * cc-switch 靠本地路由做这一层；这边同理——选了 `OPENAI_CHAT` 的统一供应商 /
 * Codex 条目的 `wire_api = chat` 时，请求经这里转一道。
 *
 * 覆盖的是 Codex 真正会发的那一子集（消息、工具、流式），不是完整的 Responses 规范。
 * 推理内容（`reasoning`）能带就带，带不了就丢掉——丢掉比硬塞一个上游不认的字段更安全。
 */
internal object ResponsesBridge {

    /** Responses 请求 → Chat Completions 请求 */
    fun toChatCompletions(responses: JsonObject): JsonObject {
        val messages = buildJsonArray {
            responses.arr("input")?.forEach { item ->
                when (item) {
                    is JsonPrimitive -> add(buildJsonObject {
                        put("role", "user")
                        put("content", item.content)
                    })
                    is JsonObject -> convertInputItem(item).forEach { add(it) }
                    else -> Unit
                }
            }
        }
        val withSystem = responses.str("instructions")?.takeIf { it.isNotBlank() }?.let { sys ->
            buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", sys)
                })
                messages.forEach { add(it) }
            }
        } ?: messages

        return buildJsonObject {
            put("model", responses.str("model") ?: "")
            put("messages", withSystem)
            responses["stream"]?.let { put("stream", it) }
            if (responses.bool("stream") == true) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            responses["temperature"]?.let { put("temperature", it) }
            responses["top_p"]?.let { put("top_p", it) }
            responses["max_output_tokens"]?.let { put("max_tokens", it) }
            responses["tools"]?.let { put("tools", convertResponsesTools(it)) }
            responses["tool_choice"]?.let { put("tool_choice", it) }
        }
    }

    /** Chat Completions 响应 → Responses 响应（非流式） */
    fun fromChatCompletions(chat: JsonObject): JsonObject {
        val choice = chat.arr("choices")?.firstOrNull() as? JsonObject
        val message = choice?.obj("message")
        val output = buildJsonArray {
            message?.str("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "output_text")
                            put("text", text)
                        })
                    })
                    put("status", "completed")
                })
            }
            message?.arr("tool_calls")?.forEach { call ->
                val obj = call as? JsonObject ?: return@forEach
                val fn = obj.obj("function")
                add(buildJsonObject {
                    put("type", "function_call")
                    put("id", obj.str("id") ?: "")
                    put("call_id", obj.str("id") ?: "")
                    put("name", fn?.str("name") ?: "")
                    put("arguments", fn?.str("arguments") ?: "{}")
                    put("status", "completed")
                })
            }
        }
        val usage = chat.obj("usage")
        return buildJsonObject {
            put("id", chat.str("id") ?: "resp_relay")
            put("object", "response")
            put("status", "completed")
            put("model", chat.str("model") ?: "")
            put("output", output)
            put("usage", buildJsonObject {
                put("input_tokens", usage?.int("prompt_tokens") ?: 0)
                put("output_tokens", usage?.int("completion_tokens") ?: 0)
            })
        }
    }

    private fun convertInputItem(item: JsonObject): List<JsonObject> {
        val type = item.str("type")
        return when (type) {
            "message", null -> {
                val role = item.str("role") ?: "user"
                val content = item["content"]
                listOf(buildJsonObject {
                    put("role", role)
                    when (content) {
                        is JsonPrimitive -> put("content", content.content)
                        is JsonArray -> put("content", flattenContent(content))
                        else -> put("content", "")
                    }
                })
            }
            "function_call_output" -> listOf(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", item.str("call_id") ?: "")
                put("content", item.str("output") ?: "")
            })
            "function_call" -> listOf(buildJsonObject {
                put("role", "assistant")
                put("content", JsonNullOrEmpty)
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", item.str("call_id") ?: item.str("id") ?: "")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", item.str("name") ?: "")
                            put("arguments", item.str("arguments") ?: "{}")
                        })
                    })
                })
            })
            else -> emptyList()
        }
    }

    private val JsonNullOrEmpty: JsonElement = kotlinx.serialization.json.JsonNull

    private fun flattenContent(arr: JsonArray): JsonElement {
        // Responses 的 content 是 [{type:input_text,text:…},…]；Chat 要么是字符串要么是 parts
        val texts = arr.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            when (obj.str("type")) {
                "input_text", "output_text", "text" -> obj.str("text")
                else -> null
            }
        }
        return if (texts.size == 1) JsonPrimitive(texts[0])
        else buildJsonArray {
            texts.forEach { add(buildJsonObject { put("type", "text"); put("text", it) }) }
        }
    }

    private fun convertResponsesTools(tools: JsonElement): JsonArray {
        val arr = tools as? JsonArray ?: return JsonArray(emptyList())
        return buildJsonArray {
            arr.forEach { tool ->
                val obj = tool as? JsonObject ?: return@forEach
                // Responses: {type:function, name, description, parameters}
                // Chat: {type:function, function:{name, description, parameters}}
                if (obj.str("type") == "function" && obj["function"] == null) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", obj.str("name") ?: "")
                            obj.str("description")?.let { put("description", it) }
                            put("parameters", obj["parameters"] ?: JsonObject(emptyMap()))
                        })
                    })
                } else {
                    add(obj)
                }
            }
        }
    }
}
