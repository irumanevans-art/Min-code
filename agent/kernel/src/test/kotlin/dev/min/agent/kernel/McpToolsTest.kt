package dev.min.agent.kernel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 工具的纯函数部分（docs/agent/detail/MCP设计.md §5.1、§5.2、§6） */
class McpToolsTest {

    @Test
    fun `names are sanitized and prefixed`() {
        val names = McpNames.assign(listOf("my.server" to "read file", "github" to "create_issue"))
        assertEquals("mcp__my_server__read_file", names.getValue("my.server" to "read file"))
        assertEquals("mcp__github__create_issue", names.getValue("github" to "create_issue"))
    }

    @Test
    fun `long names are cut to 64 characters with a stable hash`() {
        val pair = "a-very-long-server-name-that-keeps-going" to "and-an-even-longer-tool-name-for-good-measure"
        val first = McpNames.assign(listOf(pair)).getValue(pair)
        assertEquals(64, first.length)
        assertTrue(first, Regex("_[0-9a-f]{8}$").containsMatchIn(first))
        assertEquals("同一组工具永远得到同一个名字", first, McpNames.assign(listOf(pair)).getValue(pair))
    }

    @Test
    fun `names that collide after sanitizing get a hash, the first in sorted order keeps the clean name`() {
        val names = McpNames.assign(listOf("srv" to "a.b", "srv" to "a_b"))
        assertEquals("mcp__srv__a_b", names.getValue("srv" to "a.b"))
        val other = names.getValue("srv" to "a_b")
        assertTrue(other, other.startsWith("mcp__srv__a_b_") && other.length <= 64)
        assertEquals(2, names.values.toSet().size)
    }

    private fun schema(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a root allOf is flattened and keeps every branch's required`() {
        val shaped = McpShaping.shape(
            schema("""{"allOf":[{"properties":{"a":{"type":"string"}},"required":["a"]},{"properties":{"b":{"type":"number"}},"required":["b"]}]}"""),
        ) as McpShaping.Shaped.Ok
        assertEquals("object", shaped.schema["type"].toString().trim('"'))
        assertEquals(setOf("a", "b"), (shaped.schema["properties"] as JsonObject).keys)
        assertEquals(listOf("\"a\"", "\"b\""), (shaped.schema["required"] as JsonArray).map { it.toString() })
        assertEquals(null, shaped.descriptionPrefix)
    }

    @Test
    fun `a root anyOf is flattened, required only goes into the description`() {
        val shaped = McpShaping.shape(
            schema("""{"type":"object","anyOf":[{"properties":{"id":{"type":"string"}},"required":["id"]},{"properties":{"name":{"type":"string"}},"required":["name"]}]}"""),
        ) as McpShaping.Shaped.Ok
        assertFalse("anyOf 不强制 required", shaped.schema.containsKey("required"))
        assertFalse(shaped.schema.containsKey("anyOf"))
        assertTrue(shaped.descriptionPrefix!!, shaped.descriptionPrefix!!.contains("id") && shaped.descriptionPrefix!!.contains("name"))
    }

    @Test
    fun `a schema without a type becomes an object, bad property names and non-object roots are excluded`() {
        val ok = McpShaping.shape(schema("""{"properties":{"q":{"type":"string"}}}""")) as McpShaping.Shaped.Ok
        assertEquals("\"object\"", ok.schema["type"].toString())
        assertTrue(McpShaping.shape(schema("""{"type":"object","properties":{"bad name":{}}}""")) is McpShaping.Shaped.Excluded)
        assertTrue(McpShaping.shape(schema("""{"type":"array"}""")) is McpShaping.Shaped.Excluded)
    }

    @Test
    fun `descriptions are cut at 2048 characters`() {
        assertEquals(2049, McpShaping.truncate("x".repeat(5000)).length)
        assertEquals("short", McpShaping.truncate("short"))
    }

    @Test
    fun `agent patterns match whole servers, wildcards and exact tools`() {
        assertTrue(matchesMcpPattern("mcp__github", "mcp__github__create_issue"))
        assertTrue(matchesMcpPattern("mcp__github__*", "mcp__github__create_issue"))
        assertTrue(matchesMcpPattern("mcp__github__create_issue", "mcp__github__create_issue"))
        assertTrue(matchesMcpPattern("mcp__*", "mcp__anything__x"))
        assertFalse(matchesMcpPattern("mcp__git", "mcp__github__create_issue"))
        assertFalse(matchesMcpPattern("mcp__github__create", "mcp__github__create_issue"))
    }
}
