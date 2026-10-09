package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.engine.AutoReviewView
import dev.min.code.core.engine.Choice
import dev.min.code.core.engine.ConnectionView
import dev.min.code.core.engine.SettingsView
import dev.min.code.core.engine.UsageView
import dev.min.code.ui.theme.sea
import kotlin.math.roundToInt

/**
 * Min 的设置面板专有段（设计 §5.8）。由 [EngineUi.settingsLayout] 决定出不出现，面板本体只管摆。
 *
 * 和 CC 的模型选择画法不同的原因：CC 的列表是 CLI 目录 + 中转站 + 隐藏别名三段，还有「存成新会话默认」「加 [1m]」两个开关，
 * 都是围着 CLI 转的；Min 的模型来自**供应商档**：档案 env 里写的、目录里地址和方言都对得上的随包模型、再加手填。
 * 同一份表里可能混着别家档的模型，所以按档分、选中时带上档 id。
 */
@Composable
internal fun MinModelPicker(
    settings: SettingsView,
    connection: ConnectionView?,
    enabled: Boolean,
    /** (模型 id, 档 id)。档 id 为 null = 沿用当前档（手填的那一栏） */
    onPick: (String, String?) -> Unit,
) {
    // 当前模型改了（热切成功）后清掉输入框，免得看起来像还没生效
    var custom by remember(settings.model?.id, settings.model?.profileId) { mutableStateOf("") }
    Column {
        // 供应商只读：会话创建后不能直接换（设计 §10 未决 4）。别家档的模型在下面的表里，选了才算切过去
        connection?.profileLabel?.let { SettingsSubHeader(stringResource(R.string.min_settings_provider, it)) }
        SettingsSubHeader(stringResource(R.string.min_settings_provider_note))
        val current = settings.model
        settings.models.forEach { choice ->
            // 同一个模型 id 可能出现在两个档里，选中要连档一起比
            val selected = current != null && choice.id == current.id && choice.profileId == current.profileId
            val notes = listOfNotNull(
                // 不在目录里的模型：能力按保守值（适配器已写好这句话）
                choice.warning,
                stringResource(R.string.min_settings_switch_provider).takeIf { choice.switchesProviderFrom(connection) },
            )
            OptionRow(
                title = choice.label,
                subtitle = notes.joinToString("\n").ifEmpty { null },
                selected = selected,
                monospaceTitle = true,
                enabled = enabled,
                onClick = { onPick(choice.id, choice.profileId) },
            )
        }
        SettingsInlineInput(
            value = custom,
            onValueChange = { custom = it },
            label = stringResource(R.string.min_settings_model_custom),
            placeholder = "gpt-5.6-luna",
            enabled = enabled,
            onApply = { custom.trim().takeIf { it.isNotEmpty() }?.let { onPick(it, null) } },
        )
    }
}

/**
 * 自动模式的审查模型（B10 / M5）。默认是与会话同供应商的小模型——数据不出这一家；单独指定时可以选别家的模型，
 * 那时用户的消息与工具入参会发给那家，所以这句话写在表的下面、别家的行上也各标一遍。
 */
@Composable
internal fun AutoReviewSection(
    view: AutoReviewView,
    models: List<Choice>,
    connection: ConnectionView?,
    enabled: Boolean,
    /** (模型 id, 档 id)；模型 id 为 null = 回到默认 */
    onPick: (String?, String?) -> Unit,
) {
    Column {
        OptionRow(
            title = stringResource(R.string.auto_review_default),
            subtitle = stringResource(R.string.auto_review_default_desc, view.defaultModel),
            selected = view.reviewer == null,
            enabled = enabled,
            onClick = { onPick(null, null) },
        )
        SettingsSubHeader(stringResource(R.string.auto_review_custom_header))
        models.forEach { choice ->
            val chosen = view.reviewer
            OptionRow(
                title = choice.label,
                subtitle = stringResource(R.string.auto_review_other_provider).takeIf { choice.switchesProviderFrom(connection) },
                selected = chosen != null && choice.id == chosen.id && choice.profileId == chosen.profileId,
                monospaceTitle = true,
                enabled = enabled,
                onClick = { onPick(choice.id, choice.profileId) },
            )
        }
        SettingsSubHeader(stringResource(R.string.auto_review_privacy))
    }
}

/**
 * 用量段：上下文占比、缓存命中率、花费。三样都只读，没有数就不画那一行（而不是画「—」）。
 *
 * 花费是整棵会话树（主会话 + 子 agent）内核记的 [UsageView.costLabel]，币种原样，其中子 agent 的一份另给一行；它为 null 而会话又确实有过请求，
 * 说明用过的模型里有没有价格的——明说，免得用户以为是没统计。
 */
@Composable
internal fun MinUsageSection(usage: UsageView) {
    Column {
        val used = usage.contextTokens
        if (used == null && usage.cacheReadRatio == null && usage.costLabel == null) {
            SettingsSubHeader(stringResource(R.string.min_usage_none))
            return@Column
        }
        if (used != null) {
            val limit = usage.contextLimit
            val percent = limit?.takeIf { it > 0 }?.let { (used.toDouble() / it * 100).roundToInt().coerceIn(0, 100) }
            UsageLine(
                stringResource(R.string.min_usage_context),
                if (limit != null && percent != null) {
                    stringResource(R.string.min_usage_context_value, tokensText(used), windowText(limit), percent)
                } else {
                    tokensText(used)
                },
            )
        }
        usage.cacheReadRatio?.let { UsageLine(stringResource(R.string.min_usage_cache), "${(it * 100).roundToInt()}%") }
        usage.costLabel?.let { UsageLine(stringResource(R.string.min_usage_cost), it) }
        usage.subagentCostLabel?.let { UsageLine(stringResource(R.string.min_usage_cost_subagents), it) }
        if (usage.costLabel == null && used != null) SettingsSubHeader(stringResource(R.string.min_usage_no_price))
    }
}

/** 手风琴行右边那段摘要：优先花费（用户最关心），没有就写上下文占比 */
@Composable
internal fun minUsageSummary(usage: UsageView): String {
    usage.costLabel?.let { return it }
    val used = usage.contextTokens ?: return stringResource(R.string.min_usage_none)
    val limit = usage.contextLimit?.takeIf { it > 0 } ?: return tokensText(used)
    return "${(used.toDouble() / limit * 100).roundToInt().coerceIn(0, 100)}%"
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.sea.ink)
    }
}

private fun tokensText(tokens: Int): String = if (tokens >= 1000) "${tokens / 1000}k" else tokens.toString()

/** 上下文窗口：整百万写 M（1M），其余写 k——和 CC 面板同一种写法，窗口大小一眼能对上 */
private fun windowText(limit: Int): String =
    if (limit >= 1_000_000 && limit % 100_000 == 0) "${limit / 1_000_000}M" else tokensText(limit)
