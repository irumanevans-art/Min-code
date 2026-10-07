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
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.StartChoices
import dev.min.code.core.engine.StartProfile
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

/**
 * 一个档在 Min 里实际请求的 base 地址。
 *
 * Claude 风格的档入库时会**剥掉末尾 `/v1`**（`normalizeBaseUrl`：CLI 自己去拼 `/v1/messages`），
 * 但 OpenAI 系方言（Chat / Responses）的客户端是 `base + "/responses"` 直接拼——档里剥过了就会请求 `…/responses`，
 * 对 `…/v1/responses` 的真实端点是 404（模拟器上用 Lunora 的档实测到的），目录里「地址 + 方言」的匹配也永远对不上。
 * 这里还原：目录里有的地址（带不带 `/v1` 都试）以目录为准；档里本来就带着版本段（`/v1`、`/v4`…）的原样用；
 * 其余按 OpenAI 的约定补 `/v1`（和 `joinOpenAiApi` 同一个口径）。Anthropic 方言不动。
 */
internal fun agentBaseUrl(profile: ApiProfile, catalog: Catalog): String {
    val wire = agentWire(profile.apiFormat)
    if (wire == Wire.AnthropicMessages) return profile.baseUrl
    val given = profile.baseUrl.trimEnd('/')
    val known = catalog.providers.filter { it.wire == wire }.map { it.baseUrl.trimEnd('/') }
    return when {
        given in known -> profile.baseUrl
        "$given/v1" in known -> "$given/v1"
        VERSION_SEGMENT.containsMatchIn(given) -> profile.baseUrl
        else -> "$given/v1"
    }
}

private val VERSION_SEGMENT = Regex("/v\\d+$", RegexOption.IGNORE_CASE)

internal data class ProviderSelection(val provider: ProviderDescriptor, val model: ModelProfile, val notice: String?)

internal fun selectProvider(profile: ApiProfile, modelId: String, catalog: Catalog): ProviderSelection {
    require(modelId.isNotBlank()) { "请指定模型 id" }
    val wire = agentWire(profile.apiFormat)
    val baseUrl = agentBaseUrl(profile, catalog)
    val bundled = catalog.providers.firstOrNull {
        it.baseUrl.trimEnd('/') == baseUrl.trimEnd('/') && it.wire == wire &&
            catalog.model(ModelRef(it.id, modelId)) != null
    }
    val ref = ModelRef(profile.id, modelId)
    val model = bundled?.let { catalog.model(ModelRef(it.id, modelId)) }?.copy(ref = ref)
        ?: ModelProfile(ref, family = ModelFamily.Other,
            contextWindow = ClaudeCodeModelCatalog.assumedContextWindow(modelId), maxOutputTokens = 8192)
    val provider = bundled?.copy(id = profile.id, baseUrl = baseUrl, auth = agentAuth(profile.tokenEnvKey))
        ?: ProviderDescriptor(profile.id, baseUrl, wire, agentAuth(profile.tokenEnvKey))
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
 * 开始面板的选项表（[StartChoices]）：能用的档各带它能选的模型与默认模型，默认档按 [defaultAgentProfile] 的规矩，
 * 权限模式表由调用方给（与设置面板同一份）。纯函数：依赖都从参数进来，单测里不用起引擎。
 *
 * 默认模型：这个档上次选的 > 档案 env 里指定的 / 目录第一个（[defaultModelId]）；都没有就是 null，面板让用户手填。
 */
internal fun startChoicesOf(
    settings: AppSettings,
    lastUsedProfileId: String?,
    lastModelFor: (profileId: String) -> String?,
    modelIds: (ApiProfile) -> List<String>,
    defaultModelId: (ApiProfile) -> String?,
    modes: List<Choice>,
    defaultModeId: String,
): StartChoices = StartChoices(
    profiles = usableAgentProfiles(settings).map { p ->
        StartProfile(
            id = p.id,
            label = p.displayName(),
            models = modelIds(p).map { id -> Choice(id = id, label = id, profileId = p.id) },
            defaultModelId = lastModelFor(p.id) ?: defaultModelId(p),
        )
    },
    defaultProfileId = defaultAgentProfile(settings, null, lastUsedProfileId)?.id,
    modes = modes,
    defaultModeId = defaultModeId,
)

/**
 * 凭证只在 Keystore 与 HTTP 头之间流动：每次请求现从设置快照里按档 id 取，档里的 token 改了下一次请求就生效。
 * 取不到（档被删、没有 key）返回 null，客户端据此报鉴权错误，而不是拿别家的 key 去敲这家的门。
 */
internal fun agentSecrets(settings: () -> AppSettings?): Secrets = Secrets { id ->
    settings()?.profiles?.firstOrNull { it.id == id }?.token?.takeIf { it.isNotBlank() }?.let(::Secret)
}
