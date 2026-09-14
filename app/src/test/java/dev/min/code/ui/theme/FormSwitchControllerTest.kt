package dev.min.code.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSwitchControllerTest {
    @Test
    fun applyingTransitionMoreThanOncePersistsOnlyOnce() {
        var writes = 0
        val controller = FormSwitchController()
        controller.switch(null, true) { writes++ }
        val request = requireNotNull(controller.request)

        request.applyOnce()
        request.applyOnce()

        assertEquals(1, writes)
    }

    @Test
    fun replacingTransitionPreservesOrderWithoutRepeatingCancelledWrite() {
        val writes = mutableListOf<Boolean>()
        val controller = FormSwitchController()
        controller.switch(null, true) { writes += true }
        val first = requireNotNull(controller.request)
        controller.switch(null, false) { writes += false }
        val latest = requireNotNull(controller.request)

        assertTrue(first.toDark)
        assertFalse(latest.toDark)
        assertEquals(listOf(true), writes)
        first.applyOnce()
        latest.applyOnce()
        latest.applyOnce()

        assertEquals(listOf(true, false), writes)
    }
}
