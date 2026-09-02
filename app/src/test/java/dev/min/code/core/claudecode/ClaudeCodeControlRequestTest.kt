package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内控制请求（对齐 Claude Desktop 底栏）的报文断言。
 * subtype 名称与字段全部取自官方 CLI v2.1.246 二进制里的 zod schema。
 */
class ClaudeCodeControlRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun request(frame: String) = json.parseToJsonElement(frame).jsonObject

    private fun subtypeOf(frame: String) =
        request(frame)["request"]!!.jsonObject["subtype"]?.jsonPrimitive?.contentOrNull

    @Test
    fun `every control request carries type and request_id`() {
        val frames = listOf(
            encodeClaudeCodeInitialize("r1"),
            encodeClaudeCodeListModels("r2"),
            encodeClaudeCodeGetPlan("r3"),
            encodeClaudeCodeGetContextUsage("r4"),
            encodeClaudeCodeGetSessionCost("r5"),
        )
        frames.forEachIndexed { i, frame ->
            val obj = request(frame)
            assertEquals("control_request", obj["type"]?.jsonPrimitive?.contentOrNull)
            assertEquals("r${i + 1}", obj["request_id"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `subtypes match the CLI schema names`() {
        assertEquals("initialize", subtypeOf(encodeClaudeCodeInitialize("r")))
        assertEquals("list_models", subtypeOf(encodeClaudeCodeListModels("r")))
        assertEquals("get_plan", subtypeOf(encodeClaudeCodeGetPlan("r")))
        assertEquals("get_context_usage", subtypeOf(encodeClaudeCodeGetContextUsage("r")))
        assertEquals("get_session_cost", subtypeOf(encodeClaudeCodeGetSessionCost("r")))
        assertEquals("set_model", subtypeOf(encodeClaudeCodeSetModel("r", "claude-opus-5")))
        assertEquals(
            "set_permission_mode",
            subtypeOf(encodeClaudeCodeSetPermissionMode("r", ClaudeCodePermissionMode.PLAN)),
        )
        assertEquals("rename_session", subtypeOf(encodeClaudeCodeRenameSession("r", "标题")))
        assertEquals("set_max_thinking_tokens", subtypeOf(encodeClaudeCodeSetThinking("r", 1024, "summarized")))
    }

    /** schema 注明：model 省略/null/"default" 都是重置为会话默认模型 */
    @Test
    fun `set_model sends explicit null when resetting`() {
        val withModel = request(encodeClaudeCodeSetModel("r", "claude-opus-5"))["request"]!!.jsonObject
        assertEquals("claude-opus-5", withModel["model"]?.jsonPrimitive?.contentOrNull)

        val reset = request(encodeClaudeCodeSetModel("r", null))["request"]!!.jsonObject
        assertEquals(JsonNull, reset["model"])

        val blank = request(encodeClaudeCodeSetModel("r", "  "))["request"]!!.jsonObject
        assertEquals(JsonNull, blank["model"])
    }

    /**
     * mode 必须是 CLI 运行时白名单里的值，否则报
     * "Cannot set permission mode: must be one of acceptEdits, auto, bypassPermissions, default, dontAsk, plan"
     */
    @Test
    fun `permission modes use the CLI wire values`() {
        val allowed = setOf("acceptEdits", "auto", "bypassPermissions", "default", "dontAsk", "plan")
        ClaudeCodePermissionMode.entries.forEach { mode ->
            assertTrue("${mode.wire} 不在 CLI 白名单里", mode.wire in allowed)
            val body = request(encodeClaudeCodeSetPermissionMode("r", mode))["request"]!!.jsonObject
            assertEquals(mode.wire, body["mode"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `permission mode round trips from wire`() {
        assertEquals(ClaudeCodePermissionMode.BYPASS, ClaudeCodePermissionMode.fromWire("bypassPermissions"))
        assertEquals(ClaudeCodePermissionMode.DEFAULT, ClaudeCodePermissionMode.fromWire("default"))
        assertNull(ClaudeCodePermissionMode.fromWire("nonsense"))
    }

    @Test
    fun `set thinking accepts null budget and a display mode`() {
        val body = request(encodeClaudeCodeSetThinking("r", null, "omitted"))["request"]!!.jsonObject
        assertEquals(JsonNull, body["max_thinking_tokens"])
        assertEquals("omitted", body["thinking_display"]?.jsonPrimitive?.contentOrNull)
    }

    // --- control_response 成功分支 ---

    @Test
    fun `success control response is parsed with its payload`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r9",
               "response":{"commands":[{"name":"compact"}],"models":["claude-opus-5"]}}}"""
        ).single() as ClaudeCodeEvent.ControlOk

        assertEquals("r9", event.requestId)
        assertTrue(event.payload.containsKey("commands"))
        assertTrue(event.payload.containsKey("models"))
    }

    @Test
    fun `success control response with empty payload still resolves`() {
        val event = parseClaudeCodeEvents(
            """{"type":"control_response","response":{"subtype":"success","request_id":"r10"}}"""
        ).single() as ClaudeCodeEvent.ControlOk
        assertEquals("r10", event.requestId)
        assertTrue(event.payload.isEmpty())
    }
}
