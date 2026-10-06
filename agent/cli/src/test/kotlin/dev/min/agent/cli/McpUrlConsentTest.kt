package dev.min.agent.cli

import org.junit.Assert.*
import org.junit.Test

class McpUrlConsentTest {
    @Test fun `URL opener receives complete URI only after explicit yes`() {
        var opened: String? = null
        val url = "https://example.com/auth?q=a%20b#fragment"
        assertEquals("cancel", consentToMcpUrl(url, null) { opened = it.toString() }); assertNull(opened)
        assertEquals("decline", consentToMcpUrl(url, "n") { opened = it.toString() }); assertNull(opened)
        assertEquals("accept", consentToMcpUrl(url, "y") { opened = it.toString() }); assertEquals(url, opened)
    }
}
