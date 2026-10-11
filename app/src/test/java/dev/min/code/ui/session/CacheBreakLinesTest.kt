package dev.min.code.ui.session

import dev.min.code.R
import dev.min.code.core.engine.CacheBreakCause
import dev.min.code.core.engine.CacheBreakView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 用量段「最近一次缓存失效」下面那几行：原因一条一行，没有原因就照实说，不猜 */
class CacheBreakLinesTest {
    private fun broke(vararg causes: CacheBreakCause, idleMs: Long = 0) =
        CacheBreakView(atMs = 0, cachedBefore = 8_000, readNow = 0, written = 8_200, idleMs = idleMs, causes = causes.toList(), fromModel = "a", toModel = "b")

    @Test
    fun `each cause becomes one line, with the models and the gap written out`() {
        val lines = cacheBreakLines(broke(CacheBreakCause.ModelChanged, CacheBreakCause.OutputsMasked, CacheBreakCause.Idle, idleMs = 23 * 60_000L))
        assertEquals(
            listOf(
                CacheLine(R.string.cache_break_model, listOf("a", "b")),
                CacheLine(R.string.cache_break_masked),
                CacheLine(R.string.cache_break_idle, listOf("23")),
            ),
            lines,
        )
    }

    @Test
    fun `no cause in the log says so instead of guessing`() {
        assertEquals(listOf(CacheLine(R.string.cache_break_unknown)), cacheBreakLines(broke()))
    }

    @Test
    fun `every cause has its own line`() {
        val all = cacheBreakLines(broke(*CacheBreakCause.entries.toTypedArray(), idleMs = 6 * 60_000L)).map { it.res }
        assertEquals(CacheBreakCause.entries.size, all.toSet().size)
        assertFalse(R.string.cache_break_unknown in all)
    }
}
