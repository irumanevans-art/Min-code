package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切换条和子 agent 视图的数据：从 Agent 卡 + 任务表推出来 */
class SubagentThreadsTest {

    private fun agentCard(
        toolUseId: String,
        status: ChatItem.ToolCall.Status = ChatItem.ToolCall.Status.Running,
        result: String? = null,
        subItems: List<ChatItem> = emptyList(),
        type: String? = "Explore",
    ) = ChatItem.ToolCall(
        id = "card-$toolUseId",
        toolUseId = toolUseId,
        name = "Agent",
        input = buildJsonObject {
            put("description", "数一数")
            put("prompt", "从一数到三")
            if (type != null) put("subagent_type", type)
        },
        status = status,
        result = result,
        subItems = subItems,
    )

    private fun task(id: String, toolUseId: String, status: String = "running", summary: String? = null) =
        ClaudeCodeManager.TaskInfo(
            id = id, toolUseId = toolUseId, taskType = "local_agent", subagentType = "Explore",
            status = status, summary = summary, lastToolName = "Bash",
        )

    @Test
    fun `运行中的子 agent 从任务表拿 agentId 和它在干什么`() {
        val threads = subagentThreads(listOf(agentCard("toolu_A")), listOf(task("a1", "toolu_A", summary = "Counting files")))
        val t = threads.single()
        assertEquals("a1", t.agentId)
        assertEquals("Explore", t.agentType)
        assertEquals("数一数", t.description)
        assertEquals("从一数到三", t.prompt)
        assertEquals(SubagentThread.Phase.Running, t.phase)
        assertEquals("Counting files", t.activity)
        assertTrue(t.tracked)
    }

    @Test
    fun `没有进度摘要时退回最近的工具名`() {
        val t = subagentThreads(listOf(agentCard("toolu_A")), listOf(task("a1", "toolu_A"))).single()
        assertEquals("Bash", t.activity)
    }

    /** 转后台的 Agent 调用立刻就有结果（async_launched），卡是 Done，但子 agent 还在跑：以任务表为准 */
    @Test
    fun `卡已完成但任务还在跑的仍是运行中`() {
        val card = agentCard(
            "toolu_A",
            status = ChatItem.ToolCall.Status.Done,
            result = "Async agent launched successfully. agentId: a17d6b71bb0c19c8f (internal ID - do not mention)",
        )
        val t = subagentThreads(listOf(card), listOf(task("a17d6b71bb0c19c8f", "toolu_A"))).single()
        assertEquals(SubagentThread.Phase.Running, t.phase)
    }

    @Test
    fun `任务表清掉之后从结果里抠 agentId，状态跟卡走`() {
        val card = agentCard(
            "toolu_A",
            status = ChatItem.ToolCall.Status.Done,
            result = "4\nagentId: a875040e6250a7661 (for resuming to continue this agent's work if needed)",
        )
        val t = subagentThreads(listOf(card), emptyList()).single()
        assertEquals("a875040e6250a7661", t.agentId)
        assertEquals(SubagentThread.Phase.Done, t.phase)
        assertFalse(t.tracked)
    }

    @Test
    fun `被停掉的和出错的分开`() {
        val stopped = subagentThreads(listOf(agentCard("toolu_A")), listOf(task("a1", "toolu_A", status = "stopped"))).single()
        assertEquals(SubagentThread.Phase.Stopped, stopped.phase)
        val killed = subagentThreads(listOf(agentCard("toolu_A")), listOf(task("a1", "toolu_A", status = "killed"))).single()
        assertEquals(SubagentThread.Phase.Stopped, killed.phase)
        val failed = subagentThreads(listOf(agentCard("toolu_A")), listOf(task("a1", "toolu_A", status = "failed"))).single()
        assertEquals(SubagentThread.Phase.Failed, failed.phase)
    }

    @Test
    fun `没写 subagent_type 的按 general-purpose`() {
        val t = subagentThreads(listOf(agentCard("toolu_A", type = null)), emptyList()).single()
        assertEquals(DEFAULT_SUBAGENT_TYPE, t.agentType)
        assertNull(t.agentId)
    }

    @Test
    fun `嵌套的子 agent 也展开出来，普通工具卡不算`() {
        val inner = agentCard("toolu_B")
        val bash = ChatItem.ToolCall("b", "toolu_X", "Bash", JsonObject(emptyMap()), ChatItem.ToolCall.Status.Done)
        val outer = agentCard("toolu_A", subItems = listOf(bash, inner))
        val threads = subagentThreads(listOf(ChatItem.UserText("u", "go"), outer), emptyList())
        assertEquals(listOf("toolu_A", "toolu_B"), threads.map { it.toolUseId })
        assertEquals(listOf(bash, inner), threads[0].items)
    }

    @Test
    fun `切换条只列任务表里还有的，加上正在看的那个`() {
        val threads = subagentThreads(
            listOf(agentCard("toolu_A"), agentCard("toolu_B", status = ChatItem.ToolCall.Status.Done), agentCard("toolu_C", status = ChatItem.ToolCall.Status.Done)),
            listOf(task("a1", "toolu_A")),
        )
        assertEquals(listOf("toolu_A"), switcherThreads(threads, selectedToolUseId = null).map { it.toolUseId })
        assertEquals(listOf("toolu_A", "toolu_C"), switcherThreads(threads, selectedToolUseId = "toolu_C").map { it.toolUseId })
        assertTrue(switcherThreads(emptyList(), null).isEmpty())
    }

    @Test
    fun `深层找卡改到嵌套那一层，找不到原样返回同一个列表`() {
        val inner = agentCard("toolu_B")
        val items = listOf<ChatItem>(agentCard("toolu_A", subItems = listOf(inner)))
        val mapped = items.mapToolCallDeep("toolu_B") { it.copy(result = "done") }
        val newInner = (mapped[0] as ChatItem.ToolCall).subItems.single() as ChatItem.ToolCall
        assertEquals("done", newInner.result)
        assertSame(items, items.mapToolCallDeep("toolu_nope") { it.copy(result = "x") })
    }

    @Test
    fun `结果里的 agentId 认得带标签的形状`() {
        assertEquals("aworker-0123456789abcdef", agentIdInResult("… agentId: aworker-0123456789abcdef (internal)"))
        assertNull(agentIdInResult("no id here"))
    }
}
