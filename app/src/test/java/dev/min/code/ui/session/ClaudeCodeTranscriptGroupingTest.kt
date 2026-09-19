package dev.min.code.ui.session

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.min.code.core.session.ChatItem

/**
 * 会话流分组。折叠的是"跑完就不用再看"的工作过程，**绝不能把用户消息、
 * 助手正文和报错折进去** —— 那三样恰恰是回头要找的东西。
 */
class ClaudeCodeTranscriptGroupingTest {

    private var seq = 0
    private fun id() = "i${seq++}"
    private fun user(text: String = "问题") = ChatItem.UserText(id(), text)
    private fun assistant(text: String = "回答") = ChatItem.AssistantText(id(), text)
    private fun thinking() = ChatItem.Thinking(id(), "想了想")
    private fun note(error: Boolean = false) = ChatItem.Note(id(), "提示", isError = error)
    private fun tool(
        name: String = "Bash",
        status: ChatItem.ToolCall.Status = ChatItem.ToolCall.Status.Done,
    ) = ChatItem.ToolCall(id(), "t${seq++}", name, JsonObject(emptyMap()), status)

    @Test
    fun `用户消息和助手正文各自成条`() {
        val blocks = groupTranscript(listOf(user(), assistant()))
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is TranscriptBlock.Single })
    }

    @Test
    fun `连续的思考和工具折成一块`() {
        val items = listOf(user(), thinking(), tool(), tool(), assistant())
        val blocks = groupTranscript(items)
        assertEquals(3, blocks.size)
        val work = blocks[1] as TranscriptBlock.Work
        assertEquals(3, work.items.size)
    }

    /** 为一条 Bash 套一层"1 步，点开看"只是徒增一次点击 */
    @Test
    fun `孤零零一条工作不成块`() {
        val blocks = groupTranscript(listOf(user(), tool(), assistant()))
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is TranscriptBlock.Single)
    }

    @Test
    fun `助手正文会把一段工作切成两块`() {
        val items = listOf(thinking(), tool(), assistant(), tool(), tool())
        val blocks = groupTranscript(items)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is TranscriptBlock.Work)
        assertTrue(blocks[1] is TranscriptBlock.Single)
        assertTrue(blocks[2] is TranscriptBlock.Work)
    }

    /** 折叠等于把报错藏起来，而报错恰恰是最需要一眼看到的 */
    @Test
    fun `出错的系统提示单独成条并切开工作块`() {
        val items = listOf(tool(), tool(), note(error = true), tool(), tool())
        val blocks = groupTranscript(items)
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is TranscriptBlock.Single)
    }

    @Test
    fun `普通系统提示算工作过程`() {
        val blocks = groupTranscript(listOf(tool(), note(error = false)))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TranscriptBlock.Work)
    }

    @Test
    fun `空列表给空结果`() {
        assertTrue(groupTranscript(emptyList()).isEmpty())
    }

    /** key 取块内第一条的 id：块变长时它不变，LazyColumn 才不会重建整块 */
    @Test
    fun `块变长时 key 不变`() {
        val first = tool()
        val a = groupTranscript(listOf(first, tool()))
        val b = groupTranscript(listOf(first, tool(), tool()))
        assertEquals(a[0].key, b[0].key)
    }

    // --- 折叠行数出了什么 ---
    // 文案本身跟着界面语言走（workBlockSummary 是 @Composable），这里测的是计数

    @Test
    fun `摘要按工具名计数`() {
        val counts = workBlockCounts(listOf(thinking(), tool("Bash"), tool("Bash"), tool("Read")))

        assertEquals(4, counts.steps)
        assertEquals(1, counts.thinking)
        // 保持首次出现的顺序：这一行读起来就是那段活的时间线
        assertEquals(listOf("Bash" to 2, "Read" to 1), counts.tools)
    }

    @Test
    fun `出错的步数单独点出来`() {
        val items = listOf(tool("Bash"), tool("Bash", ChatItem.ToolCall.Status.Error))

        assertEquals(1, workBlockCounts(items).failed)
    }

    @Test
    fun `没有可计数的东西时只报步数`() {
        val counts = workBlockCounts(listOf(note(), note()))

        assertEquals(2, counts.steps)
        assertEquals(0, counts.thinking)
        assertTrue(counts.tools.isEmpty())
        assertEquals(0, counts.failed)
    }
}
