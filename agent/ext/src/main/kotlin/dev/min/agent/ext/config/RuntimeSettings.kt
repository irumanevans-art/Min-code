package dev.min.agent.ext.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `fallbackModel` 的取值与它来自哪一层。CC 的口径：不跨文件合并，定义了它的最高层整条链都用那一层的
 * （「the highest-precedence file that defines it supplies the whole chain」）。
 * [source] 要留着：项目 / 本地层是随仓库来的，内核据此不认它写的跨供应商条目（见 `FallbackChain.resolve`）。
 */
data class FallbackModelSetting(val entries: List<String>, val source: ConfigSource)

/**
 * 最高层定义了 `fallbackModel` 的那一层的取值。CC 写成字符串数组；Min 同时认逗号分隔的字符串
 * （任务单与 `--fallback-model a,b` 同一种写法）。类型不对的层当没定义——这里没有地方记 note，而坏值
 * 不该盖住低层的好值。
 */
fun MergedSettings.fallbackModelSetting(): FallbackModelSetting? {
    for (layer in layers.asReversed()) {
        val entries = when (val v = layer.raw["fallbackModel"]) {
            is JsonArray -> v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return@map null }.filterNotNull()
            is JsonPrimitive -> if (v.isString) listOf(v.content) else continue
            else -> continue
        }
        return FallbackModelSetting(entries, layer.source)
    }
    return null
}

/** settings 的 `awaySummaryEnabled`（会话回顾）：没写为 null（= 开，CC 默认开），不是布尔的层忽略 */
fun MergedSettings.awaySummaryEnabled(): Boolean? =
    layers.asReversed().firstNotNullOfOrNull { (it.raw["awaySummaryEnabled"] as? JsonPrimitive)?.booleanOrNull }
