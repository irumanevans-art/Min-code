package dev.min.agent.kernel

import dev.min.agent.ext.config.AvailableModelsSetting
import dev.min.agent.ext.config.ModelPolicy
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 别名 + 模型限制落到哪个型号（CC 模型配置文档「Restrict model selection」「Block specific models or versions」），用随包目录 */
class ModelChoiceTest {
    private val catalog = Catalog.bundled()
    private fun a(id: String) = ModelRef("anthropic", id)
    private fun managedList(vararg entries: String, enforce: Boolean = false, exact: Boolean = false, denied: List<String> = emptyList()) =
        ModelPolicy(AvailableModelsSetting(entries.toList(), managed = true), exact = exact, denied = denied, enforceDefault = enforce)

    @Test
    fun `without a policy an alias resolves to its tier and a full id stays as it is`() {
        assertEquals(ModelChoice.Result.Ok(a("claude-sonnet-5-5")), ModelChoice.named(a("sonnet"), catalog, ModelPolicy()))
        assertEquals(ModelChoice.Result.Ok(a("claude-x")), ModelChoice.named(a("claude-x"), catalog, ModelPolicy()))
        assertEquals(ModelChoice.Result.Ok(a("claude-opus-5-5")), ModelChoice.default("anthropic", catalog, ModelPolicy()))
    }

    @Test
    fun `a blocked family alias lands on the newest permitted version of the family and says so`() {
        // CC：["sonnet","claude-opus-4-6"] 下 opus 选到 Opus 4.6（目录里没有这一版，取名单点名的）
        val r = ModelChoice.named(a("opus"), catalog, managedList("sonnet", "claude-opus-4-6"))
        assertEquals(ModelChoice.Result.Ok(a("claude-opus-4-6"), substitutedFrom = a("claude-opus-5-5")), r)
        // 目录里有的同家族旧版：标了档的当前版被 deniedModels 挡，退到下一个版本
        val older = ModelChoice.named(a("sonnet"), catalog, ModelPolicy(denied = listOf("claude-sonnet-5-5")))
        assertEquals(ModelChoice.Result.Ok(a("claude-sonnet-5"), substitutedFrom = a("claude-sonnet-5-5")), older)
    }

    @Test
    fun `a blocked full id is not substituted, and a family with no permitted version is blocked`() {
        assertEquals(ModelChoice.Result.Blocked, ModelChoice.named(a("claude-opus-5-5"), catalog, managedList("sonnet")))
        assertEquals(ModelChoice.Result.Blocked, ModelChoice.named(a("opus"), catalog, managedList("sonnet")))
    }

    @Test
    fun `opusplan keeps the alias as long as the execution tier is usable`() {
        assertEquals(ModelChoice.Result.Ok(a("opusplan")), ModelChoice.named(a("opusplan"), catalog, managedList("sonnet")))
        assertEquals(ModelChoice.Result.Blocked, ModelChoice.named(a("opusplan"), catalog, managedList("haiku")))
    }

    @Test
    fun `a prefix allowlist alone leaves the default alone, enforce remaps it down the cost ladder`() {
        assertEquals(ModelChoice.Result.Ok(a("claude-opus-5-5")), ModelChoice.default("anthropic", catalog, managedList("sonnet")))
        assertEquals(
            ModelChoice.Result.Ok(a("claude-sonnet-5-5"), substitutedFrom = a("claude-opus-5-5")),
            ModelChoice.default("anthropic", catalog, managedList("sonnet", enforce = true)),
        )
        assertEquals(
            "sonnet 也不在时退到 haiku",
            ModelChoice.Result.Ok(a("claude-haiku-5-5"), substitutedFrom = a("claude-opus-5-5")),
            ModelChoice.default("anthropic", catalog, managedList("haiku", enforce = true)),
        )
    }

    @Test
    fun `a denied default steps down even without enforce, and nothing permitted refuses`() {
        val denied = ModelChoice.default("anthropic", catalog, ModelPolicy(denied = listOf("claude-opus-5-5")))
        assertTrue("同家族没有别的允许版本就退到 sonnet：$denied", denied == ModelChoice.Result.Ok(a("claude-sonnet-5-5"), substitutedFrom = a("claude-opus-5-5")))
        val none = ModelChoice.default("anthropic", catalog, ModelPolicy(denied = listOf("opus", "sonnet", "haiku")))
        assertEquals(ModelChoice.Result.Blocked, none)
    }

    @Test
    fun `an alias missing from a provider's catalog is a configuration problem, not a block`() {
        val r = ModelChoice.named(ModelRef("deepseek", "fable"), catalog, ModelPolicy())
        assertTrue(r.toString(), r is ModelChoice.Result.Unknown)
    }
}
