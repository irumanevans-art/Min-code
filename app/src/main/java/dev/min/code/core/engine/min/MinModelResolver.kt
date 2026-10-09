package dev.min.code.core.engine.min

import dev.min.agent.kernel.ModelResolver
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.Wire
import dev.min.agent.llm.anthropic.AnthropicMessagesClient
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.openai.OpenAIChatClient
import dev.min.agent.llm.openai.OpenAIResponsesClient
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.HttpTransport
import dev.min.code.core.engine.Choice
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 [ModelRef.providerId]（= `ApiProfile.id`）解析模型（设计 5.1）：每次 [resolve] 都去设置快照里按 id 找档，
 * 所以档里的 token / 地址改了，下一次请求就生效；档被删了抛异常，那个会话的回合以「失败」收场，
 * 界面上会话是只读的（[MinModelLookup.providerLabel] 为 null）。
 *
 * 客户端按 `(档 id, 地址, 方言, 鉴权头)` 缓存：同一档的多个会话共用一个，改了其中任何一项就换新的。
 * token 不在缓存键里——它每次请求现取（[agentSecrets]），不会被缓存住旧值。
 *
 * [settings] 是**同步**取快照的函数：[ModelResolver.resolve] 不是挂起函数，回合协程里没法 await DataStore。
 * 组合根传 `SettingsStore.snapshot`（`AppScope` 里有长期收集保证它是新的）。
 */
class MinModelResolver(
    private val settings: () -> AppSettings?,
    private val transport: HttpTransport,
    private val text: MinAgentText,
    private val catalog: Catalog = Catalog.bundled(),
) : ModelResolver, MinModelLookup {
    private val secrets = agentSecrets(settings)
    private val clients = ConcurrentHashMap<ClientKey, ModelClient>()

    private data class ClientKey(val profileId: String, val baseUrl: String, val wire: Wire, val auth: String)

    private fun profile(providerId: String): ApiProfile? = settings()?.profiles?.firstOrNull { it.id == providerId }

    override fun resolve(ref: ModelRef): ResolvedModel {
        val profile = checkNotNull(profile(ref.providerId)) { "供应商档 ${ref.providerId} 不存在（可能已被删除）" }
        check(!profile.missingToken) { "供应商「${profile.displayName()}」没有 key" }
        val selection = selectProvider(profile, ref.modelId, catalog)
        val key = ClientKey(profile.id, selection.provider.baseUrl, selection.provider.wire, profile.tokenEnvKey)
        val client = clients.getOrPut(key) {
            when (selection.provider.wire) {
                Wire.AnthropicMessages -> AnthropicMessagesClient(selection.provider, transport, secrets)
                Wire.OpenAIChat -> OpenAIChatClient(selection.provider, transport, secrets)
                Wire.OpenAIResponses -> OpenAIResponsesClient(selection.provider, transport, secrets)
                else -> error("协议不可用：${selection.provider.wire}")
            }
        }
        // 档案删掉重建之类让旧键不再被用到：只留当前设置里还存在的档，免得客户端表一直长
        settings()?.profiles?.map { it.id }?.toSet()?.let { alive -> clients.keys.removeAll { it.profileId !in alive } }
        return ResolvedModel(selection.model, client)
    }

    override fun profileOf(ref: ModelRef): ModelProfile? =
        profile(ref.providerId)?.let { selectProvider(it, ref.modelId, catalog).model }

    override fun providerLabel(providerId: String): String? = profile(providerId)?.displayName()

    /** 档对应的目录供应商（地址 + 方言对得上的第一家）给的小模型。档的 id 才是 [ModelRef.providerId]，目录的供应商 id 不是 */
    override fun fastModelOf(providerId: String): ModelRef? {
        val profile = profile(providerId) ?: return null
        val baseUrl = agentBaseUrl(profile, catalog).trimEnd('/')
        val wire = agentWire(profile.apiFormat)
        val provider = catalog.providers.firstOrNull { it.baseUrl.trimEnd('/') == baseUrl && it.wire == wire } ?: return null
        // 内核的默认规则（Anthropic 固定 Haiku，其余读目录的 fastModel，没有就是会话模型自己）原样用，不在这里再抄一份；
        // 没有小模型时它退回传进去的那个模型（这里是个空 id），按“没有”处理
        val fast = dev.min.agent.kernel.defaultAutoReviewer(ModelRef(provider.id, "")) { id -> catalog.provider(id)?.fastModel?.let { ModelRef(id, it) } }
        return fast.modelId.takeIf { it.isNotBlank() }?.let { ModelRef(providerId, it) }
    }

    /**
     * 一个档能选的模型：档案 env 里写的（`ANTHROPIC_MODEL` 在前，三个 `ANTHROPIC_DEFAULT_*_MODEL` 随后）
     * 加上目录里「地址 + 方言」都对得上的随包模型。不在目录里的模型也能用，只是能力按保守值（卡片上标出来）。
     */
    fun modelIds(profile: ApiProfile): List<String> {
        val fromEnv = ENV_MODEL_KEYS.mapNotNull { profile.env[it]?.trim()?.takeIf(String::isNotEmpty) }
        val wire = agentWire(profile.apiFormat)
        val fromCatalog = catalog.providers
            .filter { it.baseUrl.trimEnd('/') == agentBaseUrl(profile, catalog).trimEnd('/') && it.wire == wire }
            .flatMap { p -> catalog.models.filter { it.ref.providerId == p.id }.map { it.ref.modelId } }
        return (fromEnv + fromCatalog).distinct()
    }

    /** 新建会话默认用哪个模型：档案里指定的（env 第一项），没有就目录里第一个；都没有返回 null，调用方让用户手填 */
    fun defaultModelId(profile: ApiProfile): String? = modelIds(profile).firstOrNull()

    override fun choices(current: ModelRef): List<Choice> {
        val s = settings() ?: return listOf(choiceOf(current, profile(current.providerId), current.providerId))
        val usable = usableAgentProfiles(s)
        // 当前档先排：即使它没 key 了（只读会话）也要让当前模型出现在列表里
        val ordered = listOfNotNull(profile(current.providerId)) + usable.filter { it.id != current.providerId }
        val all = ordered.flatMap { p ->
            val ids = if (p.id == current.providerId) (listOf(current.modelId) + modelIds(p)).distinct() else modelIds(p)
            ids.map { choiceOf(ModelRef(p.id, it), p, current.providerId) }
        }
        return all.ifEmpty { listOf(choiceOf(current, null, current.providerId)) }
    }

    /** 别家档的模型在名字后面带上档名，免得两个档里同名的模型在表里分不清 */
    private fun choiceOf(ref: ModelRef, profile: ApiProfile?, currentProviderId: String): Choice {
        val conservative = profile != null && selectProvider(profile, ref.modelId, catalog).notice != null
        val foreign = profile != null && profile.id != currentProviderId
        return Choice(
            id = ref.modelId,
            label = if (foreign) text.modelOtherProvider(ref.modelId, profile!!.displayName()) else ref.modelId,
            warning = if (conservative) text.modelConservative() else null,
            profileId = ref.providerId,
        )
    }

    private companion object {
        val ENV_MODEL_KEYS = listOf(
            "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        )
    }
}
