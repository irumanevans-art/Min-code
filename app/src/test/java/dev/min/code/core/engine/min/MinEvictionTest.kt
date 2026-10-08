package dev.min.code.core.engine.min

import dev.min.agent.protocol.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

class MinEvictionTest {
    private val a = SessionId("a")
    private val b = SessionId("b")
    private val c = SessionId("c")
    private val d = SessionId("d")
    private val touched = mapOf(a to 10L, b to 20L, c to 30L, d to 40L)

    @Test
    fun `the least recently touched go first and only as many as are over`() {
        assertEquals(listOf(a, b), evictionCandidates(listOf(d, c, b, a), emptySet(), touched, { false }, over = 2))
        assertEquals(emptyList<SessionId>(), evictionCandidates(listOf(a, b), emptySet(), touched, { false }, over = 0))
    }

    @Test
    fun `pinned sessions and sessions with an active scheduled task are never evicted`() {
        // a 最久没碰，但它有进行中的定时任务；b 被钉住：卸下的是 c、d
        val result = evictionCandidates(listOf(a, b, c, d), pinned = setOf(b), touched = touched, hasActiveSchedule = { it == a }, over = 2)
        assertEquals(listOf(c, d), result)
    }

    @Test
    fun `when everything is protected nothing is evicted even if over the limit`() {
        assertEquals(emptyList<SessionId>(), evictionCandidates(listOf(a, b), setOf(a), touched, { it == b }, over = 2))
    }
}
