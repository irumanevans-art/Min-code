package dev.min.code.core.engine.min

import org.junit.Assert.*
import org.junit.Test

class AndroidMcpCustomTabTest {
    @Test fun `capable default browser is preferred over another provider`() {
        assertEquals("default",mcpCustomTabPackage(listOf("other","default"),"default","min") { true })
    }
    @Test fun `ordinary default browser is skipped and no capable browser refuses authorization`() {
        assertEquals("custom",mcpCustomTabPackage(listOf("ordinary","custom"),"ordinary","min") { it=="custom" })
        assertNull(mcpCustomTabPackage(listOf("ordinary"),"ordinary","min") { false })
    }
    @Test fun `Min is excluded before probing service capability even when it is the default`() {
        val probed=mutableListOf<String>()
        assertNull(mcpCustomTabPackage(listOf("min"),"min","min") { probed+=it; true })
        assertTrue(probed.isEmpty())
    }
    @Test fun `duplicate candidates are probed once and missing default uses capable fallback`() {
        val probed=mutableListOf<String>()
        assertEquals("custom",mcpCustomTabPackage(listOf("ordinary","custom","custom"),null,"min") { probed+=it; it=="custom" })
        assertEquals(listOf("ordinary","custom"),probed)
    }
}
