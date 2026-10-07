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
    @Test fun openAiProfilesGetTheirStrippedVersionSegmentBack() {
        // 入库时 Claude 风格的 normalizeBaseUrl 剥掉了 /v1；OpenAI 系方言要它，否则请求 …/responses 404
        val stripped = profile.copy(baseUrl = "https://example.test")
        assertEquals("目录里有 …/v1 的就按目录", "https://example.test/v1", agentBaseUrl(stripped, catalog))
        assertNull(selectProvider(stripped, "model", catalog).notice)
        assertEquals("https://proxy.test/v1", agentBaseUrl(profile.copy(baseUrl = "https://proxy.test"), catalog))
        assertEquals("档里本来就带版本段的原样", "https://x.test/api/v4", agentBaseUrl(profile.copy(baseUrl = "https://x.test/api/v4"), catalog))
        assertEquals("已经带 /v1（含尾斜杠）的原样", profile.baseUrl, agentBaseUrl(profile, catalog))
        // Anthropic 方言不动
        assertEquals("https://example.test", agentBaseUrl(stripped.copy(apiFormat = ApiFormat.ANTHROPIC_MESSAGES), catalog))
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
    @Test fun subscriptionModeDoesNotHideApiProfiles() {
        // 设计 10.2 拍板 3：会话绑定自己的档，订阅凭证本来就不在 ApiProfile 里，所以订阅模式不影响 Min 用 API 档
        val settings = AppSettings(profiles = listOf(profile), activeProfileId = profile.id, claudeAuth = ClaudeAuthMode.SUBSCRIPTION)
        assertEquals(listOf(profile), usableAgentProfiles(settings))
        assertEquals(profile, defaultAgentProfile(settings, null, null))
    }
    @Test fun profilesWithoutKeyAreNotUsable() {
        val noKey = profile.copy(id = "empty", token = "")
        assertEquals(listOf(profile), usableAgentProfiles(AppSettings(profiles = listOf(noKey, profile))))
        assertNull(defaultAgentProfile(AppSettings(profiles = listOf(noKey)), "empty", "empty"))
    }
    @Test fun defaultProfilePrefersRequestedThenLastUsedThenActive() {
        val a = profile.copy(id = "a"); val b = profile.copy(id = "b"); val c = profile.copy(id = "c")
        val s = AppSettings(profiles = listOf(a, b, c), activeProfileId = "c")
        assertEquals(b, defaultAgentProfile(s, "b", "a"))
        assertEquals(a, defaultAgentProfile(s, null, "a"))
        assertEquals(c, defaultAgentProfile(s, null, null))
        assertEquals(c, defaultAgentProfile(s, "gone", "gone2"))
        assertNull(defaultAgentProfile(AppSettings(), null, null))
    }
    @Test fun secretsAreFetchedPerProfileAndFollowEdits() = kotlinx.coroutines.runBlocking {
        var current: AppSettings? = AppSettings(profiles = listOf(profile))
        val secrets = agentSecrets { current }
        assertEquals("not-for-descriptor", secrets.apiKey("user")!!.reveal())
        assertNull(secrets.apiKey("other"))
        current = AppSettings(profiles = listOf(profile.copy(token = "rotated")))
        assertEquals("rotated", secrets.apiKey("user")!!.reveal())
        current = null
        assertNull(secrets.apiKey("user"))
    }
    @Test fun bundledCatalogLoadsInAppUnitTests() { assertTrue(Catalog.bundled().models.isNotEmpty()) }
}
