package dev.min.agent.llm.gemini

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 每个被删除关键字独立成为测试，避免大快照漏掉投影的某一个分支。 */
@RunWith(Parameterized::class)
class GeminiSchemaKeywordTest(private val keyword: String, private val value: String) {
    @Test fun `unsupported keyword is removed recursively while property with same name survives`() {
        val schema = obj("""{"type":"object","$keyword":$value,"properties":{"$keyword":{"type":"string","$keyword":$value}}}""")
        assertEquals(obj("""{"type":"OBJECT","properties":{"$keyword":{"type":"STRING"}}}"""), projectGeminiSchema(schema))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<String>> = listOf(
            "\$schema" to "\"https://json-schema.org/draft/2020-12/schema\"", "\$id" to "\"id\"", "${'$'}defs" to "{}", "definitions" to "{}",
            "additionalProperties" to "false", "examples" to "[]", "allOf" to "[]", "not" to "{}", "if" to "{}", "then" to "{}", "else" to "{}",
            "dependentRequired" to "{}", "dependentSchemas" to "{}", "patternProperties" to "{}", "unevaluatedProperties" to "false",
            "prefixItems" to "[]", "contains" to "{}", "minContains" to "1", "maxContains" to "2", "uniqueItems" to "true", "multipleOf" to "2",
            "exclusiveMinimum" to "1", "exclusiveMaximum" to "9", "readOnly" to "true", "writeOnly" to "true", "deprecated" to "true", "x-custom" to "true",
        ).map { arrayOf(it.first, it.second) }
    }
}

class GeminiSchemaTest {
    @Test fun `all native scalar keywords are preserved`() {
        val source = obj("""{"type":"string","format":"date","title":"t","description":"d","nullable":true,"enum":["a","b"],"pattern":"a+","example":"a","default":"a","propertyOrdering":["a"],"required":["a"],"minimum":1,"maximum":9}""")
        assertEquals(JsonObject(source + ("type" to JsonPrimitive("STRING"))), projectGeminiSchema(source))
    }
    @Test fun `all six int64 fields are decimal strings`() {
        for (key in listOf("minItems", "maxItems", "minProperties", "maxProperties", "minLength", "maxLength")) {
            assertEquals(obj("""{"type":"ARRAY","$key":"2"}"""), projectGeminiSchema(obj("""{"type":"array","$key":2}""")))
            assertEquals(obj("""{"type":"ARRAY","$key":"2"}"""), projectGeminiSchema(obj("""{"type":"array","$key":"2"}""")))
        }
    }
    @Test fun `native types are uppercased`() {
        for (type in listOf("string", "integer", "number", "boolean", "array", "object", "null")) {
            assertEquals(obj("""{"type":"${type.uppercase()}"}"""), projectGeminiSchema(obj("""{"type":"$type"}""")))
        }
    }
    @Test fun `nullable type arrays support either keyword order`() {
        for (raw in listOf("""{"type":["string","null"],"nullable":false}""", """{"nullable":false,"type":["null","string"]}""")) {
            assertEquals(obj("""{"type":"STRING","nullable":true}"""), projectGeminiSchema(obj(raw)))
        }
    }
    @Test fun `multi type union and oneOf become anyOf`() {
        assertEquals(obj("""{"anyOf":[{"type":"STRING"},{"type":"INTEGER"}],"nullable":true}"""), projectGeminiSchema(obj("""{"type":["string","integer","null"]}""")))
        assertEquals(obj("""{"anyOf":[{"type":"STRING"},{"type":"INTEGER"}]}"""), projectGeminiSchema(obj("""{"oneOf":[{"type":"string","additionalProperties":false},{"type":"integer"}]}""")))
    }
    @Test fun `const string becomes enum`() {
        assertEquals(obj("""{"type":"STRING","enum":["fixed"]}"""), projectGeminiSchema(obj("""{"type":"string","const":"fixed"}""")))
    }
    @Test fun `numeric boolean and null enum or const fail without changing types`() {
        for (raw in listOf("""{"type":"integer","enum":[1]}""", """{"type":"boolean","const":true}""", """{"enum":[null]}""")) {
            assertThrows(IllegalArgumentException::class.java) { projectGeminiSchema(obj(raw)) }
        }
    }
    @Test fun `local references expand escaped pointer and siblings deterministically`() {
        val raw = """{"type":"object","${'$'}defs":{"a/b":{"type":"string","description":"old"}},"properties":{"x":{"${'$'}ref":"#/${'$'}defs/a~1b","description":"new"},"y":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/a~1b"}}}}"""
        val schema = obj(raw)
        val expected = obj("""{"type":"OBJECT","properties":{"x":{"type":"STRING","description":"new"},"y":{"type":"ARRAY","items":{"type":"STRING","description":"old"}}}}""")
        assertEquals(expected, projectGeminiSchema(schema))
        assertEquals(projectGeminiSchema(schema).toString(), projectGeminiSchema(schema).toString())
        assertTrue(schema.containsKey("${'$'}defs"))
    }
    @Test fun `references reject recursive external missing and scalar targets`() {
        val schemas = listOf(
            """{"${'$'}ref":"https://e/schema"}""", """{"${'$'}ref":"#/missing"}""", """{"${'$'}defs":{"s":1},"${'$'}ref":"#/${'$'}defs/s"}""",
            """{"${'$'}defs":{"s":{"${'$'}ref":"#/${'$'}defs/s"}},"${'$'}ref":"#/${'$'}defs/s"}""",
        )
        schemas.forEach { assertThrows(IllegalArgumentException::class.java) { projectGeminiSchema(obj(it)) } }
    }
    @Test fun `nested anyOf items properties are projected without sorting`() {
        val schema = obj("""{"type":"object","properties":{"z":{"anyOf":[{"type":"array","items":{"type":"string"}},{"type":"null"}]},"a":{"type":"string"}},"required":["z","a"]}""")
        assertEquals("""{"type":"OBJECT","properties":{"z":{"anyOf":[{"type":"ARRAY","items":{"type":"STRING"}},{"type":"NULL"}]},"a":{"type":"STRING"}},"required":["z","a"]}""", projectGeminiSchema(schema).toString())
    }
    @Test fun `invalid native shapes fail locally`() {
        for (raw in listOf("""{"type":"wat"}""", """{"type":1}""", """{"items":[]}""", """{"properties":[]}""", """{"anyOf":{}}""", """{"minItems":-1}""", """{"maxLength":1.5}""")) {
            assertThrows(IllegalArgumentException::class.java) { projectGeminiSchema(obj(raw)) }
        }
    }
    @Test fun `empty and overlapping unions are rejected in either key order`() {
        for (raw in listOf("""{"type":[]}""", """{"type":["string","integer"],"anyOf":[]}""", """{"anyOf":[],"type":["string","integer"]}""", """{"anyOf":[],"oneOf":[]}""")) {
            assertThrows(IllegalArgumentException::class.java) { projectGeminiSchema(obj(raw)) }
        }
    }
}
