package dev.min.agent.llm.gemini

import dev.min.agent.llm.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import kotlinx.serialization.json.*

/** 原生流没有内容块下标或 DONE；parts 是顺序片段，functionCall 则是完整对象。 */
internal class GeminiStreamParser(
    private val profile: ModelProfile,
    private val overflow: GeminiOverflowMatcher,
    private val requestFingerprint: String,
) {
    var seen = 0
        private set
    private var started = false
    private var stopRaw: String? = null
    private var blocked: String? = null
    private var prompt = 0L
    private var cached = 0L
    private var candidates = 0L
    private var thoughts = 0L
    private val parts = mutableListOf<Part>()
    private val rawParts = mutableListOf<JsonObject>()
    private val callIds = mutableListOf<String>()
    private val ids = mutableSetOf<String>()

    fun feed(event: SseEvent): List<ModelStreamEvent> {
        val before = seen++ == 0
        if (event.data.isBlank() || event.event == "ping") return emptyList()
        val obj = parseObject(event.data) ?: throw unexpected(event.data, before)
        if ("error" in obj || event.event == "error") {
            throw geminiStreamError(obj["error"] as? JsonObject ?: obj, event.data, overflow, before)
        }
        if (obj["candidates"] !is JsonArray && obj["usageMetadata"] !is JsonObject && obj["promptFeedback"] !is JsonObject) {
            throw unexpected(event.data, before)
        }
        val out = mutableListOf<ModelStreamEvent>()
        if (!started) { started = true; out += ModelStreamEvent.Started(obj.str("responseId")) }
        (obj["usageMetadata"] as? JsonObject)?.let { usage ->
            usage.tokens("promptTokenCount")?.let { prompt = it }
            usage.tokens("cachedContentTokenCount")?.let { cached = it }
            usage.tokens("candidatesTokenCount")?.let { candidates = it }
            usage.tokens("thoughtsTokenCount")?.let { thoughts = it }
        }
        (obj["promptFeedback"] as? JsonObject)?.str("blockReason")?.takeIf { it != "BLOCK_REASON_UNSPECIFIED" }?.let { blocked = it }
        val candidate = (obj["candidates"] as? JsonArray)?.map {
            it as? JsonObject ?: throw unexpected("candidate 不是对象", before)
        }?.firstOrNull { (it["index"] as? JsonPrimitive)?.intOrNull?.let { index -> index == 0 } ?: true }
        if (candidate != null) {
            candidate.str("finishReason")?.takeIf { it != "FINISH_REASON_UNSPECIFIED" }?.let { reason ->
                if (stopRaw != null && stopRaw != reason) throw unexpected("finishReason 改变", before)
                stopRaw = reason
            }
            val content = candidate["content"] as? JsonObject
            if (content != null) {
                if (content.str("role") != null && content.str("role") != "model") throw unexpected("candidate role 不是 model", before)
                val wireParts = content["parts"] as? JsonArray ?: throw unexpected("content 缺少 parts", before)
                wireParts.forEach { element -> readPart(element as? JsonObject ?: throw unexpected("part 不是对象", before), out, before) }
            }
        }
        return out
    }

    private fun readPart(part: JsonObject, out: MutableList<ModelStreamEvent>, before: Boolean) {
        if (part.isEmpty()) return
        if (part.keys.any { it !in setOf("text", "thought", "thoughtSignature", "functionCall", "inlineData", "partMetadata") }) {
            throw unexpected("不支持的 Gemini part：$part", before)
        }
        val dataCount = listOf("text", "functionCall", "inlineData").count { it in part }
        if (dataCount > 1) throw unexpected("Gemini part 包含多个数据类型", before)
        rawParts += part
        when {
            "functionCall" in part -> {
                val call = part["functionCall"] as? JsonObject ?: throw unexpected("functionCall 不是对象", before)
                val name = call.str("name")?.takeIf { it.isNotEmpty() } ?: throw unexpected("functionCall 缺 name", before)
                val ordinal = callIds.size
                val id = call.str("id")?.takeIf { it.isNotEmpty() } ?: "gemini_${requestFingerprint}_$ordinal"
                if (!ids.add(id)) throw unexpected("工具调用 id 重复", before)
                callIds += id
                val args = call["args"]
                val input = when (args) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> args
                    else -> JsonObject(mapOf("__invalid_json" to JsonPrimitive(args.toString())))
                }
                parts += ToolCallPart(CallId(id), name, input)
                out += ModelStreamEvent.ToolCallStarted(ordinal, id, name)
                out += ModelStreamEvent.ToolInputDelta(ordinal, input.toString())
            }
            "text" in part -> {
                val value = part.str("text") ?: throw unexpected("text 不是字符串", before)
                if (part.flag("thought")) {
                    if (value.isNotEmpty() || part.str("thoughtSignature") != null) parts += ThinkingPart(value, profile.ref, part.str("thoughtSignature"))
                    if (value.isNotEmpty()) out += ModelStreamEvent.ThinkingDelta(value)
                } else if (value.isNotEmpty()) {
                    val last = parts.lastOrNull()
                    if (last is TextPart) parts[parts.lastIndex] = TextPart(last.text + value) else parts += TextPart(value)
                    out += ModelStreamEvent.TextDelta(value)
                }
            }
            "inlineData" in part -> {
                val data = part["inlineData"] as? JsonObject ?: throw unexpected("inlineData 不是对象", before)
                parts += ImagePart(data.str("mimeType") ?: throw unexpected("inlineData 缺 mimeType", before),
                    ImageSource.Base64(data.str("data") ?: throw unexpected("inlineData 缺 data", before)))
            }
            part.str("thoughtSignature") != null -> Unit
            else -> throw unexpected("没有数据或签名的 part：$part", before)
        }
    }

    fun complete(): ModelStreamEvent.Completed {
        if (stopRaw == null && blocked == null) throw ModelException(
            ModelFailureKind.Network, "Gemini 流在 finishReason 之前结束", beforeFirstByte = seen == 0,
        )
        val reason = stopRaw ?: blocked!!
        val stop = when {
            blocked != null -> StopReason.Refusal
            reason == "STOP" -> if (callIds.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            reason == "MAX_TOKENS" -> StopReason.MaxTokens
            reason in REFUSALS -> StopReason.Refusal
            else -> StopReason.Other(reason)
        }
        // 内核只用 truncated 拦截工具；非 STOP 的失败不能留下可执行调用。
        val completed = parts.filter { it !is ToolCallPart || reason == "STOP" && blocked == null }.toMutableList()
        if (rawParts.isNotEmpty()) completed += OpaquePart(profile.ref, buildJsonObject {
            // 失败输出只能用于诊断，不能在下一轮变成没有执行过的函数调用历史。
            put(if (reason == "STOP" && blocked == null) RAW_PARTS else REJECTED_PARTS, JsonArray(rawParts))
            put(CALL_IDS, JsonArray(callIds.map { JsonPrimitive(it) }))
        })
        return ModelStreamEvent.Completed(ModelMessage(Role.Assistant, completed),
            Usage((prompt - cached).coerceAtLeast(0), candidates + thoughts, cached, 0), stop, reason == "MAX_TOKENS")
    }

    private fun unexpected(body: String, before: Boolean) = ModelException(
        ModelFailureKind.Other, "HTTP 200，非预期的 Gemini 流：${body.take(500)}", httpStatus = 200, beforeFirstByte = before,
    )

    private companion object {
        val REFUSALS = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY",
            "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "ESCALATION", "PUP_LIMITED_DISABLED")
    }
}
