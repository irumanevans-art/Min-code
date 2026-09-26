package dev.min.code.core.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions → Anthropic Messages 的响应转换。
 *
 * 非流式是一次成形；流式要把 OpenAI 的 chunk 折成 Anthropic 的事件序列：
 * `message_start → content_block_start/delta → content_block_stop → message_delta → message_stop`。
 *
 * 上游 4xx/5xx **保留原文**转成 Anthropic 的 error 形状——用户看到的该是中转说的那句话，
 * 不是一个干巴巴的「500」。
 */
internal object OpenAIToAnthropic {

    fun convertResponse(openai: JsonObject, model: String): JsonObject {
        val choice = openai.arr("choices")?.firstOrNull() as? JsonObject
        val message = choice?.obj("message")
        val content = ArrayList<JsonElement>()
        var textIndex = 0
        message?.str("content")?.takeIf { it.isNotEmpty() }?.let {
            content += buildJsonObject {
                put("type", "text")
                put("text", it)
            }
            textIndex = 1
        }
        message?.arr("tool_calls")?.forEachIndexed { i, call ->
            val obj = call as? JsonObject ?: return@forEachIndexed
            val fn = obj.obj("function")
            content += buildJsonObject {
                put("type", "tool_use")
                put("id", obj.str("id") ?: "toolu_${i}")
                put("name", fn?.str("name") ?: "")
                put("input", parseArguments(fn?.str("arguments")))
            }
        }
        val stop = mapStopReason(choice?.str("finish_reason"), hasTools = content.any {
            (it as? JsonObject)?.str("type") == "tool_use"
        })
        val usage = openai.obj("usage")
        return buildJsonObject {
            put("id", openai.str("id") ?: "msg_relay")
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", JsonArray(content))
            put("stop_reason", stop)
            put("stop_sequence", JsonNull)
            put("usage", buildJsonObject {
                put("input_tokens", usage?.int("prompt_tokens") ?: 0)
                put("output_tokens", usage?.int("completion_tokens") ?: 0)
            })
        }
    }

    fun convertError(status: Int, body: String): JsonObject {
        // 尽量把上游的 message 抠出来；抠不到就整段原文放下 —— 总比 "HTTP 500" 有用
        val upstream = runCatching {
            relayJson.parseToJsonElement(body) as? JsonObject
        }.getOrNull()
        val message = upstream?.obj("error")?.str("message")
            ?: upstream?.str("message")
            ?: upstream?.str("error")
            ?: body.ifBlank { "HTTP $status" }
        val type = when (status) {
            401, 403 -> "authentication_error"
            429 -> "rate_limit_error"
            in 400..499 -> "invalid_request_error"
            else -> "api_error"
        }
        return buildJsonObject {
            put("type", "error")
            putJsonObject("error") {
                put("type", type)
                put("message", message)
            }
        }
    }

    /**
     * 流式转换器。有状态：要记住当前打开的 content block、tool_call 的参数缓冲。
     *
     * 用法：每来一个 OpenAI chunk 调一次 [onChunk]，拿到 0..N 条 Anthropic SSE 事件；
     * 流结束时调 [finish] 把还开着的 block 收掉。
     */
    class StreamConverter(private val model: String) {
        private var started = false
        private var textBlockOpen = false
        private var textBlockIndex = 0
        private var nextIndex = 0
        private val toolArgs = LinkedHashMap<Int, StringBuilder>() // index → args buffer
        private val toolMeta = HashMap<Int, Pair<String, String>>() // index → (id, name)
        private var openToolIndex: Int? = null
        private var stopReason: String? = null
        private var inputTokens = 0
        private var outputTokens = 0

        fun onChunk(chunk: JsonObject): List<String> {
            val events = ArrayList<String>()
            if (!started) {
                started = true
                events += event("message_start", buildJsonObject {
                    put("type", "message_start")
                    put("message", buildJsonObject {
                        put("id", chunk.str("id") ?: "msg_relay")
                        put("type", "message")
                        put("role", "assistant")
                        put("model", model)
                        put("content", JsonArray(emptyList()))
                        put("stop_reason", JsonNull)
                        put("stop_sequence", JsonNull)
                        put("usage", buildJsonObject {
                            put("input_tokens", 0)
                            put("output_tokens", 0)
                        })
                    })
                })
            }
            val choice = chunk.arr("choices")?.firstOrNull() as? JsonObject
            val delta = choice?.obj("delta")
            val finish = choice?.str("finish_reason")

            delta?.str("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                if (!textBlockOpen) {
                    textBlockOpen = true
                    textBlockIndex = nextIndex++
                    events += event("content_block_start", buildJsonObject {
                        put("type", "content_block_start")
                        put("index", textBlockIndex)
                        put("content_block", buildJsonObject {
                            put("type", "text")
                            put("text", "")
                        })
                    })
                }
                events += event("content_block_delta", buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", textBlockIndex)
                    put("delta", buildJsonObject {
                        put("type", "text_delta")
                        put("text", text)
                    })
                })
            }

            delta?.arr("tool_calls")?.forEach { call ->
                val obj = call as? JsonObject ?: return@forEach
                val idx = obj.int("index") ?: 0
                val id = obj.str("id")
                val fn = obj.obj("function")
                val name = fn?.str("name")
                val args = fn?.str("arguments")
                if (id != null || name != null) {
                    // 新的 tool_call：先把上一个还开着的收掉
                    openToolIndex?.let { prev ->
                        if (prev != idx) events += closeTool(prev)
                    }
                    if (idx !in toolMeta) {
                        toolMeta[idx] = (id ?: "toolu_$idx") to (name ?: "")
                        toolArgs[idx] = StringBuilder()
                        openToolIndex = idx
                        val blockIndex = nextIndex++
                        // 把 anthropic 的 content index 记在 toolArgs 的容量之外：用负数 key 不够干净，
                        // 直接把 blockIndex 编码进 meta 的扩展。简化：用 idx 本身当 anthropic index
                        // 的映射表
                        toolBlockIndex[idx] = blockIndex
                        events += event("content_block_start", buildJsonObject {
                            put("type", "content_block_start")
                            put("index", blockIndex)
                            put("content_block", buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolMeta[idx]!!.first)
                                put("name", toolMeta[idx]!!.second)
                                put("input", JsonObject(emptyMap()))
                            })
                        })
                    } else if (name != null) {
                        toolMeta[idx] = toolMeta[idx]!!.first to name
                    }
                }
                if (args != null) {
                    toolArgs.getOrPut(idx) { StringBuilder() }.append(args)
                    val blockIndex = toolBlockIndex[idx] ?: return@forEach
                    events += event("content_block_delta", buildJsonObject {
                        put("type", "content_block_delta")
                        put("index", blockIndex)
                        put("delta", buildJsonObject {
                            put("type", "input_json_delta")
                            put("partial_json", args)
                        })
                    })
                }
            }

            chunk.obj("usage")?.let { usage ->
                usage.int("prompt_tokens")?.let { inputTokens = it }
                usage.int("completion_tokens")?.let { outputTokens = it }
            }

            if (finish != null) {
                stopReason = mapStopReason(finish, hasTools = toolMeta.isNotEmpty())
            }
            return events
        }

        private val toolBlockIndex = HashMap<Int, Int>()

        fun finish(): List<String> {
            val events = ArrayList<String>()
            if (!started) {
                // 上游一个 chunk 都没给就断了：至少给一个空 message，CLI 才不会挂着等
                return onChunk(JsonObject(emptyMap())) + finish()
            }
            if (textBlockOpen) {
                events += event("content_block_stop", buildJsonObject {
                    put("type", "content_block_stop")
                    put("index", textBlockIndex)
                })
                textBlockOpen = false
            }
            // 每个工具块只发一次 stop。以前 openToolIndex 那个先 closeTool 一遍、下面的
            // 循环再发一遍，CLI 会看到重复的 content_block_stop
            val closed = HashSet<Int>()
            openToolIndex?.let {
                events += closeTool(it)
                closed += it
            }
            toolBlockIndex.forEach { (idx, blockIndex) ->
                if (idx in closed) return@forEach
                events += event("content_block_stop", buildJsonObject {
                    put("type", "content_block_stop")
                    put("index", blockIndex)
                })
            }
            events += event("message_delta", buildJsonObject {
                put("type", "message_delta")
                put("delta", buildJsonObject {
                    put("stop_reason", stopReason ?: "end_turn")
                    put("stop_sequence", JsonNull)
                })
                put("usage", buildJsonObject {
                    put("output_tokens", outputTokens)
                })
            })
            events += event("message_stop", buildJsonObject {
                put("type", "message_stop")
            })
            return events
        }

        private fun closeTool(idx: Int): List<String> {
            val blockIndex = toolBlockIndex[idx] ?: return emptyList()
            openToolIndex = null
            return listOf(event("content_block_stop", buildJsonObject {
                put("type", "content_block_stop")
                put("index", blockIndex)
            }))
        }

        private fun event(name: String, data: JsonObject): String =
            "event: $name\ndata: ${data}\n\n"
    }

    private fun parseArguments(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { relayJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: buildJsonObject { put("_raw", raw) }
    }

    private fun mapStopReason(finish: String?, hasTools: Boolean): String = when (finish) {
        "stop" -> "end_turn"
        "length" -> "max_tokens"
        "tool_calls" -> "tool_use"
        "content_filter" -> "refusal"
        null -> if (hasTools) "tool_use" else "end_turn"
        else -> "end_turn"
    }
}
