package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 终端页签上写什么。几个会话同时开着时，"哪个页签是哪个会话"只能靠这一行字分辨。
 */
class ClaudeCodeTerminalTabLabelTest {

    @Test
    fun `工作区根写 workspace`() {
        assertEquals("workspace", terminalTabLabel("/workspace"))
        assertEquals("workspace", terminalTabLabel("/workspace/"))
        // cwd 还没回报时是空串，也不能给出一个空标签
        assertEquals("workspace", terminalTabLabel(""))
    }

    /** 不写完整路径：每个会话的 `/workspace` 前缀都一样，手机宽度下会把尾段挤没 */
    @Test
    fun `子目录只写相对那一段`() {
        assertEquals("api", terminalTabLabel("/workspace/api"))
        assertEquals(".aindex/1", terminalTabLabel("/workspace/.aindex/1"))
        assertEquals("a/b/c", terminalTabLabel("/workspace/a/b/c/"))
    }
}
