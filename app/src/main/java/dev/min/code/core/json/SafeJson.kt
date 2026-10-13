package dev.min.code.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// 不会抛的取值：kotlinx 的 `.jsonObject` / `.jsonArray` / `.jsonPrimitive` 是硬转换，
// 类型不符就抛 IllegalArgumentException。CLI / app-server 的协议会随版本漂移，
// 解析路径上一抛，读循环就死了（进程还活着，App 却不再读）——所以一律 `as?` 降级成 null，
// 宁可少解析一个字段也不要炸掉整条流。Claude 与 Codex 两条协议解析共用这一份。
//
// 中转层（core/relay/RelayJson.kt）还有一份自己的：它的 int / long 走 String.toIntOrNull，
// 不是 kotlinx 的 intOrNull，对「1.0」这类字面量没验证过取舍一致，所以没有并进来。

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
