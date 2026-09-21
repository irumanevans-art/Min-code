package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeModelCatalog.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型目录的静态知识。数据对照 CLI v2.1.261 二进制里的模型表：
 *   claude-fable-5-1  context:{window:1e6, native_1m:true}
 *   claude-opus-5     context:{window:1e6, native_1m:true, supports_1m_suffix:true}
 *   claude-sonnet-5   context:{window:1e6, native_1m:true}
 *   claude-haiku-4-5  context:{window:200000, supports_1m_suffix:true}
 * 以及别名表 `fable:{default:"claude-fable-5-1", per_provider:{gateway:"claude-fable-5"}}`。
 */
class ClaudeCodeModelCatalogTest {

    @Test
    fun `families are recognised from aliases and canonical ids`() {
        assertEquals(Family.FABLE, ClaudeCodeModelCatalog.familyOf("fable"))
        assertEquals(Family.FABLE, ClaudeCodeModelCatalog.familyOf("claude-fable-5-1[1m]"))
        assertEquals(Family.FABLE, ClaudeCodeModelCatalog.familyOf("best"))
        assertEquals(Family.OPUS, ClaudeCodeModelCatalog.familyOf("opusplan"))
        assertEquals(Family.SONNET, ClaudeCodeModelCatalog.familyOf("claude-sonnet-5"))
        assertEquals(Family.HAIKU, ClaudeCodeModelCatalog.familyOf("haiku"))
        assertEquals(Family.UNKNOWN, ClaudeCodeModelCatalog.familyOf("default"))
        assertEquals(Family.UNKNOWN, ClaudeCodeModelCatalog.familyOf(null))
    }

    /** 用户问"默认 1M 嘛"——对 Fable / Opus 5 / Sonnet 5 答案是"是"，不用加后缀 */
    @Test
    fun `current generation is natively 1M and does not need the suffix`() {
        listOf("fable", "claude-fable-5-1", "claude-fable-5", "opus", "claude-opus-5", "sonnet", "claude-sonnet-5")
            .forEach { id ->
                assertTrue("$id 应是原生 1M", ClaudeCodeModelCatalog.isNativeLongContext(id))
                assertFalse("$id 不该再问要不要加 [1m]", ClaudeCodeModelCatalog.longContextSuffixMeaningful(id))
                assertEquals(ClaudeCodeModelCatalog.CONTEXT_1M, ClaudeCodeModelCatalog.assumedContextWindow(id))
            }
    }

    @Test
    fun `haiku and legacy models are 200k unless suffixed`() {
        listOf("haiku", "claude-haiku-4-5", "claude-opus-4-6", "claude-sonnet-4-6").forEach { id ->
            assertFalse(id, ClaudeCodeModelCatalog.isNativeLongContext(id))
            assertTrue(id, ClaudeCodeModelCatalog.longContextSuffixMeaningful(id))
            assertEquals(ClaudeCodeModelCatalog.CONTEXT_200K, ClaudeCodeModelCatalog.assumedContextWindow(id))
            assertEquals(ClaudeCodeModelCatalog.CONTEXT_1M, ClaudeCodeModelCatalog.assumedContextWindow("$id[1m]"))
        }
    }

    /**
     * 认不出的中转站自定义 id：无后缀时跟 CLI 一样按 200k；但 `[1m]` 对它们有意义
     * （CLI 原文就提示 append [1m]），否则设置页开关消失、菜单画出 …/200k。
     * default / null 按直连的 Opus 5 算。
     */
    @Test
    fun `unknown ids fall back to 200k unless suffixed and the suffix is meaningful`() {
        assertEquals(ClaudeCodeModelCatalog.CONTEXT_200K, ClaudeCodeModelCatalog.assumedContextWindow("my-relay-model"))
        assertTrue(
            "自定义 id 该能开 [1m]",
            ClaudeCodeModelCatalog.longContextSuffixMeaningful("my-relay-model"),
        )
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_1M,
            ClaudeCodeModelCatalog.assumedContextWindow("my-relay-model[1m]"),
        )
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_1M,
            ClaudeCodeModelCatalog.effectiveContextLimit(200_000, "my-relay-model[1m]"),
        )
        assertEquals(ClaudeCodeModelCatalog.CONTEXT_1M, ClaudeCodeModelCatalog.assumedContextWindow("default"))
        assertEquals(ClaudeCodeModelCatalog.CONTEXT_1M, ClaudeCodeModelCatalog.assumedContextWindow(null))
    }

    /** CLI 原话 "Effort not supported for Haiku" */
    @Test
    fun `only haiku lacks effort`() {
        assertFalse(ClaudeCodeModelCatalog.supportsEffort("haiku"))
        assertFalse(ClaudeCodeModelCatalog.supportsEffort("claude-haiku-4-5[1m]"))
        assertTrue(ClaudeCodeModelCatalog.supportsEffort("claude-fable-5-1"))
        assertTrue(ClaudeCodeModelCatalog.supportsEffort("opus[1m]"))
        assertTrue(ClaudeCodeModelCatalog.supportsEffort(null))
    }

    @Test
    fun `suffix helpers`() {
        assertEquals("opus", ClaudeCodeModelCatalog.stripLongContext("opus[1m]"))
        assertEquals("OPUS", ClaudeCodeModelCatalog.stripLongContext("OPUS[1M][1m]"))
        assertTrue(ClaudeCodeModelCatalog.hasLongContextSuffix("claude-opus-5[1m]"))
        assertFalse(ClaudeCodeModelCatalog.hasLongContextSuffix("claude-opus-5"))
        assertEquals("1M", ClaudeCodeModelCatalog.formatContextWindow(1_000_000))
        assertEquals("200k", ClaudeCodeModelCatalog.formatContextWindow(200_000))
    }

    /**
     * CLI 对不在它表里的 id（gateway 解析成的 claude-fable-5、旧二进制）会报 200k。
     * Fable 5.1 是原生 1M，界面必须取更大的那个，否则底栏画出 97k/200k 并提前压缩。
     */
    @Test
    fun `effective limit prefers native 1M over a 200k CLI report`() {
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_1M,
            ClaudeCodeModelCatalog.effectiveContextLimit(200_000, "claude-fable-5-1"),
        )
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_1M,
            ClaudeCodeModelCatalog.effectiveContextLimit(200_000, "claude-opus-5"),
        )
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_1M,
            ClaudeCodeModelCatalog.effectiveContextLimit(null, "claude-fable-5-1"),
        )
        // Haiku 本身就是 200k，CLI 报 200k 就信
        assertEquals(
            ClaudeCodeModelCatalog.CONTEXT_200K,
            ClaudeCodeModelCatalog.effectiveContextLimit(200_000, "claude-haiku-4-5"),
        )
        // CLI 报得更大（用户设了 CLAUDE_CODE_MAX_CONTEXT_TOKENS）则以 CLI 为准
        assertEquals(
            2_000_000,
            ClaudeCodeModelCatalog.effectiveContextLimit(2_000_000, "claude-fable-5-1"),
        )
    }

    /** 选中态要能把「用户选的 value」和「CLI 回读的 applied.model」对上 */
    @Test
    fun `same model ignores suffix and separators`() {
        assertTrue(ClaudeCodeModelCatalog.sameModel("claude-fable-5-1", "claude-fable-5-1[1m]"))
        assertTrue(ClaudeCodeModelCatalog.sameModel("fable-5-1", "claude-fable-5-1"))
        assertFalse(ClaudeCodeModelCatalog.sameModel("claude-fable-5-1", "claude-fable-5"))
        assertFalse(ClaudeCodeModelCatalog.sameModel("fable", "claude-fable-5-1"))
        assertFalse(ClaudeCodeModelCatalog.sameModel(null, "opus"))
    }

    @Test
    fun `fable entry point is the fixed id not the provider dependent alias`() {
        assertEquals("claude-fable-5-1", ClaudeCodeModelCatalog.FABLE_MODEL_ID)
        assertTrue(ClaudeCodeManager.PROMOTED_MODEL_IDS.contains(ClaudeCodeModelCatalog.FABLE_MODEL_ID))
        assertEquals(
            ClaudeCodeModelCatalog.FABLE_MODEL_ID,
            ClaudeCodeManager.HIDDEN_MODEL_ALIASES.first().value,
        )
    }
}
