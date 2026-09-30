package dev.min.agent.cli

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.ReasoningEcho
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path

class ModelSelectionTest {
    private fun args(vararg argv: String) = parseArgs(argv.toList().toTypedArray(), Path.of("."))

    @Test fun `catalog capabilities and quirks survive default CLI values`() {
        val catalog = Catalog.bundled()
        val selected = resolveModel(args("--model", "claude-sonnet-5"), catalog)
        assertEquals(catalog.provider("anthropic"), selected.provider)
        assertEquals(catalog.model(ModelRef("anthropic", "claude-sonnet-5")), selected.profile)
        assertEquals(Wire.AnthropicMessages, selected.wire)
        assertEquals("ANTHROPIC_API_KEY", selected.keyEnv)
        assertFalse(resolveModel(args("--provider", "deepseek", "--model", "unknown"), catalog).provider.quirks.anthropic.cacheControl)
    }

    @Test fun `catalog OpenAI provider chooses Chat without a wire override`() {
        val provider = ProviderDescriptor("chat", "https://example.invalid/v1", Wire.OpenAIChat, AuthScheme.Bearer)
        val profile = ModelProfile(ModelRef("chat", "m"), family = ModelFamily.Other, contextWindow = 12345, maxOutputTokens = 789)
        val selected = resolveModel(args("--provider", "chat", "--model", "m"), Catalog(listOf(provider), listOf(profile)))
        assertEquals(provider, selected.provider)
        assertEquals(profile, selected.profile)
        assertEquals(Wire.OpenAIChat, selected.wire)
        assertEquals("CHAT_API_KEY", selected.keyEnv)
    }

    @Test fun `custom model and explicit overrides are resolved without IO`() {
        val selected = resolveModel(args("--provider", "local", "--model", "m", "--base-url", "http://localhost/v1", "--wire", "openai_chat", "--context", "32000", "--max-output", "2048", "--api-key-env", "LOCAL_TOKEN"), Catalog(emptyList(), emptyList()))
        assertEquals(ProviderDescriptor("local", "http://localhost/v1", Wire.OpenAIChat, AuthScheme.Bearer), selected.provider)
        assertEquals(ModelProfile(ModelRef("local", "m"), family = ModelFamily.Other, contextWindow = 32000, maxOutputTokens = 2048), selected.profile)
        assertEquals("LOCAL_TOKEN", selected.keyEnv)
        val legacy = resolveModel(args("--provider", "old", "--model", "m", "--base-url", "http://localhost"), Catalog(emptyList(), emptyList()))
        assertEquals(Wire.AnthropicMessages, legacy.wire)
        assertEquals(8192, legacy.profile.maxOutputTokens)
    }

    @Test fun `bad wire limits and missing endpoints are usage errors`() {
        assertThrows(UsageException::class.java) { args("--model", "m", "--wire", "gemini") }
        assertThrows(UsageException::class.java) { args("--model", "m", "--context", "0") }
        assertThrows(UsageException::class.java) { resolveModel(args("--provider", "missing", "--model", "m"), Catalog(emptyList(), emptyList())) }
    }

    @Test fun `real catalog Chat entries retain reasoning while template URLs require configuration`() {
        val catalog = Catalog.bundled()
        val selected = resolveModel(args("--provider", "deepseek-openai", "--model", "deepseek-flash", "--api-key-env", "DEEPSEEK_API_KEY"), catalog)
        assertEquals(Wire.OpenAIChat, selected.wire)
        assertEquals("reasoning_content", selected.provider.quirks.openAiChat.reasoningField)
        assertEquals(ReasoningEcho.WithinToolLoop, selected.profile.reasoningEcho)
        assertEquals(1000000, selected.profile.contextWindow)
        assertEquals("DEEPSEEK_API_KEY", selected.keyEnv)
        assertThrows(UsageException::class.java) { resolveModel(args("--provider", "qwen-openai", "--model", "qwen3.8-max"), catalog) }
        val configured = resolveModel(args("--provider", "qwen-openai", "--model", "qwen3.8-max", "--base-url", "https://space.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"), catalog)
        assertEquals("https://space.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", configured.provider.baseUrl)
        assertEquals(catalog.model(ModelRef("qwen-openai", "qwen3.8-max")), configured.profile)
    }
}
