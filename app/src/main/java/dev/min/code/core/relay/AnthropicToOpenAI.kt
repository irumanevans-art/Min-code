package dev.min.code.core.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages → OpenAI Chat Completions 的请求转换。
 *
 * Claude Code 只会发 Anthropic Messages。上游只提供 `/chat/completions` 时
 * （DeepSeek、GLM、Kimi、千帆、SiliconFlow…），请求要经这一道才能用。
 *
 * ## 不丢字段
 *
 * 转换只动「形状对不上」的那几处（system / content blocks / tools / tool_choice），
 * 其余顶层字段原样带走。上游加了个我们还不认识的字段，丢掉它往往就是丢掉工具调用。
 *
 * ## 图片
 *
 * Anthropic 的 `image.source.base64` ↔ OpenAI 的 `image_url` data URL。
 * URL 型图片（`source.type = url`）同样转到 `image_url`。
 */
internal object AnthropicToOpenAI {

    /**
     * `/v1/messages/count_tokens` 的本地估算。
     *
     * chat 中转没有这个端点：原样转发出去，上游回的是一次 chat completion，形状对不上，
     * CLI 拿去当 token 数会错。按字符粗估（ASCII 约 4 字一个、CJK 约一字一个），
     * 只给上下文占用一个量级，不追求和上游计费一致。
     */
    fun estimateTokens(anthropic: JsonObject): Int {
        val text = buildString {
            append(anthropic["system"]?.let { systemText(it) }.orEmpty())
            anthropic.arr("messages")?.forEach { msg ->
                val m = msg as? JsonObject ?: return@forEach
                when (val content = m["content"]) {
                    is JsonPrimitive -> append(content.content)
                    is JsonArray -> content.forEach { block ->
                        val b = block as? JsonObject ?: return@forEach
                        append(b.str("text").orEmpty())
                        append(b.str("name").orEmpty())
                        b["input"]?.let { append(it.toString()) }
                    }
                    else -> Unit
                }
            }
            anthropic.arr("tools")?.forEach { append(it.toString()) }
        }
        if (text.isEmpty()) return 0
        val cjk = text.count { it.code > 0x2E80 }
        return (cjk + (text.length - cjk) / 4).coerceAtLeast(1)
    }

    fun convertRequest(anthropic: JsonObject, modelOverride: String? = null): JsonObject {
        val messages = buildJsonArray {
            // system 可以是字符串，也可以是 content block 数组 —— 两种都收到 messages 开头
            anthropic["system"]?.let { system ->
                val text = systemText(system) ?: return@let
                add(buildJsonObject {
                    put("role", "system")
                    put("content", text)
                })
            }
            anthropic.arr("messages")?.forEach { msg ->
                val obj = msg as? JsonObject ?: return@forEach
                convertMessage(obj).forEach { add(it) }
            }
        }

        return buildJsonObject {
            put("model", modelOverride ?: anthropic.str("model") ?: "")
            put("messages", messages)
            anthropic["stream"]?.let { put("stream", it) }
            // OpenAI 流式要 usage 得显式要；Anthropic 是默认给的。不设的话底栏花费永远是 0
            if (anthropic.bool("stream") == true) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
            anthropic["max_tokens"]?.let { put("max_tokens", it) }
            anthropic["temperature"]?.let { put("temperature", it) }
            anthropic["top_p"]?.let { put("top_p", it) }
            anthropic["stop_sequences"]?.let { put("stop", it) }
            anthropic["tools"]?.let { tools ->
                put("tools", convertTools(tools))
            }
            anthropic["tool_choice"]?.let { put("tool_choice", convertToolChoice(it)) }
            // 其余原样带走（metadata、user、service_tier…），认得的上面已经处理过
            anthropic.forEach { (k, v) ->
                if (k !in HANDLED) put(k, v)
            }
        }
    }

    private val HANDLED = setOf(
        "model", "messages", "system", "stream", "max_tokens", "temperature",
        "top_p", "stop_sequences", "tools", "tool_choice",
        // Anthropic 专有、OpenAI 没有对应物的：丢掉比硬塞过去更安全
        "metadata", "anthropic_version", "anthropic_beta",
    )

    private fun systemText(system: JsonElement): String? = when (system) {
        is JsonPrimitive -> system.content
        is JsonArray -> system.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "text") obj.str("text") else null
        }.joinToString("\n").ifBlank { null }
        else -> null
    }

    /**
     * 一条 Anthropic message 可能拆成多条 OpenAI message：
     * `tool_result` 必须是独立的 `role=tool`，不能混在 user content 里。
     */
    private fun convertMessage(msg: JsonObject): List<JsonObject> {
        val role = msg.str("role") ?: "user"
        val content = msg["content"]
        if (content is JsonPrimitive) {
            return listOf(buildJsonObject {
                put("role", role)
                put("content", content.content)
            })
        }
        val blocks = (content as? JsonArray) ?: return listOf(buildJsonObject {
            put("role", role)
            put("content", "")
        })

        val out = ArrayList<JsonObject>()
        val textAndImages = ArrayList<JsonElement>()
        val toolCalls = ArrayList<JsonElement>()

        blocks.forEach { block ->
            val obj = block as? JsonObject ?: return@forEach
            when (obj.str("type")) {
                "text" -> textAndImages += buildJsonObject {
                    put("type", "text")
                    put("text", obj.str("text") ?: "")
                }
                "image" -> textAndImages += convertImage(obj)
                "tool_use" -> toolCalls += buildJsonObject {
                    put("id", obj.str("id") ?: "")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", obj.str("name") ?: "")
                        // OpenAI 要的是 JSON 字符串，不是对象
                        put("arguments", (obj["input"] ?: JsonObject(emptyMap())).toString())
                    })
                }
                "tool_result" -> {
                    // 先把攒着的 user/assistant 内容落下去，再写 tool
                    flushAssistantOrUser(out, role, textAndImages, toolCalls)
                    textAndImages.clear()
                    toolCalls.clear()
                    out += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", obj.str("tool_use_id") ?: "")
                        put("content", toolResultContent(obj))
                    }
                }
            }
        }
        flushAssistantOrUser(out, role, textAndImages, toolCalls)
        return out
    }

    private fun flushAssistantOrUser(
        out: MutableList<JsonObject>,
        role: String,
        textAndImages: List<JsonElement>,
        toolCalls: List<JsonElement>,
    ) {
        if (textAndImages.isEmpty() && toolCalls.isEmpty()) return
        out += buildJsonObject {
            put("role", role)
            when {
                textAndImages.isEmpty() -> put("content", JsonNull)
                textAndImages.size == 1 && (textAndImages[0] as? JsonObject)?.str("type") == "text" ->
                    put("content", (textAndImages[0] as JsonObject).str("text") ?: "")
                else -> put("content", JsonArray(textAndImages))
            }
            if (toolCalls.isNotEmpty()) put("tool_calls", JsonArray(toolCalls))
        }
    }

    private fun convertImage(block: JsonObject): JsonObject {
        val source = block.obj("source")
        val url = when (source?.str("type")) {
            "base64" -> {
                val media = source.str("media_type") ?: "image/png"
                val data = source.str("data") ?: ""
                "data:$media;base64,$data"
            }
            "url" -> source.str("url") ?: ""
            else -> ""
        }
        return buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", url) })
        }
    }

    private fun toolResultContent(block: JsonObject): String {
        val content = block["content"]
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
            else -> ""
        }
    }

    private fun convertTools(tools: JsonElement): JsonArray {
        val arr = tools as? JsonArray ?: return JsonArray(emptyList())
        return buildJsonArray {
            arr.forEach { tool ->
                val obj = tool as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", obj.str("name") ?: "")
                        obj.str("description")?.let { put("description", it) }
                        // Anthropic 的 input_schema ↔ OpenAI 的 parameters
                        put("parameters", obj["input_schema"] ?: buildJsonObject {
                            put("type", "object")
                            put("properties", JsonObject(emptyMap()))
                        })
                    })
                })
            }
        }
    }

    private fun convertToolChoice(choice: JsonElement): JsonElement = when (choice) {
        is JsonPrimitive -> when (choice.content) {
            "auto", "any" -> JsonPrimitive("auto")
            "none" -> JsonPrimitive("none")
            else -> choice
        }
        is JsonObject -> when (choice.str("type")) {
            // Anthropic: {"type":"tool","name":"…"} → OpenAI: {"type":"function","function":{"name":"…"}}
            "tool" -> buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", choice.str("name") ?: "") })
            }
            // any = 必须调用其中一个工具。OpenAI 没有 any，对应的是 required；
            // 原样透传会被严格的上游直接 400
            "any" -> JsonPrimitive("required")
            "auto" -> JsonPrimitive("auto")
            "none" -> JsonPrimitive("none")
            else -> choice
        }
        else -> choice
    }
}
