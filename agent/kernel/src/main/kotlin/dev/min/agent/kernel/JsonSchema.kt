package dev.min.agent.kernel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 最小 JSON Schema 校验器（`--json-schema` 结构化输出用）。覆盖的关键字：
 * `type`（含类型数组）、`properties` / `required`、`items`、`enum` / `const`、`additionalProperties`
 * （false 或子 schema）、`minItems` / `maxItems`、`minLength` / `maxLength`、`minimum` / `maximum`、
 * `anyOf` / `oneOf` / `allOf`。不认识的关键字一律忽略（`$schema`、`title`、`description`、`format`、
 * `pattern`、`default`、`examples`、`$defs`……完整清单见当期汇报）——跨供应商的工具式约束只需要
 * 这些结构关键字；语义类的（format、pattern）各家实现参差，宁可少拦也不误拦。
 *
 * schema 本身写得不合法（关键字的形状不对，比如 `required` 不是字符串数组）同样忽略那个关键字：
 * 校验器的职责是「不该放的挡下来」，不是替人改 schema。
 *
 * 返回不合格的路径与原因（空列表 = 合格）。路径用 `$.a[0].b` 的写法，错误结果原样给模型看，让它能改对。
 */
internal object JsonSchema {

    fun validate(schema: JsonObject, value: JsonElement): List<String> = validate(schema, value, "$")

    private fun validate(schema: JsonObject, value: JsonElement, path: String): List<String> {
        val errors = mutableListOf<String>()
        schema["allOf"]?.schemaList()?.forEachIndexed { i, s -> errors += validate(s, value, "$path.allOf[$i]") }
        schema["anyOf"]?.schemaList()?.takeIf { it.isNotEmpty() }?.let { options ->
            if (options.none { validate(it, value, path).isEmpty() }) errors += "$path: 不满足 anyOf 的任何一个分支"
        }
        schema["oneOf"]?.schemaList()?.takeIf { it.isNotEmpty() }?.let { options ->
            val matched = options.count { validate(it, value, path).isEmpty() }
            if (matched != 1) errors += "$path: oneOf 要求恰好一个分支成立，实际成立 $matched 个"
        }
        schema["enum"]?.let { options ->
            if (options is JsonArray && options.none { it == value }) {
                errors += "$path: 必须是 ${options.joinToString("、") { it.toString() }} 之一，实际是 $value"
            }
        }
        schema["const"]?.let { if (it != value) errors += "$path: 必须恒等于 $it，实际是 $value" }
        typeOf(schema["type"])?.let { allowed ->
            if (allowed.none { matchesType(it, value) }) {
                errors += "$path: 类型必须是 ${allowed.joinToString(" 或 ")}，实际是 ${typeName(value)}"
            }
        }
        if (value is JsonObject) errors += validateObject(schema, value, path)
        if (value is JsonArray) errors += validateArray(schema, value, path)
        if (value is JsonPrimitive || value is JsonNull) errors += validatePrimitive(schema, value, path)
        return errors
    }

    private fun validateObject(schema: JsonObject, value: JsonObject, path: String): List<String> {
        val errors = mutableListOf<String>()
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.forEach { key -> if (key !in value) errors += "$path: 缺少必填字段 $key" }
        for ((key, sub) in properties) {
            val member = value[key] ?: continue
            errors += validate(sub as? JsonObject ?: continue, member, "$path.$key")
        }
        when (val extra = schema["additionalProperties"]) {
            is JsonPrimitive -> if (extra.content == "false" && !extra.isString) {
                value.keys.filterNot { it in properties }.forEach { key -> errors += "$path.$key: schema 不允许这个额外字段（additionalProperties: false）" }
            }
            is JsonObject -> value.keys.filterNot { it in properties }.forEach { key ->
                errors += validate(extra, value[key]!!, "$path.$key")
            }
            else -> Unit
        }
        return errors
    }

    private fun validateArray(schema: JsonObject, value: JsonArray, path: String): List<String> {
        val errors = mutableListOf<String>()
        val items = schema["items"] as? JsonObject
        if (items != null) value.forEachIndexed { i, element -> errors += validate(items, element, "$path[$i]") }
        minMax(schema, "minItems", value.size) { n, size -> size < n }?.let { errors += "$path: 元素个数 ${value.size} 少于 minItems $it" }
        minMax(schema, "maxItems", value.size) { n, size -> size > n }?.let { errors += "$path: 元素个数 ${value.size} 多于 maxItems $it" }
        return errors
    }

    private fun validatePrimitive(schema: JsonObject, value: JsonElement, path: String): List<String> {
        val errors = mutableListOf<String>()
        if (value is JsonPrimitive && value.isString) {
            val length = value.content.length
            minMax(schema, "minLength", length) { n, len -> len < n }?.let { errors += "$path: 长度 $length 少于 minLength $it" }
            minMax(schema, "maxLength", length) { n, len -> len > n }?.let { errors += "$path: 长度 $length 多于 maxLength $it" }
        }
        number(value)?.let { n ->
            minMax(schema, "minimum", n) { bound, v -> v < bound }?.let { errors += "$path: 值 $n 小于 minimum $it" }
            minMax(schema, "maximum", n) { bound, v -> v > bound }?.let { errors += "$path: 值 $n 大于 maximum $it" }
        }
        return errors
    }

    /** 取数值型关键字（minItems / maximum……）：不是整数就当没写 */
    private fun minMax(schema: JsonObject, key: String, actual: Number, over: (Double, Double) -> Boolean): Double? {
        val raw = schema[key] as? JsonPrimitive ?: return null
        if (raw.isString) return null
        val bound = raw.content.toDoubleOrNull() ?: return null
        return if (over(bound, actual.toDouble())) bound else null
    }

    /** `type` 的合法写法：字符串，或字符串数组（满足其一即可） */
    private fun typeOf(type: JsonElement?): List<String>? = when (type) {
        is JsonPrimitive -> if (type.isString) listOf(type.content) else null
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> (value as? JsonPrimitive)?.takeIf { !it.isString }?.content in setOf("true", "false")
        "null" -> value is JsonNull
        "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
        "integer" -> number(value)?.let { it == kotlin.math.floor(it) } == true
        else -> true // 不认识的类型名不拦：宁少拦不误拦
    }

    private fun typeName(value: JsonElement): String = when (value) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "string"
            value.content == "true" || value.content == "false" -> "boolean"
            else -> "number"
        }
    }

    private fun number(value: JsonElement): Double? {
        if (value !is JsonPrimitive || value.isString) return null
        return value.content.toDoubleOrNull()
    }

    /** 关键字值应是「子 schema 数组」；形状不对返回空列表（按忽略处理） */
    private fun JsonElement.schemaList(): List<JsonObject> =
        (this as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
}
