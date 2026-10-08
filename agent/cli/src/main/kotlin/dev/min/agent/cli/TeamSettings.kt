package dev.min.agent.cli

import dev.min.agent.kernel.TeamLimits
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * settings.json 的 `teamMaxConcurrent`：agent 团队同时运行的成员数（默认 3，硬上限 [TeamLimits.MAX_CONCURRENT]）。
 * 超过硬上限的值按上限用并提示（不再有「要用户确认」的路径）；写错的值用默认值并提示。
 */
internal fun teamMaxConcurrentFrom(raw: JsonElement?, note: (String) -> Unit): Int {
    if (raw == null) return TeamLimits.DEFAULT_MAX_CONCURRENT
    val requested = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (requested == null) {
        note("settings 的 teamMaxConcurrent 必须是整数，按默认值 ${TeamLimits.DEFAULT_MAX_CONCURRENT} 处理。")
        return TeamLimits.DEFAULT_MAX_CONCURRENT
    }
    return when (val setting = TeamLimits.resolve(requested)) {
        is TeamLimits.Setting.Accepted -> {
            setting.note?.let { note("settings 的 $it") }
            setting.value
        }
        is TeamLimits.Setting.Invalid -> {
            note("settings 的 teamMaxConcurrent=$requested 不合法（${setting.reason}），按默认值 ${TeamLimits.DEFAULT_MAX_CONCURRENT} 处理。")
            TeamLimits.DEFAULT_MAX_CONCURRENT
        }
    }
}
