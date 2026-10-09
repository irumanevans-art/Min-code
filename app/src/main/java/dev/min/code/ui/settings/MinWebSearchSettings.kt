package dev.min.code.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.min.code.R
import dev.min.code.core.settings.SearchBackendKind
import dev.min.code.core.settings.WebSearchConfig
import dev.min.code.core.settings.WebSearchEdit
import dev.min.code.core.settings.parseSearxngUrl
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SettingRow

/** 分段选择器的顺序就是这里的顺序：不设 / Brave / Tavily / SearXNG（按下标定位，不依赖枚举声明顺序） */
private val BACKEND_ORDER = listOf(SearchBackendKind.NONE, SearchBackendKind.BRAVE, SearchBackendKind.TAVILY, SearchBackendKind.SEARXNG)

@Composable
private fun backendName(kind: SearchBackendKind): String = when (kind) {
    SearchBackendKind.NONE -> stringResource(R.string.settings_web_search_backend_none)
    SearchBackendKind.BRAVE -> "Brave"
    SearchBackendKind.TAVILY -> "Tavily"
    SearchBackendKind.SEARXNG -> "SearXNG"
}

/**
 * Min Agent 的 WebSearch 后端（设置项 `webSearch`）：默认不设，模型看不到 WebSearch；
 * 选一家并填好 key（SearXNG 填地址）后，下一个请求起模型就有这个工具，清掉配置后工具消失。
 * **key 只显示「已填」、不回显**；要换就重新输入，要清就点「清除」。
 */
@Composable
internal fun WebSearchRow(config: WebSearchConfig, onSave: (WebSearchEdit) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val name = backendName(config.backend)
    SettingRow(
        title = stringResource(R.string.settings_web_search_title),
        subtitle = when {
            config.backend == SearchBackendKind.NONE -> stringResource(R.string.settings_web_search_off)
            config.configured && config.backend == SearchBackendKind.SEARXNG ->
                stringResource(R.string.settings_web_search_ready_url, name, config.searxngUrl)
            config.configured -> stringResource(R.string.settings_web_search_ready_key, name)
            config.backend == SearchBackendKind.SEARXNG -> stringResource(R.string.settings_web_search_needs_url, name)
            else -> stringResource(R.string.settings_web_search_needs_key, name)
        },
        onClick = { editing = true },
    )
    if (editing) WebSearchDialog(config, onDismiss = { editing = false }, onSave = { onSave(it); editing = false })
}

@Composable
private fun WebSearchDialog(config: WebSearchConfig, onDismiss: () -> Unit, onSave: (WebSearchEdit) -> Unit) {
    var backend by remember { mutableStateOf(config.backend) }
    // 输入框里永远是空的起点：已填的 key 不回显，空 = 保持不变
    var braveKey by remember { mutableStateOf("") }
    var tavilyKey by remember { mutableStateOf("") }
    var clearBrave by remember { mutableStateOf(false) }
    var clearTavily by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(config.searxngUrl) }
    val urlInvalid = backend == SearchBackendKind.SEARXNG && url.isNotBlank() && parseSearxngUrl(url) == null
    InkDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_web_search_title),
        confirmButton = {
            InkTextButton(
                enabled = !urlInvalid,
                onClick = { onSave(WebSearchEdit(backend, braveKey, tavilyKey, url, clearBrave, clearTavily)) },
            ) { Text(stringResource(R.string.settings_web_search_save)) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        InkSegmented(
            options = BACKEND_ORDER.map { backendName(it) },
            selected = BACKEND_ORDER.indexOf(backend),
            onSelect = { backend = BACKEND_ORDER[it] },
        )
        when (backend) {
            SearchBackendKind.NONE -> Unit
            SearchBackendKind.BRAVE -> KeyField(
                label = "Brave", saved = config.braveKey.isNotBlank(), value = braveKey, onValue = { braveKey = it },
                cleared = clearBrave, onClear = { clearBrave = true },
            )
            SearchBackendKind.TAVILY -> KeyField(
                label = "Tavily", saved = config.tavilyKey.isNotBlank(), value = tavilyKey, onValue = { tavilyKey = it },
                cleared = clearTavily, onClear = { clearTavily = true },
            )
            SearchBackendKind.SEARXNG -> InkTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.settings_web_search_url_label),
                placeholder = "https://searx.example.com",
                singleLine = true,
                monospace = true,
                isError = urlInvalid,
                supporting = if (urlInvalid) stringResource(R.string.settings_web_search_url_invalid) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        Text(
            stringResource(R.string.settings_web_search_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一家的 key 输入：始终遮住、没有「显示」按钮（已填的值本来就不在界面里）；已填时提示「留空保持不变」并给「清除」 */
@Composable
private fun KeyField(label: String, saved: Boolean, value: String, onValue: (String) -> Unit, cleared: Boolean, onClear: () -> Unit) {
    Column {
        InkTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.settings_web_search_key_label, label),
            placeholder = stringResource(if (saved && !cleared) R.string.settings_web_search_key_saved else R.string.settings_web_search_key_unsaved),
            singleLine = true,
            monospace = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (saved && !cleared) {
            InkButton(onClick = onClear, tone = InkButtonTone.Paper, compact = true) { Text(stringResource(R.string.settings_web_search_key_clear)) }
        } else if (saved) {
            Text(
                stringResource(R.string.settings_web_search_key_cleared),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
