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

/**
 * 能给 Min 用的供应商档：有 key 的 API 档。**不看当前是不是订阅模式**（设计 10.2 拍板 3）：
 * 订阅凭证本来就不在 [ApiProfile] 里，Min 从来碰不到（ClaudeSubscription 的头注释与 M2-A 的原则不变），
 * 用户切到订阅模式后，已有的 Min 会话照样用自己绑定的 API key 档。M2-A 时这里会因订阅模式整个拒绝，B3 起不再。
 */
internal fun usableAgentProfiles(settings: AppSettings): List<ApiProfile> = settings.profiles.filter { !it.missingToken }

/**
 * 新建会话默认用哪一档：调用方指定的 > 最近一次用的 > 全局当前档 > 第一个能用的。
 * 「最近一次用的」由引擎自己记（[MinLastUsed]）；全局当前档只做兜底，不再是会话的绑定对象。
 */
internal fun defaultAgentProfile(settings: AppSettings, requestedId: String?, lastUsedId: String?): ApiProfile? {
    val usable = usableAgentProfiles(settings)
    return listOfNotNull(requestedId, lastUsedId, settings.activeProfile?.id)
        .firstNotNullOfOrNull { id -> usable.firstOrNull { it.id == id } }
        ?: usable.firstOrNull()
}

/**
 * 凭证只在 Keystore 与 HTTP 头之间流动：每次请求现从设置快照里按档 id 取，档里的 token 改了下一次请求就生效。
 * 取不到（档被删、没有 key）返回 null，客户端据此报鉴权错误，而不是拿别家的 key 去敲这家的门。
 */
internal fun agentSecrets(settings: () -> AppSettings?): Secrets = Secrets { id ->
    settings()?.profiles?.firstOrNull { it.id == id }?.token?.takeIf { it.isNotBlank() }?.let(::Secret)
}
