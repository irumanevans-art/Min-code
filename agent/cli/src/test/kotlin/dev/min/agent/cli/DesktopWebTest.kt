package dev.min.agent.cli

import dev.min.agent.spi.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path

class DesktopWebTest {
    private fun args(vararg extra: String) = parseArgs(arrayOf("--model", "test", *extra), Path.of("."))

    @Test fun `web tools append and search is registered only when configured`() {
        val original = listOf("Read", "Write", "Edit", "NotebookEdit", "Glob", "Grep", "Bash", "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "Agent", "SendMessage", "TaskStop", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "Skill")
        assertEquals(original + "WebFetch", desktopTools().map { it.name })
        assertEquals(original + listOf("WebFetch", "WebSearch"), desktopTools(searchConfigured = true).map { it.name })
    }

    @Test fun `search configuration uses environment keys and missing config omits backend`() {
        assertNull(desktopSearchBackend(args()) { null })
        assertNull(desktopSearchBackend(args("--search-backend", "brave")) { null })
        assertNull(desktopSearchBackend(args("--search-backend", "tavily")) { null })
        assertNotNull(desktopSearchBackend(args("--search-backend", "brave")) { if (it == "BRAVE_SEARCH_API_KEY") "test-only-key" else null })
        assertNotNull(desktopSearchBackend(args()) { mapOf("MIN_AGENT_SEARCH_BACKEND" to "tavily", "TAVILY_API_KEY" to "test-only-key")[it] })
        assertNotNull(desktopSearchBackend(args("--search-backend", "searxng", "--search-url", "http://127.0.0.1:8080")) { null })
    }

    @Test fun `parse optional fast model cap cache and deadline`() {
        val args = args("--fast-model", "small", "--max-web-searches", "3", "--web-cache-ttl-ms", "0", "--web-deadline-ms", "1200")
        assertEquals("small", args.fastModel)
        assertEquals(3, args.maxWebSearches)
        assertEquals(0, args.webCacheTtlMs.toInt())
        assertEquals(1200, args.webDeadlineMs.toInt())
        assertThrows(UsageException::class.java) { args("--search-backend", "other") }
        assertThrows(UsageException::class.java) { args("--max-web-searches", "0") }
        assertThrows(UsageException::class.java) { args("--web-deadline-ms", "-1") }
    }

    @Test fun `temporary converter decodes basic named and numeric entities`() {
        assertEquals(PageContent(null, "Hello & 中 😀"), TemporaryPageConverter.convert("<p>Hello &amp; &#20013; &#x1f600;</p>", null))
    }
}
