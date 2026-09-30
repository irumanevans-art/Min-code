package dev.min.agent.tools

import dev.min.agent.spi.EditStyle
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五个工具的 schema 与描述的共同约束（规格 §1）：只用各家都认的子集，required 都在 properties 里 */
class ToolSchemaTest {
    private val tools: List<Tool<*>> = listOf(ReadTool, WriteTool, GlobTool, GrepTool, BashTool)
    private val profile = HarnessProfile(ModelFamily.Claude, EditStyle.StrReplace)

    @Test
    fun `名字与 Claude Code 一致`() {
        assertEquals(listOf("Read", "Write", "Glob", "Grep", "Bash"), tools.map { it.name })
    }

    @Test
    fun `schema 是 object，required 都在 properties 里，且不含 oneOf 与 ref`() {
        for (t in tools) {
            val s = t.inputSchema
            assertEquals(t.name, JsonPrimitive("object"), s["type"])
            val props = s["properties"] as JsonObject
            val required = (s["required"] as JsonArray).map { (it as JsonPrimitive).content }
            assertTrue(t.name, required.isNotEmpty())
            assertTrue(t.name, props.keys.containsAll(required))
            val text = s.toString()
            assertFalse(t.name, text.contains("oneOf") || text.contains("\$ref") || text.contains("anyOf"))
            props.forEach { (k, v) -> assertTrue("${t.name}.$k 缺 description", (v as JsonObject).containsKey("description")) }
        }
    }

    @Test
    fun `每个工具都有描述`() {
        tools.forEach { assertTrue(it.name, it.description(profile).length > 20) }
    }
}
