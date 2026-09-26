package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子 agent 相关的线格式：hook_callback、initialize 的开关、stop_task、agents_killed */
class SubagentProtocolTest {

    private fun obj(frame: String) = Json.parseToJsonElement(frame).jsonObject

    @Test
    fun `hook_callback 解析出回调 id 和输入`() {
        val events = parseClaudeCodeEvents(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"hook_callback",
               |"callback_id":"min-subagent-post-tool","tool_use_id":"toolu_S",
               |"input":{"hook_event_name":"PostToolUse","agent_id":"a1","agent_type":"general-purpose"}}}""".trimMargin()
        )
        val hook = events.single() as ClaudeCodeEvent.HookCallback
        assertEquals("r1", hook.requestId)
        assertEquals(SubagentHookIds.POST_TOOL, hook.callbackId)
        assertEquals(SubagentHookInput("PostToolUse", "a1", false), parseSubagentHookInput(hook.input))
    }

    @Test
    fun `initialize 把开关放进请求体`() {
        val frame = obj(encodeClaudeCodeInitialize("i1", subagentInitializeOptions()))
        val request = frame["request"]!!.jsonObject
        assertEquals("initialize", request["subtype"]!!.jsonPrimitive.content)
        assertTrue(request.containsKey("hooks"))
        assertEquals("true", request["forwardSubagentText"]!!.jsonPrimitive.content)
        // 不带开关时仍是原来那个空请求
        assertEquals(setOf("subtype"), obj(encodeClaudeCodeInitialize("i2"))["request"]!!.jsonObject.keys)
    }

    @Test
    fun `hook 的应答是一个成功的 control_response，载荷原样放进去`() {
        val payload = buildJsonObject { put("decision", "block"); put("reason", "r") }
        val frame = obj(encodeClaudeCodeControlSuccess("r1", payload))
        assertEquals("control_response", frame["type"]!!.jsonPrimitive.content)
        val response = frame["response"]!!.jsonObject
        assertEquals("success", response["subtype"]!!.jsonPrimitive.content)
        assertEquals("r1", response["request_id"]!!.jsonPrimitive.content)
        assertEquals(payload, response["response"])
        // 空对象就是「不表态」
        assertEquals(JsonObject(emptyMap()), obj(encodeClaudeCodeControlSuccess("r2", JsonObject(emptyMap())))["response"]!!.jsonObject["response"])
    }

    @Test
    fun `stop_task 带 task_id`() {
        val request = obj(encodeClaudeCodeStopTask("s1", "a1"))["request"]!!.jsonObject
        assertEquals("stop_task", request["subtype"]!!.jsonPrimitive.content)
        assertEquals("a1", request["task_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `agents_killed 在会话流里留一句`() {
        val events = parseClaudeCodeEvents("""{"type":"system","subtype":"agents_killed","uuid":"u","session_id":"s"}""")
        assertEquals(listOf(ClaudeCodeEvent.SystemNote("子 agent 已全部停止")), events)
    }
}
