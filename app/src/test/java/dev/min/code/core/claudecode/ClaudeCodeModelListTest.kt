package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表的拼装规则：中转站目录的解析与排序、版本号比较、系列排位。
 * 全是纯函数，直接喂输入断言输出，不需要 Manager。
 */
class ClaudeCodeModelListTest {

    // --- 中转站 GET /v1/models ---

    /** Anthropic 原版形状：带 type / display_name / created_at */
    @Test
    fun `relay models keep claude entries and drop the rest`() {
        val body = """
            {"data":[
              {"type":"model","id":"claude-fable-5-1","display_name":"Claude Fable 5.1","created_at":"2026-08-01T00:00:00Z"},
              {"type":"model","id":"claude-opus-5","display_name":"Claude Opus 5","created_at":"2026-05-01T00:00:00Z"},
              {"id":"gpt-5","object":"model"},
              {"id":"claude-opus-5"}
            ],"has_more":false}
        """.trimIndent()
        val models = ClaudeCodeModelList.parseRelayModels(body)
        // gpt 被过滤，重复的 opus 只留一个，Fable 5.1 在
        assertEquals(listOf("claude-fable-5-1", "claude-opus-5"), models.map { it.id }.sorted())
        assertEquals("Claude Fable 5.1", models.first { it.id == "claude-fable-5-1" }.displayName)
    }

    /** OpenAI 风格的中转站：没有 display_name，且全是改过名的 id → 退回全量而不是列空 */
    @Test
    fun `relay models fall back to everything when nothing says claude`() {
        val body = """{"object":"list","data":[{"id":"fable-5.1","object":"model"},{"id":"opus-5","object":"model"}]}"""
        val models = ClaudeCodeModelList.parseRelayModels(body)
        assertEquals(2, models.size)
        assertNull(models[0].displayName)
    }

    /** 真实中转站的目录：display_name 等于 id 时视为没有；顺序 fable > opus > sonnet > haiku，同系列新的在前 */
    @Test
    fun `relay models are ordered by family then version`() {
        val ids = listOf(
            "claude-3-5-haiku-20241022", "claude-sonnet-4-5-20250929", "claude-opus-4-1-20250805",
            "claude-fable-5", "claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-opus-4-8",
        )
        val body = """{"data":[${ids.joinToString(",") { "{\"id\":\"$it\",\"display_name\":\"$it\"}" }}]}"""
        val models = ClaudeCodeModelList.parseRelayModels(body)
        assertEquals(
            listOf(
                "claude-fable-5-1", "claude-fable-5",
                "claude-opus-5", "claude-opus-4-8", "claude-opus-4-1-20250805",
                "claude-sonnet-5", "claude-sonnet-4-5-20250929",
                "claude-3-5-haiku-20241022",
            ),
            models.map { it.id },
        )
        assertTrue(models.all { it.displayName == null })
    }

    @Test(expected = IllegalStateException::class)
    fun `relay models reject a non list body`() {
        // 中转站把 /v1/models 当成未知路径返回一段 HTML/错误 JSON 时，必须报错而不是当成空列表
        ClaudeCodeModelList.parseRelayModels("""{"error":{"message":"not found"}}""")
    }

    // --- 排序键 ---

    @Test
    fun `family rank puts the strongest family first and ignores case`() {
        val ranks = listOf("claude-fable-5-1", "Claude-OPUS-5", "claude-sonnet-5", "claude-3-5-haiku", "gpt-5")
            .map(ClaudeCodeModelList::familyRank)
        assertEquals(listOf(0, 1, 2, 3, 4), ranks)
    }

    /** 主版本压过次版本，次版本压过日期；日期只在前两者相同时分先后 */
    @Test
    fun `version key compares major then minor then date`() {
        val key = ClaudeCodeModelList::versionKey
        assertTrue(key("claude-opus-5") > key("claude-opus-4-8"))
        // 4.8 没有日期，4.1 带了较新的日期：次版本仍然更大
        assertTrue(key("claude-opus-4-8") > key("claude-opus-4-1-20250805"))
        assertTrue(key("claude-opus-4-1-20251001") > key("claude-opus-4-1-20250805"))
        assertTrue(key("claude-opus-4-1-20250805") > key("claude-opus-4-1"))
    }

    /** 8 位数字是日期，不是版本段；没有数字的 id 排在所有带版本的后面 */
    @Test
    fun `version key reads two digit segments as version and eight digit as date`() {
        assertEquals(4_050_020_251_101L, ClaudeCodeModelList.versionKey("claude-opus-4-5-20251101"))
        assertEquals(5_010_000_000_000L, ClaudeCodeModelList.versionKey("claude-fable-5-1"))
        assertEquals(5_000_000_000_000L, ClaudeCodeModelList.versionKey("claude-opus-5"))
        assertEquals(0L, ClaudeCodeModelList.versionKey("custom-model"))
    }

    /** 系列和版本都相同时按 id 字典序，保证两次刷新排出来的顺序一致，面板不抖 */
    @Test
    fun `relay models with equal rank fall back to id order`() {
        val body = """{"data":[{"id":"b-claude-opus-5"},{"id":"a-claude-opus-5"}]}"""
        assertEquals(
            listOf("a-claude-opus-5", "b-claude-opus-5"),
            ClaudeCodeModelList.parseRelayModels(body).map { it.id },
        )
    }
}
