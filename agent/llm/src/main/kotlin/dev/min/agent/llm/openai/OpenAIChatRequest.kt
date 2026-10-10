package dev.min.agent.llm.openai

import dev.min.agent.llm.EmptyContent
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.OpenAiChatQuirks
import dev.min.agent.llm.ReasoningEcho
import dev.min.agent.llm.StreamUsage
import dev.min.agent.llm.ToolChoice
import dev.min.agent.llm.ToolResultImages
import dev.min.agent.llm.deepMerge
import dev.min.agent.llm.joinedText
import dev.min.agent.llm.lastUserInputIndex
import dev.min.agent.llm.partsToSend
import dev.min.agent.llm.patchFor
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun buildOpenAIChatBody(request: ModelRequest, quirks: OpenAiChatQuirks): JsonObject {
    val body = buildJsonObject {
        put("model", request.profile.wireModelId)
        put("messages", messages(request, quirks))
        put("stream", true)
        put(quirks.maxTokensField, request.maxOutputTokens ?: request.profile.maxOutputTokens)
        if (quirks.streamUsage == StreamUsage.IncludeUsageOption) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        if (quirks.promptCacheKey) request.cache.key?.let { put("prompt_cache_key", it) }
        if (request.tools.isNotEmpty()) {
            put("tools", JsonArray(request.tools.map { tool ->
                buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    }
                }
            }))
            put("tool_choice", toolChoice(request.toolChoice))
            if (quirks.parallelToolCalls) put("parallel_tool_calls", true)
        }
    }
    return request.profile.patchFor(request.effort)?.let { body.deepMerge(it) } ?: body
}

private fun toolChoice(choice: ToolChoice): JsonElement = when (choice) {
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.Required -> JsonPrimitive("required")
    ToolChoice.None -> JsonPrimitive("none")
    is ToolChoice.Named -> buildJsonObject {
        put("type", "function")
        putJsonObject("function") { put("name", choice.name) }
    }
}

private fun messages(request: ModelRequest, quirks: OpenAiChatQuirks): JsonArray {
    val out = mutableListOf<JsonObject>()
    val system = request.system.filter { it.isNotEmpty() }.joinToString("\n\n")
    if (system.isNotEmpty()) out += message(if (quirks.developerRole) "developer" else "system", JsonPrimitive(system))
    val lastInput = lastUserInputIndex(request.messages)
    // Kimi 要求工具结果带 name；从历史调用关联，不猜一个可能错误的工具名。
    val names = request.messages.flatMap { it.parts }.filterIsInstance<ToolCallPart>().associate { it.callId to it.name }
    request.messages.forEachIndexed { index, turn ->
        when (turn.role) {
            Role.Assistant -> assistantMessage(
                partsToSend(turn.parts, request.profile.ref, request.profile.reasoningEcho, index > lastInput),
                request.profile.reasoningEcho, quirks,
            )?.let { out += it }
            Role.User -> {
                val results = turn.parts.filterIsInstance<ToolResultPart>()
                results.forEach { result ->
                    // 即使本端点不支持图片也要拒绝未内联附件；否则会掩盖内核装配错误。
                    attachments(result.content).forEach { attachmentBlock(it) }
                    out += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId.value)
                        if (quirks.toolResultName) put("name", requireNotNull(names[result.callId]) {
                            "工具结果 ${result.callId.value} 缺少历史工具调用，无法回传 name"
                        })
                        val files = attachments(result.content)
                        val text = result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                        put("content", when {
                            files.isNotEmpty() && quirks.toolResultImages == ToolResultImages.Inline -> content(result.content)
                            files.isNotEmpty() && quirks.toolResultImages == ToolResultImages.Unsupported ->
                                JsonPrimitive(listOf(text, omittedNote(files)).filter { it.isNotEmpty() }.joinToString("\n"))
                            else -> JsonPrimitive(text)
                        })
                    }
                }
                // 所有 tool 消息先发完才补 user，保证并行调用的结果不会被用户消息隔开。
                if (quirks.toolResultImages == ToolResultImages.SeparateUserMessage) {
                    val files = attachments(results.flatMap { it.content })
                    if (files.isNotEmpty()) out += message("user", content(files))
                }
                val user = turn.parts.filter { it is ImagePart || it is DocumentPart || it is TextPart && it.text.isNotEmpty() }
                if (user.isNotEmpty()) out += message("user", content(user))
            }
        }
    }
    return JsonArray(out)
}

private fun assistantMessage(parts: List<Part>, echo: ReasoningEcho, quirks: OpenAiChatQuirks): JsonObject? {
    val text = parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    val calls = parts.filterIsInstance<ToolCallPart>()
    val thinking = parts.filterIsInstance<ThinkingPart>()
    val reasoning = quirks.reasoningField?.takeIf { thinking.isNotEmpty() || echo == ReasoningEcho.AlwaysIncludingEmpty }
    val encrypted = quirks.encryptedReasoningField?.let { field ->
        parts.filterIsInstance<OpaquePart>().mapNotNull { (it.json as? JsonObject)?.get(field) }.lastOrNull()
    }
    if (text.isEmpty() && calls.isEmpty() && reasoning == null && encrypted == null) return null
    return buildJsonObject {
        put("role", "assistant")
        when {
            text.isNotEmpty() || calls.isEmpty() -> put("content", text)
            quirks.emptyContentWithToolCalls == EmptyContent.Null -> put("content", JsonNull)
            quirks.emptyContentWithToolCalls == EmptyContent.EmptyString -> put("content", "")
        }
        if (reasoning != null) put(reasoning, thinking.joinToString("") { it.text })
        if (encrypted != null) put(quirks.encryptedReasoningField, encrypted)
        if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls.map { call ->
            buildJsonObject {
                // Chat 协议不限制 id 字符集：原样回传才能和供应商给的结果相配。
                put("id", call.callId.value)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.input.toString())
                }
            }
        }))
    }
}

private fun message(role: String, content: JsonElement) = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun content(parts: List<Part>): JsonElement {
    if (parts.none { it is ImagePart || it is DocumentPart }) return JsonPrimitive(parts.joinedText())
    return JsonArray(parts.mapNotNull { part ->
        when (part) {
            is TextPart -> if (part.text.isEmpty()) null else buildJsonObject { put("type", "text"); put("text", part.text) }
            is ImagePart -> imageBlock(part)
            is DocumentPart -> fileBlock(part)
            else -> null
        }
    })
}

private fun imageBlock(part: ImagePart): JsonObject {
    val data = when (val source = part.source) {
        is ImageSource.Base64 -> source.data
        is ImageSource.Artifact -> throw IllegalArgumentException("图片附件 ${source.path} 必须先内联成 base64 再交给编解码器")
    }
    return buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") { put("url", "data:${part.mediaType};base64,$data") }
    }
}

/** 图片与文档都是「附件」：工具消息只能放文本，所以三种 [ToolResultImages] 模式都按附件处理 */
private fun attachments(parts: List<Part>): List<Part> = parts.filter { it is ImagePart || it is DocumentPart }

private fun attachmentBlock(part: Part): JsonObject = when (part) {
    is ImagePart -> imageBlock(part)
    is DocumentPart -> fileBlock(part)
    else -> throw IllegalArgumentException("不是附件：${part::class.simpleName}")
}

/** 「未发送」说明分开数：图片与文档对模型是不同的东西，混成「附件」会让它不知道缺了什么 */
private fun omittedNote(files: List<Part>): String {
    val images = files.count { it is ImagePart }
    val documents = files.count { it is DocumentPart }
    return buildList {
        if (images > 0) add("此处有 $images 张图片未发送")
        if (documents > 0) add("此处有 $documents 份文档未发送")
    }.joinToString("\n")
}

/**
 * PDF 走 Chat Completions 的 file 内容块（官方形状：file.filename + file.file_data 为 data URL）。
 * filename 必填；文档本身不带文件名，用固定名。
 */
private fun fileBlock(part: DocumentPart): JsonObject {
    val data = when (val source = part.source) {
        is ImageSource.Base64 -> source.data
        is ImageSource.Artifact -> throw IllegalArgumentException("文档附件 ${source.path} 必须先内联成 base64 再交给编解码器")
    }
    return buildJsonObject {
        put("type", "file")
        putJsonObject("file") {
            put("filename", "document.pdf")
            put("file_data", "data:${part.mediaType};base64,$data")
        }
    }
}
