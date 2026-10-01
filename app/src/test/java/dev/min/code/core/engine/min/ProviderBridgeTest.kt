package dev.min.code.core.engine.min

import dev.min.agent.llm.*
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFamily
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.settings.*
import org.junit.Assert.*
import org.junit.Test

class ProviderBridgeTest {
    @Test fun allDialectsChooseTheirOwnCodec() {
        assertEquals(Wire.AnthropicMessages, agentWire(ApiFormat.ANTHROPIC_MESSAGES))
        assertEquals(Wire.OpenAIChat, agentWire(ApiFormat.OPENAI_CHAT))
        assertEquals(Wire.OpenAIResponses, agentWire(ApiFormat.OPENAI_RESPONSES))
    }
    @Test fun authFollowsTokenEnvKey() {
        assertEquals(AuthScheme.XApiKey, agentAuth("ANTHROPIC_API_KEY"))
        assertEquals(AuthScheme.Bearer, agentAuth("ANTHROPIC_AUTH_TOKEN"))
        assertThrows(IllegalStateException::class.java) { agentAuth("CUSTOM_TOKEN") }
    }
    private val known = ModelProfile(ModelRef("factory", "model"), family = ModelFamily.DeepSeek,
        contextWindow = 123456, maxOutputTokens = 4321, supportsImages = true)
    private val provider = ProviderDescriptor("factory", "https://example.test/v1", Wire.OpenAIChat, AuthScheme.Bearer,
        quirks = ProviderQuirks(openAiChat = OpenAiChatQuirks(reasoningField = "reasoning_content")))
    private val catalog = Catalog(listOf(provider), listOf(known))
    private val profile = ApiProfile("user", baseUrl = "https://example.test/v1/", apiFormat = ApiFormat.OPENAI_CHAT,
        authHeader = AuthHeader.API_KEY, token = "not-for-descriptor", env = mapOf("TOKEN" to "not-for-bash"))

    @Test fun addressAndModelPreserveCatalogCapabilitiesWithUserIdentity() {
        val selected = selectProvider(profile, "model", catalog)
        assertEquals(known.copy(ref = ModelRef("user", "model")), selected.model)
        assertEquals(profile.baseUrl, selected.provider.baseUrl)
        assertEquals("user", selected.provider.id)
        assertEquals(provider.quirks, selected.provider.quirks)
        assertEquals(AuthScheme.XApiKey, selected.provider.auth)
        assertNull(selected.notice)
        assertFalse(selected.provider.toString().contains(profile.token))
    }
    @Test fun unknownAddressDoesNotBorrowNamedModelCapabilities() {
        val selected = selectProvider(profile.copy(baseUrl = "https://proxy.test"), "model", catalog)
        assertEquals(ModelFamily.Other, selected.model.family)
        assertFalse(selected.model.supportsImages)
        assertTrue(selected.model.effortPatches.isEmpty())
        assertEquals(ClaudeCodeModelCatalog.assumedContextWindow("model"), selected.model.contextWindow)
        assertEquals("未在目录中，能力按保守值处理", selected.notice)
    }
    @Test fun unknownModelAndWrongWireUseConservativeFallback() {
        assertNotNull(selectProvider(profile, "unknown", catalog).notice)
        assertNotNull(selectProvider(profile.copy(apiFormat = ApiFormat.OPENAI_RESPONSES), "model", catalog).notice)
        assertThrows(IllegalArgumentException::class.java) { selectProvider(profile, " ", catalog) }
    }
    @Test fun subscriptionNeverProvidesEvenExistingApiProfile() {
        val settings = AppSettings(profiles = listOf(profile), activeProfileId = profile.id, claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        val e = assertThrows(IllegalStateException::class.java) { availableAgentProfile(settings) }
        assertTrue(e.message!!.contains("官方 CLI"))
    }
    @Test fun activeProfileAndMissingProfile() {
        assertEquals(profile, availableAgentProfile(AppSettings(profiles = listOf(profile), activeProfileId = "missing")))
        assertThrows(IllegalStateException::class.java) { availableAgentProfile(AppSettings()) }
    }
    @Test fun bundledCatalogLoadsInAppUnitTests() { assertTrue(Catalog.bundled().models.isNotEmpty()) }
}
