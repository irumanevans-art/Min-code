package dev.min.code.core.claudecode

import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.PROCESS_OUTPUT_LINE_CHARS
import dev.min.code.core.session.PROCESS_OUTPUT_MAX_LINES
import dev.min.code.core.session.appendProcessOutputLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程 stderr 进对话流。
 *
 * 规矩是「官方终端能看到的，这里也要能看到」：每一行都留下（以前只有像错误的行
 * 会被提升成红字，其余直接丢掉）。但刷屏的进程不能把会话撑爆，所以有界。
 */
class ClaudeCodeStderrTest {

    private fun lines(items: List<ChatItem>): List<String> =
        (items.last() as ChatItem.ProcessOutput).lines

    @Test
    fun `第一行新建一条`() {
        val items = appendProcessOutputLine(emptyList(), "npm warn deprecated", "a")
        assertEquals(1, items.size)
        assertEquals(listOf("npm warn deprecated"), lines(items))
    }

    @Test
    fun `连续的行并进同一条`() {
        var items: List<ChatItem> = emptyList()
        items = appendProcessOutputLine(items, "一", "a")
        items = appendProcessOutputLine(items, "二", "b")
        items = appendProcessOutputLine(items, "三", "c")
        assertEquals(1, items.size)
        assertEquals(listOf("一", "二", "三"), lines(items))
    }

    /** 中间夹了别的事件就另起一条：折叠行上的「N 行」该对应一次真实的输出爆发 */
    @Test
    fun `中间夹了别的条目就另起一条`() {
        var items: List<ChatItem> = appendProcessOutputLine(emptyList(), "一", "a")
        items = items + ChatItem.AssistantText("m", "在干活了")
        items = appendProcessOutputLine(items, "二", "b")
        assertEquals(3, items.size)
        assertEquals(listOf("二"), lines(items))
    }

    @Test
    fun `超出上限丢最旧并记下丢了多少`() {
        var items: List<ChatItem> = emptyList()
        repeat(PROCESS_OUTPUT_MAX_LINES + 5) { i ->
            items = appendProcessOutputLine(items, "第 $i 行", "id$i")
        }
        val block = items.single() as ChatItem.ProcessOutput
        assertEquals(PROCESS_OUTPUT_MAX_LINES, block.lines.size)
        assertEquals(5, block.dropped)
        // 留下的是最新的那一批
        assertEquals("第 ${PROCESS_OUTPUT_MAX_LINES + 4} 行", block.lines.last())
        assertEquals("第 5 行", block.lines.first())
    }

    @Test
    fun `单行过长时截断`() {
        val items = appendProcessOutputLine(emptyList(), "x".repeat(PROCESS_OUTPUT_LINE_CHARS * 3), "a")
        assertEquals(PROCESS_OUTPUT_LINE_CHARS, lines(items).single().length)
    }

    /** 噪声照样留下 —— 可见不等于报警，红字的提升规则是另一条路 */
    @Test
    fun `噪声行也留下但不算错误`() {
        val noise = "(node:123) ExperimentalWarning: stream/web is an experimental feature"
        val items = appendProcessOutputLine(emptyList(), noise, "a")
        assertEquals(listOf(noise), lines(items))
        assertTrue(!ClaudeCodeManager.looksLikeError(noise))
    }
}
