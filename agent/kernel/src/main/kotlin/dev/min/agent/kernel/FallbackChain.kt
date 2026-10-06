package dev.min.agent.kernel

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.FallbackModelSetting
import dev.min.agent.protocol.ModelRef

/** 回退链的解析结果：[models] 是按顺序要试的模型，[notes] 说明哪些条目被丢了、为什么（给人看） */
data class ResolvedFallbackChain(val models: List<ModelRef>, val notes: List<String> = emptyList())

/**
 * 模型回退链的配置解析（CC 的 `fallbackModel` / `--fallback-model`）。纯函数，不碰模型目录与网络：
 * 目录里有没有这个模型、怎么连，是宿主在 [ModelResolver] 里的事。
 *
 * 写法：逗号分隔，每项 `model` 或 `provider:model`（Min 的超越：链里可以跨供应商）。
 * 没写供应商就是主模型的那家。模型 id 本身常带冒号（`qwen3:8b`、Bedrock 的 `…-v1:0`），
 * 所以只有冒号前面**是已知供应商**才当供应商前缀，否则整串都是模型 id。
 */
object FallbackChain {
    /** CC：去重之后最多保留三个，多出的忽略 */
    const val MAX_MODELS = 3

    /**
     * [flag] 是命令行 `--fallback-model`，给了就整个盖过 [setting]（CC：flag 优先，不合并）。
     * [primary] 是主模型：写进链里的主模型没有意义（它刚失败过），丢掉；这个过滤在去重之后、截三个之前，
     * 所以「主模型 + 三个备用」仍能留下三个备用。
     *
     * 项目 / 本地层的 settings 随仓库而来：它写的跨供应商条目会让对话内容被送去另一家，用的还是用户在环境里
     * 配好的那家的 key——不认，记一条说明。用户层、托管层与命令行是用户自己的选择，照认。
     */
    fun resolve(
        flag: String?,
        setting: FallbackModelSetting?,
        primary: ModelRef,
        isProvider: (String) -> Boolean,
    ): ResolvedFallbackChain {
        val notes = mutableListOf<String>()
        val raw = flag?.let(::listOf) ?: setting?.entries.orEmpty()
        val trustedForCrossProvider = flag != null || setting?.source !in setOf(ConfigSource.Project, ConfigSource.Local)
        val parsed = parse(raw, primary.providerId, isProvider).filter { ref ->
            val allowed = ref.providerId == primary.providerId || trustedForCrossProvider
            if (!allowed) {
                notes += "忽略 ${setting?.source?.label} 层 fallbackModel 里的 ${ref.providerId}:${ref.modelId}：" +
                    "项目设置不能把对话送去别的供应商，要用请写进用户设置或 --fallback-model"
            }
            allowed
        }
        return ResolvedFallbackChain(parsed.filter { it != primary }.take(MAX_MODELS), notes)
    }

    /** 逗号分隔的各项 → 模型引用，保持顺序、去重。没有写供应商的算 [defaultProvider] */
    fun parse(entries: List<String>, defaultProvider: String, isProvider: (String) -> Boolean): List<ModelRef> =
        entries.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.map { item ->
            val colon = item.indexOf(':')
            val prefix = if (colon > 0) item.substring(0, colon) else null
            if (prefix != null && colon < item.length - 1 && isProvider(prefix)) ModelRef(prefix, item.substring(colon + 1))
            else ModelRef(defaultProvider, item)
        }.distinct()
}
