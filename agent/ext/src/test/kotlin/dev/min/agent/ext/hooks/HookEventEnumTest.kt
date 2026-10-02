package dev.min.agent.ext.hooks

import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 事件枚举是兼容性的根：33 个一个不多一个不少，名字与调研文档 §1 事件表逐个一致。
 */
class HookEventEnumTest {
    @Test
    fun `exactly 33 events with the documented names in doc order`() {
        val expected = listOf(
            "SessionStart", "Setup", "UserPromptSubmit", "UserPromptExpansion", "PreToolUse",
            "PermissionRequest", "PermissionDenied", "PostToolUse", "PostToolUseFailure", "PostToolBatch",
            "Notification", "MessageDisplay", "SubagentStart", "SubagentStop", "TaskCreated", "TaskCompleted",
            "Stop", "StopFailure", "TeammateIdle", "InstructionsLoaded", "ConfigChange", "CwdChanged",
            "DirectoryAdded", "FileChanged", "WorktreeCreate", "WorktreeRemove", "PreCompact", "PostCompact",
            "PreModelSwitch", "PostModelSwitch", "Elicitation", "ElicitationResult", "SessionEnd",
        )
        assertEquals(33, HookEvent.entries.size)
        assertEquals(expected, HookEvent.entries.map { it.name })
        // 线上拼写以序列化结果为准，防止将来有人改了常量名或 SerialName 悄悄改了协议
        assertEquals(expected, HookEvent.entries.map { HookJson.encodeToJsonElement(it).jsonPrimitive.content })
    }
}
