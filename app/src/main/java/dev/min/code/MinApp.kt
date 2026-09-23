package dev.min.code

import android.app.Application
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dev.min.code.core.crash.CrashRecorder
import dev.min.code.core.device.AgentDisplaySession
import dev.min.code.core.device.DeviceMcpRegistrar
import dev.min.code.core.rootfs.WorkspaceRepository
import dev.min.code.core.service.LocalServiceRegistry
import dev.min.code.core.settings.ProviderSync
import dev.min.code.core.settings.AppLanguage
import dev.min.code.core.settings.AppLocale
import dev.min.code.core.settings.SettingsStore
import dev.min.code.di.appModule
import dev.min.code.privileged.PrivilegedClient
import dev.min.code.ui.theme.GpuWarmup
import dev.min.code.ui.theme.SeaPlate
import dev.min.code.ui.theme.Skins
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
        // 设备操控的 MCP server：跟着设置里的开关起停，并把地址写进 CLI 的 mcpServers。
        // **必须在用户可能开始会话之前就位** —— CLI 是启动时读一次 MCP 配置的，
        // 等模型想用了再起，那会儿它手里的配置已经是旧的了（见 DeviceMcpRegistrar）
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching { getKoin().get<DeviceMcpRegistrar>().follow() }
                .onFailure { Log.w(TAG, "device mcp registrar stopped", it) }
        }
        // 虚拟屏壳进程是独立的 shell 进程：清掉 Min 后台杀不掉它。
        // App 再打开时等壳定时重交 Binder，然后把仍在的 display 接回会话。
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            val client = getKoin().get<PrivilegedClient>()
            val session = getKoin().get<AgentDisplaySession>()
            // 最多等约 15s（壳每 3s 重交一次）
            repeat(30) {
                if (client.state.value == PrivilegedClient.State.Ready) {
                    runCatching { session.restoreIfPossible() }
                        .onSuccess { restored ->
                            if (restored != null) {
                                Log.i(TAG, "restored virtual display ${restored.displayId}")
                            }
                        }
                        .onFailure { Log.w(TAG, "restore virtual display failed", it) }
                    return@launch
                }
                delay(500)
            }
        }
        // rootfs 装好 / 重装 / 删掉之后，告诉系统「文件」App 重新读一遍我们的根。
        // 不通知的话，装完 rootfs 侧栏里那个入口要等系统自己想起来才出现
        //（见 WorkspaceDocumentsProvider）
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            getKoin().get<WorkspaceRepository>().workspace
                .map { it.shellStatus }
                .distinctUntilChanged()
                .collect {
                    runCatching {
                        contentResolver.notifyChange(
                            DocumentsContract.buildRootsUri("${BuildConfig.APPLICATION_ID}.documents"),
                            null,
                        )
                    }.onFailure { e -> Log.w(TAG, "notify documents roots failed", e) }
                }
        }
        // 上一条命留下的托管服务这会儿还在跑（`killOnExit=false` 就是要它活过 App），
        // 可内存里的进程表是空的。不认回来的话，用户看到的是一张空面板加一个「端口被占」。
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching { getKoin().get<LocalServiceRegistry>().reconcile() }
                .onFailure { Log.w(TAG, "local service reconcile failed", it) }
        }
        // 托管开着的话，把当前供应商重新投影到 Rootfs 的配置文件上 —— 那两个文件
        // 上一次运行之后可能被 CLI 自己、或被用户手动改过。托管关着时这一步会早退，
        // 一个字节都不写（见 ProviderSync.apply）
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching { getKoin().get<ProviderSync>().apply() }
                .onFailure { Log.w(TAG, "provider sync failed", it) }
        }
        // 取底纹理 + BitmapShader 管线：别等用户第一次点发送 / 第一次开窗时在主线程冷编译。
        //
        // **只解当前风格那一张**。以前是无条件解海和云两张（≈10 MB 常驻），因为云是
        // Codex 页专属、进那一页必然要用；现在三套风格都是全局的，任何时刻只有一张在用，
        // 另外两张解出来就是白占内存。切风格时由 SeaPlate.evictExcept 放掉旧的那张。
        getKoin().get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val plate = Skins.of(getKoin().get<SettingsStore>().current().skin).plate
                SeaPlate.preload(this@MinApp, plate)
                SeaPlate.evictExcept(plate)
                GpuWarmup.warm(this@MinApp, plate)
            }.onFailure { Log.w(TAG, "SeaPlate/GpuWarmup failed", it) }
        }
    }

    private fun createNotificationChannels() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CLAUDE_CODE_LIVE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notif_keepalive_name))
                .setDescription(getString(R.string.notif_keepalive_desc))
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.notif_alert_name))
                .setDescription(getString(R.string.notif_alert_desc))
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
