package dev.min.code.ui.session

import dev.min.code.core.claudecode.groupSessionIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入一条会话后它排到抽屉最上面，但 LazyColumn 按 key 锚住原来的第一行，
 * 新行落在视口上方——冒烟时看起来就是「列表没刷新」。这里钉住「什么时候拉回顶上」。
 */
class SessionDrawerTopTest {

    @Test
    fun `a new row above a list resting at the top is revealed`() {
        assertTrue(shouldRevealNewTop("old", "imported", firstVisibleKey = "old", firstVisibleOffset = 0))
    }

    @Test
    fun `a reader scrolled down keeps their place`() {
        assertFalse(shouldRevealNewTop("old", "imported", firstVisibleKey = "row-7", firstVisibleOffset = 0))
        assertFalse(shouldRevealNewTop("old", "imported", firstVisibleKey = "old", firstVisibleOffset = 24))
    }

    @Test
    fun `nothing happens when the top row did not change or the list was empty`() {
        assertFalse(shouldRevealNewTop("old", "old", firstVisibleKey = "old", firstVisibleOffset = 0))
        assertFalse(shouldRevealNewTop(null, "imported", firstVisibleKey = null, firstVisibleOffset = 0))
    }

    @Test
    fun `top key is the first session without headers and the header key with them`() {
        val plain = groupSessionIds(listOf("a", "b"), listOf(false, false), listOf(null, null))
        assertEquals("a", sessionListTopKey(plain))
        val pinned = groupSessionIds(listOf("a", "b"), listOf(true, false), listOf(null, null))
        assertEquals("h-pinned", sessionListTopKey(pinned))
        assertNull(sessionListTopKey(emptyList()))
    }
}
