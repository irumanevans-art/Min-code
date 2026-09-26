package dev.min.code.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSlotTest {
    @Test
    fun `opening fills the slot and expands it`() {
        val slot = PreviewSlot().opened("http://localhost:5173/app")
        assertEquals("http://127.0.0.1:5173/app", slot.url)
        assertTrue(slot.expanded)
        assertTrue(slot.armed)
    }

    @Test
    fun `a discovery only fills the slot`() {
        val slot = PreviewSlot().discovered("http://localhost:3000/")
        assertEquals("http://127.0.0.1:3000/", slot.url)
        assertFalse(slot.expanded)
    }

    @Test
    fun `a discovery never changes what is already on screen`() {
        val open = PreviewSlot().opened("http://127.0.0.1:3000/")
        val after = open.discovered("http://127.0.0.1:4000/")
        assertEquals("http://127.0.0.1:3000/", after.url)
        assertTrue(after.expanded)
    }

    @Test
    fun `a discovery replaces the url of a collapsed slot`() {
        val collapsed = PreviewSlot().opened("http://127.0.0.1:3000/").collapsed()
        val after = collapsed.discovered("http://127.0.0.1:4000/")
        assertEquals("http://127.0.0.1:4000/", after.url)
        assertFalse(after.expanded)
    }

    @Test
    fun `the process table only fills an empty slot`() {
        val filled = PreviewSlot().discovered("http://127.0.0.1:3000/")
        val after = filled.discovered("http://127.0.0.1:4000/", onlyIfEmpty = true)
        assertEquals("http://127.0.0.1:3000/", after.url)
        assertEquals("http://127.0.0.1:4000/", PreviewSlot().discovered("http://127.0.0.1:4000/", onlyIfEmpty = true).url)
    }

    @Test
    fun `anything that is not loopback is ignored`() {
        val slot = PreviewSlot()
        assertEquals(slot, slot.opened("https://github.com/login"))
        assertEquals(slot, slot.discovered("https://example.com/"))
        assertFalse(slot.armed)
    }

    @Test
    fun `the toggle only works once the slot is armed`() {
        assertFalse(PreviewSlot().toggled().expanded)
        val open = PreviewSlot().opened("http://127.0.0.1:3000/")
        assertFalse(open.toggled().expanded)
        assertTrue(open.toggled().toggled().expanded)
    }

    @Test
    fun `collapsing hides the panel but keeps the url`() {
        val collapsed = PreviewSlot().opened("http://127.0.0.1:3000/").collapsed()
        assertEquals("http://127.0.0.1:3000/", collapsed.url)
        assertFalse(collapsed.expanded)
        assertTrue(collapsed.armed)
    }
}
