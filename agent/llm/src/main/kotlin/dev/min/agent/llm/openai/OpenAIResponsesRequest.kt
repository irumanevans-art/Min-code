package dev.min.agent.llm.openai

import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ToolChoice
import dev.min.agent.llm.deepMerge
import dev.min.agent.llm.joinedText
import dev.min.agent.llm.lastUserInputIndex
import dev.min.agent.llm.partsToSend
import dev.min.agent.llm.patchFor
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 日志承载全部上下文；不用服务端 previous_response_id，恢复时无需依赖服务端会话。 */
internal fun buildResponsesBody(request: ModelRequest): JsonObject {
    val body = buildJsonObject {
        put("model", request.profile.wireModelId)
        put("stream", true)
        put("store", false)
        put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
        put("max_output_tokens", request.maxOutputTokens ?: request.profile.maxOutputTokens)
        if (request.profile.effortPatches.isEmpty()) request.effort?.let { effort ->
            require(effort != Effort.Max) { "Responses 的 max 档需要模型资料提供明确补丁" }
            putJsonObject("reasoning") { put("effort", if (effort == Effort.Off) "none" else effort.name.lowercase()) }
        }
        put("input", responsesInput(request))
        request.cache.key?.let { put("prompt_cache_key", it) }
        if (request.tools.isNotEmpty()) {
            put("tools", JsonArray(request.tools.map { tool -> buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.inputSchema)
                // 现有工具的可选参数不是 strict schema，不能让服务端隐式改成必填。
                put("strict", false)
            } }))
            put("tool_choice", when (val choice = request.toolChoice) {
                ToolChoice.Auto -> JsonPrimitive("auto")
                ToolChoice.Required -> JsonPrimitive("required")
                ToolChoice.None -> JsonPrimitive("none")
                is ToolChoice.Named -> buildJsonObject { put("type", "function"); put("name", choice.name) }
            })
            put("parallel_tool_calls", true)
        }
    }
    return request.profile.patchFor(request.effort)?.let { body.deepMerge(it) } ?: body
}

private fun responsesInput(request: ModelRequest): JsonArray {
    val items = mutableListOf<JsonObject>()
    request.system.filter { it.isNotEmpty() }.joinToString("\n\n").takeIf { it.isNotEmpty() }?.let {
        items += buildJsonObject { put("role", "developer"); put("content", it) }
    }
    val lastInput = lastUserInputIndex(request.messages)
    request.messages.forEachIndexed { index, message ->
        if (message.role == Role.Assistant) {
            val parts = partsToSend(message.parts, request.profile.ref, request.profile.reasoningEcho, index > lastInput)
            // 保留供应商的 item id、phase 和加密推理，避免工具循环或前缀缓存因重编码丢字段。
            val raw = parts.filterIsInstance<OpaquePart>().mapNotNull { it.json as? JsonObject }
                .filter { it["type"]?.jsonPrimitive?.content in setOf("reasoning", "message", "function_call") }
            val callIds = raw.filter { it["type"]?.jsonPrimitive?.content == "function_call" }
                .mapNotNull { it["call_id"]?.jsonPrimitive?.content }.toSet()
            items += raw
            if (raw.none { it["type"]?.jsonPrimitive?.content == "message" }) {
                val text = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
                if (text.isNotEmpty()) items += buildJsonObject { put("role", "assistant"); put("content", text) }
            }
            parts.filterIsInstance<ToolCallPart>().filter { it.callId.value !in callIds }.forEach { call ->
                items += buildJsonObject {
                    put("type", "function_call"); put("call_id", call.callId.value)
                    put("name", call.name); put("arguments", call.input.toString())
                }
            }
        } else {
            message.parts.filterIsInstance<ToolResultPart>().forEach { result ->
                items += buildJsonObject {
                    put("type", "function_call_output"); put("call_id", result.callId.value)
                    put("output", inputContent(result.content))
                }
            }
            val user = message.parts.filter { it is TextPart && it.text.isNotEmpty() || it is ImagePart }
            if (user.isNotEmpty()) items += buildJsonObject { put("role", "user"); put("content", inputContent(user, separate = true)) }
        }
    }
    return JsonArray(items)
}

/**
 * [separate] = 把多个文本块压成字符串时用 [dev.min.agent.llm.TEXT_BLOCK_SEPARATOR] 隔开：只给用户消息（用户的话与 system-reminder 是独立块）；
 * 工具输出沿用原样直接拼接，与 Chat 一致（既有快照把工具结果里的多段文本钉成「AB」）。
 */
private fun inputContent(parts: List<Part>, separate: Boolean = false) = if (parts.none { it is ImagePart }) {
    JsonPrimitive(if (separate) parts.joinedText() else parts.filterIsInstance<TextPart>().joinToString("") { it.text })
} else JsonArray(parts.mapNotNull { part -> when (part) {
    is TextPart -> part.text.takeIf { it.isNotEmpty() }?.let {
        buildJsonObject { put("type", "input_text"); put("text", it) }
    }
    is ImagePart -> buildJsonObject {
        val source = part.source as? ImageSource.Base64
            ?: throw IllegalArgumentException("图片附件必须先内联成 base64 再交给编解码器")
        put("type", "input_image"); put("image_url", "data:${part.mediaType};base64,${source.data}")
    }
    else -> null
} })
