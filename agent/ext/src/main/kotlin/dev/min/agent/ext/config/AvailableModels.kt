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

    /** [modelId] 是否在名单 [entries] 里 */
    fun allows(modelId: String, entries: List<String>): Boolean {
        val names = entries.map(::strip).distinct()
        val id = strip(modelId)
        if (id in names) return true
        if (names.any { it.startsWith("claude-") && (id == it || id.startsWith("$it-")) }) return true
        val fam = family(id) ?: return false
        return fam in names && names.none { it.startsWith("claude-$fam-") }
    }

    /**
     * 别名 [name]（或家族名）在名单下选哪个型号：目录里按顺序（新的在前）第一个被允许的同家族型号；
     * 目录里都不允许时，取名单里点名的同家族具体版本（CC：`["sonnet","claude-opus-4-6"]` 下 `opus` 选到 4.6）。
     * 都没有返回 null。
     */
    fun pick(name: String, entries: List<String>, catalogIds: List<String>): String? {
        val fam = family(name) ?: return null
        catalogIds.firstOrNull { family(it) == fam && allows(it, entries) }?.let { return it }
        return entries.map(::strip).firstOrNull { it.startsWith("claude-") && family(it) == fam && allows(it, entries) }
    }

    private fun strip(text: String): String = text.trim().replace(Regex("""\[1m\]$""", RegexOption.IGNORE_CASE), "")
}
