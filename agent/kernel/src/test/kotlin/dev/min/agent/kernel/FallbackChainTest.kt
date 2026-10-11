package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.FallbackModelSetting
import dev.min.agent.protocol.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackChainTest {
    private val primary = ModelRef("anthropic", "opus")
    private val known = setOf("anthropic", "openai", "deepseek")
    private fun resolve(flag: String? = null, setting: FallbackModelSetting? = null) =
        FallbackChain.resolve(flag, setting, primary) { it in known }

    @Test
    fun `entries default to the primary provider and may name another`() {
        val chain = resolve("sonnet, openai:gpt-5 ,deepseek:v4")
        assertEquals(listOf(ModelRef("anthropic", "sonnet"), ModelRef("openai", "gpt-5"), ModelRef("deepseek", "v4")), chain.models)
    }

    @Test
    fun `a colon only means provider when the prefix is a known provider`() {
        // qwen3:8b、Bedrock 的 …-v1:0 都是模型 id 自带冒号
        val chain = resolve("qwen3:8b,anthropic.claude-3-v1:0,openai:gpt:preview")
        assertEquals(
            listOf(ModelRef("anthropic", "qwen3:8b"), ModelRef("anthropic", "anthropic.claude-3-v1:0"), ModelRef("openai", "gpt:preview")),
            chain.models,
        )
        // 冒号在最前或最后：没有可用的供应商 / 模型一半，整串当模型 id
        assertEquals(listOf(ModelRef("anthropic", "openai:")), resolve("openai:").models)
    }

    @Test
    fun `duplicates and the primary itself are dropped before the cap of three`() {
        val chain = resolve("opus,a,a,anthropic:a,b,c,d,e")
        assertEquals(listOf("a", "b", "c"), chain.models.map { it.modelId })
        assertEquals(FallbackChain.MAX_MODELS, chain.models.size)
    }

    @Test
    fun `the flag replaces the setting instead of merging with it`() {
        val setting = FallbackModelSetting(listOf("from-settings"), ConfigSource.User)
        assertEquals(listOf("from-flag"), resolve("from-flag", setting).models.map { it.modelId })
        assertEquals(listOf("from-settings"), resolve(null, setting).models.map { it.modelId })
        assertTrue(resolve(null, null).models.isEmpty())
    }

    @Test
    fun `cross provider entries from project and local settings are refused with a note`() {
        for (source in listOf(ConfigSource.Project, ConfigSource.Local)) {
            val chain = resolve(null, FallbackModelSetting(listOf("sonnet", "openai:gpt-5"), source))
            assertEquals(listOf(ModelRef("anthropic", "sonnet")), chain.models)
            assertEquals(1, chain.notes.size)
            assertTrue(chain.notes.single().contains("openai:gpt-5"))
        }
        // 用户层、托管层、命令行是用户自己的选择
        for (source in listOf(ConfigSource.User, ConfigSource.Managed)) {
            assertEquals(2, resolve(null, FallbackModelSetting(listOf("sonnet", "openai:gpt-5"), source)).models.size)
        }
        assertEquals(2, resolve("sonnet,openai:gpt-5").models.size)
    }

    @Test
    fun `entries outside availableModels are dropped with a note naming the layer`() {
        val chain = FallbackChain.resolve(
            "sonnet,openai:gpt-5,haiku", null, primary,
            allowed = { it.modelId != "gpt-5" },
        ) { it in known }
        assertEquals(listOf(ModelRef("anthropic", "sonnet"), ModelRef("anthropic", "haiku")), chain.models)
        // 丢了要说清是哪一项、为什么（给人看）
        assertTrue(chain.notes.single().let { it.contains("gpt-5") && it.contains("availableModels") })
    }
}
