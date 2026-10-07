package dev.min.code.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundTest {
    @Test
    fun `away time is measured from the last background to the next foreground`() {
        var now = 1_000L
        val tracker = AwayTracker { now }
        tracker.onBackground()
        now += 7 * 60_000
        assertEquals(7 * 60_000L, tracker.onForeground())
    }

    @Test
    fun `a foreground without a matching background reports nothing`() {
        val tracker = AwayTracker { 5L }
        assertNull("冷启动没有离开", tracker.onForeground())
        tracker.onBackground()
        tracker.onForeground()
        assertNull("同一次离开只算一次", tracker.onForeground())
    }

    @Test
    fun `the threshold keeps a quick glance at another app from counting as away`() {
        assertTrue(60_000L < AWAY_RECAP_AFTER_MS)
        assertTrue(10 * 60_000L >= AWAY_RECAP_AFTER_MS)
    }
}
