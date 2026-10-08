package dev.min.agent.cli

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * settings.json 的 `cronMaxBudgetUsd`：定时任务单次运行花费的宿主天花板（美元）。**可选，默认不设**——定时任务和 Claude Code 一样
 * 没有默认上限。设了就是模型传的 `max_budget_usd` 的天花板（取小）。写错的值（非数字、不大于 0、非有限数）当没设并提示：
 * 宁可不限，也不要因为手误把所有定时任务都卡死在 0。
 */
internal fun cronMaxBudgetUsdFrom(raw: JsonElement?, note: (String) -> Unit): Double? {
    if (raw == null) return null
    val value = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    if (value == null || !value.isFinite() || value <= 0) {
        note("settings 的 cronMaxBudgetUsd 必须是大于 0 的数字（美元），按未设置处理（定时任务不限花费）。")
        return null
    }
    return value
}
