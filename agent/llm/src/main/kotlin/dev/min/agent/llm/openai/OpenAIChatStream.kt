package dev.min.agent.llm.openai

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.OverflowMatcher
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.llm.INVALID_JSON_KEY
import dev.min.agent.llm.OpenAiChatQuirks
import dev.min.agent.llm.bodyPreview
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.SseEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 状态机不碰协程：每个请求独享缓冲，增量仅供显示，完整参数在 DONE 时解析。 */
internal class OpenAIChatStreamParser(
    private val profile: ModelProfile,
    private val quirks: OpenAiChatQuirks,
    private val overflow: OverflowMatcher,
) {
    var seen = 0
        private set
    var finished = false
        private set

    private sealed interface Block {
        class Text(val text: StringBuilder = StringBuilder()) : Block
        class Tool(val index: Int) : Block {
            var id: String? = null
            var name: String? = null
            var announced = false
            val arguments = StringBuilder()
        }
        class Encrypted(val field: String, val text: StringBuilder = StringBuilder()) : Block
    }

    private var started = false
    private var messageId: String? = null
    private var stopRaw: String? = null
    private val thinking = StringBuilder()
    private val tools = LinkedHashMap<Int, Block.Tool>()
    private val ordered = mutableListOf<Block>()
    private var encrypted: Block.Encrypted? = null
    private var prompt: Long? = null
    private var cacheRead = 0L
    private var cacheWrite = 0L
    private var cacheMiss: Long? = null
    private var output = 0L

    fun feed(event: SseEvent): List<ModelStreamEvent> {
        val before = seen++
        if (finished || event.data.isBlank() || event.event == "ping") return emptyList()
        val out = mutableListOf<ModelStreamEvent>()
        if (event.data.trim() == "[DONE]") {
            ensureStarted(out)
            // 没有完整身份的调用不可执行，也不能编造 id/name 让工具误跑。
            if (tools.values.any { !it.announced }) throw unexpected("工具调用缺少 id 或 function.name", before)
            finished = true
            val parts = mutableListOf<Part>()
            if (thinking.isNotEmpty()) parts += ThinkingPart(thinking.toString(), profile.ref)
            parts += ordered.map { block ->
                when (block) {
                    is Block.Text -> TextPart(block.text.toString())
                    is Block.Tool -> ToolCallPart(CallId(block.id!!), block.name!!, parseArguments(block.arguments.toString()))
                    is Block.Encrypted -> OpaquePart(profile.ref, JsonObject(mapOf(block.field to JsonPrimitive(block.text.toString()))))
                }
            }
            out += ModelStreamEvent.Completed(
                ModelMessage(Role.Assistant, parts),
                Usage(cacheMiss ?: ((prompt ?: cacheRead + cacheWrite) - cacheRead - cacheWrite).coerceAtLeast(0), output, cacheRead, cacheWrite),
                mapStop(stopRaw),
                truncated = stopRaw == "length" || stopRaw == "model_context_window_exceeded",
            )
            return out
        }
        val obj = parseObject(event.data) ?: throw unexpected(event.data, before)
        val error = obj["error"]
        if (error != null && error !is JsonNull || event.event == "error") {
            val details = error as? JsonObject
            throw classifyStreamError(details?.str("type") ?: obj.str("type"), event.data, overflow, before == 0)
        }
        val choices = obj["choices"] as? JsonArray ?: throw unexpected(event.data, before)
        val choice = choices.firstOrNull() as? JsonObject
        // 200 JSON completion（message 而非 delta）也是中转的非流响应，不能假装成空成功。
        if (choices.isNotEmpty() && (choice == null || choice["delta"] !is JsonObject)) throw unexpected(event.data, before)
        messageId = obj.str("id") ?: messageId
        ensureStarted(out)
        readUsage(obj["usage"] as? JsonObject)
        stopRaw = choice?.str("finish_reason") ?: stopRaw
        val delta = choice?.get("delta") as? JsonObject ?: return out
        quirks.reasoningField?.let { field -> delta.str(field)?.takeIf { it.isNotEmpty() }?.let { text ->
            thinking.append(text)
            out += ModelStreamEvent.ThinkingDelta(text)
        } }
        delta.str("content")?.takeIf { it.isNotEmpty() }?.let { text ->
            val block = (ordered.lastOrNull() as? Block.Text) ?: Block.Text().also { ordered += it }
            block.text.append(text)
            out += ModelStreamEvent.TextDelta(text)
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { element ->
            val call = element as? JsonObject ?: throw unexpected(event.data, before)
            readTool(call, out, before)
        }
        quirks.encryptedReasoningField?.let { field -> delta.str(field)?.takeIf { it.isNotEmpty() }?.let { text ->
            val block = encrypted ?: Block.Encrypted(field).also { encrypted = it; ordered += it }
            block.text.append(text)
        } }
        return out
    }

    private fun ensureStarted(out: MutableList<ModelStreamEvent>) {
        if (started) return
        started = true
        out += ModelStreamEvent.Started(messageId)
    }

    private fun readTool(call: JsonObject, out: MutableList<ModelStreamEvent>, before: Int) {
        val wireIndex = (call["index"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw unexpected("工具调用缺少有效 index：$call", before)
        val tool = tools.getOrPut(wireIndex) { Block.Tool(tools.size).also { ordered += it } }
        val function = call["function"] as? JsonObject
        call.str("id")?.takeIf { it.isNotEmpty() }?.let { tool.id = it }
        function?.str("name")?.takeIf { it.isNotEmpty() }?.let { tool.name = it }
        val partial = function?.str("arguments").orEmpty()
        tool.arguments.append(partial)
        if (!tool.announced && tool.id != null && tool.name != null) {
            tool.announced = true
            out += ModelStreamEvent.ToolCallStarted(tool.index, tool.id!!, tool.name!!)
            // 身份晚于参数到达时补发之前缓冲的参数，保证界面也能重建全部输入。
            if (tool.arguments.isNotEmpty()) out += ModelStreamEvent.ToolInputDelta(tool.index, tool.arguments.toString())
        } else if (tool.announced && partial.isNotEmpty()) {
            out += ModelStreamEvent.ToolInputDelta(tool.index, partial)
        }
    }

    private fun readUsage(usage: JsonObject?) {
        if (usage == null) return
        // usage 是累计快照，不能逐块相加；缺失字段保留前值，末块 choices=[] 也要读取。
        usage.long("prompt_tokens")?.let { prompt = it }
        usage.long("completion_tokens")?.let { output = it }
        val details = usage["prompt_tokens_details"] as? JsonObject
        (usage.long("prompt_cache_hit_tokens") ?: details?.long("cached_tokens"))?.let { cacheRead = it }
        usage.long("prompt_cache_miss_tokens")?.let { cacheMiss = it }
        details?.long("cache_write_tokens")?.let { cacheWrite = it }
    }

    private fun unexpected(body: String, before: Int) = ModelException(
        ModelFailureKind.Other, "HTTP 200，非预期的流数据：${bodyPreview(body)}", httpStatus = 200, beforeFirstByte = before == 0,
    )
}

private fun parseObject(raw: String): JsonObject? = try {
    AgentJson.parseToJsonElement(raw) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun parseArguments(raw: String): JsonObject {
    if (raw.isEmpty()) return JsonObject(emptyMap())
    return parseObject(raw) ?: JsonObject(mapOf(INVALID_JSON_KEY to JsonPrimitive(raw)))
}

private fun mapStop(raw: String?): StopReason = when (raw) {
    "stop" -> StopReason.EndTurn
    "tool_calls", "function_call" -> StopReason.ToolUse
    "length" -> StopReason.MaxTokens
    "content_filter" -> StopReason.Refusal
    null -> StopReason.Other("missing")
    else -> StopReason.Other(raw)
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
