package dev.min.code.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 协议转换层共用的 JSON 工具。
 *
 * 转换函数一律吃 [JsonObject] / 吐 [JsonObject]，不引入 data class ——
 * 上游的字段集每个中转都不一样，用 data class 等于声明「我认得这些、其余丢掉」，
 * 而丢掉一个我们还不认识的字段，往往就是丢掉工具调用或图片。
 */
internal val relayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? =
    this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? =
    this[key] as? JsonArray

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        ?: (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() }?.contentOrNull?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> if (v != null && v !is JsonNull) put(k, v) }
}

internal fun jsonArrayOf(vararg elements: JsonElement): JsonArray = buildJsonArray {
    elements.forEach { add(it) }
}

internal fun stringPrim(value: String): JsonPrimitive = JsonPrimitive(value)
internal fun numberPrim(value: Number): JsonPrimitive = JsonPrimitive(value)
internal fun boolPrim(value: Boolean): JsonPrimitive = JsonPrimitive(value)

/** 把任意 JsonElement 尽量折成可读的字符串，错误透传时用 */
internal fun JsonElement.stringifyLoose(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}
