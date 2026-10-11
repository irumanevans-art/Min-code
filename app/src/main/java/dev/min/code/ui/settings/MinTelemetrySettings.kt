package dev.min.code.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import dev.min.code.core.telemetry.TelemetryExportReport
import dev.min.code.core.telemetry.TelemetrySettings
import dev.min.code.ui.components.InkDialog
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.SettingRow

/**
 * 遥测设置（OpenTelemetry 的本地等价，默认关）。头部令牌只显示「已设置」：编辑框永远是空的，
 * 保存即替换，留空保存即清除——界面上拿不到已存的明文，也就不会被截屏或分享出去。
 */
@Composable
internal fun TelemetryRows(
    settings: TelemetrySettings,
    /** 托管层锁住的开关 / 端点 / 头部：[settings] 已经是生效值，这里只决定哪几行点不动 */
    locks: dev.min.code.core.engine.min.ManagedLocks,
    report: TelemetryExportReport?,
    onEnabled: (Boolean) -> Unit,
    onEndpoint: (String) -> Unit,
    onHeaders: (String) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    var editingEndpoint by remember { mutableStateOf(false) }
    var editingHeaders by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.settings_telemetry_title),
        subtitle = lockedSubtitle(
            stringResource(if (settings.enabled) R.string.settings_telemetry_on else R.string.settings_telemetry_off),
            locks.telemetryEnabled != null,
        ),
        onClick = if (locks.telemetryEnabled != null) null else { { onEnabled(!settings.enabled) } },
        trailing = { InkSwitch(checked = settings.enabled, onCheckedChange = onEnabled, enabled = locks.telemetryEnabled == null) },
    )
    SettingRow(
        title = stringResource(R.string.settings_telemetry_endpoint_title),
        subtitle = lockedSubtitle(settings.endpoint.ifBlank { stringResource(R.string.settings_telemetry_endpoint_none) }, locks.telemetryEndpoint != null),
        onClick = if (locks.telemetryEndpoint != null) null else { { editingEndpoint = true } },
    )
    SettingRow(
        title = stringResource(R.string.settings_telemetry_headers_title),
        // 锁住时只说「已由管理员设置」：托管层里的原文（令牌）不在界面上出现
        subtitle = lockedSubtitle(
            stringResource(if (settings.headersConfigured) R.string.settings_telemetry_headers_set else R.string.settings_telemetry_headers_unset),
            locks.telemetryHeaders != null,
        ),
        onClick = if (locks.telemetryHeaders != null) null else { { editingHeaders = true } },
    )
    SettingRow(
        title = stringResource(R.string.settings_telemetry_export_title),
        subtitle = exportSubtitle(report),
        onClick = onExport,
    )
    SettingRow(
        title = stringResource(R.string.settings_telemetry_share_title),
        subtitle = stringResource(R.string.settings_telemetry_share_subtitle),
        onClick = onShare,
    )
    if (editingEndpoint) {
        TelemetryTextDialog(
            title = stringResource(R.string.settings_telemetry_endpoint_title),
            initial = settings.endpoint,
            label = stringResource(R.string.settings_telemetry_endpoint_field),
            onDismiss = { editingEndpoint = false },
            onSave = { onEndpoint(it); editingEndpoint = false },
        )
    }
    if (editingHeaders) {
        TelemetryTextDialog(
            title = stringResource(R.string.settings_telemetry_headers_title),
            initial = "",
            label = stringResource(R.string.settings_telemetry_headers_field),
            onDismiss = { editingHeaders = false },
            onSave = { onHeaders(it); editingHeaders = false },
        )
    }
}

@Composable
private fun exportSubtitle(report: TelemetryExportReport?): String = when {
    report == null -> stringResource(R.string.settings_telemetry_export_idle)
    report.skipped -> stringResource(R.string.settings_telemetry_export_skipped)
    else -> {
        val result = stringResource(R.string.settings_telemetry_export_result, report.sessions, report.records, report.failures)
        val problem = report.problems.firstOrNull()?.let { stringResource(R.string.settings_telemetry_problem, it) }
        listOfNotNull(result, report.lastFailure, problem).joinToString(" · ")
    }
}

@Composable
private fun TelemetryTextDialog(
    title: String,
    initial: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    InkDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            InkTextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.settings_telemetry_save)) }
        },
        dismissButton = { InkTextButton(onClick = onDismiss) { Text(stringResource(R.string.team_cancel)) } },
    ) {
        InkTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = label,
            singleLine = false,
        )
    }
}
