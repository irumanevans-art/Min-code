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
 * 对话流里同时有一张文件卡片（[UserFileCard]），读的是同一份缓存：落盘位置按会话 id + 调用 id 算（[userFileStageKey]），
 * 两边不用互相通知。通知只是「人不在这一页时」的提醒，卡片才是常驻的入口。
 * 点通知时按 [UserFile.display] 打开：render 用 ACTION_VIEW 直接看；attach 与未指定的非预览类型交给分享面板。
 */
internal class AndroidUserFileSink(private val context: Context) : UserFileSink {
    private val next = AtomicInteger(0)

    override suspend fun deliver(file: UserFile): Boolean = withContext(Dispatchers.IO) {
        val slot = next.getAndIncrement() and (SLOTS - 1)
        // 内核总会带上会话与调用；没带（别的调用方）时退回按通知槽位起名，卡片找不到它，通知仍打得开
        val session = file.sessionId
        val call = file.callId
        val key = if (session != null && call != null) userFileStageKey(session.value, call.value) else "slot-$slot"
        val target = stageUserFile(userFilesRoot(context), key, file.index, file.name, file.bytes)
        val uri = context.fileProviderUri(target)
        val id = BASE_ID + slot
        val open = userFileIntent(uri, file.mime, view = prefersView(file.display, file.mime))
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

    internal companion object {
        /** 与推送通知（9_500_000 起）错开 */
        const val BASE_ID = 9_600_000

        /** 通知编号的槽位数，必须是 2 的幂。文件不再按槽位存（见 [USER_FILE_CALLS_KEPT]），槽位只管通知别无限堆 */
        const val SLOTS = 32
    }
}

/** 文件缓存的根：通知与卡片共用 */
internal fun userFilesRoot(context: Context): File = File(context.cacheDir, "user_files")

/** 未指定展示方式时：图片、文本、PDF 直接看，其余交给分享面板（与 CC「由客户端决定」一致） */
internal fun prefersView(display: UserFileDisplay?, mime: String): Boolean = when (display) {
    UserFileDisplay.Render -> true
    UserFileDisplay.Attach -> false
    null -> mime.startsWith("image/") || mime.startsWith("text/") || mime == "application/pdf"
}

/** 打开（ACTION_VIEW）或分享（ACTION_SEND）一份缓存里的文件；都只授读权限 */
internal fun userFileIntent(uri: Uri, mime: String, view: Boolean): Intent =
    if (view) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
