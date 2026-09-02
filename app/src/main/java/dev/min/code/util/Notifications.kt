package dev.min.code.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.min.code.R

/** 通知构建器的配置 DSL（从 Min/RikkaHub 的 NotificationUtil 精简而来） */
class NotificationConfig {
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.ic_stat_min
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false
    var useDefaults: Boolean = false

    /**
     * 通知上的动作按钮。Claude Code 在后台等权限确认时，用户应该能直接在通知上
     * 允许/拒绝，而不是必须先把 App 切回前台再找到那个 sheet。
     */
    val actions: MutableList<NotificationCompat.Action> = mutableListOf()

    fun addAction(title: String, intent: PendingIntent, icon: Int = 0) {
        actions += NotificationCompat.Action.Builder(icon, title, intent).build()
    }
}

fun Context.hasNotificationPermission(): Boolean =
    ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit,
): Boolean {
    if (!hasNotificationPermission()) return false
    val c = NotificationConfig().apply(config)
    val builder = NotificationCompat.Builder(this, channelId).apply {
        setContentTitle(c.title)
        setContentText(c.content)
        setSmallIcon(c.smallIcon)
        setAutoCancel(c.autoCancel)
        setOngoing(c.ongoing)
        setOnlyAlertOnce(c.onlyAlertOnce)
        setVisibility(c.visibility)
        c.subText?.let { setSubText(it) }
        c.category?.let { setCategory(it) }
        c.contentIntent?.let { setContentIntent(it) }
        c.actions.forEach { addAction(it) }
        if (c.useBigTextStyle) setStyle(NotificationCompat.BigTextStyle().bigText(c.content))
        if (c.useDefaults) setDefaults(NotificationCompat.DEFAULT_ALL)
    }
    NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    return true
}

fun Context.cancelNotification(notificationId: Int) {
    NotificationManagerCompat.from(this).cancel(notificationId)
}
