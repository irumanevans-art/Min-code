package dev.min.agent.llm.gemini

import kotlinx.serialization.json.*
import java.util.Locale

/** 白名单对应原生 Schema 而非 parametersJsonSchema，防止 JSON Schema 新字段让整次工具请求被拒。 */
internal fun projectGeminiSchema(schema: JsonObject): JsonObject = SchemaProjection(schema).project(schema)

private class SchemaProjection(private val root: JsonObject) {
    private val lengths = setOf("minItems", "maxItems", "minProperties", "maxProperties", "minLength", "maxLength")
    private val scalar = setOf("format", "title", "description", "nullable", "required", "pattern", "example",
        "propertyOrdering", "default", "minimum", "maximum")

    fun project(schema: JsonObject, stack: Set<String> = emptySet()): JsonObject {
        val ref = schema.str("\$ref")
        if (ref != null) {
            require(ref.startsWith("#/")) { "Gemini 工具 schema 只支持本地 JSON Pointer 引用" }
            require(ref !in stack) { "Gemini 工具 schema 不支持循环引用" }
            val resolved = ref.removePrefix("#/").split('/').fold(root as JsonElement) { node, token ->
                val key = token.replace("~1", "/").replace("~0", "~")
                when (node) {
                    is JsonObject -> node[key]
                    is JsonArray -> key.toIntOrNull()?.let { node.getOrNull(it) }
                    else -> null
                } ?: throw IllegalArgumentException("Gemini 工具 schema 的引用不存在")
            } as? JsonObject ?: throw IllegalArgumentException("Gemini 工具 schema 的引用不是对象")
            return project(JsonObject(resolved + schema.filterKeys { it != "\$ref" }), stack + ref)
        }
        val out = linkedMapOf<String, JsonElement>()
        val typeArray = schema["type"] as? JsonArray
        require(typeArray == null || typeArray.isNotEmpty()) { "Gemini schema type 数组不能为空" }
        val typeUnion = typeArray?.map { type(it) }?.filter { it != "NULL" }?.distinct()?.size?.let { it > 1 } ?: false
        require(listOf(typeUnion, "anyOf" in schema, "oneOf" in schema).count { it } <= 1) {
            "Gemini schema 存在不能无损投影的多个 union"
        }
        schema.forEach { (key, value) -> when {
            key == "type" -> {
                val types = if (value is JsonArray) value.map { type(it) } else listOf(type(value))
                val nonNull = types.filter { it != "NULL" }.distinct()
                if ("NULL" in types && nonNull.isNotEmpty()) out["nullable"] = JsonPrimitive(true)
                when {
                    nonNull.size == 1 -> out["type"] = JsonPrimitive(nonNull.single())
                    nonNull.isEmpty() -> out["type"] = JsonPrimitive("NULL")
                    else -> out["anyOf"] = JsonArray(nonNull.map { JsonObject(mapOf("type" to JsonPrimitive(it))) })
                }
            }
            key == "enum" || key == "const" -> {
                val values = if (key == "const") listOf(value) else (value as? JsonArray)?.toList()
                    ?: throw IllegalArgumentException("Gemini schema enum 必须是数组")
                require(values.all { it is JsonPrimitive && it.isString }) { "Gemini OpenAPI schema 只支持字符串 enum" }
                out["enum"] = JsonArray(values)
            }
            key == "properties" -> out[key] = JsonObject((value as? JsonObject
                ?: throw IllegalArgumentException("Gemini schema properties 必须是对象")).mapValues { (_, child) ->
                project(child as? JsonObject ?: throw IllegalArgumentException("Gemini schema property 必须是对象"), stack)
            })
            key == "items" -> out[key] = project(value as? JsonObject
                ?: throw IllegalArgumentException("Gemini schema 不支持 tuple items"), stack)
            key == "anyOf" || key == "oneOf" -> {
                require("anyOf" !in out) { "Gemini schema 存在不能无损投影的多个 union" }
                out["anyOf"] = JsonArray((value as? JsonArray
                    ?: throw IllegalArgumentException("Gemini schema union 必须是数组")).map {
                    project(it as? JsonObject ?: throw IllegalArgumentException("Gemini schema union 元素必须是对象"), stack)
                })
            }
            key in lengths -> {
                val number = (value as? JsonPrimitive)?.longOrNull
                require(number != null && number >= 0) { "Gemini schema 数量限制必须是非负整数" }
                out[key] = JsonPrimitive(number.toString())
            }
            key in scalar -> if (key != "nullable" || out[key] != JsonPrimitive(true)) out[key] = value
        } }
        return JsonObject(out)
    }

    private fun type(value: JsonElement): String {
        val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase(Locale.ROOT)
        require(name in setOf("STRING", "NUMBER", "INTEGER", "BOOLEAN", "ARRAY", "OBJECT", "NULL")) {
            "Gemini schema type 不是 OpenAPI 类型"
        }
        return name!!
    }
}
