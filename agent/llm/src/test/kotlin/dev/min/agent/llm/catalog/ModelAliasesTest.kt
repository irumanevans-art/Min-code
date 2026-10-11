package dev.min.agent.llm.catalog

import dev.min.agent.protocol.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAliasesTest {
    private val catalog = Catalog.bundled()
    private fun resolve(provider: String, model: String, planMode: Boolean = false) =
        ModelAliases.resolve(ModelRef(provider, model), catalog, planMode)

    @Test
    fun `family aliases point at the anthropic model of that tier`() {
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), resolve("anthropic", "opus").ref)
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), resolve("anthropic", "sonnet").ref)
        assertEquals(ModelRef("anthropic", "claude-haiku-5-5"), resolve("anthropic", "haiku").ref)
        assertEquals(ModelRef("anthropic", "claude-fable-5-1"), resolve("anthropic", "fable").ref)
    }

    @Test
    fun `best is fable where the provider has it and opus otherwise, default is opus`() {
        assertEquals(ModelRef("anthropic", "claude-fable-5-1"), resolve("anthropic", "best").ref)
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), resolve("anthropic", "default").ref)
        // 没有 fable 档的供应商：best 退到 opus（CC：other wise the same model as opus）
        assertEquals(ModelRef("deepseek", "deepseek-v4-pro"), resolve("deepseek", "best").ref)
    }

    @Test
    fun `opusplan is opus while planning and sonnet for execution`() {
        assertEquals(ModelRef("anthropic", "claude-opus-5-5"), resolve("anthropic", "opusplan", planMode = true).ref)
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), resolve("anthropic", "opusplan", planMode = false).ref)
    }

    @Test
    fun `the 1m suffix is stripped and reported, on aliases and on full ids`() {
        val alias = resolve("anthropic", "sonnet[1m]")
        assertEquals(ModelRef("anthropic", "claude-sonnet-5-5"), alias.ref)
        assertTrue(alias.oneMillion)
        assertEquals(ModelRef("anthropic", "claude-opus-4-6"), resolve("anthropic", "claude-opus-4-6[1m]").ref)
        assertTrue(resolve("anthropic", "claude-opus-4-6[1m]").oneMillion)
        assertFalse(resolve("anthropic", "opus").oneMillion)
        // 大小写不敏感：`[1M]` 也认（CC 的 Fable 修复里出现过 `[1M][1m]` 的双写）
        assertTrue(resolve("anthropic", "opus[1M]").oneMillion)
    }

    @Test
    fun `a full model id is not an alias and passes through unchanged`() {
        assertEquals(ModelRef("anthropic", "claude-sonnet-4-5-20250929"), resolve("anthropic", "claude-sonnet-4-5-20250929").ref)
        assertEquals(ModelRef("openai", "gpt-5.4"), resolve("openai", "gpt-5.4").ref)
    }

    @Test
    fun `non anthropic providers resolve tiers inside their own catalog`() {
        assertEquals(ModelRef("deepseek", "deepseek-v4-pro"), resolve("deepseek", "opus").ref)
        assertEquals(ModelRef("deepseek", "deepseek-flash"), resolve("deepseek", "haiku").ref)
        // 同一家的 -openai 线协议变体用同一档
        assertEquals(ModelRef("deepseek-openai", "deepseek-flash"), resolve("deepseek-openai", "haiku").ref)
    }

    @Test
    fun `a tier the provider catalog does not have is an error that names it, not a silent swap`() {
        val e = assertThrows(IllegalArgumentException::class.java) { resolve("openai", "sonnet") }
        assertTrue(e.message!!, e.message!!.contains("sonnet"))
        assertTrue(e.message!!, e.message!!.contains("openai"))
    }

    @Test
    fun `aliases are case sensitive words and unknown names are not rewritten`() {
        // CC 的 `Sonnet` 大小写在 VSCode 里也被归一化过；Min 的别名只认小写，其它字符串照原样当模型 id，找不到就由内核报错
        assertEquals(ModelRef("anthropic", "Sonnet"), resolve("anthropic", "Sonnet").ref)
    }

    @Test
    fun `every provider has at most one model per tier`() {
        val seen = catalog.models.mapNotNull { m -> m.tier?.let { Triple(m.ref.providerId, it, m.ref.modelId) } }
        val dupes = seen.groupBy { it.first to it.second }.filterValues { it.size > 1 }
        assertTrue("同一供应商同一档只能有一个型号：$dupes", dupes.isEmpty())
    }
}
