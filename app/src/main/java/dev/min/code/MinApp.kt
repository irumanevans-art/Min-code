package dev.min.code

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppLocale
import dev.min.code.core.settings.SettingsStore
import dev.min.code.di.appModule
import dev.min.code.ui.theme.GpuWarmup
import dev.min.code.ui.theme.SeaPlate
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
            val stored = store.current().appLanguage
            // Android 13+ 的语言也能从「系统设置 → 应用 → Min → 语言」改，那是绕过我们的一次
            // 用户操作，该以系统为准回写，免得设置页显示 English、界面却是中文。
            //
            // 但**只认系统给出具体语言的情况**。系统侧为空有两种可能，从这里分不出来：
            // 用户真的选了「跟随系统」，或者我们压根还没写过（每次全新安装都是这样）。
            // 把空当成用户的选择，等于每次冷启动都把人选的语言抹回跟随系统。
            val system = AppLocale.readSystem(this@MinApp)
            if (system != null && system != AppLanguage.SYSTEM && system != stored) {
                store.setAppLanguage(system)
            } else {
                AppLocale.apply(this@MinApp, stored)
            }
            store.settings
                .map { it.appLanguage }
                .distinctUntilChanged()
                .collect { AppLocale.apply(this@MinApp, it) }
        }
        // 上一条命留下的托管服务这会儿还在跑（`killOnExit=false` 就是要它活过 App），
        // 可内存里的进程表是空的。不认回来的话，用户看到的是一张空面板加一个「端口被占」。
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching { getKoin().get<LocalServiceRegistry>().reconcile() }
                .onFailure { Log.w(TAG, "local service reconcile failed", it) }
        }
        // 海纹理 ~1.7MB + BitmapShader 管线：别等用户第一次点发送/海窗时在主线程冷编译
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                SeaPlate.preload(this@MinApp)
                GpuWarmup.warm(this@MinApp)
            }.onFailure { Log.w(TAG, "SeaPlate/GpuWarmup failed", it) }
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
