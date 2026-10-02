package dev.min.agent.llm.gemini

import dev.min.agent.protocol.AgentJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun parseObject(raw: String): JsonObject? = try {
    AgentJson.parseToJsonElement(raw) as? JsonObject
} catch (_: SerializationException) {
    null
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.tokens(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0)
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal const val RAW_PARTS = "geminiParts"
internal const val CALL_IDS = "geminiCallIds"
internal const val REJECTED_PARTS = "geminiRejectedParts"
