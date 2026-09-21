package dev.min.code.ui.theme

import dev.min.code.core.settings.SkinStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSwitchControllerTest {
    @Test
    fun applyingTransitionMoreThanOncePersistsOnlyOnce() {
        var writes = 0
        val controller = FormSwitchController()
        controller.switch(null, true, SkinStyle.SEA) { writes++ }
        val request = requireNotNull(controller.request)

        request.applyOnce()
        request.applyOnce()

        assertEquals(1, writes)
    }

    @Test
    fun replacingTransitionPreservesOrderWithoutRepeatingCancelledWrite() {
        val writes = mutableListOf<Boolean>()
        val controller = FormSwitchController()
        controller.switch(null, true, SkinStyle.SEA) { writes += true }
        val first = requireNotNull(controller.request)
        controller.switch(null, false, SkinStyle.SEA) { writes += false }
        val latest = requireNotNull(controller.request)

        assertTrue(first.toDark)
        assertFalse(latest.toDark)
        assertEquals(listOf(true), writes)
        first.applyOnce()
        latest.applyOnce()
        latest.applyOnce()

        assertEquals(listOf(true, false), writes)
    }

    /**
     * 换风格走的是同一套过渡。这一条钉的是「同一套」这件事本身：
     * 换形要保证设置一定落盘（动画被取消也要落），而那个保证只写过一遍 ——
     * 风格要是另起一条路，就没有人保它了。
     */
    @Test
    fun switching_only_the_skin_still_persists_exactly_once() {
        var writes = 0
        val controller = FormSwitchController()
        controller.switch(null, false, SkinStyle.ANTHROPIC) { writes++ }
        val request = requireNotNull(controller.request)

        assertEquals(SkinStyle.ANTHROPIC, request.toStyle)
        // 昼夜没变 —— 这正是「只换风格」那一路
        assertFalse(request.toDark)

        request.applyOnce()
        request.applyOnce()
        assertEquals(1, writes)
    }
}
