package dev.min.code.core.settings

/**
 * 设置里「定时任务单次花费上限」输入框的一次输入（美元）。纯函数，界面把结果交给 [SettingsStore.setCronMaxBudgetUsd]。
 *
 * 留空 = 不设上限——和 Claude Code 一样的默认，定时任务想花多少就花多少，所以空不是错误。
 * 写 0、负数、不是数字都不收：写 0 会让每个任务一触发就超限，那不是「不限」。
 */
sealed interface CronBudgetInput {
    data object NoCap : CronBudgetInput
    data class Cap(val usd: Double) : CronBudgetInput
    data object Invalid : CronBudgetInput
}

private val DECIMAL = Regex("""\d+(\.\d+)?|\.\d+""")

/** 容忍前后空白和开头的 `$`；小数点只认 `.`（`1,000` 这类千分位写法不是用户想表达的数，宁可拒绝） */
fun parseCronBudgetInput(text: String): CronBudgetInput {
    val body = text.trim().removePrefix("$").trim()
    if (body.isEmpty()) return CronBudgetInput.NoCap
    if (!DECIMAL.matches(body)) return CronBudgetInput.Invalid
    val usd = body.toDoubleOrNull() ?: return CronBudgetInput.Invalid
    return if (usd.isFinite() && usd > 0.0 && usd <= MAX_CRON_BUDGET_USD) CronBudgetInput.Cap(usd) else CronBudgetInput.Invalid
}

/** 输入上限：再大的数多半是手滑（多敲了几个 0），而且和「不设上限」没有实际区别 */
const val MAX_CRON_BUDGET_USD = 100_000.0
