package dev.min.code.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.BuildConfig
import dev.min.code.R
import dev.min.code.core.update.AppUpdateChecker
import dev.min.code.core.update.AppUpdater
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkLineProgress
import dev.min.code.ui.components.InkTextButton
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.ToastType
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 关于页上的 App 自更新。和 CLI 维护面板那颗「检查更新」不是一回事。
 */
@Composable
internal fun AppUpdateSection() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<AppUpdateChecker.AppRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InkButton(
            onClick = {
                if (checking || downloading) return@InkButton
                checking = true
                status = null
                statusError = false
                scope.launch {
                    when (val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)) {
                        AppUpdateChecker.Result.UpToDate -> {
                            status = context.getString(R.string.about_check_update_latest)
                            statusError = false
                            toaster.show(context.getString(R.string.about_check_update_latest), ToastType.Success)
                        }
                        is AppUpdateChecker.Result.UpdateAvailable -> {
                            status = context.getString(
                                R.string.about_check_update_available,
                                result.release.version,
                            )
                            statusError = false
                            pending = result.release
                        }
                        is AppUpdateChecker.Result.Failed -> {
                            status = context.getString(R.string.about_check_update_failed, result.reason)
                            statusError = true
                            toaster.show(status.orEmpty(), ToastType.Error)
                        }
                    }
                    checking = false
                }
            },
            enabled = !checking && !downloading,
            busy = checking,
            tone = InkButtonTone.Paper,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (checking) R.string.about_check_update_checking
                    else R.string.about_check_update,
                ),
            )
        }

        status?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (statusError) MaterialTheme.sea.vermilion
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (downloading) {
            InkLineProgress(progress = progress)
            Text(
                stringResource(R.string.about_check_update_downloading, (progress * 100).toInt()),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val release = pending
    if (release != null) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.about_check_update_dialog_title, release.version),
            confirmText = stringResource(
                if (release.apk == null) R.string.about_check_update_open_release
                else R.string.about_check_update_download,
            ),
            dismissText = stringResource(R.string.common_cancel),
            destructive = false,
            onConfirm = {
                val apk = release.apk
                if (apk == null) {
                    AppUpdater.openReleasePage(context, release.htmlUrl)
                    pending = null
                    return@RikkaConfirmDialog
                }
                if (!AppUpdater.canInstallPackages(context)) {
                    toaster.show(
                        context.getString(R.string.about_check_update_need_permission),
                        ToastType.Error,
                    )
                    runCatching { context.startActivity(AppUpdater.unknownSourcesSettingsIntent(context)) }
                    return@RikkaConfirmDialog
                }
                pending = null
                downloading = true
                progress = 0f
                scope.launch {
                    val dest = AppUpdater.defaultApkFile(context, release.version)
                    val result = AppUpdater.download(apk.downloadUrl, dest) { p ->
                        // 下载在 IO；进度回主线程再画
                        scope.launch(Dispatchers.Main.immediate) { progress = p }
                    }
                    downloading = false
                    when (result) {
                        is AppUpdater.DownloadResult.Ok -> {
                            if (!AppUpdater.installApk(context, result.file)) {
                                toaster.show(
                                    context.getString(R.string.about_check_update_install_failed),
                                    ToastType.Error,
                                )
                            }
                        }
                        is AppUpdater.DownloadResult.Failed -> {
                            status = context.getString(
                                R.string.about_check_update_download_failed,
                                result.reason,
                            )
                            statusError = true
                            toaster.show(status.orEmpty(), ToastType.Error)
                        }
                    }
                }
            },
            onDismiss = { pending = null },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(release.name.ifBlank { release.tag })
                if (release.apk == null) {
                    Text(
                        stringResource(R.string.about_check_update_no_apk),
                        color = MaterialTheme.sea.vermilion,
                    )
                    if (release.apkNames.isNotEmpty()) {
                        Text(
                            release.apkNames.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        release.apk.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (release.body.isNotBlank()) {
                    Text(
                        release.body.take(1200),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (release.htmlUrl.isNotBlank()) {
                    InkTextButton(
                        onClick = { AppUpdater.openReleasePage(context, release.htmlUrl) },
                    ) {
                        Text(stringResource(R.string.about_check_update_open_release))
                    }
                }
            }
        }
    }
}
