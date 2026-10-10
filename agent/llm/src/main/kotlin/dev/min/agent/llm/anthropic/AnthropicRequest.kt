package dev.min.agent.llm.anthropic

import dev.min.agent.llm.AnthropicQuirks
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ToolChoice
import dev.min.agent.llm.deepMerge
import dev.min.agent.llm.lastUserInputIndex
import dev.min.agent.llm.partsToSend
import dev.min.agent.llm.patchFor
import dev.min.agent.protocol.DocumentPart
import dev.min.agent.protocol.ImagePart
import dev.min.agent.protocol.ImageSource
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * 规范请求 → Anthropic Messages 请求体（编解码器规范 §2、§3）。纯函数，不碰网络与凭证。
 */

/** 思考预算顶到 max_tokens 时多留给正文的余量 */
private const val BUDGET_HEADROOM = 1024

private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_-]")

/**
 * 工具调用 id 只许 `[A-Za-z0-9_-]`。确定性替换：同一个 id 在 tool_use 与 tool_result 两处得到同一个值。
 * 不同 id 替换后撞车的可能性存在但极小：供应商给的 id 本来就是这个字符集，只有别的协议来的 id 才会被改。
 */
internal fun sanitizeToolId(id: String): String = id.replace(UNSAFE_ID_CHARS, "_")

internal fun buildAnthropicBody(request: ModelRequest, quirks: AnthropicQuirks): JsonObject {
    val profile = request.profile
    val mark = if (quirks.cacheControl) cacheMark(request.cache.longTtl) else null
    val maxTokens = request.maxOutputTokens ?: profile.maxOutputTokens

    val body = buildJsonObject {
        put("model", profile.wireModelId)
        put("max_tokens", maxTokens)
        put("stream", true)
        val system = systemBlocks(request.system, mark)
        if (system.isNotEmpty()) put("system", system)
        if (request.tools.isNotEmpty()) {
            put("tools", toolBlocks(request, mark))
            put("tool_choice", toolChoice(request.toolChoice))
        }
        put("messages", messageBlocks(request, quirks, mark))
    }

    val patch = profile.patchFor(request.effort)
    val merged = if (patch != null) body.deepMerge(patch) else body
    return fitThinkingBudget(merged, maxTokens, profile.maxOutputTokens)
}

private fun cacheMark(longTtl: Boolean): JsonObject = buildJsonObject {
    put("type", "ephemeral")
    if (longTtl) put("ttl", "1h")
}

private fun JsonObject.withCache(mark: JsonObject?): JsonObject =
    if (mark == null) this else JsonObject(this + ("cache_control" to mark))

private fun systemBlocks(system: List<String>, mark: JsonObject?): JsonArray {
    val blocks = system.filter { it.isNotEmpty() }.map { textBlock(it) }
    return JsonArray(blocks.mapIndexed { i, b -> if (i == blocks.lastIndex) b.withCache(mark) else b })
}

private fun toolBlocks(request: ModelRequest, mark: JsonObject?): JsonArray {
    val last = request.tools.lastIndex
    return JsonArray(
        request.tools.mapIndexed { i, tool ->
            val block = buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.inputSchema)
            }
            if (i == last) block.withCache(mark) else block
        },
    )
}

private fun toolChoice(choice: ToolChoice): JsonObject = buildJsonObject {
    when (choice) {
        ToolChoice.Auto -> put("type", "auto")
        ToolChoice.Required -> put("type", "any")
        ToolChoice.None -> put("type", "none")
        is ToolChoice.Named -> {
            put("type", "tool")
            put("name", choice.name)
        }
    }
}

private fun textBlock(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

/** 相邻同角色合并前的一轮：兼容端点对 user/assistant 严格交替更挑剔，所以合并 */
private class Turn(val role: Role, val blocks: MutableList<JsonObject>)

private fun messageBlocks(request: ModelRequest, quirks: AnthropicQuirks, mark: JsonObject?): JsonArray {
    val profile = request.profile
    val lastInput = lastUserInputIndex(request.messages)
    val turns = mutableListOf<Turn>()
    request.messages.forEachIndexed { index, message ->
        val blocks = when (message.role) {
            Role.User -> userBlocks(message.parts)
            Role.Assistant -> assistantBlocks(
                partsToSend(message.parts, profile.ref, profile.reasoningEcho, inToolLoop = index > lastInput),
                quirks,
            )
        }
        // 投影后一个块都不剩的消息整条不发：空 content 会被拒
        if (blocks.isEmpty()) return@forEachIndexed
        val previous = turns.lastOrNull()
        if (previous != null && previous.role == message.role) previous.blocks += blocks
        else turns += Turn(message.role, blocks.toMutableList())
    }
    // tool_result 必须排在同一条 user 消息的其他内容之前（Anthropic 的硬性要求）；sortedBy 稳定，不动其余顺序
    turns.filter { it.role == Role.User }.forEach { turn ->
        val sorted = turn.blocks.sortedBy { if (it["type"] == JsonPrimitive("tool_result")) 0 else 1 }
        turn.blocks.clear()
        turn.blocks.addAll(sorted)
    }
    // 缓存断点：最后一条 user 消息的最后一个内容块
    val lastUser = turns.lastOrNull { it.role == Role.User }
    if (mark != null && lastUser != null) {
        lastUser.blocks[lastUser.blocks.lastIndex] = lastUser.blocks.last().withCache(mark)
    }
    return JsonArray(
        turns.map { turn ->
            buildJsonObject {
                put("role", if (turn.role == Role.User) "user" else "assistant")
                put("content", JsonArray(turn.blocks))
            }
        },
    )
}

private fun userBlocks(parts: List<Part>): List<JsonObject> = parts.mapNotNull { part ->
    when (part) {
        is TextPart -> if (part.text.isEmpty()) null else textBlock(part.text)
        is ImagePart -> imageBlock(part)
        is DocumentPart -> documentBlock(part)
        is ToolResultPart -> toolResultBlock(part)
        else -> null
    }
}

private fun imageBlock(part: ImagePart): JsonObject {
    val data = when (val source = part.source) {
        is ImageSource.Base64 -> source.data
        // 附件由内核在组请求前内联成 base64；编解码器不读文件
        is ImageSource.Artifact ->
            throw IllegalArgumentException("图片附件 ${source.path} 必须先内联成 base64 再交给编解码器")
    }
    return buildJsonObject {
        put("type", "image")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", part.mediaType)
            put("data", data)
        }
    }
}

/**
 * PDF 走 Anthropic 的 document 块（base64 源）。与图片同一套「必须先内联」的约束；
 * 块形状是官方文档（pdf-support）的 `{"type":"document","source":{"type":"base64","media_type","data"}}`。
 */
private fun documentBlock(part: DocumentPart): JsonObject {
    val data = when (val source = part.source) {
        is ImageSource.Base64 -> source.data
        is ImageSource.Artifact ->
            throw IllegalArgumentException("文档附件 ${source.path} 必须先内联成 base64 再交给编解码器")
    }
    return buildJsonObject {
        put("type", "document")
        putJsonObject("source") {
            put("type", "base64")
            put("media_type", part.mediaType)
            put("data", data)
        }
    }
}

private fun toolResultBlock(part: ToolResultPart): JsonObject = buildJsonObject {
    put("type", "tool_result")
    put("tool_use_id", sanitizeToolId(part.callId.value))
    put(
        "content",
        buildJsonArray {
            part.content.forEach { inner ->
                when (inner) {
                    is TextPart -> if (inner.text.isNotEmpty()) add(textBlock(inner.text))
                    is ImagePart -> add(imageBlock(inner))
                    is DocumentPart -> add(documentBlock(inner))
                    else -> Unit
                }
            }
        },
    )
    // 只在为真时写：多数兼容端点忽略它，写 false 只会多一个可能被拒的字段
    if (part.isError) put("is_error", true)
}

private fun assistantBlocks(parts: List<Part>, quirks: AnthropicQuirks): List<JsonObject> = parts.mapNotNull { part ->
    when (part) {
        is ThinkingPart -> buildJsonObject {
            put("type", "thinking")
            put("thinking", part.text)
            if (quirks.thinkingSignature && part.signature != null) put("signature", part.signature)
        }
        is RedactedThinkingPart ->
            if (!quirks.redactedThinking) {
                null
            } else {
                buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", part.data)
                }
            }
        is TextPart -> if (part.text.isEmpty()) null else textBlock(part.text)
        is ToolCallPart -> buildJsonObject {
            put("type", "tool_use")
            put("id", sanitizeToolId(part.callId.value))
            put("name", part.name)
            put("input", part.input)
        }
        // opaque 是别家协议的专有块，Anthropic 没有对应物；图片与工具结果不会出现在助手消息里
        else -> null
    }
}

/**
 * 补丁合并后若 `thinking.budget_tokens` ≥ `max_tokens`（Anthropic 要求预算严格小于上限），把上限提到 预算 + 余量，
 * 但不超过模型的输出上限；仍不满足说明模型数据写错了，抛异常大声报出来，而不是发一个必然 400 的请求。
 */
private fun fitThinkingBudget(body: JsonObject, maxTokens: Int, modelMax: Int): JsonObject {
    val budget = ((body["thinking"] as? JsonObject)?.get("budget_tokens") as? JsonPrimitive)?.intOrNull ?: return body
    if (budget < maxTokens) return body
    val raised = budget + BUDGET_HEADROOM
    if (raised > modelMax) {
        throw IllegalArgumentException(
            "thinking.budget_tokens=$budget 与 max_tokens=$maxTokens 冲突，提到 $raised 又超过模型输出上限 $modelMax：模型数据有误",
        )
    }
    return JsonObject(body + ("max_tokens" to JsonPrimitive(raised)))
}
