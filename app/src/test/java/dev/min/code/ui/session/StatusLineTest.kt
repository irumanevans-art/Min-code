package dev.min.code.ui.session

import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.UsageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底栏（「+」菜单那两栏）的数与缓存失效那几行字：照 CC statusline 取的字段，算对了才显示 */
class StatusLineTest {
    private val usage = UsageView.Empty

    @Test
    fun `a Min session uses its own context window, even when the Claude catalog would assume a bigger one`() {
        // 128k 的模型以前被「按 CC 目录取较大值」画成 200k，环永远到不了朱
        assertEquals(128_000, EngineUi.contextLimit(EngineKind.MinAgent, 128_000, "deepseek-chat"))
        // CC 照旧按目录纠正 CLI 报的 200k
        assertEquals(1_000_000, EngineUi.contextLimit(EngineKind.ClaudeCode, 200_000, "claude-fable-5-1"))
    }

    @Test
    fun `ratio and warning follow used over window`() {
        val calm = statusLineOf(usage.copy(contextTokens = 64_000), 128_000)
        assertEquals(0.5f, calm.ratio!!, 0.0001f)
        assertFalse(calm.warn)
        val near = statusLineOf(usage.copy(contextTokens = 110_000), 128_000)
        assertTrue("逼近上限转朱", near.warn)
        // 超出窗口也只画满一圈
        assertEquals(1f, statusLineOf(usage.copy(contextTokens = 200_000), 128_000).ratio!!, 0f)
    }

    @Test
    fun `no context usage yet means an empty ring, not zero percent`() {
        val line = statusLineOf(usage, 128_000)
        assertNull(line.ratio)
        assertNull(line.contextUsed)
        assertFalse(line.warn)
    }

    @Test
    fun `session totals pass through for the arrows`() {
        val line = statusLineOf(usage.copy(totalInputTokens = 1_234_567, totalOutputTokens = 34_000), 128_000)
        assertEquals(1_234_567L, line.tokensIn)
        assertEquals(34_000L, line.tokensOut)
    }

    @Test
    fun `token counts are written short`() {
        assertEquals("950", compactTokens(950))
        assertEquals("12.3k", compactTokens(12_345))
        assertEquals("1.2M", compactTokens(1_234_567))
    }
}
