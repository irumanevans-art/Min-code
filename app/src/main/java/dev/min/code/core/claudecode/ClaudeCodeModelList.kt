package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.ModelOption
import dev.min.code.core.claudecode.ClaudeCodeManager.RelayModel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 模型**列表**怎么拼：CLI 的 `list_models`、我们补的隐藏别名、中转站的 `/v1/models` 三个来源，
 * 各自的解析与排序。纯函数 + 常量，和会话状态无关，所以不放在 [ClaudeCodeManager] 里。
 *
 * 和 [ClaudeCodeModelCatalog] 的分工：那边是单个模型的静态知识（窗口多大、能不能调 effort），
 * 这边是"界面上该列哪几项、按什么顺序"。
 */
object ClaudeCodeModelList {

    /**
     * CLI 接受但**不会主动列在 list_models 里**的模型别名。
     *
     * 实测（v2.1.246，全新配置 + 中转站 env）：
     *   list_models                  → [default, opus[1m], sonnet, sonnet[1m], haiku]  ← 没有 fable
     *   set_model {"model":"fable"}  → success，applied.model = claude-fable-5
     *   再 list_models               → [default, opus[1m], fable, sonnet, sonnet[1m], haiku]  ← 出现了
     *
     * 即 CLI 只把「当前选中」的模型放进目录。没选中就不列，不列就选不到，
     * 用户会陷在这个死循环里 —— 所以这里补上入口。
     * `--model` 的官方帮助明写着接受 'fable' / 'opus' / 'sonnet' 这类别名。
     *
     * v2.1.261 补充：Fable 在 `/model` 面板里是否可见还有一道门槛（`_se()`：直连要账号
     * 有 usage credits，gateway 下要探测通过），而 `ANTHROPIC_DEFAULT_FABLE_MODEL` 一设就
     * 无条件可见 —— 启动 env 里已经注入。别名 `fable` 在 gateway 下解析成 claude-fable-5
     * （不是 5.1），所以第一项用固定 id。
     *
     * 切换后若中转站不供应该模型，[ClaudeCodeManager.setModel] 会比对 applied.model 并给出提示。
     */
    val HIDDEN_MODEL_ALIASES = listOf(
        // 固定 id 而不是别名：v2.1.261 的表里 `fable` 走 gateway 解析成 claude-fable-5，
        // 直连才是 5.1（见 ClaudeCodeModelCatalog.FABLE_MODEL_ID）。Min 自己的入口不赌别名。
        ModelOption(
            ClaudeCodeModelCatalog.FABLE_MODEL_ID, "Fable 5.1",
            "最强，最难、最长的任务 · 每百万 token \$10 / \$50 · 1M 上下文（原生）· 思考常开",
            resolvedModel = ClaudeCodeModelCatalog.FABLE_MODEL_ID,
            hiddenAlias = true,
        ),
        ModelOption("fable", "Fable（别名）", "由 CLI 解析：直连是 Fable 5.1，经 gateway 可能是 Fable 5", hiddenAlias = true),
        ModelOption("fable[1m]", "Fable（别名，[1m]）", "同上；Fable 原生就是 1M，加不加 [1m] 一样", hiddenAlias = true),
        ModelOption("best", "Best", "自动选当前最强的模型（现在是 Fable）", hiddenAlias = true),
        ModelOption("opusplan", "Opus Plan", "计划阶段用 Opus，执行阶段用 Sonnet", hiddenAlias = true),
        ModelOption("opus", "Opus", "由 CLI 解析：2.1.280 起是 Opus 5.5，更旧的是 Opus 5 · 直连 API 上原生 1M", hiddenAlias = true),
        ModelOption("sonnet", "Sonnet", "Sonnet 5 · 直连 API 上原生 1M", hiddenAlias = true),
        ModelOption("haiku", "Haiku", "Haiku 4.5 · 最快 · 200k · 不支持思考强度", hiddenAlias = true),
    )

    /**
     * 「推荐」区：CLI 目录里没有时补在最前面的那几项。Fable 是最强模型却被 CLI 藏起来
     * （只有选中过才列，或要 usage credits），用户在群里问的第一句就是"为啥没有 fable"。
     */
    val PROMOTED_MODEL_IDS = listOf(ClaudeCodeModelCatalog.FABLE_MODEL_ID)

    /**
     * **路由别名**：不对应任何固定模型 id，所以不能拿它们和 `get_settings` 回读的
     * `applied.model` 做字符串比对。`default` 由中转站决定、`best` 由 CLI 选当前最强的、
     * `opusplan` 在计划/执行两个阶段用不同模型 —— 比对必然失败，会误报"实际运行的是 …"。
     */
    val META_MODEL_ALIASES = setOf("default", "best", "opusplan")

    /**
     * 解析 `GET /v1/models` 的响应。Anthropic 原版和 OpenAI 风格的中转站都是
     * `{"data":[{"id":…}]}`，只是 `display_name` 有没有的区别。
     *
     * 只留 id 里带 "claude" 的：中转站往往把 gpt / gemini 一起列出来，Claude Code 走的是
     * Anthropic 协议，选了也跑不起来。一个都不带 "claude" 时（有的站给模型改名）退回全量，
     * 宁可多列也不能列空。
     */
    internal fun parseRelayModels(json: String): List<RelayModel> {
        val data = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
                .asJsonObjectOrNull()?.get("data").asJsonArrayOrNull()
        }.getOrNull() ?: error("响应不是模型列表")
        val all = data.mapNotNull { el ->
            val obj = el.asJsonObjectOrNull() ?: return@mapNotNull null
            val id = obj["id"].asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 有的中转站把 display_name 填成和 id 一样的东西，那就等于没有
            val display = obj["display_name"].asStringOrNull()?.trim()?.takeIf { it.isNotBlank() && it != id }
            RelayModel(id = id, displayName = display)
        }.distinctBy { it.id }
        val claude = all.filter { it.id.contains("claude", ignoreCase = true) }
        return (claude.ifEmpty { all }).sortedWith(RELAY_MODEL_ORDER)
    }

    /**
     * 目录的排序：先按系列（fable > opus > sonnet > haiku > 其他），同系列新版本在前。
     * 按字典序排的话 `claude-sonnet-5` 会压在所有 opus 上面，而用户最常找的是最新最强的那个。
     */
    private val RELAY_MODEL_ORDER: Comparator<RelayModel> = compareBy<RelayModel> { familyRank(it.id) }
        .thenByDescending { versionKey(it.id) }
        .thenBy { it.id }

    internal fun familyRank(id: String): Int {
        val lower = id.lowercase()
        return when {
            "fable" in lower -> 0
            "opus" in lower -> 1
            "sonnet" in lower -> 2
            "haiku" in lower -> 3
            else -> 4
        }
    }

    /** `claude-opus-4-5-20251101` → 4.5 + 日期；`claude-opus-5` → 5.0。数字段越多越具体，比不出来时看日期 */
    internal fun versionKey(id: String): Long {
        val parts = Regex("[0-9]+").findAll(id).map { it.value }.toList()
        val version = parts.filter { it.length <= 2 }.map { it.toInt() }
        val major = version.getOrNull(0) ?: 0
        val minor = version.getOrNull(1) ?: 0
        val date = parts.firstOrNull { it.length == 8 }?.toLongOrNull() ?: 0L
        return major * 1_000_000_000_000L + minor * 10_000_000_000L + date
    }
}

/**
 * 实测形状：`{value, resolvedModel, displayName, description, supportedEffortLevels, ...}`。
 * 老式的裸字符串条目也兼容一下，免得旧版 CLI 直接不可用。
 *
 * 字段名都是拿真 CLI 对拍出来的，不是照直觉写的：list_models 的模型 id 在 `value`（不是 id/name）。
 */
internal fun JsonElement.toModelOption(): ModelOption? =
    when (this) {
        is JsonPrimitive ->
            contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { ModelOption(value = it, displayName = it) }

        is JsonObject -> {
            val value = this["value"].asStringOrNull() ?: this["id"].asStringOrNull()
            value?.takeIf { it.isNotBlank() }?.let {
                ModelOption(
                    value = it,
                    displayName = this["displayName"].asStringOrNull() ?: it,
                    description = this["description"].asStringOrNull(),
                    supportedEffortLevels = this["supportedEffortLevels"].asJsonArrayOrNull()
                        ?.mapNotNull { lvl -> lvl.asStringOrNull() }
                        .orEmpty(),
                    resolvedModel = this["resolvedModel"].asStringOrNull(),
                )
            }
        }

        else -> null
    }
