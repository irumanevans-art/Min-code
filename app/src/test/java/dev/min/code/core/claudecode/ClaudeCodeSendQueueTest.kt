package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追加消息的两格调度台。这些断言就是「发送排序」这件事本身：
 * 谁先出手、打断之后谁先回来、什么时候才敢说 busy 结束了。
 */
class ClaudeCodeSendQueueTest {

    private fun queue() = ClaudeCodeSendQueue<String>()

    @Test
    fun `empty queue has no work`() {
        assertFalse(queue().hasWork())
    }

    @Test
    fun `handed off message still counts as work until confirmed`() {
        val q = queue()
        q.handOff("a") {}
        assertTrue("交棒了不等于模型看见了，busy 不能在这里落地", q.hasWork())
        assertEquals(listOf("a"), q.confirm())
        assertFalse(q.hasWork())
    }

    @Test
    fun `hand off writes in registration order`() {
        val q = queue()
        val written = mutableListOf<String>()
        q.handOff("a") { written += it }
        q.handOff("b") { written += it }
        assertEquals(listOf("a", "b"), written)
    }

    @Test
    fun `confirm only reports the batch handed off before it`() {
        val q = queue()
        q.handOff("a") {}
        assertEquals(listOf("a"), q.confirm())
        q.handOff("b") {}
        assertEquals("上一批已经放行了，不能再报一次", listOf("b"), q.confirm())
        assertEquals(emptyList<String>(), q.confirm())
    }

    @Test
    fun `reclaim puts handed off messages back in front and keeps their order`() {
        val q = queue()
        q.handOff("a") {}
        q.handOff("b") {}
        q.hold("later")
        q.reclaim()
        assertEquals(listOf("a", "b", "later"), q.drainHeld())
    }

    @Test
    fun `reclaim leaves confirmed messages alone`() {
        val q = queue()
        q.handOff("seen") {}
        q.confirm()
        q.handOff("unseen") {}
        q.reclaim()
        assertEquals("已经被模型看见的不能重发", listOf("unseen"), q.drainHeld())
    }

    @Test
    fun `drain held empties the first slot`() {
        val q = queue()
        q.hold("a")
        q.hold("b")
        assertEquals(listOf("a", "b"), q.drainHeld())
        assertEquals(emptyList<String>(), q.drainHeld())
        assertFalse(q.hasWork())
    }

    @Test
    fun `snapshot is held first then handed off`() {
        val q = queue()
        q.handOff("handed") {}
        q.hold("held")
        assertEquals(listOf("held", "handed"), q.snapshot())
        assertTrue("快照不该动队列本身", q.hasWork())
    }

    @Test
    fun `clear drops both slots`() {
        val q = queue()
        q.hold("a")
        q.handOff("b") {}
        q.clear()
        assertFalse(q.hasWork())
        assertEquals(emptyList<String>(), q.drainHeld())
        assertEquals(emptyList<String>(), q.confirm())
    }

    /** 完整走一遍：发出 → 交棒 → 打断要回来 → 重发 → 被看见 */
    @Test
    fun `interrupt then queued round trip`() {
        val q = queue()
        val written = mutableListOf<String>()
        q.handOff("追加的一句") { written += it }

        // Esc：CLI 侧队列被 cancel_queued 清掉，副本要回来
        q.reclaim()
        assertTrue(q.hasWork())

        // 本轮收尾后原样重发
        val again = q.drainHeld()
        assertEquals(listOf("追加的一句"), again)
        again.forEach { m -> q.handOff(m) { written += it } }
        assertEquals("要回来的那条必须重发一次，不能只靠 CLI 那份", listOf("追加的一句", "追加的一句"), written)

        q.confirm()
        assertFalse(q.hasWork())
    }
}
