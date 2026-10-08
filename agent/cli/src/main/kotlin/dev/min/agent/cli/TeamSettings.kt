package dev.min.agent.cli

import dev.min.agent.kernel.TeamLimits
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * settings.json 的 `teamMaxConcurrent`：agent 团队同时运行的成员数（默认 3）。
 * 超过 [TeamLimits.CONFIRM_ABOVE] 的值要用户明确确认——命令行没有确认的地方，所以这里不照单全收：
 * 按上限 8 用并提示；真要更多，等有确认界面的宿主（App 的设置页）来设。写错的值用默认值并提示。
 */
internal fun teamMaxConcurrentFrom(raw: JsonElement?, note: (String) -> Unit): Int {
    if (raw == null) return TeamLimits.DEFAULT_MAX_CONCURRENT
    val requested = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (requested == null) {
        note("settings 的 teamMaxConcurrent 必须是整数，按默认值 ${TeamLimits.DEFAULT_MAX_CONCURRENT} 处理。")
        return TeamLimits.DEFAULT_MAX_CONCURRENT
    }
    return when (val setting = TeamLimits.resolve(requested)) {
        is TeamLimits.Setting.Accepted -> setting.value
        is TeamLimits.Setting.NeedsConfirmation -> {
            note("settings 的 teamMaxConcurrent=$requested 超过 ${TeamLimits.CONFIRM_ABOVE}，命令行无法让你确认，按 ${TeamLimits.CONFIRM_ABOVE} 处理。")
            TeamLimits.CONFIRM_ABOVE
        }
        is TeamLimits.Setting.Invalid -> {
            note("settings 的 teamMaxConcurrent=$requested 不合法（${setting.reason}），按默认值 ${TeamLimits.DEFAULT_MAX_CONCURRENT} 处理。")
            TeamLimits.DEFAULT_MAX_CONCURRENT
        }
    }
}
