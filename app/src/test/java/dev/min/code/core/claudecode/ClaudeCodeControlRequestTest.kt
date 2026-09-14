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
        assertEquals(
            "generate_session_title",
            subtypeOf(encodeClaudeCodeGenerateSessionTitle("r", "首条用户消息")),
        )
        assertEquals("set_max_thinking_tokens", subtypeOf(encodeClaudeCodeSetThinking("r", 1024, "summarized")))
    }

    /** 无头模式要主动调；persist 默认 true，好让 CLI 落盘 ai-title */
    @Test
    fun `generate_session_title carries description and persist`() {
        val body = request(encodeClaudeCodeGenerateSessionTitle("r", "修复登录闪退", persist = true))
            .get("request")!!.jsonObject
        assertEquals("generate_session_title", body["subtype"]?.jsonPrimitive?.contentOrNull)
        assertEquals("修复登录闪退", body["description"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", body["persist"]?.jsonPrimitive?.contentOrNull)

        val noPersist = request(encodeClaudeCodeGenerateSessionTitle("r", "x", persist = false))
            .get("request")!!.jsonObject
        assertEquals("false", noPersist["persist"]?.jsonPrimitive?.contentOrNull)
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

    // --- set_cwd -------------------------------------------------------------
    // CLI 的 schema 是 {subtype:"set_cwd", path, trust_accepted?, trusted_directory?}。
    // 这里曾经发的是 `cwd`，于是每次切目录都被回
    // "set_cwd: invalid request — path must be a non-empty string"。

    @Test
    fun `set_cwd carries the target as path, not cwd`() {
        val body = request(encodeClaudeCodeSetCwd("r", "/workspace/src"))["request"]!!.jsonObject
        assertEquals("set_cwd", body["subtype"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/workspace/src", body["path"]?.jsonPrimitive?.contentOrNull)
        assertNull(body["cwd"])
    }

    @Test
    fun `set_cwd omits the trust attestation unless it was actually granted`() {
        val plain = request(encodeClaudeCodeSetCwd("r", "/workspace"))["request"]!!.jsonObject
        assertNull(plain["trust_accepted"])
        assertNull(plain["trusted_directory"])

        // trust_accepted 为 true 时 CLI 要求 trusted_directory 必须逐字回传
        val trusted = request(
            encodeClaudeCodeSetCwd("r", "/workspace/p", trustAccepted = true, trustedDirectory = "/workspace/p")
        )["request"]!!.jsonObject
        assertEquals(true, trusted["trust_accepted"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        assertEquals("/workspace/p", trusted["trusted_directory"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `set_cwd result splits ok from the two failure arms`() {
        val ok = parseClaudeCodeSetCwdResult(
            json.parseToJsonElement(
                """{"status":"ok","cwd":"/workspace/src","changed":true,"transcript_relocated":true}"""
            ).jsonObject
        )
        assertEquals(ClaudeCodeSetCwdResult.Ok("/workspace/src", changed = true), ok)

        // needs_trust 不是成功：目录没动，之前它被当成 Ok，界面显示切过去了其实没有
        val trust = parseClaudeCodeSetCwdResult(
            json.parseToJsonElement(
                """{"status":"needs_trust","directory":"/workspace/p","trust_root":"/workspace"}"""
            ).jsonObject
        )
        assertEquals(ClaudeCodeSetCwdResult.NeedsTrust("/workspace/p", "/workspace"), trust)

        val rejected = parseClaudeCodeSetCwdResult(
            json.parseToJsonElement(
                """{"status":"rejected","reason":"not_found","message":"no such directory"}"""
            ).jsonObject
        )
        assertEquals(ClaudeCodeSetCwdResult.Rejected("not_found", "no such directory"), rejected)
    }

    @Test
    fun `an unrecognised set_cwd status is treated as a failure`() {
        // 认不出来的形状不能假装成功：那正是"界面切了、CLI 没切"的来源
        val result = parseClaudeCodeSetCwdResult(json.parseToJsonElement("""{}""").jsonObject)
        assertTrue(result is ClaudeCodeSetCwdResult.Rejected)
    }
}
