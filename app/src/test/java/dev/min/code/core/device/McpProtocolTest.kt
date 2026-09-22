package dev.min.code.core.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    private fun decode(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    private fun idOf(line: String): JsonElement = decode(line).getValue("id")

    private fun resultOf(line: String): JsonObject = decode(line).getValue("result").jsonObject

    // -----------------------------------------------------------------------
    // id 原样回写：JSON-RPC 的 id 可能是整数也可能是字符串，转成字符串再回写会让
    // 对端回调表查不中、请求永远挂住。这几条是这个文件存在的理由。
    // -----------------------------------------------------------------------

    @Test
    fun `integer id round trips unchanged`() {
        val request = parseMcpRequest("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""")
        val frame = encodeToolsList((request as McpRequest.ListTools).id, emptyList())

        val written = idOf(frame) as JsonPrimitive
        assertEquals("7", written.content)
        assertFalse("整数 id 不能被写成字符串", written.isString)
        assertTrue(frame.contains("\"id\":7"))
    }

    @Test
    fun `string id round trips unchanged`() {
        val request = parseMcpRequest("""{"jsonrpc":"2.0","id":"req-42","method":"tools/list"}""")
        val frame = encodeToolsList((request as McpRequest.ListTools).id, emptyList())

        val written = idOf(frame) as JsonPrimitive
        assertEquals("req-42", written.content)
        assertTrue(written.isString)
        assertTrue(frame.contains("\"id\":\"req-42\""))
    }

    /** 数字 7 和字符串 "7" 必须保持是两种东西——归一的实现在这里露馅。 */
    @Test
    fun `number id and string id are not normalised into each other`() {
        val numeric = parseMcpRequest("""{"id":7,"method":"tools/list"}""") as McpRequest.ListTools
        val textual = parseMcpRequest("""{"id":"7","method":"tools/list"}""") as McpRequest.ListTools

        val numericFrame = encodeToolsList(numeric.id, emptyList())
        val textualFrame = encodeToolsList(textual.id, emptyList())

        assertTrue(numericFrame.contains("\"id\":7"))
        assertTrue(textualFrame.contains("\"id\":\"7\""))
        assertFalse((idOf(numericFrame) as JsonPrimitive).isString)
        assertTrue((idOf(textualFrame) as JsonPrimitive).isString)
    }

    @Test
    fun `id is preserved through tools call and error frames too`() {
        val call = parseMcpRequest(
            """{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"read_file"}}""",
        ) as McpRequest.CallTool
        assertTrue(encodeToolResult(call.id, "ok").contains("\"id\":\"call-1\""))
        assertTrue(encodeError(call.id, McpErrorCode.INTERNAL_ERROR, "boom").contains("\"id\":\"call-1\""))

        val numeric = parseMcpRequest("""{"id":9007199254740993,"method":"initialize"}""") as McpRequest.Initialize
        // 超过 double 精度的大整数也不能被浮点改写，字面量原样搬运
        assertTrue(
            encodeInitializeResult(numeric.id, "min-code", "1.0")
                .contains("\"id\":9007199254740993"),
        )
    }

    // -----------------------------------------------------------------------
    // 解析
    // -----------------------------------------------------------------------

    @Test
    fun `parses the three supported methods`() {
        val initialize = parseMcpRequest("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        val list = parseMcpRequest("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val call = parseMcpRequest(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"a.txt"}}}""",
        )

        assertTrue(initialize is McpRequest.Initialize)
        assertTrue(list is McpRequest.ListTools)
        val callTool = call as McpRequest.CallTool
        assertEquals("read_file", callTool.name)
        assertEquals("a.txt", callTool.arguments.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `unknown method parses into Unsupported with id and method kept`() {
        val request = parseMcpRequest("""{"jsonrpc":"2.0","id":"r-9","method":"resources/list"}""")

        val unsupported = request as McpRequest.Unsupported
        assertEquals("resources/list", unsupported.method)
        assertEquals(JsonPrimitive("r-9"), unsupported.id)
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(parseMcpRequest("""{"jsonrpc":"2.0","method":"""))
        assertNull(parseMcpRequest("not json at all"))
        assertNull(parseMcpRequest("{"))
        assertNull(parseMcpRequest(""))
        assertNull(parseMcpRequest("   "))
    }

    @Test
    fun `missing method returns null`() {
        assertNull(parseMcpRequest("""{"jsonrpc":"2.0","id":1}"""))
        // method 不是字符串也算缺
        assertNull(parseMcpRequest("""{"jsonrpc":"2.0","id":1,"method":123}"""))
        assertNull(parseMcpRequest("""{"jsonrpc":"2.0","id":1,"method":null}"""))
    }

    @Test
    fun `non object root returns null`() {
        assertNull(parseMcpRequest("""[{"jsonrpc":"2.0","id":1,"method":"tools/list"}]"""))
        assertNull(parseMcpRequest("42"))
        assertNull(parseMcpRequest(""""just a string""""))
    }

    @Test
    fun `request without id parses as notification with null id`() {
        val initialize = parseMcpRequest("""{"jsonrpc":"2.0","method":"initialize"}""")
        assertTrue(initialize is McpRequest.Initialize)
        assertNull((initialize as McpRequest.Initialize).id)

        val initialized = parseMcpRequest("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertNull((initialized as McpRequest.Unsupported).id)
    }

    /** 显式 `"id":null` 按没有 id 处理：没有可回写的键，应答出去也对不上。 */
    @Test
    fun `explicit json null id counts as a notification`() {
        val request = parseMcpRequest("""{"jsonrpc":"2.0","id":null,"method":"tools/list"}""")
        assertNull((request as McpRequest.ListTools).id)
    }

    @Test
    fun `tools call without arguments gets an empty json object instead of throwing`() {
        val call = parseMcpRequest(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file"}}""",
        ) as McpRequest.CallTool

        assertEquals("read_file", call.name)
        assertTrue(call.arguments.isEmpty())
        assertEquals(JsonObject(emptyMap()), call.arguments)
    }

    @Test
    fun `tools call with a non object arguments value falls back to empty`() {
        val call = parseMcpRequest(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":"oops"}}""",
        ) as McpRequest.CallTool

        assertTrue(call.arguments.isEmpty())
    }

    @Test
    fun `tools call without params does not throw and leaves name blank`() {
        val call = parseMcpRequest("""{"jsonrpc":"2.0","id":1,"method":"tools/call"}""") as McpRequest.CallTool

        // 空名字由上层翻成 -32602；这里的关键是请求没被丢掉、也没炸
        assertEquals("", call.name)
        assertTrue(call.arguments.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 编码
    // -----------------------------------------------------------------------

    @Test
    fun `initialize result carries protocol version and server info`() {
        val frame = encodeInitializeResult(JsonPrimitive(1), "min-code", "2.1.8")
        val root = decode(frame)
        val result = resultOf(frame)

        assertEquals("2.0", root.getValue("jsonrpc").jsonPrimitive.content)
        // 字面量写死：改协议版本时这条测试得有人看见
        assertEquals("2024-11-05", result.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals(MCP_PROTOCOL_VERSION, result.getValue("protocolVersion").jsonPrimitive.content)

        val serverInfo = result.getValue("serverInfo").jsonObject
        assertEquals("min-code", serverInfo.getValue("name").jsonPrimitive.content)
        assertEquals("2.1.8", serverInfo.getValue("version").jsonPrimitive.content)
        assertTrue(result.getValue("capabilities").jsonObject.containsKey("tools"))
    }

    @Test
    fun `tools list renders name description and input schema`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
        }
        val frame = encodeToolsList(JsonPrimitive("list-1"), listOf(McpTool("read_file", "读一个文件", schema)))

        val tools = resultOf(frame).getValue("tools").jsonArray
        assertEquals(1, tools.size)

        val tool = tools[0].jsonObject
        assertEquals("read_file", tool.getValue("name").jsonPrimitive.content)
        assertEquals("读一个文件", tool.getValue("description").jsonPrimitive.content)
        // inputSchema 原样透传，一个键都不许改
        assertEquals(schema, tool.getValue("inputSchema"))
    }

    @Test
    fun `empty tools list renders an empty array`() {
        val frame = encodeToolsList(JsonPrimitive(1), emptyList())
        assertTrue(resultOf(frame).getValue("tools").jsonArray.isEmpty())
        assertTrue(frame.contains("\"tools\":[]"))
    }

    @Test
    fun `successful tool result omits isError`() {
        val frame = encodeToolResult(JsonPrimitive(3), "done")
        val result = resultOf(frame)

        assertFalse("isError=false 时不该输出这个字段", result.containsKey("isError"))
        assertFalse(frame.contains("isError"))

        val content = result.getValue("content").jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("done", content[0].jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `failed tool result sets isError true`() {
        val frame = encodeToolResult(JsonPrimitive(3), "permission denied", isError = true)
        val result = resultOf(frame)

        assertEquals("true", result.getValue("isError").jsonPrimitive.content)
        assertEquals("permission denied", result.getValue("content").jsonArray[0]
            .jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `error response uses the jsonrpc error object`() {
        val frame = encodeError(JsonPrimitive(5), McpErrorCode.METHOD_NOT_FOUND, "Method not found: x")
        val root = decode(frame)

        assertFalse(root.containsKey("result"))
        val error = root.getValue("error").jsonObject
        assertEquals(-32601, error.getValue("code").jsonPrimitive.int)
        assertEquals("Method not found: x", error.getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `standard jsonrpc error codes`() {
        assertEquals(-32700, McpErrorCode.PARSE_ERROR)
        assertEquals(-32601, McpErrorCode.METHOD_NOT_FOUND)
        assertEquals(-32602, McpErrorCode.INVALID_PARAMS)
        assertEquals(-32603, McpErrorCode.INTERNAL_ERROR)
    }

    /** 逐行帧：单行、无缩进、末尾不带换行；字符串里的换行必须转义。 */
    @Test
    fun `frames are single line without trailing newline`() {
        val frames = listOf(
            encodeInitializeResult(JsonPrimitive(1), "min-code", "1.0"),
            encodeToolsList(JsonPrimitive(1), listOf(McpTool("t", "d", buildJsonObject { }))),
            encodeToolResult(JsonPrimitive(1), "多行\n文本\n"),
            encodeError(JsonPrimitive(1), McpErrorCode.PARSE_ERROR, "Parse error"),
        )

        frames.forEach { frame ->
            assertFalse(frame.contains("\n"))
            assertFalse(frame.contains("\r"))
            assertFalse(frame.contains("  "))
            assertTrue(frame.startsWith("{"))
            assertTrue(frame.endsWith("}"))
            assertEquals("2.0", decode(frame).getValue("jsonrpc").jsonPrimitive.content)
        }
    }

    @Test
    fun `newlines inside tool text are escaped and survive a round trip`() {
        val text = "第一行\n第二行\ttab"
        val frame = encodeToolResult(JsonPrimitive(1), text)

        assertFalse(frame.contains("\n"))
        val back = resultOf(frame).getValue("content").jsonArray[0]
            .jsonObject.getValue("text").jsonPrimitive.content
        assertEquals(text, back)
    }

    /** id 认不出来时写出 "id":null（JSON-RPC 对无法确定 id 的应答就是这么写的）。 */
    @Test
    fun `null id is written as json null`() {
        val root = decode(encodeToolResult(null, "x"))
        assertTrue(root.containsKey("id"))
        assertEquals(JsonNull, root["id"])
    }
}
