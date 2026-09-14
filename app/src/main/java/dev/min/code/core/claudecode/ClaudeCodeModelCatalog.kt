package dev.min.code.core.claudecode

/**
 * 模型目录的**静态知识**：家族、上下文窗口、是否支持 effort、`[1m]` 后缀有没有意义。
 *
 * ## 为什么要有一份自己的表
 *
 * CLI 的 `list_models` 只列它**当前认得且愿意列**的那几项，中转站的 `/v1/models` 给的是
 * 裸 id（`claude-fable-5-1`），两边都不告诉我们"这个模型窗口多大、能不能调 effort"。
 * 界面上却处处要用到这些：底栏的上下文条要知道分母是 200k 还是 1M，思考强度面板要知道
 * Haiku 根本没有 effort（CLI 原话 "Effort not supported for Haiku"），模型列表要知道
 * Fable 5.1 / Opus 5 / Sonnet 5 是**原生 1M**、不用加 `[1m]`。
 *
 * 数据来源：CLI 二进制内置的模型表（`context:{window, native_1m, supports_1m_suffix}`；
 * 最近一次对照 v2.1.270）与官方 model-config 文档。**CLI 回报的值永远优先**
 * （`get_context_usage.maxTokens`、`list_models[].supportedEffortLevels`），这里只在它没说的时候兜底。
 */
object ClaudeCodeModelCatalog {

    /**
     * Fable 5.1 的固定 id。
     *
     * 别名 `fable` 在 CLI 里**按供应商解析**：直连 Anthropic 是 `claude-fable-5-1`，
     * 走 gateway 时表里写的是 `claude-fable-5`（`fable:{default:"claude-fable-5-1",
     * per_provider:{gateway:"claude-fable-5"}}`）。用户在 Windows 上 `/model fable` 得到
     * "Set model to Fable 5" 就是这一条。所以 Min 自己的入口一律用固定 id，不赌别名。
     */
    const val FABLE_MODEL_ID = "claude-fable-5-1"

    const val CONTEXT_200K = 200_000
    const val CONTEXT_1M = 1_000_000

    /** `[1m]` 后缀：Claude Code 用它表示"这一会话请求 1M 上下文" */
    const val LONG_CONTEXT_SUFFIX = "[1m]"

    enum class Family(val label: String) {
        FABLE("Fable"), OPUS("Opus"), SONNET("Sonnet"), HAIKU("Haiku"), UNKNOWN("");
    }

    /** `opus[1m]` → `opus`；大小写不敏感，多余的 `[1m][1m]` 也一起去掉 */
    fun stripLongContext(model: String): String {
        var out = model.trim()
        while (out.endsWith(LONG_CONTEXT_SUFFIX, ignoreCase = true)) {
            out = out.dropLast(LONG_CONTEXT_SUFFIX.length).trimEnd()
        }
        return out
    }

    fun hasLongContextSuffix(model: String?): Boolean =
        model?.trim()?.endsWith(LONG_CONTEXT_SUFFIX, ignoreCase = true) == true

    /**
     * 按 id 里的家族名判断。`best` 在 CLI 里解析成当前最强（`best:"fable"`），
     * `opusplan` 计划阶段用 Opus；`default` 由账号 / 中转站决定，认不出。
     */
    fun familyOf(model: String?): Family {
        val lower = model?.lowercase().orEmpty()
        return when {
            lower.isBlank() -> Family.UNKNOWN
            "fable" in lower || lower.startsWith("best") -> Family.FABLE
            "opus" in lower -> Family.OPUS
            "sonnet" in lower -> Family.SONNET
            "haiku" in lower -> Family.HAIKU
            else -> Family.UNKNOWN
        }
    }

    /**
     * 原生 1M 上下文：不加 `[1m]` 也是 1M（CLI 表里 `native_1m:true` 的那些）。
     *
     * - Fable 5 / 5.1、Opus 5、Sonnet 5 是；Opus 4.7 / 4.8 也是
     * - Haiku 4.5 是 200k；Opus 4.6 以前、Sonnet 4.6 以前要靠 `[1m]`
     * - 裸别名 `opus` / `sonnet` 在直连 API 上分别解析成 Opus 5 / Sonnet 5，按原生 1M 算；
     *   `haiku` 按 200k 算
     */
    fun isNativeLongContext(model: String?): Boolean {
        val id = stripLongContext(model.orEmpty()).lowercase()
        if (id.isBlank()) return false
        return when (familyOf(id)) {
            Family.FABLE -> true
            Family.HAIKU -> false
            Family.OPUS -> !LEGACY_OPUS.any { id.contains(it) }
            Family.SONNET -> !LEGACY_SONNET.any { id.contains(it) }
            Family.UNKNOWN -> false
        }
    }

    /**
     * 给这个模型加 `[1m]` 有没有意义。原生 1M 的加了也无害（CLI 照收），但界面上
     * 不该再拿"启用 1M 上下文"去问用户 —— 那正是"咋设置 1m 上下文"这类困惑的来源：
     * 答案是"默认就是，不用设"。
     */
    fun longContextSuffixMeaningful(model: String?): Boolean =
        !isNativeLongContext(model) && familyOf(model) != Family.UNKNOWN

    /** Haiku 没有 effort 阶梯（CLI: "Effort not supported for Haiku"），其余家族都有 low…max */
    fun supportsEffort(model: String?): Boolean = familyOf(model) != Family.HAIKU

    /**
     * 没拿到 CLI 回报的上限时，按模型猜一个分母。
     *
     * 认不出的模型（中转站的自定义 id）CLI 自己也按 200k 处理（它会提示
     * "append [1m] to the model name for 1M" 或设 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`），
     * 这里保持一致。`null` / `default` 在直连 API 上是 Opus 5（1M）。
     */
    fun assumedContextWindow(model: String?): Int {
        if (hasLongContextSuffix(model)) return CONTEXT_1M
        val id = model?.trim().orEmpty()
        if (id.isBlank() || id.equals("default", ignoreCase = true)) return CONTEXT_1M
        return if (isNativeLongContext(id)) CONTEXT_1M else CONTEXT_200K
    }

    /**
     * 界面上真正该用的分母。
     *
     * `get_context_usage.maxTokens` 对 CLI 表里没有 `native_1m` 的 id 会报 200k
     * （旧二进制、gateway 解析成的 `claude-fable-5`、中转站自定义 id）。
     * 界面信了就会在 Fable 5.1 上画出 `97k/200k` 并让 CLI 按 200k 自动压缩。
     * 模型本身是原生 1M 时，取 CLI 回报和目录假设里更大的那个；CLI 报得更大
     * （用户设了 `CLAUDE_CODE_MAX_CONTEXT_TOKENS`）则以 CLI 为准。
     */
    fun effectiveContextLimit(reported: Int?, model: String?): Int {
        val assumed = assumedContextWindow(model)
        if (reported == null || reported <= 0) return assumed
        return maxOf(reported, assumed)
    }

    /** `1000000` → `1M`，`200000` → `200k`。上下文条的分母，越短越好 */
    fun formatContextWindow(limit: Int): String = when {
        limit >= 1_000_000 && limit % 100_000 == 0 -> "${limit / 1_000_000}M"
        limit >= 1_000 -> "${limit / 1_000}k"
        else -> limit.toString()
    }

    /**
     * 两个模型标识指的是不是同一个模型。用来把「用户选的 value」和「CLI 回报的 applied.model」
     * 对上：`fable` ↔ `claude-fable-5-1`、`opus[1m]` ↔ `claude-opus-5`。
     * 只做家族级比对，版本号交给 CLI 的 `resolvedModel`。
     */
    fun sameModel(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = stripLongContext(a).lowercase()
        val y = stripLongContext(b).lowercase()
        if (x == y) return true
        // `claude-fable-5-1` 和 `fable-5-1` / `fable5.1` 这种去掉分隔符后相等
        val nx = x.removePrefix("claude-").filter(Char::isLetterOrDigit)
        val ny = y.removePrefix("claude-").filter(Char::isLetterOrDigit)
        return nx.isNotEmpty() && nx == ny
    }

    /** 没有 `native_1m` 的旧 Opus：CLI 表里 `wse()` 那份名单 */
    private val LEGACY_OPUS = listOf("opus-4-0", "opus-4-1", "opus-4-5", "opus-4-6", "opus-4-2025", "opus-3")
    private val LEGACY_SONNET = listOf("sonnet-4-6", "sonnet-4-5", "sonnet-4-2025", "sonnet-4-0", "sonnet-3", "3-5-sonnet", "3-7-sonnet")
}
