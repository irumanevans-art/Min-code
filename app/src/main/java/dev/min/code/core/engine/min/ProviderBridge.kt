package dev.min.code.core.engine.min

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.Wire
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFamily
import dev.min.agent.spi.Secret
import dev.min.agent.spi.Secrets
import dev.min.code.core.claudecode.ClaudeCodeModelCatalog
import dev.min.code.core.settings.ApiFormat
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import dev.min.code.core.settings.ClaudeAuthMode
import dev.min.code.core.settings.SettingsStore

internal fun agentWire(format: ApiFormat): Wire = when (format) {
    ApiFormat.ANTHROPIC_MESSAGES -> Wire.AnthropicMessages
    ApiFormat.OPENAI_CHAT -> Wire.OpenAIChat
    ApiFormat.OPENAI_RESPONSES -> Wire.OpenAIResponses
}

internal fun agentAuth(tokenEnvKey: String): AuthScheme = when (tokenEnvKey) {
    "ANTHROPIC_API_KEY" -> AuthScheme.XApiKey
    "ANTHROPIC_AUTH_TOKEN" -> AuthScheme.Bearer
    else -> error("未知鉴权类型")
}

internal data class ProviderSelection(val provider: ProviderDescriptor, val model: ModelProfile, val notice: String?)

internal fun selectProvider(profile: ApiProfile, modelId: String, catalog: Catalog): ProviderSelection {
    require(modelId.isNotBlank()) { "请指定模型 id" }
    val wire = agentWire(profile.apiFormat)
    val bundled = catalog.providers.firstOrNull {
        it.baseUrl.trimEnd('/') == profile.baseUrl.trimEnd('/') && it.wire == wire &&
            catalog.model(ModelRef(it.id, modelId)) != null
    }
    val ref = ModelRef(profile.id, modelId)
    val model = bundled?.let { catalog.model(ModelRef(it.id, modelId)) }?.copy(ref = ref)
        ?: ModelProfile(ref, family = ModelFamily.Other,
            contextWindow = ClaudeCodeModelCatalog.assumedContextWindow(modelId), maxOutputTokens = 8192)
    val provider = bundled?.copy(id = profile.id, baseUrl = profile.baseUrl, auth = agentAuth(profile.tokenEnvKey))
        ?: ProviderDescriptor(profile.id, profile.baseUrl, wire, agentAuth(profile.tokenEnvKey))
    return ProviderSelection(provider, model, if (bundled == null) "未在目录中，能力按保守值处理" else null)
}

internal fun availableAgentProfile(settings: AppSettings): ApiProfile {
    check(settings.claudeAuth != ClaudeAuthMode.SUBSCRIPTION) { "Min Agent 不可用：Claude 订阅只能通过官方 CLI 使用" }
    return checkNotNull(settings.claudeProfile) { "Min Agent 不可用：未配置供应商" }
}

internal class ProviderBridge(private val settings: SettingsStore) {
    suspend fun select(modelId: String): ProviderSelection =
        selectProvider(availableAgentProfile(settings.current()), modelId, Catalog.bundled())

    // SettingsStore.current 已经由 Keystore 解密；不读取 rootfs 的配置或订阅凭证。
    val secrets = Secrets { id ->
        val current = settings.current()
        if (current.claudeAuth == ClaudeAuthMode.SUBSCRIPTION) null
        else current.profiles.firstOrNull { it.id == id }?.token?.takeIf { it.isNotBlank() }?.let(::Secret)
    }
}
