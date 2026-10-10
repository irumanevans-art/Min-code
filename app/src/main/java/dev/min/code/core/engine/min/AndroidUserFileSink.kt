package dev.min.code.core.engine.min

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import dev.min.agent.spi.UserFile
import dev.min.agent.spi.UserFileDisplay
import dev.min.agent.spi.UserFileSink
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.util.fileProviderUri
import dev.min.code.util.sendNotification
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模型用 SendUserFile 发来的文件：先写进缓存目录（经 FileProvider 给出 URI，和工作区分享同一条路），再发一条告警通道的通知。
 * 点通知时按 [UserFile.display] 打开：render 用 ACTION_VIEW 直接看；attach 与未指定用 ACTION_SEND 交给分享面板。
 * 缓存每次写之前先清空，分享过的东西不长期占空间（与工作区分享同一条规矩）。
 */
internal class AndroidUserFileSink(private val context: Context) : UserFileSink {
    private val next = AtomicInteger(0)

    override suspend fun deliver(file: UserFile): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "user_files").apply {
            listFiles()?.forEach { it.deleteRecursively() }
            mkdirs()
        }
        val target = File(dir, file.name).apply { writeBytes(file.bytes) }
        val uri = context.fileProviderUri(target)
        val id = BASE_ID + (next.getAndIncrement() and (SLOTS - 1))
        val open = openIntent(uri, file)
        val pending = PendingIntent.getActivity(
            context, id, Intent.createChooser(open, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.sendNotification(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, id) {
            title = file.caption ?: file.name
            content = file.name
            autoCancel = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pending
        }
    }

    private fun openIntent(uri: Uri, file: UserFile): Intent {
        // 未指定展示方式时：图片、文本、PDF 直接看，其余交给分享面板（与 CC「由客户端决定」一致）
        val view = when (file.display) {
            UserFileDisplay.Render -> true
            UserFileDisplay.Attach -> false
            null -> file.mime.startsWith("image/") || file.mime.startsWith("text/") || file.mime == "application/pdf"
        }
        return if (view) {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = file.mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private companion object {
        /** 与推送通知（9_500_000 起）错开 */
        const val BASE_ID = 9_600_000

        /** 必须是 2 的幂 */
        const val SLOTS = 32
    }
}
