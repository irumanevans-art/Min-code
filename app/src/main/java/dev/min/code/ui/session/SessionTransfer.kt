package dev.min.code.ui.session

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.claudecode.SessionImportReject
import dev.min.code.core.claudecode.SessionJsonl
import dev.min.code.ui.components.LocalToaster
import dev.min.code.ui.components.RikkaConfirmDialog
import dev.min.code.ui.components.ToastType

/** 抽屉拿到的两个入口：行菜单里的「导出」、题跋上的「导入」 */
class SessionTransferActions(
    val export: (ClaudeCodeVM.SessionEntry) -> Unit,
    val import: () -> Unit,
)

/**
 * 会话 JSONL 的导出 / 导入：两个 SAF 启动器、结果提示、同 id 冲突时的覆盖确认。
 * 交互形状照供应商导入导出（`ProviderTransferSheet`）：`CreateDocument` 直接写进用户选的位置，
 * `OpenDocument` 不按类型过滤；不经 cacheDir、不走分享。
 *
 * 放在调用处（会话页）而不是抽屉里：抽屉在窄屏上是一张会被关掉的 sheet，启动器和确认框
 * 得活得比它久——选文件那一下抽屉可能已经收起来了。
 */
@Composable
fun rememberSessionTransfer(vm: ClaudeCodeVM, sessions: List<ClaudeCodeVM.SessionEntry>): SessionTransferActions {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    // 选位置那一趟 Activity 可能被系统回收重建，要导出的是哪一条得跟着存档
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }

    // MIME 故意给 octet-stream：DocumentsProvider 按 MIME 补扩展名，给 application/json 的话
    // `x.jsonl` 会被存成 `x.jsonl.json`（jsonl 不在系统的 MIME 表里）；octet-stream 时原样保留文件名
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val id = exportingId
        exportingId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        vm.exportSession(id, { context.contentResolver.openOutputStream(uri) }) { ok ->
            if (ok) {
                toaster.show(context.getString(R.string.session_export_done), ToastType.Success)
            } else {
                toaster.show(context.getString(R.string.session_export_failed), ToastType.Error)
            }
        }
    }

    val onImportResult: (ClaudeCodeVM.SessionImportOutcome) -> Unit = { outcome ->
        val (text, type) = importMessage(context, outcome)
        toaster.show(text, type)
    }

    // 收 */* 而不是按 MIME 过滤：.jsonl 在各家文件管理器里的 MIME 五花八门（多半是
    // octet-stream），按类型过滤的结果是用户明明看到文件却点不动
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importSession({ context.contentResolver.openInputStream(uri) }, onImportResult)
    }

    val pendingId by vm.pendingImportId.collectAsStateWithLifecycle()
    pendingId?.let { id ->
        val title = sessions.firstOrNull { it.id == id }?.title ?: id.take(8)
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.session_import_conflict_title),
            confirmText = stringResource(R.string.session_import_overwrite),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = { vm.resolveImport(overwrite = true, onDone = onImportResult) },
            onDismiss = { vm.resolveImport(overwrite = false, onDone = onImportResult) },
        ) {
            Text(stringResource(R.string.session_import_conflict_body, title))
        }
    }

    return remember(vm) {
        SessionTransferActions(
            export = { entry ->
                exportingId = entry.id
                exportLauncher.launch(sessionExportFileName(entry.title, entry.titled, entry.id))
            },
            import = { importLauncher.launch(arrayOf("*/*")) },
        )
    }
}

private fun importMessage(context: Context, outcome: ClaudeCodeVM.SessionImportOutcome): Pair<String, ToastType> =
    when (outcome) {
        is ClaudeCodeVM.SessionImportOutcome.Imported -> context.getString(
            R.string.session_import_done,
            outcome.sessionId.take(8),
            outcome.cwd,
        ) to ToastType.Success
        ClaudeCodeVM.SessionImportOutcome.Running ->
            context.getString(R.string.session_import_running) to ToastType.Error
        ClaudeCodeVM.SessionImportOutcome.Failed ->
            context.getString(R.string.session_import_failed) to ToastType.Error
        is ClaudeCodeVM.SessionImportOutcome.Rejected -> when (outcome.reason) {
            SessionImportReject.EMPTY -> context.getString(R.string.session_import_empty)
            SessionImportReject.TOO_LARGE -> context.getString(
                R.string.session_import_too_large,
                (SessionJsonl.MAX_IMPORT_BYTES / (1024 * 1024)).toInt(),
            )
            SessionImportReject.BAD_LINE -> context.getString(R.string.session_import_bad_line, outcome.line)
            SessionImportReject.NO_SESSION_ID -> context.getString(R.string.session_import_no_id)
            SessionImportReject.MIXED_SESSION_ID -> context.getString(R.string.session_import_mixed_id, outcome.line)
            SessionImportReject.BAD_SESSION_ID -> context.getString(R.string.session_import_bad_id, outcome.line)
        } to ToastType.Error
    }
