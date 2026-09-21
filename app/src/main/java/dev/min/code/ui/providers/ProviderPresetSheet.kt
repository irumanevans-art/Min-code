package dev.min.code.ui.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.settings.ClaudePreset
import dev.min.code.core.settings.CodexPreset
import dev.min.code.core.settings.PRESET_CATEGORIES
import dev.min.code.core.settings.PRESET_CATEGORY_AGGREGATOR
import dev.min.code.core.settings.PRESET_CATEGORY_CN_OFFICIAL
import dev.min.code.core.settings.PRESET_CATEGORY_COMMUNITY
import dev.min.code.core.settings.PRESET_CATEGORY_OFFICIAL
import dev.min.code.core.settings.PRESET_CATEGORY_SELF_HOST
import dev.min.code.core.settings.ProviderPresets
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.JetbrainsMono

/**
 * 从预设里挑一家。
 *
 * 表有八十多条，所以有搜索；按分类分组，官方和国内官方排在前面 —— **不继承上游的
 * 文件顺序**，那是按赞助位排的（见 `tools/import_ccswitch_presets.py`）。
 *
 * 底下常驻一句「列在这里不等于背书」。这张表大半是第三方中转，端点也会变；
 * 把它摆出来是方便，不是担保。
 */
@Composable
fun ProviderPresetSheet(
    presets: ProviderPresets,
    zh: Boolean,
    onDismiss: () -> Unit,
    codex: Boolean = false,
    onPick: (ClaudePreset) -> Unit = {},
    onPickCodex: (CodexPreset) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.providers_preset_title),
                style = MaterialTheme.typography.titleSmall,
            )
            InkTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.providers_preset_search),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val claudeGroups = remember(presets, query, zh) {
                presets.claude
                    .filter { it.matches(query, zh) }
                    .groupBy { it.category }
            }
            val codexGroups = remember(presets, query, zh) {
                presets.codex
                    .filter { it.matches(query, zh) }
                    .groupBy { it.category }
            }
            val empty = if (codex) codexGroups.isEmpty() else claudeGroups.isEmpty()

            if (empty) {
                Text(
                    stringResource(R.string.providers_preset_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PRESET_CATEGORIES.forEach { category ->
                        val claudeRows = claudeGroups[category].orEmpty()
                        val codexRows = codexGroups[category].orEmpty()
                        if (codex && codexRows.isEmpty()) return@forEach
                        if (!codex && claudeRows.isEmpty()) return@forEach

                        item("head-$category") {
                            SectionTitle(
                                categoryLabel(category),
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        if (codex) {
                            items(codexRows.size, key = { "c-${codexRows[it].id}" }) { i ->
                                val p = codexRows[i]
                                PresetRow(p.displayName(zh), p.baseUrl, p.displayNote(zh)) { onPickCodex(p) }
                            }
                        } else {
                            items(claudeRows.size, key = { claudeRows[it].id }) { i ->
                                val p = claudeRows[i]
                                PresetRow(p.displayName(zh), p.baseUrl, p.displayNote(zh)) { onPick(p) }
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.providers_preset_disclaimer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PresetRow(title: String, subtitle: String, note: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 「这家的 key 和另一家不通用」这类话只在有的时候占一行
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun categoryLabel(category: String): String = stringResource(
    when (category) {
        PRESET_CATEGORY_OFFICIAL -> R.string.providers_category_official
        PRESET_CATEGORY_CN_OFFICIAL -> R.string.providers_category_cn_official
        PRESET_CATEGORY_AGGREGATOR -> R.string.providers_category_aggregator
        PRESET_CATEGORY_SELF_HOST -> R.string.providers_category_self_host
        PRESET_CATEGORY_COMMUNITY -> R.string.providers_category_community
        else -> R.string.providers_category_community
    },
)

/** 搜名字也搜地址：记得住「就是那个 bigmodel」的人比记得住中文名的多 */
private fun ClaudePreset.matches(query: String, zh: Boolean): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return displayName(zh).lowercase().contains(q) ||
        name.lowercase().contains(q) ||
        baseUrl.lowercase().contains(q)
}

private fun CodexPreset.matches(query: String, zh: Boolean): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return displayName(zh).lowercase().contains(q) ||
        name.lowercase().contains(q) ||
        baseUrl.lowercase().contains(q)
}
