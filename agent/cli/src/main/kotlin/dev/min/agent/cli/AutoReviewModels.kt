package dev.min.agent.cli

import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.Wire
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.spi.AutoModeReviewer
import dev.min.agent.spi.ModelFamily
import java.util.Locale

internal fun resolveReviewerSelection(setting: AutoModeReviewer, catalog: Catalog, session: ModelSelection): ModelSelection {
    val ref = setting.model
    val provider = if (ref.providerId == session.provider.id) session.provider else catalog.provider(ref.providerId)
        ?: throw UsageException("审查供应商 ${ref.providerId} 不在目录里，请先配置该供应商")
    if (provider.wire !in setOf(Wire.AnthropicMessages, Wire.OpenAIChat, Wire.OpenAIResponses)) throw UsageException("CLI 尚不支持审查协议 ${provider.wire}")
    val profile = catalog.model(ref) ?: when {
        setting.contextWindow != null && setting.maxOutputTokens != null -> ModelProfile(
            ref = ref, family = ModelFamily.Other, contextWindow = requireNotNull(setting.contextWindow), maxOutputTokens = requireNotNull(setting.maxOutputTokens),
        )
        ref == session.profile.ref -> session.profile
        ref.providerId == "anthropic" && ref.modelId == "claude-haiku-5-5" ->
            // 用户明确指定此默认 id；目录尚无其能力与价格，审查只用保守的纯文本窗口，不借用别的 Haiku 定价。
            ModelProfile(ref = ref, family = ModelFamily.Claude, contextWindow = 16_384, maxOutputTokens = 512)
        else -> throw UsageException("审查模型 $ref 不在目录里；请在 autoModeReviewer 对象声明 contextWindow 与 maxOutputTokens")
    }
    val keyEnv = if (provider.id == session.provider.id) session.keyEnv else "${provider.id.uppercase(Locale.ROOT)}_API_KEY"
    return ModelSelection(provider, profile, keyEnv)
}
