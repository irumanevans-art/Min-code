package dev.min.agent.ext.hooks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通用输入/输出字段的线上键名（R1a.0/R1a.1），以及「多余字段不报错」的宽容解码。
 */
class HookCommonWireTest {
    private fun common(event: HookEvent) = HookInputCommon(
        sessionId = "s1",
        promptId = "p1",
        transcriptPath = "/tmp/t.jsonl",
        cwd = "/w",
        scratchpadDir = "/tmp/scratch",
        permissionMode = "default",
        effort = EffortInfo("high"),
        hookEventName = event,
        agentId = "a1",
        agentType = "tester",
    )

    @Test
    fun `common input keys use the documented snake_case names`() {
        val json = HookJson.encodeToJsonElement(common(HookEvent.PreToolUse)).jsonObject
        assertEquals(
            setOf(
                "session_id", "prompt_id", "transcript_path", "cwd", "scratchpad_dir",
                "permission_mode", "effort", "hook_event_name", "agent_id", "agent_type",
            ),
            json.keys,
        )
        assertEquals("PreToolUse", json.getValue("hook_event_name").jsonPrimitive.content)
        assertEquals("high", json.getValue("effort").jsonObject.getValue("level").jsonPrimitive.content)
        assertEquals("default", json.getValue("permission_mode").jsonPrimitive.content)
    }

    @Test
    fun `common output keys use the documented names`() {
        val out = HookJson.decodeFromString(
            HookOutputCommon.serializer(),
            """{"continue": false, "stopReason": "halt", "suppressOutput": true, "systemMessage": "watch out",
               "terminalSequence": "seq", "decision": "block", "reason": "why",
               "hookSpecificOutput": {"hookEventName": "Stop", "additionalContext": "ctx"}}""",
        )
        assertEquals(false, out.shouldContinue)
        assertEquals("halt", out.stopReason)
        assertEquals(true, out.suppressOutput)
        assertEquals("watch out", out.systemMessage)
        assertEquals("seq", out.terminalSequence)
        assertEquals("block", out.decision)
        assertEquals("why", out.reason)
        assertEquals("ctx", out.hookSpecificOutput?.jsonObject?.getValue("additionalContext")?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown fields in hook output are ignored`() {
        // 用户脚本可能多写字段：顶层与 hookSpecificOutput 里各塞一个未知的，解析不许炸
        val out = HookJson.decodeFromString(
            HookOutputCommon.serializer(),
            """{"continue": true, "futureTopLevel": 123,
               "hookSpecificOutput": {"hookEventName": "Stop", "additionalContext": "x",
                                      "futureNested": {"a": 1}}}""",
        )
        assertEquals(true, out.shouldContinue)
        val stop = HookJson.decodeFromJsonElement<StopSpecificOutput>(out.hookSpecificOutput!!)
        assertEquals("x", stop.additionalContext)
    }

    @Test
    fun `input decode ignores common and unknown keys`() {
        val full = hookInputJson(common(HookEvent.PreToolUse), PreToolUseInput(toolName = "Bash", toolUseId = "call_1"))
        assertEquals("Bash", HookJson.decodeFromJsonElement<PreToolUseInput>(full).toolName)
        // 顶层混进未知键（可能来自未来版本）也不炸
        val withExtras = JsonObject(full + mapOf("unknown_key" to JsonPrimitive(1)))
        assertEquals("Bash", HookJson.decodeFromJsonElement<PreToolUseInput>(withExtras).toolName)
    }

    @Test
    fun `merging common and specific stays flat`() {
        val common = common(HookEvent.PreToolUse)
        val json = hookInputJson(common, PreToolUseInput(toolName = "Bash", toolUseId = "call_1"))
        // stdin 是一层平对象：通用键与专属键同级，没有 common 这样的包裹层
        val specificKeys = json.keys - HookJson.encodeToJsonElement(common).jsonObject.keys
        assertEquals(setOf("tool_name", "tool_use_id"), specificKeys)
        assertEquals("call_1", json.getValue("tool_use_id").jsonPrimitive.content)
    }
}
