package dev.min.code.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.min.agent.kernel.TeamLimits
import dev.min.agent.llm.Cost
import dev.min.code.R
import dev.min.code.core.engine.min.formatCost
import dev.min.code.core.settings.CronBudgetInput
import dev.min.code.core.settings.parseCronBudgetInput
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SettingRow

/**
 * 定时任务单次花费上限（M5，设置项 `cronMaxBudgetUsd`）：**可选，留空 = 不设上限**，和 Claude Code 一样的默认。
 * 设了之后每个定时任务每次运行最多花这么多（任务自己写的上限只能比它更低），超了只停那一次运行。
 * 需要当前模型有 USD 价格，核对不了的运行会被跳过——说明写在输入框下面。
 */
@Composable
internal fun CronBudgetRow(valueUsd: Double?, onChange: (Double?) -> Unit, locked: Boolean = false) {
    var editing by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.settings_cron_budget_title),
        subtitle = lockedSubtitle(
            valueUsd?.let { stringResource(R.string.settings_cron_budget_value, formatCost(Cost(it, "USD"))) }
                ?: stringResource(R.string.settings_cron_budget_none),
            locked,
        ),
        onClick = if (locked) null else { { editing = true } },
    )
    if (editing) CronBudgetDialog(valueUsd, onDismiss = { editing = false }, onSave = { onChange(it); editing = false })
}

@Composable
private fun CronBudgetDialog(valueUsd: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(valueUsd?.let { trimmed(it) }.orEmpty()) }
    val parsed = parseCronBudgetInput(text)
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_cron_budget_title),
        confirmButton = {
            InkTextButton(
                enabled = parsed !is CronBudgetInput.Invalid,
                onClick = { onSave((parsed as? CronBudgetInput.Cap)?.usd) },
            ) { Text(stringResource(R.string.settings_cron_budget_save)) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        InkTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_cron_budget_field),
            placeholder = stringResource(R.string.settings_cron_budget_none_short),
            singleLine = true,
            isError = parsed is CronBudgetInput.Invalid,
            supporting = if (parsed is CronBudgetInput.Invalid) stringResource(R.string.settings_cron_budget_invalid) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Text(
            stringResource(R.string.settings_cron_budget_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InkButton(onClick = { onSave(null) }, tone = InkButtonTone.Paper, compact = true) { Text(stringResource(R.string.settings_cron_budget_clear)) }
    }
}

/** 0.5 -> "0.5"，1.0 -> "1"：输入框里不带多余的 .0 */
/** 对话框里回显已设的上限。不能用 [Double.toString]：0.0001 会写成 `1.0E-4`，解析器不认，重开对话框就成了错误、存不了 */
internal fun trimmed(usd: Double): String = java.math.BigDecimal.valueOf(usd).stripTrailingZeros().toPlainString()

/**
 * 团队同时运行的成员数（`teamMaxConcurrent`，1–8）。多出来的成员排队等名额；调低不会杀正在跑的，只是之后不再放新的；
 * 8 是硬上限（手机既跑不动更多，负责人也看不过来）。
 */
@Composable
internal fun TeamConcurrencyRow(value: Int, onChange: (Int) -> Unit, locked: Boolean = false) {
    val range = 1..TeamLimits.MAX_CONCURRENT
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow(
            title = stringResource(R.string.settings_team_title),
            subtitle = lockedSubtitle(stringResource(R.string.settings_team_subtitle, value, TeamLimits.MAX_CONCURRENT), locked),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkSegmented(
                options = range.map { it.toString() },
                selected = (value - range.first).coerceIn(0, range.last - range.first),
                onSelect = { onChange(range.first + it) },
                enabled = !locked,
            )
        }
    }
}
