package dev.min.agent.cli

import dev.min.agent.llm.AuthScheme
import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.llm.ProviderQuirks
import dev.min.agent.llm.Wire
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.catalog.ModelAliases
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.ModelFamily
import java.util.Locale

internal data class ModelSelection(val provider: ProviderDescriptor, val profile: ModelProfile, val keyEnv: String) {
    val wire: Wire get() = provider.wire
}

/**
 * 启动时选哪个模型（CC 模型配置文档「Setting your model」「Restrict model selection」）：
 * `--model` → `ANTHROPIC_MODEL` → 设置里的 `model` → 这家供应商的默认选项。点名的选择被模型限制挡住时，
 * 家族别名换成允许的同家族最新版；换不到就警告（点名要的与换成的）并落到默认选项。默认选项也被挡、逐级退也退不到时
 * 拒绝启动并点名该改的键。[warnings] 由调用方打到标准错误。
 * 返回的 [model] 是交给 [resolveModel] 的字符串：别名原样（`opusplan` 要在会话里留着别名才能随计划模式切换），被替换的是具体型号
 */
internal data class StartupModel(val model: String, val warnings: List<String>)

internal fun chooseStartupModel(
    flag: String?,
    envModel: String?,
    setting: String?,
    provider: String,
    catalog: Catalog,
    policy: dev.min.agent.ext.config.ModelPolicy,
): StartupModel {
    val warnings = mutableListOf<String>()
    val (requested, where) = listOf(flag to "--model", envModel to "ANTHROPIC_MODEL", setting to "设置里的 model")
        .firstOrNull { !it.first.isNullOrBlank() }?.let { it.first!!.trim() to it.second } ?: (null to "")
    var blocked: String? = null
    if (requested != null && requested != "default") {
        when (val r = dev.min.agent.kernel.ModelChoice.named(ModelRef(provider, requested), catalog, policy)) {
            is dev.min.agent.kernel.ModelChoice.Result.Ok -> {
                val from = r.substitutedFrom ?: return StartupModel(requested, warnings)
                warnings += "$where 要的 ${from.modelId} 不在允许的模型里，改用同家族允许的 ${r.ref.modelId}"
                return StartupModel(r.ref.modelId + if (r.oneMillion) "[1m]" else "", warnings)
            }
            is dev.min.agent.kernel.ModelChoice.Result.Unknown -> throw UsageException(r.message)
            dev.min.agent.kernel.ModelChoice.Result.Blocked -> blocked = "$where 要的 $requested 不在允许的模型里（availableModels / deniedModels），改用默认模型"
        }
    }
    return when (val d = dev.min.agent.kernel.ModelChoice.default(provider, catalog, policy)) {
        is dev.min.agent.kernel.ModelChoice.Result.Ok -> {
            // CC：替换时的警告点名要的与换成的
            blocked?.let { warnings += "$it ${d.ref.modelId}" }
            d.substitutedFrom?.let { warnings += "默认模型 ${it.modelId} 不在允许的模型里，改用 ${d.ref.modelId}" }
            StartupModel(d.ref.modelId, warnings)
        }
        is dev.min.agent.kernel.ModelChoice.Result.Unknown -> throw UsageException(d.message + "\n\n" + USAGE)
        dev.min.agent.kernel.ModelChoice.Result.Blocked -> throw UsageException(
            "托管设置挡住了默认模型，名单里也没有可用的：请改托管设置的 availableModels / deniedModels / availableModelsMatch，或用 --model 点名一个允许的模型",
        )
    }
}

/** 保留目录里的能力与怪癖；命令行只覆盖用户明确指定的字段，避免默认参数冲掉模型数据。[model] 默认取 `--model`（没给就是默认选项） */
internal fun resolveModel(args: CliArgs, catalog: Catalog, model: String = args.model ?: "default"): ModelSelection {
    val bundled = catalog.provider(args.provider)
    val wire = args.wire ?: bundled?.wire ?: Wire.AnthropicMessages
    if (wire !in setOf(Wire.AnthropicMessages, Wire.OpenAIChat, Wire.OpenAIResponses)) throw UsageException("CLI 尚不支持协议 $wire")
    val provider = ProviderDescriptor(
        id = args.provider,
        baseUrl = args.baseUrl ?: bundled?.baseUrl ?: throw UsageException("供应商 ${args.provider} 没有默认地址，请用 --base-url 指定"),
        wire = wire,
        auth = args.auth ?: bundled?.auth ?: if (wire == Wire.AnthropicMessages) AuthScheme.XApiKey else AuthScheme.Bearer,
        extraHeaders = bundled?.extraHeaders ?: emptyMap(),
        // 切换线协议时不能把另一种协议的供应商假设带过去。
        quirks = if (bundled?.wire == wire) bundled.quirks else ProviderQuirks(),
        fastModel = bundled?.fastModel,
    )
    // 别名（sonnet / opus / haiku / fable / best / default / opusplan，可带 [1m]）先解析成这家供应商目录里的型号；完整 id 原样
    val resolved = try { ModelAliases.resolve(ModelRef(args.provider, model), catalog) } catch (e: IllegalArgumentException) { throw UsageException(e.message ?: "模型别名解析失败") }
    val ref = resolved.ref
    val profile = (catalog.model(ref) ?: ModelProfile(
        ref = ref, family = ModelFamily.Other, contextWindow = 200_000, maxOutputTokens = 8_192,
    )).let {
        it.copy(
            // 带 [1m]：窗口标到 1M（型号本来就是 1M 的没有变化）；命令行 --context 仍然优先
            contextWindow = args.contextWindow ?: if (resolved.oneMillion) maxOf(it.contextWindow, 1_000_000) else it.contextWindow,
            maxOutputTokens = args.maxOutput ?: it.maxOutputTokens,
            // 目录里的能力是查证过的，--images 只能补上（目录外的自定义模型默认当它看不了图），不能关掉
            supportsImages = args.images || it.supportsImages,
        )
    }
    // 目录保存厂商模板，业务空间 ID 是用户配置，不能带着占位符联网后再报难读的 DNS 错误。
    if ('{' in provider.baseUrl || '}' in provider.baseUrl) throw UsageException("供应商 ${args.provider} 的地址含占位符，请用 --base-url 指定实际地址")
    return ModelSelection(provider, profile, args.apiKeyEnv ?: "${args.provider.uppercase(Locale.ROOT)}_API_KEY")
}
