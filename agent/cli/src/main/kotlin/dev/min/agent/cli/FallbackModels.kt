package dev.min.agent.cli

import dev.min.agent.ext.config.FallbackModelSetting
import dev.min.agent.kernel.FallbackChain
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef

/**
 * 模型回退链在桌面 CLI 里的落地：链上每个模型按它自己的供应商选好能力与凭证变量。
 * [chain] 里只留选得出来的；选不出的（目录里没有这个供应商又没有地址……）记进 [notes]，不让一条写错的备用项挡住启动。
 */
internal class FallbackModels(
    val chain: List<ModelRef>,
    val selections: Map<ModelRef, ModelSelection>,
    val notes: List<String>,
) {
    companion object {
        val None = FallbackModels(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * 命令行 `--fallback-model` 优先于 settings 的 `fallbackModel`（[FallbackChain.resolve]）。
 * 同一供应商的备用模型沿用主模型那一份地址 / 协议 / 凭证写法（`--base-url` 等），上下文窗口与输出上限不沿用——
 * 那是给主模型指定的数；别的供应商按目录的默认来，凭证走它自己的环境变量（`<供应商>_API_KEY`）。
 */
internal fun resolveFallbackModels(
    args: CliArgs,
    catalog: Catalog,
    setting: FallbackModelSetting?,
    primary: ModelSelection,
): FallbackModels {
    val resolved = FallbackChain.resolve(args.fallbackModel, setting, primary.profile.ref) { id ->
        id == primary.provider.id || catalog.provider(id) != null
    }
    val notes = resolved.notes.toMutableList()
    val selections = LinkedHashMap<ModelRef, ModelSelection>()
    for (ref in resolved.models) {
        val same = ref.providerId == primary.provider.id
        val asArgs = args.copy(
            provider = ref.providerId, model = ref.modelId,
            baseUrl = args.baseUrl.takeIf { same }, wire = args.wire.takeIf { same }, auth = args.auth.takeIf { same },
            apiKeyEnv = args.apiKeyEnv.takeIf { same },
            contextWindow = null, maxOutput = null, images = false,
        )
        try {
            selections[ref] = resolveModel(asArgs, catalog)
        } catch (e: UsageException) {
            notes += "回退模型 ${ref.providerId}:${ref.modelId} 用不了，已跳过：${e.message}"
        }
    }
    return FallbackModels(selections.keys.toList(), selections, notes)
}
