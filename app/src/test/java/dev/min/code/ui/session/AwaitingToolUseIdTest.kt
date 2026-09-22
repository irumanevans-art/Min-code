package dev.min.code.ui.session

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「哪张卡在等你批准」的解析。
 *
 * 这件事不进 [ChatItem.ToolCall.Status] 而是每帧从会话级真值派生，所以正确性全压在
 * 这个函数上：它要在 id 缺失、帧序颠倒、同名调用重复出现的情况下都指到对的那张卡，
 * 指不准时宁可不指（转圈总好过把「等你」贴到一张其实在跑的卡上）。
 */
class AwaitingToolUseIdTest {

    private fun call(
        toolUseId: String,
        name: String = "Bash",
        status: ChatItem.ToolCall.Status = ChatItem.ToolCall.Status.Running,
    ) = ChatItem.ToolCall(
        id = toolUseId,
        toolUseId = toolUseId,
        name = name,
        input = JsonObject(emptyMap()),
        status = status,
    )

    @Test
    fun `没有挂起的请求时谁都不等`() {
        val items = listOf(call("t1"))
        assertNull(awaitingToolUseId(items, pendingToolName = null, pendingToolUseId = null))
    }

    @Test
    fun `带 id 的请求直接指到那张卡`() {
        val items = listOf(call("t1"), call("t2"))
        assertEquals("t2", awaitingToolUseId(items, "Bash", "t2"))
    }

    /** Claude 的 can_use_tool 里 toolUseId 是可选的 */
    @Test
    fun `缺 id 时按工具名回退到最后一条在跑的同名调用`() {
        val items = listOf(call("t1"), call("t2", name = "Read"), call("t3"))
        assertEquals("t3", awaitingToolUseId(items, "Bash", null))
    }

    /** 跑完的卡不该被重新点亮成"等你"——它已经有结果了 */
    @Test
    fun `回退只看还在跑的卡`() {
        val items = listOf(
            call("t1"),
            call("t2", status = ChatItem.ToolCall.Status.Done),
        )
        assertEquals("t1", awaitingToolUseId(items, "Bash", null))
    }

    /**
     * `can_use_tool` 先于 `tool_use` 到达：列表里还没有那张卡。
     * 此时不能瞎猜一张贴上去，返回 null，等下一帧把卡带来重组时自然对上。
     */
    @Test
    fun `卡还没到时不猜`() {
        assertNull(awaitingToolUseId(emptyList(), "Bash", "t1"))
        assertNull(awaitingToolUseId(listOf(call("t1", name = "Read")), "Bash", null))
    }

    /**
     * id 给了却匹配不上（历史回放里那张卡已经落定成 Done），仍按工具名回退，
     * 让还在跑的那次接住 —— 直接返回 null 会让真正在等的卡一直转圈。
     */
    @Test
    fun `id 对不上时回退到工具名`() {
        val items = listOf(
            call("stale", status = ChatItem.ToolCall.Status.Done),
            call("live"),
        )
        assertEquals("live", awaitingToolUseId(items, "Bash", "stale"))
    }

    /** Codex 那边没有工具名的概念，只有 itemId，映射时就等于 toolUseId */
    @Test
    fun `只有 id 没有工具名也能指到卡`() {
        val items = listOf(call("item-7", name = "Edit"))
        assertEquals("item-7", awaitingToolUseId(items, pendingToolName = null, pendingToolUseId = "item-7"))
    }
}
