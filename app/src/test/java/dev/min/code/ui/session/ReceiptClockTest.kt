package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReceiptClockTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `same day shows only the clock`() {
        assertEquals("09:05", formatReceiptClock(at(27, 9, 5), now = at(27, 23, 59), zone = zone))
    }

    @Test
    fun `another day adds month and day`() {
        assertEquals("09-26 23:59", formatReceiptClock(at(26, 23, 59), now = at(27, 0, 1), zone = zone))
    }
}
