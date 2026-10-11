package dev.min.code.ui.session

import dev.min.code.core.engine.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「继续最近的会话」选谁：同引擎、同目录、最近更新、说过话的 */
class ContinueRecentTest {
    private fun entry(id: String, updatedAt: Long, cwd: String? = "/workspace", engine: EngineKind = EngineKind.MinAgent, messages: Int = 2) =
        SessionListVM.SessionEntry(
            id = id, title = id, updatedAt = updatedAt, messageCount = messages, isLive = false, isActive = false,
            engine = engine, cwd = cwd,
        )

    @Test
    fun `the most recently updated session in the same directory wins`() {
        val list = listOf(entry("old", 100), entry("new", 300), entry("mid", 200))
        assertEquals("new", latestContinuable(list, EngineKind.MinAgent, "/workspace")?.id)
    }

    @Test
    fun `other directories, other engines and empty sessions are skipped`() {
        val list = listOf(
            entry("elsewhere", 900, cwd = "/workspace/app"),
            entry("claude", 800, engine = EngineKind.ClaudeCode),
            entry("empty", 700, messages = 0),
            entry("pick", 100),
        )
        assertEquals("pick", latestContinuable(list, EngineKind.MinAgent, "/workspace")?.id)
    }

    @Test
    fun `a trailing slash does not make it another directory`() {
        assertEquals("a", latestContinuable(listOf(entry("a", 1, cwd = "/workspace/app/")), EngineKind.MinAgent, "/workspace/app")?.id)
    }

    @Test
    fun `nothing to continue gives nothing`() {
        assertNull(latestContinuable(emptyList(), EngineKind.MinAgent, "/workspace"))
        assertNull(latestContinuable(listOf(entry("x", 1, cwd = null)), EngineKind.MinAgent, "/workspace"))
    }
}
