package dev.min.agent.llm.anthropic

import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import dev.min.agent.spi.SseEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Anthropic SSE → ModelStreamEvent（编解码器规范 §3「流」）。
 * 做成不碰协程的状态机：feed 一个 SSE 事件，吐出零到多个流式事件。这样测试可以逐事件喂，
 * 客户端那一层只管接线与错误分类。
 */

/** 工具调用参数解析失败时的占位键：工具的 parse 会拒掉它并告诉模型（规范 §2） */
internal const val INVALID_JSON_KEY = "__invalid_json"

/** 撞输出上限的原文。model_context_window_exceeded 是上下文窗口先满了：同样意味着输出被截断、工具参数可能不全 */
private val TRUNCATING_STOP_REASONS = setOf("max_tokens", "model_context_window_exceeded")

internal class AnthropicStreamParser(
    private val profile: ModelProfile,
    private val overflow: OverflowMatcher,
) {
    /** 已收到的 SSE 事件数（含 ping）。0 = 还没有任何字节，此时失败内核可以放心重发 */
    var seen = 0
        private set

    /** 收到 message_stop 或流内错误之后为真；没到这一步流就结束 = 断流 */
    var finished = false
        private set

    private sealed interface Block {
        class Text(val sb: StringBuilder) : Block
        class Thinking(val sb: StringBuilder, val signature: StringBuilder) : Block
        class Redacted(val data: String) : Block
        class Tool(val index: Int, val id: String, val name: String, val startInput: JsonObject) : Block {
            val json = StringBuilder()
        }

        /** 不认识的块（服务端工具等）：Min 不发这类工具，见到就整块忽略，连同它的增量 */
        data object Ignored : Block
    }

    private val blocks = HashMap<Int, Block>()

    /** 按 content_block_start 的先后排，等于模型输出顺序 */
    private val ordered = mutableListOf<Block>()
    private var toolCount = 0
    private var started = false
    private var messageId: String? = null
    private var stopRaw: String? = null
    private var input = 0L
    private var output = 0L
    private var cacheRead = 0L
    private var cacheWrite = 0L

    fun feed(event: SseEvent): List<ModelStreamEvent> {
        val before = seen
        seen++
        if (finished || event.data.isBlank()) return emptyList()
        val obj = parseObject(event.data) ?: throw ModelException(
            ModelFailureKind.Other,
            "无法解析的 SSE 事件 ${event.event ?: ""}：${bodyPreview(event.data)}",
            beforeFirstByte = before == 0,
        )
        val out = mutableListOf<ModelStreamEvent>()
        when (obj.str("type") ?: event.event) {
            "message_start" -> onMessageStart(obj, out)
            "content_block_start" -> onBlockStart(obj, out)
            "content_block_delta" -> onBlockDelta(obj, out)
            "content_block_stop" -> Unit // 块的收尾统一放在 Completed 里做：没收到 stop 的块也不丢
            "message_delta" -> onMessageDelta(obj)
            "message_stop" -> onMessageStop(out)
            "error" -> {
                finished = true
                val error = obj["error"] as? JsonObject
                throw classifyStreamError(error?.str("type"), error?.str("message"), overflow, before == 0)
            }
            else -> Unit // ping 与将来新增的事件类型：忽略
        }
        return out
    }

    private fun ensureStarted(out: MutableList<ModelStreamEvent>) {
        if (started) return
        started = true
        out += ModelStreamEvent.Started(messageId)
    }

    private fun onMessageStart(obj: JsonObject, out: MutableList<ModelStreamEvent>) {
        val message = obj["message"] as? JsonObject
        messageId = message?.str("id")
        readUsage(message?.get("usage") as? JsonObject, cumulativeDelta = false)
        ensureStarted(out)
    }

    private fun onBlockStart(obj: JsonObject, out: MutableList<ModelStreamEvent>) {
        ensureStarted(out)
        val index = obj.int("index") ?: return
        val start = obj["content_block"] as? JsonObject
        val block = when (start?.str("type")) {
            "text" -> Block.Text(StringBuilder(start.str("text").orEmpty()))
            "thinking" -> Block.Thinking(StringBuilder(start.str("thinking").orEmpty()), StringBuilder(start.str("signature").orEmpty()))
            "redacted_thinking" -> Block.Redacted(start.str("data").orEmpty())
            "tool_use" -> Block.Tool(
                index = toolCount++,
                id = start.str("id").orEmpty(),
                name = start.str("name").orEmpty(),
                startInput = start["input"] as? JsonObject ?: JsonObject(emptyMap()),
            )
            else -> Block.Ignored
        }
        blocks[index] = block
        if (block !is Block.Ignored) ordered += block
        when (block) {
            is Block.Text -> if (block.sb.isNotEmpty()) out += ModelStreamEvent.TextDelta(block.sb.toString())
            is Block.Thinking -> if (block.sb.isNotEmpty()) out += ModelStreamEvent.ThinkingDelta(block.sb.toString())
            is Block.Tool -> out += ModelStreamEvent.ToolCallStarted(block.index, block.id, block.name)
            else -> Unit
        }
    }

    private fun onBlockDelta(obj: JsonObject, out: MutableList<ModelStreamEvent>) {
        ensureStarted(out)
        val index = obj.int("index") ?: return
        val delta = obj["delta"] as? JsonObject ?: return
        when (val block = blocks[index]) {
            is Block.Text -> if (delta.str("type") == "text_delta") {
                val text = delta.str("text").orEmpty()
                block.sb.append(text)
                if (text.isNotEmpty()) out += ModelStreamEvent.TextDelta(text)
            }
            is Block.Thinking -> when (delta.str("type")) {
                "thinking_delta" -> {
                    val text = delta.str("thinking").orEmpty()
                    block.sb.append(text)
                    if (text.isNotEmpty()) out += ModelStreamEvent.ThinkingDelta(text)
                }
                "signature_delta" -> block.signature.append(delta.str("signature").orEmpty())
            }
            is Block.Tool -> if (delta.str("type") == "input_json_delta") {
                val partial = delta.str("partial_json").orEmpty()
                block.json.append(partial)
                if (partial.isNotEmpty()) out += ModelStreamEvent.ToolInputDelta(block.index, partial)
            }
            else -> Unit
        }
    }

    private fun onMessageDelta(obj: JsonObject) {
        stopRaw = (obj["delta"] as? JsonObject)?.str("stop_reason") ?: stopRaw
        readUsage(obj["usage"] as? JsonObject, cumulativeDelta = true)
    }

    /**
     * message_start 给初值；message_delta 的 output_tokens 是累计值，直接覆盖。
     * 部分兼容端点（起始事件里输入量填 0、结束时才给真值）会在 message_delta 里补 input / cache 字段，
     * 所以这几项在 delta 里「有且非 0」才覆盖，免得把起始事件的真值冲成 0。
     */
    private fun readUsage(usage: JsonObject?, cumulativeDelta: Boolean) {
        if (usage == null) return
        fun field(name: String): Long? = usage.long(name)
        val creation = field("cache_creation_input_tokens")
            ?: (usage["cache_creation"] as? JsonObject)?.values?.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0L }
        if (!cumulativeDelta) {
            input = field("input_tokens") ?: 0
            cacheRead = field("cache_read_input_tokens") ?: 0
            cacheWrite = creation ?: 0
            output = field("output_tokens") ?: 0
        } else {
            field("input_tokens")?.takeIf { it > 0 }?.let { input = it }
            field("cache_read_input_tokens")?.takeIf { it > 0 }?.let { cacheRead = it }
            creation?.takeIf { it > 0 }?.let { cacheWrite = it }
            field("output_tokens")?.let { output = it }
        }
    }

    private fun onMessageStop(out: MutableList<ModelStreamEvent>) {
        ensureStarted(out)
        finished = true
        val stop = mapStop(stopRaw)
        out += ModelStreamEvent.Completed(
            message = ModelMessage(Role.Assistant, ordered.mapNotNull { toPart(it) }),
            usage = Usage(input, output, cacheRead, cacheWrite),
            stop = stop,
            truncated = stopRaw in TRUNCATING_STOP_REASONS,
        )
    }

    private fun toPart(block: Block): Part? = when (block) {
        is Block.Text -> if (block.sb.isEmpty()) null else TextPart(block.sb.toString())
        is Block.Thinking ->
            // 全空的思考块（兼容端点常见）没有回传价值，丢掉；有签名的哪怕文本为空也要留，回传时要原样带
            if (block.sb.isEmpty() && block.signature.isEmpty()) null
            else ThinkingPart(block.sb.toString(), profile.ref, block.signature.toString().ifEmpty { null })
        is Block.Redacted -> RedactedThinkingPart(block.data, profile.ref)
        is Block.Tool -> ToolCallPart(CallId(block.id), block.name, parseToolInput(block))
        Block.Ignored -> null
    }

    private fun parseToolInput(block: Block.Tool): JsonObject {
        val raw = block.json.toString()
        // 少数兼容端点把完整参数放在 content_block_start 里、后面不发增量
        if (raw.isBlank()) return block.startInput
        val parsed: JsonElement? = try {
            AgentJson.parseToJsonElement(raw)
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
        return parsed as? JsonObject ?: JsonObject(mapOf(INVALID_JSON_KEY to JsonPrimitive(raw)))
    }

    private fun mapStop(raw: String?): StopReason = when (raw) {
        "end_turn" -> StopReason.EndTurn
        "tool_use" -> StopReason.ToolUse
        "max_tokens" -> StopReason.MaxTokens
        "stop_sequence" -> StopReason.StopSequence
        "refusal" -> StopReason.Refusal
        null -> StopReason.Other("missing")
        else -> StopReason.Other(raw)
    }
}

private fun parseObject(text: String): JsonObject? =
    try {
        AgentJson.parseToJsonElement(text) as? JsonObject
    } catch (e: kotlinx.serialization.SerializationException) {
        null
    }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
