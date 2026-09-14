package dev.min.code.core.claudecode

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子 agent 的帧归位。
 *
 * CLI 把子 agent 的消息和主线程的消息混在同一条 stdout 上，只靠顶层
 * `parent_tool_use_id` 区分。认错了的后果是子任务的活被当成主 agent 干的，
 * 更糟的是子 agent 的增量会冲掉主 agent 正在生成的正文。
 */
class ClaudeCodeSubagentTest {

    private val emptyInput = JsonObject(emptyMap())

    @Test
    fun `主线程的 assistant 帧不套信封`() {
        val events = parseClaudeCodeEvents(
            """{"type":"assistant","parent_tool_use_id":null,
               |"message":{"content":[{"type":"text","text":"主线程说话"}]}}""".trimMargin()
        )
        assertEquals(listOf(ClaudeCodeEvent.AssistantText("主线程说话")), events)
    }

    @Test
    fun `带 parent_tool_use_id 的 assistant 帧归到子 agent`() {
        val events = parseClaudeCodeEvents(
            """{"type":"assistant","parent_tool_use_id":"toolu_1",
               |"message":{"content":[{"type":"text","text":"子任务说话"}]}}""".trimMargin()
        )
        val sub = events.single() as ClaudeCodeEvent.Subagent
        assertEquals("toolu_1", sub.parentToolUseId)
        assertEquals(ClaudeCodeEvent.AssistantText("子任务说话"), sub.event)
    }

    @Test
    fun `子 agent 的工具结果也归位`() {
        val events = parseClaudeCodeEvents(
            """{"type":"user","parent_tool_use_id":"toolu_1",
               |"message":{"content":[{"type":"tool_result","tool_use_id":"toolu_9","content":"ok"}]}}"""
                .trimMargin()
        )
        val sub = events.single() as ClaudeCodeEvent.Subagent
        assertEquals("toolu_1", sub.parentToolUseId)
        assertTrue(sub.event is ClaudeCodeEvent.ToolResult)
    }

    /** 流式缓冲区只有一个，子 agent 的 token 混进去会把主 agent 那段正文冲掉 */
    @Test
    fun `子 agent 的流式增量直接丢掉`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event","parent_tool_use_id":"toolu_1",
               |"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""
                .trimMargin()
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `主线程的流式增量照常解析`() {
        val events = parseClaudeCodeEvents(
            """{"type":"stream_event",
               |"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""
                .trimMargin()
        )
        assertEquals(listOf(ClaudeCodeEvent.PartialText("x", thinking = false)), events)
    }

    // --- transcript 回放 ---

    /** 认不出归属的 sidechain 只能扔：挂不上任何一张卡，平铺又会污染主线程 */
    @Test
    fun `没有归属的 sidechain 仍然跳过`() {
        val events = parseTranscriptLine(
            """{"type":"assistant","isSidechain":true,
               |"message":{"content":[{"type":"text","text":"x"}]}}""".trimMargin()
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `带归属的 sidechain 挂回对应的 Task`() {
        val events = parseTranscriptLine(
            """{"type":"assistant","isSidechain":true,"parent_tool_use_id":"toolu_1",
               |"message":{"content":[{"type":"text","text":"子任务的结论"}]}}""".trimMargin()
        )
        val sub = events.single() as ClaudeCodeEvent.Subagent
        assertEquals("toolu_1", sub.parentToolUseId)
    }

    /** 子线程里的 user 行是 CLI 回填的任务提示词，不是人说的话 */
    @Test
    fun `子 agent 线程里的用户文本不进主流`() {
        val events = parseTranscriptLine(
            """{"type":"user","parent_tool_use_id":"toolu_1",
               |"message":{"role":"user","content":"去查一下这个"}}""".trimMargin()
        )
        assertTrue(events.isEmpty())
    }

    // --- 合并进工具卡 ---

    @Test
    fun `文本和思考按顺序追加`() {
        var items = emptyList<ClaudeCodeManager.ChatItem>()
        items = mergeSubagentItem(items, ClaudeCodeEvent.Thinking("想想"), "a", 100)
        items = mergeSubagentItem(items, ClaudeCodeEvent.AssistantText("结论"), "b", 100)
        assertEquals(2, items.size)
        assertTrue(items[0] is ClaudeCodeManager.ChatItem.Thinking)
        assertTrue(items[1] is ClaudeCodeManager.ChatItem.AssistantText)
    }

    @Test
    fun `空文本不占一条`() {
        val items = mergeSubagentItem(emptyList(), ClaudeCodeEvent.AssistantText("   "), "a", 100)
        assertTrue(items.isEmpty())
    }

    /** tool_result 是回填，不是追加 */
    @Test
    fun `工具结果回填到对应的调用上`() {
        var items = mergeSubagentItem(
            emptyList(),
            ClaudeCodeEvent.ToolUse("toolu_9", "Bash", emptyInput),
            "a",
            100,
        )
        items = mergeSubagentItem(
            items,
            ClaudeCodeEvent.ToolResult("toolu_9", "done", isError = false),
            "b",
            100,
        )
        val call = items.single() as ClaudeCodeManager.ChatItem.ToolCall
        assertEquals(ClaudeCodeManager.ChatItem.ToolCall.Status.Done, call.status)
        assertEquals("done", call.result)
    }

    @Test
    fun `出错的结果标成 Error`() {
        var items = mergeSubagentItem(
            emptyList(),
            ClaudeCodeEvent.ToolUse("toolu_9", "Bash", emptyInput),
            "a",
            100,
        )
        items = mergeSubagentItem(
            items,
            ClaudeCodeEvent.ToolResult("toolu_9", "boom", isError = true),
            "b",
            100,
        )
        val call = items.single() as ClaudeCodeManager.ChatItem.ToolCall
        assertEquals(ClaudeCodeManager.ChatItem.ToolCall.Status.Error, call.status)
        assertTrue(call.isError)
    }

    /** 匹配不到就原样返回，绝不凭空造一条无头的结果 */
    @Test
    fun `孤儿结果不造条目`() {
        val items = mergeSubagentItem(
            emptyList(),
            ClaudeCodeEvent.ToolResult("toolu_x", "ok", isError = false),
            "a",
            100,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `结果按上限截断`() {
        var items = mergeSubagentItem(
            emptyList(),
            ClaudeCodeEvent.ToolUse("toolu_9", "Bash", emptyInput),
            "a",
            5,
        )
        items = mergeSubagentItem(
            items,
            ClaudeCodeEvent.ToolResult("toolu_9", "0123456789", isError = false),
            "b",
            5,
        )
        assertEquals("01234", (items.single() as ClaudeCodeManager.ChatItem.ToolCall).result)
    }
}
