package dev.min.code.ui.session

import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「跳到改动它的那一轮」找的是顶层的块；两个引擎的页面共用 [blockIndexOf] */
class ChangedFilesJumpTest {

    private fun tool(id: String, subItems: List<ChatItem> = emptyList()) = ChatItem.ToolCall(
        id = id, toolUseId = "toolu_$id", name = "Edit", input = JsonObject(emptyMap()),
        status = ChatItem.ToolCall.Status.Done, subItems = subItems,
    )

    @Test
    fun `finds a single item and an item inside a work block`() {
        val items = listOf(
            ChatItem.UserText("u1", "改一下"),
            tool("t1"),
            tool("t2"),
            ChatItem.AssistantText("a1", "好了"),
        )
        val blocks = groupTranscript(items)
        assertEquals(0, blockIndexOf(blocks, "u1"))
        assertEquals("连续的工具调用成一个 Work 块", 1, blockIndexOf(blocks, "t1"))
        assertEquals(1, blockIndexOf(blocks, "t2"))
        assertEquals(2, blockIndexOf(blocks, "a1"))
    }

    @Test
    fun `a subagent item is not at the top level so only its Agent card can be found`() {
        val blocks = groupTranscript(listOf(ChatItem.UserText("u1", "x"), tool("agent", subItems = listOf(tool("sub")))))
        assertEquals(1, blockIndexOf(blocks, "agent"))
        assertEquals(-1, blockIndexOf(blocks, "sub"))
    }

    @Test
    fun `an unknown id is -1`() {
        assertEquals(-1, blockIndexOf(groupTranscript(listOf(tool("t1"))), "nope"))
    }
}
