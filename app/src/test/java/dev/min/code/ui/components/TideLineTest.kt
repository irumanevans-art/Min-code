package dev.min.code.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/** 潮句按星期切换：周末等，工作日候潮。断行位置也钉住——那是排版的一部分 */
class TideLineTest {
    @Test
    fun `weekend waits with no hurry`() {
        assertEquals("I wait,\nwith no hurry", tideLineFor(Calendar.SATURDAY))
        assertEquals("I wait,\nwith no hurry", tideLineFor(Calendar.SUNDAY))
    }

    @Test
    fun `weekdays wait for the tide`() {
        listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY).forEach {
            assertEquals("Till the\ntide turns", tideLineFor(it))
        }
    }
}
