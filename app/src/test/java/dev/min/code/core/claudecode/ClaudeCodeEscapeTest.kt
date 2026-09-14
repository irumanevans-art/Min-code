package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.EscapeAction
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.escapeAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 停止键 = 电脑上的 Esc。
 *
 * 三档的差别很大：撤回一条还没开始的消息、打断一轮再跑排队的那条、单纯打断。
 * 判错档的后果是用户按下去发生了他没要的那件事 —— 比如本来只想撤回一句话，
 * 结果把跑了十分钟的任务掐了。
 */
class ClaudeCodeEscapeTest {

    @Test
    fun `没在跑时什么都不做`() {
        assertEquals(
            EscapeAction.Nothing,
            escapeAction(busy = false, hasQueued = false, turnProduced = false),
        )
        // 闲置时队列本来就该是空的，但即使有也不许在这里偷偷发出去
        assertEquals(
            EscapeAction.Nothing,
            escapeAction(busy = false, hasQueued = true, turnProduced = false),
        )
    }

    @Test
    fun `本轮还没产出就撤回`() {
        assertEquals(
            EscapeAction.Withdraw,
            escapeAction(busy = true, hasQueued = false, turnProduced = false),
        )
    }

    @Test
    fun `已经产出内容就只是打断`() {
        assertEquals(
            EscapeAction.Interrupt,
            escapeAction(busy = true, hasQueued = false, turnProduced = true),
        )
    }

    /**
     * 排队优先于撤回。用户在任务跑着的时候补了一句、等不及了按 Esc，
     * 他要的是"掉头处理新的那条"，不是把刚补的那句也一起吃掉。
     */
    @Test
    fun `有排队消息时打断并接着跑它`() {
        assertEquals(
            EscapeAction.InterruptThenQueued,
            escapeAction(busy = true, hasQueued = true, turnProduced = true),
        )
        assertEquals(
            EscapeAction.InterruptThenQueued,
            escapeAction(busy = true, hasQueued = true, turnProduced = false),
        )
    }
}
