package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.codex.CODEX_EFFORT_LEVELS
import dev.min.code.core.codex.CodexPermissionPreset
import dev.min.code.core.codex.CodexRuntime
import dev.min.code.core.settings.CODEX_WIRE_API_OPTIONS
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkChip
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkRadio
import dev.min.code.ui.components.InkSegmented
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea

/**
 * 开始面板上 Codex 一栏要的东西（设计 §9.2 B9）：安装 / 登录状态与三个去处。分开打包而不是散着传，
 * 理由同 [StartEngines]——它们是一个整体。
 */
internal data class CodexStart(
    val status: CodexRuntime.Status,
    val onInstall: () -> Unit,
    /** 登录要在终端里做（`codex login --device-auth`）：打开终端页 */
    val onOpenTerminal: () -> Unit,
    /** 连接方式面板（API key / 中转 / CLI 登录 + 默认模型与强度） */
    val onOpenConnection: () -> Unit,
)

/**
 * Codex 选中时开始面板上的那一段（原 Codex 页会话外那一屏）：装 Codex、去登录、选权限档、开始。
 *
 * 没装、没登录都不是错误，是「还差一步」：没装给安装按钮，没登录用海的水洗版警示（不上朱红）加去处。
 * 权限档在开始时就能选——Codex 的 sandbox 是线程开出来时就固化的，开始后改只能管下一轮。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CodexStartSection(
    codex: CodexStart,
    errorMessage: String?,
    presetId: String?,
    onPreset: (String) -> Unit,
    /** 继续最近的那条 Codex 会话（只读回放，要接着聊再按 ▶ 或发一句）；没有就不出这个按钮 */
    recentTitle: String?,
    onContinue: () -> Unit,
    onStart: () -> Unit,
) {
    val runtime = codex.status
    errorMessage?.let { Notice(text = it, tone = NoticeTone.Error, maxLines = 6) }
    if (!runtime.installed) {
        Notice(
            text = runtime.error ?: stringResource(R.string.codex_install_hint),
            tone = if (runtime.error != null) NoticeTone.Error else NoticeTone.Info,
        )
        InkButton(
            onClick = codex.onInstall,
            busy = runtime.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.codex_install)) }
        return
    }
    Text(
        runtime.version ?: stringResource(R.string.codex_installed),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetbrainsMono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!runtime.authenticated) {
        // 还没登录不是错误，是「还差一步」——用海的水洗版警示，不上朱红
        Notice(text = runtime.loginStatus ?: stringResource(R.string.codex_login_hint), tone = NoticeTone.Warn)
        InkButton(
            onClick = codex.onOpenTerminal,
            tone = InkButtonTone.Paper,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.codex_open_login_terminal)) }
        Text(
            CODEX_LOGIN_COMMAND,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    InkButton(
        onClick = codex.onOpenConnection,
        tone = InkButtonTone.Paper,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.codex_connection_open)) }

    SectionTitle(stringResource(R.string.codex_section_permissions))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CodexPermissionPreset.entries.forEach { preset ->
            InkChip(
                label = stringResource(codexPresetLabelRes(preset)),
                selected = preset.id == presetId,
                onClick = { onPreset(preset.id) },
            )
        }
    }
    CodexPermissionPreset.entries.firstOrNull { it.id == presetId }?.let { preset ->
        Text(
            stringResource(codexPresetDescRes(preset)),
            style = MaterialTheme.typography.bodySmall,
            // 全放开那一档是判定，朱字；其余是说明
            color = if (preset == CodexPermissionPreset.FULL_ACCESS) MaterialTheme.sea.vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    recentTitle?.let { title ->
        InkButton(
            onClick = onContinue,
            tone = InkButtonTone.Paper,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.session_continue_recent, title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    InkButton(
        onClick = onStart,
        enabled = runtime.authenticated,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.session_start_action)) }
}

internal fun codexPresetLabelRes(preset: CodexPermissionPreset): Int = when (preset) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access
}

internal fun codexPresetDescRes(preset: CodexPermissionPreset): Int = when (preset) {
    CodexPermissionPreset.READ_ONLY -> R.string.codex_permission_read_only_desc
    CodexPermissionPreset.AUTO -> R.string.codex_permission_auto_desc
    CodexPermissionPreset.FULL_ACCESS -> R.string.codex_permission_full_access_desc
}

/** 官方登录命令，给用户照着在终端里敲 */
internal const val CODEX_LOGIN_COMMAND = "/opt/codex/bin/codex login --device-auth"

/**
 * 连接方式。从会话页挪进 sheet —— 它是设一次就不再看的东西，不该常驻在会话上方。
 *
 * **列表在上、编辑在下**：存储层一直是一张表，可这一页以前只认 `id = "default"` 那一条，
 * 于是「在两家中转之间来回切」在 Codex 这边根本做不到。上半部分是那张表（单选 + 新建），
 * 下半部分永远编辑当前选中的那条。整表的增删改排序、预设与导入导出在供应商页，
 * 这里只留一条「管理…」通过去。
 *
 * B9 之前住在 `ui/codex/CodexPage.kt` 里，原样搬来（逻辑与文案一字未改）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CodexConnectionSheet(
    profile: CodexProfile,
    profiles: List<CodexProfile>,
    onSave: (CodexProfile) -> Unit,
    onActivate: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var apiKey by remember(profile.apiKey) { mutableStateOf(profile.apiKey) }
    var baseUrl by remember(profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var mode by remember(profile.authMode) { mutableStateOf(profile.authMode) }
    var model by remember(profile.model) { mutableStateOf(profile.model) }
    var effort by remember(profile.effort) { mutableStateOf(profile.effort) }
    var wireApi by remember(profile.wireApi) { mutableStateOf(profile.effectiveWireApi) }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.codex_connection),
                style = MaterialTheme.typography.titleSmall,
            )
            // 只有一条时这张表是多余的噪音 —— 那就是绝大多数人的状态
            if (profiles.size > 1) {
                profiles.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onActivate(entry.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InkRadio(selected = entry.id == profile.id, onClick = { onActivate(entry.id) })
                        Text(
                            entry.displayName(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                InkTextButton(onClick = onAdd) { Text(stringResource(R.string.providers_add_manual)) }
                InkTextButton(onClick = onManage) { Text(stringResource(R.string.providers_manage_action)) }
            }
            InkDivider(brush = true)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AuthModeChip(R.string.codex_auth_cli, CodexAuthMode.CLI, mode) { mode = it }
                AuthModeChip(R.string.codex_auth_openai, CodexAuthMode.OPENAI_API_KEY, mode) { mode = it }
                AuthModeChip(R.string.codex_auth_relay, CodexAuthMode.RELAY, mode) { mode = it }
            }
            if (mode != CodexAuthMode.CLI) {
                InkTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = stringResource(R.string.codex_api_key),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (mode == CodexAuthMode.RELAY) {
                    InkTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = stringResource(R.string.codex_base_url),
                        singleLine = true,
                    )
                    // 以前这一档是写死的 responses。不少中转只提供 /chat/completions，
                    // 对它们来说写死那一行等于这条配置永远连不上，而界面上完全看不出是为什么
                    Text(
                        stringResource(R.string.providers_codex_wire_api),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    InkSegmented(
                        options = CODEX_WIRE_API_OPTIONS,
                        selected = CODEX_WIRE_API_OPTIONS.indexOf(wireApi).coerceAtLeast(0),
                        onSelect = { wireApi = CODEX_WIRE_API_OPTIONS[it] },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.providers_codex_wire_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 模型跟着连接走：thread/start 和每一轮 turn/start 都带上；思考强度只有 turn/start 收。
            // 空着 = 不传，用 Codex 自己的默认——不替用户猜模型
            InkTextField(
                value = model,
                onValueChange = { model = it },
                label = stringResource(R.string.codex_model),
                placeholder = "gpt-5.1-codex-max",
                singleLine = true,
                monospace = true,
            )
            Text(
                stringResource(R.string.codex_effort),
                style = MaterialTheme.typography.labelMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InkChip(
                    label = stringResource(R.string.codex_effort_default),
                    selected = effort.isBlank(),
                    onClick = { effort = "" },
                )
                CODEX_EFFORT_LEVELS.forEach { level ->
                    InkChip(
                        label = level,
                        selected = effort == level,
                        onClick = { effort = level },
                        monospace = true,
                    )
                }
            }
            InkButton(
                onClick = {
                    onSave(
                        profile.copy(
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            authMode = mode,
                            model = model.trim(),
                            effort = effort.trim(),
                            wireApi = wireApi,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.codex_save_connection)) }
        }
    }
}

@Composable
private fun RowScope.AuthModeChip(
    labelRes: Int,
    value: CodexAuthMode,
    current: CodexAuthMode,
    onPick: (CodexAuthMode) -> Unit,
) {
    InkButton(
        onClick = { onPick(value) },
        tone = if (current == value) InkButtonTone.Ink else InkButtonTone.Paper,
        compact = true,
        modifier = Modifier.weight(1f),
    ) { Text(stringResource(labelRes)) }
}
