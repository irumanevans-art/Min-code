package dev.min.agent.spi

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.MessageId
import org.junit.Assert.assertEquals
import org.junit.Test

/** hook_context_added 的线形状。期望 JSON 按源码里的 SerialName 手写，谁改了判别值立刻红 */
class HookEventsWireTest {

    private fun assertEventWire(event: SessionEvent, expectedJson: String) {
        val text = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertEquals("往返不相等：$text", event, AgentJson.decodeFromString(SessionEvent.serializer(), text))
        assertEquals(AgentJson.parseToJsonElement(expectedJson), AgentJson.parseToJsonElement(text))
    }

    @Test
    fun `a compaction hook context keeps its wire shape`() {
        assertEventWire(
            HookContextAdded(
                HookContextTarget.Compaction("cmp_01J"),
                "SessionStart",
                "压缩后请重读任务清单",
            ),
            """{"type":"hook_context_added","target":{"type":"compaction","compactionId":"cmp_01J"},
                "event":"SessionStart","text":"压缩后请重读任务清单"}""",
        )
    }

    @Test
    fun `existing hook context targets keep their wire names`() {
        assertEventWire(
            HookContextAdded(HookContextTarget.Message(MessageId("m1")), "UserPromptSubmit", "当前分支：main"),
            """{"type":"hook_context_added","target":{"type":"message","messageId":"m1"},
                "event":"UserPromptSubmit","text":"当前分支：main"}""",
        )
        assertEventWire(
            HookContextAdded(HookContextTarget.ToolResults(MessageId("a1")), "PostToolUse", "记得跑测试"),
            """{"type":"hook_context_added","target":{"type":"tool_results","assistantMessageId":"a1"},
                "event":"PostToolUse","text":"记得跑测试"}""",
        )
        assertEventWire(
            HookContextAdded(HookContextTarget.NextUserMessage, "SessionStart", "这个仓库用 pnpm"),
            """{"type":"hook_context_added","target":{"type":"next_user_message"},
                "event":"SessionStart","text":"这个仓库用 pnpm"}""",
        )
    }

    @Test
    fun `a fork seed omits the compaction when the parent never compacted`() {
        assertEventWire(
            ForkSeeded(messages = emptyList(), results = emptyList()),
            """{"type":"fork_seeded","messages":[],"results":[]}""",
        )
    }
}