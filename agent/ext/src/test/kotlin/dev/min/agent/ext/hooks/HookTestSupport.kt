package dev.min.agent.ext.hooks

import org.junit.Assert.assertEquals

/*
 * 输入键名测试共用的固定件：R1a.0 的通用字段、填满的 common、以及「合成 stdin 后断言键集合」的一步。
 */

/** R1a.0 的 10 个通用输入字段（JSON 原名） */
internal val COMMON_INPUT_KEYS = setOf(
    "session_id", "prompt_id", "transcript_path", "cwd", "scratchpad_dir",
    "permission_mode", "effort", "hook_event_name", "agent_id", "agent_type",
)

/** 通用字段全部填上：合成后的 JSON 里 10 个键都要在 */
internal fun hookCommon(event: HookEvent) = HookInputCommon(
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

/** 专属字段全部填上，合成 stdin JSON，断言键集合 = 通用 10 键 + 文档原名 */
internal inline fun <reified T : Any> assertInputKeys(event: HookEvent, specific: T, expectedSpecificKeys: Set<String>) {
    val json = hookInputJson(hookCommon(event), specific)
    assertEquals("$event 的 stdin 键集合", COMMON_INPUT_KEYS + expectedSpecificKeys, json.keys)
}
