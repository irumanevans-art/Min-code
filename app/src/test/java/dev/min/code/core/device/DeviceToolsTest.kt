package dev.min.code.core.device

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolsTest {

    private val expectedToolNames = setOf(
        "device_ui_tree",
        "device_tap",
        "device_input",
        "device_swipe",
        "device_open_app",
        "device_back",
        // 截图是 UI 树读不到东西时的兜底（自绘界面、WebView），见 DeviceController.screenshot
        "device_screenshot",
    )

    @Test
    fun `所有设备工具都在且没有重名`() {
        val actualNames = DEVICE_TOOLS.map { it.name }.toSet()
        assertEquals(expectedToolNames, actualNames)
        assertEquals(expectedToolNames.size, DEVICE_TOOLS.size)
    }

    @Test
    fun `tool names are unique`() {
        val names = DEVICE_TOOLS.map { it.name }
        assertEquals("duplicate tool names in $names", names.size, names.toSet().size)
    }

    @Test
    fun `every input schema has type properties and required keys`() {
        DEVICE_TOOLS.forEach { tool ->
            val schema = tool.inputSchema
            assertTrue("${tool.name}: missing type", schema.containsKey("type"))
            assertTrue("${tool.name}: missing properties", schema.containsKey("properties"))
            assertTrue("${tool.name}: missing required", schema.containsKey("required"))

            assertEquals("${tool.name}: type", "object", schema.getValue("type").jsonPrimitive.content)
            assertTrue("${tool.name}: properties must be an object", schema.getValue("properties") is JsonObject)
            assertTrue("${tool.name}: required must be an array", schema.getValue("required") is JsonArray)
        }
    }

    @Test
    fun `required keys are declared in properties`() {
        DEVICE_TOOLS.forEach { tool ->
            val schema = tool.inputSchema
            val properties = schema.getValue("properties") as JsonObject
            val requiredKeys = (schema.getValue("required") as JsonArray).map { it.jsonPrimitive.content }

            requiredKeys.forEach { key ->
                assertTrue(
                    "${tool.name}: required key '$key' is not present in properties",
                    properties.containsKey(key),
                )
            }
        }
    }
}