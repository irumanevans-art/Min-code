package dev.min.code.ui.providers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.settings.ProviderBackup
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkSwitch
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.components.SettingRow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport

/**
 * # 导入与导出
 *
 * 换机、重装、或者只是「这张表我攒了半年」——没有这一页的话，那些地址只能一条条重敲。
 *
 * ## 明文 key 这道门按最严的标准把
 *
 * 这个项目对凭据泄漏一向敏感（Keystore 整表加密、密文打不开时拒写、Codex 故意不写
 * auth.json）。导出是新开的一个口子，所以：
 *
 * - 默认 redacted，`token` 那个键**整个不出现**；
 * - 要带 key 得过一次 `destructive` 的确认，文案把三件事说清楚；
 * - 走 SAF 的 `openOutputStream` **直接写进用户选的位置**，绝不先落 cacheDir 再分享 ——
 *   那等于把一份明文 key 递给他临时选中的任意一个 App；
 * - 文件名带 `-with-keys`，让人一眼看出哪份烫手。
 */
@Composable
fun ProviderTransferSheet(vm: ProvidersVM, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by vm.transfer.collectAsStateWithLifecycle()
    var secrets by remember { mutableStateOf(false) }
    var secretsConfirm by remember { mutableStateOf(false) }
    val guestLabel = stringResource(R.string.providers_import_guest_label)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.exportTo(secrets) { context.contentResolver.openOutputStream(uri) }
    }
    // 收 */* 而不是 application/json：别的 App 导出的 .json 常常被标成
    // application/octet-stream，按 MIME 过滤的结果是用户明明看到文件却点不动
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importFromFile { context.contentResolver.openInputStream(uri) }
    }

    // 每次打开都从干净的状态开始：上一次的「新增 3」留在这儿会被当成这一次的结果
    LaunchedEffect(Unit) { vm.clearTransferState() }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle(stringResource(R.string.providers_export_section))
            Hint(stringResource(R.string.providers_export_hint))
            SettingRow(
                title = stringResource(R.string.providers_export_secrets_title),
                subtitle = stringResource(R.string.providers_export_secrets_subtitle),
                onClick = { if (secrets) secrets = false else secretsConfirm = true },
                trailing = {
                    InkSwitch(
                        checked = secrets,
                        onCheckedChange = { on -> if (on) secretsConfirm = true else secrets = false },
                    )
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                InkButton(
                    onClick = { exportLauncher.launch(vm.suggestedFileName(secrets)) },
                    icon = HugeIcons.FileExport,
                    compact = true,
                ) { Text(stringResource(R.string.providers_export_action)) }
            }

            InkDivider(Modifier.padding(vertical = 8.dp), brush = true)

            SectionTitle(stringResource(R.string.providers_import_section))
            Hint(stringResource(R.string.providers_import_hint))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                InkTextButton(
                    onClick = { vm.importFromGuest(guestLabel) },
                    icon = HugeIcons.FileImport,
                ) { Text(stringResource(R.string.providers_import_guest)) }
                InkTextButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = HugeIcons.FileImport,
                ) { Text(stringResource(R.string.providers_import_file)) }
            }

            transferMessage(state)?.let { (text, tone) ->
                Notice(text = text, tone = tone, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (secretsConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_export_secrets_confirm_title),
            confirmText = stringResource(R.string.common_confirm),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                secrets = true
                secretsConfirm = false
            },
            onDismiss = { secretsConfirm = false },
        ) {
            Text(stringResource(R.string.providers_export_secrets_confirm_body))
        }
    }
}

/** 结果那一条。导入必须报数，见 `MergeOutcome` 的注释 */
@Composable
private fun transferMessage(state: ProvidersVM.TransferState?): Pair<String, NoticeTone>? = when (state) {
    null -> null
    is ProvidersVM.TransferState.Exported -> {
        val text = if (state.withSecrets) {
            stringResource(R.string.providers_transfer_exported_keys)
        } else {
            stringResource(R.string.providers_transfer_exported)
        }
        text to if (state.withSecrets) NoticeTone.Warn else NoticeTone.Info
    }
    is ProvidersVM.TransferState.Imported ->
        stringResource(
            R.string.providers_transfer_imported,
            state.added,
            state.skipped,
            state.renamed,
        ) to NoticeTone.Info
    is ProvidersVM.TransferState.Failed -> stringResource(
        when (state.reason) {
            ProvidersVM.Failure.NOT_JSON -> R.string.providers_transfer_failed_not_json
            ProvidersVM.Failure.UNKNOWN_SCHEMA -> R.string.providers_transfer_failed_schema
            ProvidersVM.Failure.EMPTY -> R.string.providers_transfer_failed_empty
            ProvidersVM.Failure.NOTHING_FOUND -> R.string.providers_transfer_failed_nothing
            ProvidersVM.Failure.IO -> R.string.providers_transfer_failed_io
        },
    ) to NoticeTone.Error
}

/**
 * 托管动手之前留的那些底。
 *
 * 只列 settings.json 的——config.toml 不做备份，理由在 `ProviderBackup` 的头注释里：
 * 那个文件每次 `prepare()` 都会整份重写，给它做的「恢复」下一秒就被盖掉，是个假功能。
 */
@Composable
fun ProviderBackupSheet(vm: ProvidersVM, onDismiss: () -> Unit) {
    val backups by vm.backups.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<ProviderBackup.Snapshot?>(null) }

    LaunchedEffect(Unit) { vm.loadBackups() }

    InkSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionTitle(stringResource(R.string.providers_backup_title))
            Hint(stringResource(R.string.providers_backup_hint))
            if (backups.isEmpty()) {
                Hint(stringResource(R.string.providers_backup_empty))
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(backups.size, key = { backups[it].name }) { i ->
                        val snapshot = backups[i]
                        SettingRow(
                            title = snapshot.name,
                            subtitle = stringResource(R.string.providers_backup_bytes, snapshot.bytes),
                            trailing = {
                                InkTextButton(onClick = { confirming = snapshot }) {
                                    Text(stringResource(R.string.providers_backup_restore))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    confirming?.let { snapshot ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.providers_backup_restore_title),
            confirmText = stringResource(R.string.providers_backup_restore),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.restoreBackup(snapshot)
                confirming = null
                onDismiss()
            },
            onDismiss = { confirming = null },
        ) {
            Text(stringResource(R.string.providers_backup_restore_body, snapshot.name))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
