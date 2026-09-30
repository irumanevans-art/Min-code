package dev.min.agent.cli

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ProviderQuirks
import dev.min.agent.llm.Wire
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFamily
import java.util.Locale

internal data class ModelSelection(val provider: ProviderDescriptor, val profile: ModelProfile, val keyEnv: String) {
    val wire: Wire get() = provider.wire
}

/** 保留目录里的能力与怪癖；命令行只覆盖用户明确指定的字段，避免默认参数冲掉模型数据。 */
internal fun resolveModel(args: CliArgs, catalog: Catalog): ModelSelection {
    val bundled = catalog.provider(args.provider)
    val wire = args.wire ?: bundled?.wire ?: Wire.AnthropicMessages
    if (wire != Wire.AnthropicMessages && wire != Wire.OpenAIChat) throw UsageException("CLI 尚不支持协议 $wire")
    val provider = ProviderDescriptor(
        id = args.provider,
        baseUrl = args.baseUrl ?: bundled?.baseUrl ?: throw UsageException("供应商 ${args.provider} 没有默认地址，请用 --base-url 指定"),
        wire = wire,
        auth = args.auth ?: bundled?.auth ?: if (wire == Wire.OpenAIChat) AuthScheme.Bearer else AuthScheme.XApiKey,
        extraHeaders = bundled?.extraHeaders ?: emptyMap(),
        // 切换线协议时不能把另一种协议的供应商假设带过去。
        quirks = if (bundled?.wire == wire) bundled.quirks else ProviderQuirks(),
    )
    val ref = ModelRef(args.provider, args.model)
    val profile = (catalog.model(ref) ?: ModelProfile(
        ref = ref, family = ModelFamily.Other, contextWindow = 200_000, maxOutputTokens = 8_192,
    )).let { it.copy(contextWindow = args.contextWindow ?: it.contextWindow, maxOutputTokens = args.maxOutput ?: it.maxOutputTokens) }
    return ModelSelection(provider, profile, args.apiKeyEnv ?: "${args.provider.uppercase(Locale.ROOT)}_API_KEY")
}
