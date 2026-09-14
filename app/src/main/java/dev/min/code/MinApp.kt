package dev.min.code

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.core.settings.AppLocale
import dev.min.code.core.settings.SettingsStore
import dev.min.code.di.appModule
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

private const val TAG = "MinApp"

/** Claude Code 前台服务那条常驻通知；静音、低优先级，只是保活的载体 */
const val CLAUDE_CODE_LIVE_NOTIFICATION_CHANNEL_ID = "claude_code_live"

/**
 * Claude Code 的待办提醒：等待审批 / 任务完成 / 会话中断。
 * 必须 HIGH —— 「等待审批」不响的话，会话会一直停在那里等，用户不会知道。
 */
const val CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID = "claude_code_alert"

class MinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 先装崩溃记录器：之后任何初始化炸了都能在下次启动时看到堆栈，而不是一个静默闪退
        CrashRecorder.install(this)
        startKoin {
            androidLogger(Level.WARNING)
            androidContext(this@MinApp)
            modules(appModule)
        }
        createNotificationChannels()
        // 跟随设置里的语言偏好；DataStore 异步，先读一次再订阅变更
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            val store = getKoin().get<SettingsStore>()
            AppLocale.apply(store.current().appLanguage)
            store.settings
                .map { it.appLanguage }
                .distinctUntilChanged()
                .collect { AppLocale.apply(it) }
        }
    }

    private fun createNotificationChannels() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CLAUDE_CODE_LIVE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("会话保活")
                .setDescription("会话在后台运行时的常驻通知")
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("会话提醒")
                .setDescription("等待审批、任务完成、会话中断")
                .setVibrationEnabled(true)
                .build()
        )
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob() +
        Dispatchers.Main +
        CoroutineName("AppScope") +
        CoroutineExceptionHandler { _, e -> Log.e(TAG, "AppScope exception", e) }
)
