package dev.min.agent.ext.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `availableModels` 的取值与来源。[managed] 为真表示名单来自托管层（managed-settings）：托管层定义了就只认它，
 * 低层（user / project / local / --settings）一个条目都不并入，所以用户或项目设置永远放宽不了托管的名单。
 * 空名单（[entries] 为空）= 什么都不让选，不回退到低层。
 */
data class AvailableModelsSetting(val entries: List<String>, val managed: Boolean)

/**
 * 读 `availableModels`（照 CC 设置文档的层级与合并）：
 * - 托管层定义了（哪怕是空数组）：只认托管层的名单。类型不对的托管值按空名单处理（失败关闭，不因写错而放宽）。
 * - 否则 user / project / local / --settings 层里定义了的数组拼起来、去重（与 CC 的数组合并一致）。
 * - 没有任何一层定义：返回 null（不限制）。非数组的低层值当没定义。
 * 条目去掉首尾空白，非字符串与空串丢掉。
 */
fun MergedSettings.availableModelsSetting(): AvailableModelsSetting? {
    val managedLayer = layers.firstOrNull { it.source == ConfigSource.Managed && "availableModels" in it.raw }
    if (managedLayer != null) {
        return AvailableModelsSetting(stringEntries(managedLayer.raw["availableModels"]), managed = true)
    }
    val lower = layers.filter {
        it.source == ConfigSource.User || it.source == ConfigSource.Project || it.source == ConfigSource.Local || it.source == ConfigSource.Cli
    }
    val defined = lower.filter { it.raw["availableModels"] is JsonArray }
    if (defined.isEmpty()) return null
    return AvailableModelsSetting(defined.flatMap { stringEntries(it.raw["availableModels"]) }.distinct(), managed = false)
}

private fun stringEntries(value: JsonElement?): List<String> {
    val array = value as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
}

/**
 * 名单的匹配规则（照 CC 文档「Entries match a model family such as `sonnet`, a version prefix such as `claude-sonnet-4-5`, or a full model ID」）：
 * - 家族名（`sonnet` / `opus` / `haiku` / `fable`）：该家族的所有版本；
 * - 版本前缀（`claude-sonnet-4-5`）：以它开头、并且在段边界上结束的 id（`claude-sonnet-4-50` 不算）；
 * - 完整 id：完全相等。
 * 名单里只要有某家族的具体版本，该家族的通配项就失效（`["sonnet","claude-sonnet-4-5"]` 只放 Sonnet 4.5）。
 * `[1m]` 后缀匹配时忽略。
 */
object AvailableModels {
    private val FAMILIES = listOf("fable", "opus", "sonnet", "haiku")
    private val SEGMENT = Regex("""(^|-)(fable|opus|sonnet|haiku)(-|$)""")

    /** 型号 id 属于哪个家族：本身就是家族名（别名），或 `claude-…-<家族>-…` 里含家族段；其它返回 null */
    fun family(modelId: String): String? {
        val id = modelId.replace(Regex("""\[1m\]$""", RegexOption.IGNORE_CASE), "")
        if (id in FAMILIES) return id
        if (!id.startsWith("claude-")) return null
        return SEGMENT.find(id)?.groupValues?.get(2)
    }

    /**
     * [modelId] 是否在名单 [entries] 里。[exact] = `availableModelsMatch: "exact"`：型号 id 条目只放它点名的那一版，
     * 不再放「在它后面再接一段」的新版本（家族名条目不受影响）
     */
    fun allows(modelId: String, entries: List<String>, exact: Boolean = false): Boolean {
        val names = entries.map(::strip).distinct()
        val id = strip(modelId)
        if (id in names) return true
        if (!exact && names.any { it.startsWith("claude-") && (id == it || id.startsWith("$it-")) }) return true
        val fam = family(id) ?: return false
        return fam in names && names.none { it.startsWith("claude-$fam-") }
    }

    /**
     * 别名 [name]（或家族名）在名单下选哪个型号：目录里按顺序（新的在前）第一个被允许的同家族型号；
     * 目录里都不允许时，取名单里点名的同家族具体版本（CC：`["sonnet","claude-opus-4-6"]` 下 `opus` 选到 4.6）。
     * 都没有返回 null。
     */
    fun pick(name: String, entries: List<String>, catalogIds: List<String>, exact: Boolean = false): String? {
        val fam = family(name) ?: return null
        catalogIds.firstOrNull { family(it) == fam && allows(it, entries, exact) }?.let { return it }
        return entries.map(::strip).firstOrNull { it.startsWith("claude-") && family(it) == fam && allows(it, entries, exact) }
    }

    private fun strip(text: String): String = text.trim().replace(Regex("""\[1m\]$""", RegexOption.IGNORE_CASE), "")
}

/**
 * 模型选择的整套限制（CC 模型配置文档「Restrict model selection」「Block specific models or versions」
 * 「Enforce the allowlist for the default model」）：
 * - [listing]：`availableModels`（见 [availableModelsSetting]）；null = 没有名单；
 * - [exact]：`availableModelsMatch: "exact"`，只读托管层；
 * - [denied]：`deniedModels`，只读托管层；有没有名单都生效，名单放行的也挡；
 * - [enforceDefault]：`enforceAvailableModels`；有托管设置时只读托管源，没有时任何一层都算。
 *
 * 「点名的选择」（`/model`、`--model`、`model` 设置、子 agent / skill / 顾问 / 审查 / 回退链里写的模型）用 [allows]；
 * 「默认选项」（没点名、落到这家供应商默认的那个）用 [allowsDefault]——CC 里名单单独设时动不了默认选项，
 * 只有 deniedModels、非空的 exact 名单、或 enforce + 非空名单才管到它。
 */
data class ModelPolicy(
    val listing: AvailableModelsSetting? = null,
    val exact: Boolean = false,
    val denied: List<String> = emptyList(),
    val enforceDefault: Boolean = false,
    /** 写错了层（低层写了只认托管层的键）、类型不对时给人看的提示；不影响判定 */
    val warnings: List<String> = emptyList(),
) {
    fun allows(modelId: String): Boolean =
        !isDenied(modelId) && (listing == null || AvailableModels.allows(modelId, listing.entries, exact))

    fun allowsDefault(modelId: String): Boolean {
        if (isDenied(modelId)) return false
        val entries = listing?.entries.orEmpty()
        // 空名单：点名的都挡，默认选项不受 enforce 影响（CC 原文）
        if (entries.isEmpty()) return true
        return if (exact || enforceDefault) AvailableModels.allows(modelId, entries, exact) else true
    }

    /**
     * deniedModels：家族名挡整个家族；型号 id 挡它的各种写法（带日期、带 `[1m]` 的同一版），
     * 也就是在段边界上以它开头的 id——与名单的前缀匹配同一口径
     */
    fun isDenied(modelId: String): Boolean {
        if (denied.isEmpty()) return false
        val id = modelId.trim().replace(Regex("""\[1m\]$""", RegexOption.IGNORE_CASE), "")
        val fam = AvailableModels.family(id)
        return denied.any { raw ->
            val d = raw.trim().replace(Regex("""\[1m\]$""", RegexOption.IGNORE_CASE), "")
            id == d || id.startsWith("$d-") || (fam != null && fam == d)
        }
    }
}

/** 读整套模型限制（见 [ModelPolicy]） */
fun MergedSettings.modelPolicy(): ModelPolicy {
    val warnings = mutableListOf<String>()
    val managedLayers = layers.filter { it.source == ConfigSource.Managed }
    val lower = layers.filter { it.source != ConfigSource.Managed && it.source != ConfigSource.Plugin }
    // 只认托管层的两个键：低层写了照原文忽略并提示
    for (key in listOf("deniedModels", "availableModelsMatch")) {
        lower.filter { key in it.raw }.forEach { warnings += "${it.source.label} 层的 $key 被忽略：这个键只认托管（managed）设置（${it.path.value}）" }
    }
    val deniedRaw = managedLayers.lastOrNull { "deniedModels" in it.raw }?.raw?.get("deniedModels")
    val denied = when (deniedRaw) {
        null -> emptyList()
        is JsonArray -> deniedRaw.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.distinct()
        else -> { warnings += "托管设置的 deniedModels 不是数组，已忽略"; emptyList() }
    }
    val matchRaw = managedLayers.lastOrNull { "availableModelsMatch" in it.raw }?.raw?.get("availableModelsMatch")
    val exact = when ((matchRaw as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        null -> { if (matchRaw != null) warnings += "托管设置的 availableModelsMatch 不是字符串，按默认的前缀匹配"; false }
        "exact" -> true
        "prefix" -> false
        else -> { warnings += "托管设置的 availableModelsMatch 只能是 exact 或 prefix，按默认的前缀匹配"; false }
    }
    // enforceAvailableModels：有托管设置就只读托管源，否则按层的优先级取最后定义的那层
    val enforceSources = if (managedLayers.isNotEmpty()) managedLayers else lower
    val enforce = (enforceSources.lastOrNull { "enforceAvailableModels" in it.raw }?.raw?.get("enforceAvailableModels") as? JsonPrimitive)
        ?.content?.toBooleanStrictOrNull() == true
    return ModelPolicy(availableModelsSetting(), exact, denied, enforce, warnings)
}
