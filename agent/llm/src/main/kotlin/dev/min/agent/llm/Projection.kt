package dev.min.agent.llm

import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.OpaquePart
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.RedactedThinkingPart
import dev.min.agent.protocol.ThinkingPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Role
import kotlinx.serialization.json.JsonObject

/*
 * 各线协议编解码器共用的「发送时投影」规则（docs/agent/04-子系统设计.md §4.2）。
 * 历史按规范形状原样存；这里决定发给某个具体模型时，哪些块带、哪些块丢。协议特有的改写留在各编解码器里。
 */

/**
 * 最后一条「人说的话」的位置：含有非工具结果内容的 user 消息。它之后的助手消息都属于当前这轮工具循环。
 * 没有则返回 -1。
 */
fun lastUserInputIndex(messages: List<ModelMessage>): Int =
    messages.indexOfLast { m -> m.role == Role.User && m.parts.any { it !is ToolResultPart } }

/**
 * 一条助手消息发给 [target] 时应保留的块。
 *
 * - 别的模型产生的推理（thinking / redacted_thinking）与专有块（opaque）一律丢弃：签名只对原模型有效，
 *   拿另一个模型的思考风格喂新模型也只会带偏它（ZCode 的做法；pi 是转成普通文本，Min 不取）。
 * - 同一模型的推理按 [echo] 规则：[inToolLoop] 表示这条消息在最后一条人说的话之后。
 * - 其余块（文本、工具调用）原样保留。
 */
fun partsToSend(parts: List<Part>, target: ModelRef, echo: ReasoningEcho, inToolLoop: Boolean): List<Part> {
    val keepOwnReasoning = when (echo) {
        ReasoningEcho.Always, ReasoningEcho.AlwaysIncludingEmpty -> true
        ReasoningEcho.Never -> false
        ReasoningEcho.WithinToolLoop -> inToolLoop
    }
    return parts.filter { part ->
        when (part) {
            is ThinkingPart -> keepOwnReasoning && part.origin == target
            is RedactedThinkingPart -> keepOwnReasoning && part.origin == target
            is OpaquePart -> part.origin == target
            else -> true
        }
    }
}

/**
 * 请求的思考档位对应的请求体补丁。档位不在表里时取最接近的一档，同距离取较低的（省钱）；
 * [effort] 为 null 或表为空时返回 null（不控制思考强度）。
 */
fun ModelProfile.patchFor(effort: Effort?): JsonObject? {
    if (effort == null || effortPatches.isEmpty()) return null
    effortPatches[effort]?.let { return it }
    val nearest = effortPatches.keys.minWith(
        compareBy<Effort> { kotlin.math.abs(it.ordinal - effort.ordinal) }.thenBy { it.ordinal },
    )
    return effortPatches.getValue(nearest)
}

/**
 * 深合并：[patch] 里的对象键递归合并进来，其余类型（含数组）直接覆盖。
 * 用于把思考补丁、供应商额外参数合并进编解码器组好的请求体。
 */
fun JsonObject.deepMerge(patch: JsonObject): JsonObject {
    val merged = LinkedHashMap(this)
    patch.forEach { (key, value) ->
        val existing = merged[key]
        merged[key] = if (existing is JsonObject && value is JsonObject) existing.deepMerge(value) else value
    }
    return JsonObject(merged)
}

/**
 * OpenAI 系（Chat Completions / Responses）把一条消息里的多个文本块压成一个字符串时用的分隔。
 * 内核把用户的话、hook 与记忆的 system-reminder 各自放成独立的文本块（Anthropic、Gemini 逐块发送，天然分开）；
 * 兼容端点（DeepSeek、Kimi、GLM 等）的 user content 多半只认字符串，不认 `[{type:text}]` 数组，
 * 所以不能改成数组，直接 `joinToString("")` 又会把「用户的话」和紧随的 `<system-reminder>` 粘成一段、边界消失。
 * 空行分隔：不依赖任何一个块自己以换行结尾。含图片的消息本来就是数组形态，每个文本块独立，不走这里。
 * 只用于用户消息；工具结果里多段文本的直接拼接是既有行为（快照钉着），这里不动。
 */
internal const val TEXT_BLOCK_SEPARATOR = "\n\n"

internal fun List<Part>.joinedText(): String =
    filterIsInstance<dev.min.agent.protocol.TextPart>().filter { it.text.isNotEmpty() }.joinToString(TEXT_BLOCK_SEPARATOR) { it.text }
