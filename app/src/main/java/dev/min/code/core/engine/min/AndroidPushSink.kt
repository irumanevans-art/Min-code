package dev.min.code.core.engine.min

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.min.agent.spi.PushSink
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.R
import dev.min.code.util.sendNotification
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模型用 PushNotification 发来的通知：走 App 已有的告警通道（与定时任务、会话结束的通知同一个通道），不另开通道。
 * 通知权限没给或通道被用户关了，[sendNotification] 返回 false，内核据此告诉模型「没有发出」，模型不会以为用户已经收到。
 * 通知编号在一小段里轮换：同一时刻最多 [SLOTS] 条模型通知挂在通知栏上，超出的覆盖最早那条，不会无限堆积。
 */
internal class AndroidPushSink(private val context: Context) : PushSink {
    private val next = AtomicInteger(0)

    override suspend fun push(title: String?, message: String): Boolean {
        val id = BASE_ID + (next.getAndIncrement() and (SLOTS - 1))
        return context.sendNotification(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, id) {
            this.title = title ?: context.getString(R.string.app_name)
            content = message
            useBigTextStyle = true
            autoCancel = true
            category = NotificationCompat.CATEGORY_MESSAGE
        }
    }

    private companion object {
        /** 与定时任务通知（9_000_000 起）错开 */
        const val BASE_ID = 9_500_000

        /** 必须是 2 的幂：编号用位与轮换 */
        const val SLOTS = 32
    }
}
