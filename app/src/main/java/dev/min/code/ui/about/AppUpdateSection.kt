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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import dev.min.code.ui.richtext.MarkdownBlock
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
    // 下好了、但还没拿到「安装未知应用」授权的那个包。授权完回到这一页就自动接着装
    var awaitingInstall by remember { mutableStateOf<java.io.File?>(null) }

    fun install(file: java.io.File) {
        if (!AppUpdater.installApk(context, file)) {
            toaster.show(context.getString(R.string.about_check_update_install_failed), ToastType.Error)
        }
    }

    fun askInstallPermission() {
        runCatching { context.startActivity(AppUpdater.unknownSourcesSettingsIntent(context)) }
    }

    // 从系统设置那一页回来：授权了就直接装，不用再点一遍「下载并安装」
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val file = awaitingInstall ?: return@LifecycleEventEffect
        if (AppUpdater.canInstallPackages(context)) {
            awaitingInstall = null
            status = null
            install(file)
        }
    }

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
                        AppUpdateChecker.Result.RateLimited -> {
                            status = context.getString(R.string.about_check_update_rate_limited)
                            statusError = true
                            toaster.show(status.orEmpty(), ToastType.Error)
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

        if (awaitingInstall != null) {
            // 授权页被人直接退回来的时候，给一个再进去的入口
            InkTextButton(onClick = ::askInstallPermission) {
                Text(stringResource(R.string.about_check_update_open_permission))
            }
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
                // 先下再说：下载不需要任何授权。以前是一进来就先跳「安装未知应用」设置页、
                // 什么都没下，回来还得再点一遍 —— 看上去就是「点了更新，被甩到别的地方去了」
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
                            if (AppUpdater.canInstallPackages(context)) {
                                install(result.file)
                            } else {
                                // Android 不许 App 自己静默装包：头一次必须用户在系统设置里点一下。
                                // 记下这个包，授权完回来由上面的 ON_RESUME 接着装
                                awaitingInstall = result.file
                                status = context.getString(R.string.about_check_update_ready_need_permission)
                                statusError = false
                                askInstallPermission()
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
                    // Release 说明是 Markdown（### / ** / `…`），当纯文本摆出来就是一堆符号。
                    // 不再放「打开 Release 页」：它和底下的「下载并安装」挤在一起，一点就被带去浏览器；
                    // 真没有匹配的 APK 时，底下那颗按钮本身就是去 Release 页
                    MarkdownBlock(
                        content = release.body.take(4000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
