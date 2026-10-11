package dev.min.code.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.min.code.R
import dev.min.code.core.telemetry.TelemetryShareFiles
import dev.min.code.util.fileProviderUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 分享导出的本机遥测文件：拷一份快照进缓存目录，经 FileProvider 交给系统分享面板。
 * 只在用户点了「分享」时发生，没有任何自动发送。还没导出过就提示一句，不弹空的分享面板。
 */
internal suspend fun shareTelemetryFile(context: Context, source: File) {
    val shared = withContext(Dispatchers.IO) { TelemetryShareFiles.prepare(source, context.cacheDir, System.currentTimeMillis()) }
    if (shared == null) {
        Toast.makeText(context, R.string.settings_telemetry_share_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        // 纯文本：JSONL 没有统一的 MIME，text/plain 让邮件、网盘、文件管理器都接得住
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, context.fileProviderUri(shared))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
