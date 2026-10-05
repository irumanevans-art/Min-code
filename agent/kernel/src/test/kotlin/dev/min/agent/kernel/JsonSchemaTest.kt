package dev.min.agent.kernel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 最小 JSON Schema 校验器：任务单点名的关键字逐一覆盖，不认识的关键字忽略 */
class JsonSchemaTest {
    private fun validate(schema: JsonObject, value: String): List<String> =
        JsonSchema.validate(schema, dev.min.agent.protocol.AgentJson.parseToJsonElement(value))

    private fun errorsOf(schema: JsonObject, value: String) = validate(schema, value).joinToString("\n")

    // ---- type ----

    @Test fun `type rejects mismatches with the actual type named`() {
        val schema = buildJsonObject { put("type", "string") }
        assertEquals("类型必须是 string，实际是 number", errorsOf(schema, "12").substringAfter("\$: "))
        assertTrue(validate(schema, "\"hi\"").isEmpty())
        assertTrue("整数也是 number，但不是 string", validate(schema, "true").isNotEmpty())
    }

    @Test fun `integer accepts whole numbers in any notation and rejects fractions`() {
        val schema = buildJsonObject { put("type", "integer") }
        assertTrue(validate(schema, "3").isEmpty())
        assertTrue(validate(schema, "1e2").isEmpty())
        assertTrue(validate(schema, "3.0").isEmpty())
        assertTrue(validate(schema, "3.5").isNotEmpty())
        assertTrue(validate(schema, "\"3\"").isNotEmpty())
    }

    @Test fun `type can be a list - any one match suffices`() {
        val schema = buildJsonObject { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
        assertTrue(validate(schema, "\"x\"").isEmpty())
        assertTrue(validate(schema, "null").isEmpty())
        assertTrue(validate(schema, "5").isNotEmpty())
    }

    // ---- object ----

    @Test fun `required and properties report the offending paths`() {
        val schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("age") { put("type", "integer"); put("minimum", 0) }
            }
            putJsonArray("required") { add(JsonPrimitive("name")) }
        }
        val errors = validate(schema, """{"age": -1}""")
        assertEquals("\$: 缺少必填字段 name\n$.age: 值 -1.0 小于 minimum 0.0", errors.joinToString("\n"))
        assertTrue(validate(schema, """{"name": "Min", "age": 1}""").isEmpty())
    }

    @Test fun `additionalProperties false rejects extras and a schema validates them`() {
        val closed = buildJsonObject {
            putJsonObject("properties") { putJsonObject("a") { put("type", "string") } }
            put("additionalProperties", false)
        }
        assertTrue(validate(closed, """{"a": "x"}""").isEmpty())
        assertEquals("\$.b: schema 不允许这个额外字段（additionalProperties: false）", errorsOf(closed, """{"a": "x", "b": 1}"""))
        val typed = buildJsonObject {
            putJsonObject("properties") { putJsonObject("a") { put("type", "string") } }
            putJsonObject("additionalProperties") { put("type", "boolean") }
        }
        assertTrue(validate(typed, """{"a": "x", "flag": true}""").isEmpty())
        assertEquals("\$.b: 类型必须是 boolean，实际是 number", errorsOf(typed, """{"a": "x", "b": 2}"""))
    }

    // ---- array ----

    @Test fun `items validates each element and minItems maxItems bound the count`() {
        val schema = buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("minItems", 1)
            put("maxItems", 2)
        }
        assertEquals("\$[1]: 类型必须是 string，实际是 number", errorsOf(schema, """["a", 2]"""))
        assertEquals("\$: 元素个数 0 少于 minItems 1.0", errorsOf(schema, "[]"))
        assertEquals("\$: 元素个数 3 多于 maxItems 2.0", errorsOf(schema, """["a", "b", "c"]"""))
    }

    // ---- string bounds ----

    @Test fun `minLength and maxLength apply to strings only`() {
        val schema = buildJsonObject { put("type", "string"); put("minLength", 2); put("maxLength", 3) }
        assertTrue(validate(schema, "\"ab\"").isEmpty())
        assertEquals("\$: 长度 1 少于 minLength 2.0", errorsOf(schema, "\"a\""))
        assertEquals("\$: 长度 4 多于 maxLength 3.0", errorsOf(schema, "\"abcd\""))
    }

    // ---- enum / const ----

    @Test fun `enum and const compare structurally`() {
        val enum = buildJsonObject { putJsonArray("enum") { add(JsonPrimitive("red")); add(JsonPrimitive("blue")) } }
        assertTrue(validate(enum, "\"blue\"").isEmpty())
        assertEquals("\$: 必须是 \"red\"、\"blue\" 之一，实际是 \"green\"", errorsOf(enum, "\"green\""))
        val const = buildJsonObject { putJsonObject("const") { put("k", 1) } }
        assertTrue(validate(const, """{"k": 1}""").isEmpty())
        assertTrue(validate(const, """{"k": 2}""").isNotEmpty())
    }

    // ---- combinators ----

    @Test fun `anyOf oneOf allOf combine subschemas`() {
        val anyOf = buildJsonObject {
            putJsonArray("anyOf") {
                add(buildJsonObject { put("type", "string") })
                add(buildJsonObject { put("type", "integer") })
            }
        }
        assertTrue(validate(anyOf, "\"x\"").isEmpty())
        assertTrue(validate(anyOf, "3").isEmpty())
        assertEquals("\$: 不满足 anyOf 的任何一个分支", errorsOf(anyOf, "3.5"))
        val oneOf = buildJsonObject {
            putJsonArray("oneOf") {
                add(buildJsonObject { put("type", "number") })
                add(buildJsonObject { put("type", "string") })
            }
        }
        assertTrue("恰好一个分支成立就通过", validate(oneOf, "1").isEmpty())
        assertEquals("\$: oneOf 要求恰好一个分支成立，实际成立 0 个", errorsOf(oneOf, "true"))
        val overlap = buildJsonObject {
            putJsonArray("oneOf") {
                add(buildJsonObject { put("type", "number") })
                add(buildJsonObject { put("minimum", 0) })
            }
        }
        assertEquals("没有 type 的子 schema 对什么都能过，1 在这里撞了两个分支", "\$: oneOf 要求恰好一个分支成立，实际成立 2 个", errorsOf(overlap, "1"))
        val allOf = buildJsonObject {
            putJsonArray("allOf") {
                add(buildJsonObject { put("minimum", 0) })
                add(buildJsonObject { put("maximum", 10) })
            }
        }
        assertTrue(validate(allOf, "5").isEmpty())
        assertEquals("\$.allOf[1]: 值 11.0 大于 maximum 10.0", errorsOf(allOf, "11"))
    }

    // ---- 不认识的关键字忽略 ----

    @Test fun `unknown keywords are ignored without failing a valid value`() {
        val schema = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", "t"); put("description", "d"); put("format", "date-time")
            put("pattern", "^a"); put("default", 0)
            putJsonArray("examples") { add(JsonPrimitive(1)) }
            putJsonObject("\$defs") { putJsonObject("x") { put("type", "string") } }
            putJsonObject("definitions") { putJsonObject("y") { put("type", "string") } }
            put("minProperties", 1)
            putJsonArray("required") { add(JsonPrimitive("a")) }
            putJsonObject("properties") { putJsonObject("a") { put("type", "integer") } }
        }
        assertTrue("认识的照常生效", validate(schema, """{"a": 1}""").isEmpty())
        assertTrue("不认识的不拦", validate(schema, """{"a": 2}""").isEmpty())
        assertEquals("认识的照常报错", "\$.a: 类型必须是 integer，实际是 string", errorsOf(schema, """{"a": "x"}"""))
    }

    @Test fun `malformed keyword shapes are ignored rather than crashing`() {
        val schema = buildJsonObject {
            put("required", "a") // 不是字符串数组：忽略
            putJsonObject("properties") { put("a", JsonPrimitive("不是子 schema")) } // 子项不是 schema：忽略
            put("items", JsonArray(emptyList())) // 不是子 schema：忽略
            putJsonArray("enum") { add(JsonObject(mapOf("anything" to JsonPrimitive(true)))); add(JsonPrimitive("ok")) }
        }
        assertTrue("形状坏的关键字不拦合法值", validate(schema, """{"anything": true}""").isEmpty())
        assertTrue(validate(schema, "\"ok\"").isEmpty())
        assertTrue("enum 自己照常生效", validate(schema, "\"other\"").isNotEmpty())
    }
}
