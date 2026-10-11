package dev.min.agent.llm.catalog

import dev.min.agent.protocol.ModelRef

/**
 * 模型别名（CC 的 `sonnet` / `opus` / `haiku` / `fable` / `best` / `default` / `opusplan`，以及 `[1m]` 后缀）。
 *
 * 口径（照 CC 模型配置文档）：别名不是固定的型号，而是「这家供应商当前的某一档」，所以设置里存的是别名本身，
 * 每次用的时候再解析，型号升级后自动跟着走。档位在目录里用 [ModelProfile.tier] 标：Anthropic 的档就是它的 Claude 型号；
 * 非 Anthropic 没有同名的档，就取同一家目录里对应的那一个型号（例如 deepseek 的 `haiku` 是 `deepseek-flash`），
 * 这一家目录里没有这一档就报错、点名这一档，不悄悄换成别的档。
 *
 * - `default`：CC 是「清掉覆盖，回到账户的默认模型」；Min 没有账户默认，取同家的 opus 档（与 CC 默认 Opus 的口径一致）。
 * - `best`：有 fable 档用 fable，没有就用 opus（CC：和 opus 一样）。
 * - `opusplan`：计划模式用 opus 档，其余用 sonnet 档（CC 的组合别名）。
 * - `[1m]`：不改型号，只把上下文窗口标成 1M；型号本身已有 1M 的，这个后缀没有效果（由调用方决定怎么用 [Resolved.oneMillion]）。
 *
 * 不是别名的字符串（完整型号 id，包括大小写不同的 `Sonnet`）原样当型号 id 用，找不到是内核的事。
 */
object ModelAliases {
    /** 档位名；`fable` 只有有 fable 档的供应商才有 */
    val TIERS: List<String> = listOf("fable", "opus", "sonnet", "haiku")

    /** 别名解析结果：[ref] 是目录里的具体型号（或原样的完整 id），[oneMillion] 是否带了 `[1m]` */
    data class Resolved(val ref: ModelRef, val oneMillion: Boolean)

    private val SUFFIX = Regex("""\[1m\]$""", RegexOption.IGNORE_CASE)

    /** 去掉末尾的 `[1m]`（大小写不敏感），返回 (去掉后的名字, 是否带了) */
    fun splitSuffix(text: String): Pair<String, Boolean> {
        val has = SUFFIX.containsMatchIn(text)
        return (if (has) text.replace(SUFFIX, "") else text) to has
    }

    /** 是不是别名（只认小写；`Sonnet` 不是别名） */
    fun isAlias(name: String): Boolean = name == "default" || name == "best" || name == "opusplan" || name in TIERS

    /**
     * 把 [ref] 的 modelId 解析成 [catalog] 里这家供应商的型号。[planMode] 只对 `opusplan` 有意义。
     * 完整 id 原样返回（找不到不在这里报错）。别名在这家目录里没有对应档位就抛 [IllegalArgumentException]。
     */
    fun resolve(ref: ModelRef, catalog: Catalog, planMode: Boolean = false): Resolved {
        val (name, oneMillion) = splitSuffix(ref.modelId)
        if (!isAlias(name)) return Resolved(ModelRef(ref.providerId, name), oneMillion)
        val provider = ref.providerId
        fun tier(t: String): String? = catalog.models.firstOrNull { it.ref.providerId == provider && it.tier == t }?.ref?.modelId
        val target = when (name) {
            "default" -> tier("opus") ?: throw missing(provider, "opus")
            "best" -> tier("fable") ?: tier("opus") ?: throw missing(provider, "opus")
            "opusplan" -> if (planMode) tier("opus") ?: throw missing(provider, "opus") else tier("sonnet") ?: throw missing(provider, "sonnet")
            else -> tier(name) ?: throw missing(provider, name)
        }
        return Resolved(ModelRef(provider, target), oneMillion)
    }

    private fun missing(provider: String, tier: String) =
        IllegalArgumentException("供应商 $provider 的目录里没有 $tier 档，请写具体型号（或换一个供应商）")
}
