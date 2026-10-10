package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.Role
import kotlinx.serialization.json.*

internal fun buildGeminiBody(request: ModelRequest): JsonObject {
    val body = buildJsonObject {
        put("contents", contents(request))
        request.system.filter { it.isNotEmpty() }.joinToString("\n\n").takeIf { it.isNotEmpty() }?.let {
            putJsonObject("systemInstruction") { put("parts", JsonArray(listOf(text(it)))) }
        }
        putJsonObject("generationConfig") { put("maxOutputTokens", request.maxOutputTokens ?: request.profile.maxOutputTokens) }
        if (request.tools.isNotEmpty()) {
            put("tools", JsonArray(listOf(buildJsonObject {
                put("functionDeclarations", JsonArray(request.tools.map { tool -> buildJsonObject {
                    put("name", tool.name); put("description", tool.description)
                    put("parameters", projectGeminiSchema(tool.inputSchema))
                } }))
            })))
            putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") {
                put("mode", when (request.toolChoice) {
                    ToolChoice.Auto -> "AUTO"
                    ToolChoice.None -> "NONE"
                    else -> "ANY"
                })
                (request.toolChoice as? ToolChoice.Named)?.let { put("allowedFunctionNames", JsonArray(listOf(JsonPrimitive(it.name)))) }
            } }
        }
    }
    val merged = request.profile.patchFor(request.effort)?.let { body.deepMerge(it) } ?: body
    val count = (merged["generationConfig"] as? JsonObject)?.get("candidateCount")
    require(count == null || (count as? JsonPrimitive)?.intOrNull == 1) { "Gemini 编解码器只支持一个 candidate" }
    return merged
}

private data class Binding(val name: String, val wireId: String?, val ordinal: Int)
private data class Turn(val role: Role, val parts: MutableList<Part>)

private fun contents(request: ModelRequest): JsonArray {
    val turns = mutableListOf<Turn>()
    val lastUser = lastUserInputIndex(request.messages)
    request.messages.forEachIndexed { index, message ->
        val parts = if (message.role == Role.Assistant) partsToSend(
            message.parts, request.profile.ref, request.profile.reasoningEcho, index > lastUser,
        ) else message.parts
        val meaningful = parts.filter { it !is TextPart || it.text.isNotEmpty() }
        if (meaningful.isNotEmpty()) {
            // 合并相邻 user 后才排序结果，否则同名并行结果分两条消息时仍可能关联错位。
            if (message.role == Role.User && turns.lastOrNull()?.role == Role.User) turns.last().parts += meaningful
            else turns += Turn(message.role, meaningful.toMutableList())
        }
    }
    var pending = linkedMapOf<String, Binding>()
    val out = mutableListOf<JsonObject>()
    turns.forEach { turn ->
        val wire = mutableListOf<JsonObject>()
        if (turn.role == Role.Assistant) {
            require(pending.isEmpty()) { "Gemini 历史存在没有结果的工具调用" }
            val raw = turn.parts.filterIsInstance<OpaquePart>().mapNotNull { it.json as? JsonObject }
                .filter { it[RAW_PARTS] is JsonArray }
            if (raw.isNotEmpty()) {
                require(raw.size == 1) { "Gemini 助手消息包含重复原始 parts" }
                val envelope = raw.single()
                val ids = envelope[CALL_IDS] as? JsonArray ?: JsonArray(emptyList())
                var callIndex = 0
                val keepThoughts = turn.parts.any { it is ThinkingPart }
                (envelope[RAW_PARTS] as JsonArray).forEach { element ->
                    val part = element as? JsonObject ?: throw IllegalArgumentException("Gemini 原始 part 不是对象")
                    val call = part["functionCall"] as? JsonObject
                    if (call != null) {
                        val id = (ids.getOrNull(callIndex) as? JsonPrimitive)?.contentOrNull
                            ?: throw IllegalArgumentException("Gemini 原始调用缺少规范关联 id")
                        require(!pending.containsKey(id)) { "Gemini 历史工具 id 重复" }
                        pending[id] = Binding(requireNotNull(call.str("name")), call.str("id"), callIndex++)
                    }
                    // 签名是续推理状态，不能因为不显示摘要而丢掉有签名的 part。
                    if (!part.flag("thought") || keepThoughts || part.str("thoughtSignature") != null) {
                        if (part.str("text") != "" || part.size > 1) wire += part
                    }
                }
                require(callIndex == ids.size) { "Gemini 原始调用关联数量不匹配" }
            } else turn.parts.forEach { part -> when (part) {
                is TextPart -> if (part.text.isNotEmpty()) wire += text(part.text)
                is ImagePart -> wire += image(part)
                is ThinkingPart -> if (part.text.isNotEmpty() || part.signature != null) wire += buildJsonObject {
                    put("text", part.text); put("thought", true)
                    part.signature?.let { put("thoughtSignature", it) }
                }
                is ToolCallPart -> {
                    require(!pending.containsKey(part.callId.value)) { "Gemini 历史工具 id 重复" }
                    pending[part.callId.value] = Binding(part.name, part.callId.value, pending.size)
                    wire += buildJsonObject { putJsonObject("functionCall") {
                        put("id", part.callId.value); put("name", part.name); put("args", part.input)
                    } }
                }
                else -> Unit
            } }
        } else {
            val results = turn.parts.filterIsInstance<ToolResultPart>()
            require(results.map { it.callId }.distinct().size == results.size) { "Gemini 工具结果重复" }
            results.forEach { require(it.callId.value in pending) { "Gemini 工具结果没有对应的历史调用" } }
            if (pending.values.any { it.wireId == null }) {
                require(results.map { it.callId.value }.toSet() == pending.keys) { "Gemini 无 id 并行调用必须完整回传结果" }
            }
            results.sortedBy { pending.getValue(it.callId.value).ordinal }.forEach { result ->
                val binding = pending.remove(result.callId.value)!!
                wire += buildJsonObject { putJsonObject("functionResponse") {
                    put("name", binding.name)
                    binding.wireId?.let { put("id", it) }
                    putJsonObject("response") {
                        put(if (result.isError) "error" else "output", result.content.filterIsInstance<TextPart>().joinToString("") { it.text })
                    }
                    val attachments = result.content.filter { it is ImagePart || it is DocumentPart }
                    if (attachments.isNotEmpty()) put("parts", JsonArray(attachments.map(::attachment)))
                } }
            }
            turn.parts.forEach { part -> when (part) {
                is TextPart -> if (part.text.isNotEmpty()) wire += text(part.text)
                is ImagePart -> wire += image(part)
                is DocumentPart -> wire += document(part)
                else -> Unit
            } }
        }
        if (wire.isNotEmpty()) out += buildJsonObject {
            put("role", if (turn.role == Role.Assistant) "model" else "user"); put("parts", JsonArray(wire))
        }
    }
    require(pending.isEmpty()) { "Gemini 请求存在没有结果的工具调用" }
    return JsonArray(out)
}

private fun text(value: String) = buildJsonObject { put("text", value) }
private fun image(part: ImagePart): JsonObject {
    val source = part.source as? ImageSource.Base64
        ?: throw IllegalArgumentException("图片附件必须先内联成 base64 再交给编解码器")
    return buildJsonObject { putJsonObject("inlineData") { put("mimeType", part.mediaType); put("data", source.data) } }
}

/** PDF 与图片同走 inlineData；mimeType 为 application/pdf（官方文档的 document 输入同样是 base64 + 媒体类型） */
private fun document(part: DocumentPart): JsonObject {
    val source = part.source as? ImageSource.Base64
        ?: throw IllegalArgumentException("文档附件必须先内联成 base64 再交给编解码器")
    return buildJsonObject { putJsonObject("inlineData") { put("mimeType", part.mediaType); put("data", source.data) } }
}

/** 函数结果里的附件（图片与文档）按出现顺序放进 functionResponse.parts */
private fun attachment(part: Part): JsonObject = when (part) {
    is ImagePart -> image(part)
    is DocumentPart -> document(part)
    else -> throw IllegalArgumentException("不是附件：${part::class.simpleName}")
}
