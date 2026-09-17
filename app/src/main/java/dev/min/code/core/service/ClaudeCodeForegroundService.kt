package dev.min.code.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import dev.min.code.CLAUDE_CODE_LIVE_NOTIFICATION_CHANNEL_ID
import dev.min.code.R
import dev.min.code.MainActivity
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.crash.CrashRecorder
import org.koin.android.ext.android.inject

private const val TAG = "ClaudeCodeFgs"

/**
 * 让 Claude Code 的 CLI 进程在 App 退到后台 / 息屏后继续活着。
 *
 * ## 为什么必须有它
 *
 * 一个会话 = 一个 `proot` 进程树（bash → node → claude），它们全是 App 进程的**子进程**。
 * App 一旦进入 cached 状态，系统在内存压力下会连同整棵树一起回收 —— 表现就是"锁屏出去
 * 接个电话，回来任务没了、transcript 也没落盘"。前台服务是 Android 上唯一能声明
 * "这个进程正在替用户干活、别杀"的手段。
 *
 * ## 借鉴自 Termux（TermuxService）
 *
 * Termux 解决的是同一个问题，做法这里原样沿用：
 * - 常驻前台通知 + 「停止全部」动作（Termux 是 `ACTION_STOP_SERVICE`）
 * - `PARTIAL_WAKE_LOCK` + `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` 一对锁：
 *   只有 CPU 锁不够，Wi-Fi 在深度休眠时同样会被断掉，长轮询的 SSE 连接会直接中断。
 *
 * **和 Termux 的一处有意不同**：Termux 的唤醒锁是用户手动开关的，一按住就持有到退出。
 * 那对插着电的开发机没问题，对手机是明显掉电。这里改成绑定
 * [ClaudeCodeSessionRegistry.anyBusy] —— 只在真的有一轮任务在跑时持锁，会话开着但闲置
 * （等用户输入）时放开。
 *
 * ## 生命周期：拉起在外面，停止在里面
 *
 * [ClaudeCodeSessionSupervisor] 观察 [ClaudeCodeSessionRegistry.anyLive]，第一个会话起来时
 * 调 [start]。**停止不由外面 stopService，而是服务自己观察注册表，最后一个会话结束后收尾。**
 *
 * 这不是风格问题，是一次真实的崩溃：`startForegroundService()` 的契约是「服务随后必须成功
 * `startForeground()`」，在那之前它被 `stopService` / `stopSelf` 掉，或者 `startForeground()`
 * 被系统拒绝（部分 OEM ROM 会这么干），系统都会抛
 * `ForegroundServiceDidNotStartInTimeException` **直接杀掉整个进程**，App 侧 catch 不到。
 * 之前的实现恰好两条都踩了：会话启动失败时外面立刻 `stopService`；`startForeground()`
 * 失败时里面立刻 `stopSelf()`。所以现在：
 * - 拉起优先用普通 `startService()`（见 [start]），它没有那条契约；
 * - `startForeground()` 失败**绝不** `stopSelf`，服务退化成普通服务继续观察注册表；
 * - 收尾统一走 [stopForegroundAndSelf]，那时前台早就成功了，怎么停都安全。
 */
class ClaudeCodeForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2003

        private const val ACTION_START = "dev.min.code.action.CLAUDE_CODE_START"
        private const val ACTION_STOP_ALL = "dev.min.code.action.CLAUDE_CODE_STOP_ALL"

        /**
         * 最后一个会话结束后等这么久再停服务。
         *
         * 改 effort 之类的操作是「关进程 → 重开」，注册表里会有一瞬间没有活着的会话，
         * 不缓冲的话服务会停一次再起一次，通知跟着闪一下、唤醒锁也白放一回。
         */
        private const val STOP_DEBOUNCE_MS = 1_500L

        /**
         * 保活是否真的生效。
         *
         * 必须能被界面看见：部分 OEM ROM（ColorOS / realme UI 等）会在系统侧拒发 FGS 类型权限，
         * 那时保活**静默失效** —— 用户以为切出去还在跑，回来才发现任务早断了，
         * 而现象（连接中断、任务没继续）和网络问题一模一样，根本分不清是哪一种。
         * 与其让用户猜，不如把状态直接摆出来。
         */
        val keepAlive: StateFlow<KeepAlive> get() = _keepAlive
        private val _keepAlive = MutableStateFlow(KeepAlive.Stopped)

        /**
         * 这个进程里 `startForeground()` 被系统**永久性**拒绝过（SecurityException 一类，
         * 不是"此刻在后台不允许"那种）。拒过就不再拿 `startForegroundService` 去撞 —— 那条路
         * 上再被拒一次就是进程被杀。成功一次即清零，用户在系统设置里放开限制后能自愈。
         */
        @Volatile
        private var rejectedInProcess = false

        /**
         * 拉起服务。**优先普通 `startService()`，不是 `startForegroundService()`。**
         *
         * 两者只在失败时有区别，而区别是致命的：`startForegroundService()` 拉起的服务若
         * `startForeground()` 没成功（被拒、或在那之前被停掉），系统会杀进程。普通
         * `startService()` 没有这条契约，`startForeground()` 失败只是退化成一个普通服务。
         *
         * 普通 `startService()` 只在 App 处于前台时可用（Android 8+ 禁止后台起服务），而会话
         * 总是用户在页面上点出来的，正常情况下永远走这条路。真在后台（抛
         * IllegalStateException）才退回 `startForegroundService()`，且被拒过就不再试。
         */
        fun start(context: Context) {
            val intent = Intent(context, ClaudeCodeForegroundService::class.java)
                .apply { action = ACTION_START }
            try {
                context.startService(intent)
                return
            } catch (e: IllegalStateException) {
                // App 在后台。Android 12+ 的 BackgroundServiceStartNotAllowedException 也是它的子类
                Log.w(TAG, "startService refused (app in background), trying startForegroundService", e)
            } catch (e: Exception) {
                Log.e(TAG, "startService failed", e)
                _keepAlive.value = KeepAlive.Rejected
                return
            }
            if (rejectedInProcess) {
                Log.w(TAG, "startForeground was rejected before; not risking startForegroundService")
                _keepAlive.value = KeepAlive.Rejected
                return
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.e(TAG, "startForegroundService failed", it)
                    _keepAlive.value = KeepAlive.Rejected
                }
        }
    }

    /**
     * 保活状态。
     * - [Rejected]：系统拒绝了前台服务，切后台随时可能被回收。
     * - [Expired]：本机不接受 specialUse 类型、退回了 dataSync，而 Android 15+ 对它有每天
     *   6 小时的上限，已经用完。会话没有被动过，只是不再有保活。
     */
    enum class KeepAlive { Stopped, Active, Rejected, Expired }

    private val registry: ClaudeCodeSessionRegistry by inject()
    private val localServices: LocalServiceRegistry by inject()
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            // 没有会话状态可置，记日志 + 落盘就够了；不装的话 SupervisorJob 会把异常崩到 App
            Log.e(TAG, "uncaught exception in FGS scope", e)
            CrashRecorder.record(applicationContext, Thread.currentThread(), e)
        }
    )

    private var isForeground = false
    private var observerStarted = false

    /** 最近一次 onStartCommand 的 startId，收尾时用 `stopSelf(startId)` 而不是裸 `stopSelf()` */
    private var lastStartId = -1

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP_ALL) {
            // 用户在通知上点了「停止全部」：会话 + 本地服务一起停。
            // 这里不直接 stopSelf —— shutdown 是挂起的（要给 CLI 时间落盘 transcript）。
            registry.closeAll()
            localServices.stopAll(LocalServiceStopReason.StopAll)
            releaseLocks()
            observeRegistry()
            return START_NOT_STICKY
        }

        // 不管是哪条路拉起的，都尽早 startForeground：startForegroundService 那条路要求
        // ~10s 内完成；startService 那条路则是 App 一退后台超过宽限期就再也进不了前台。
        // 失败**不 stopSelf**（那正是之前崩溃的触发点，见类注释）：退化成普通服务，
        // 继续观察注册表，会话结束时照常收尾。
        if (!isForeground) enterForeground(currentNotification())
        observeRegistry()
        return START_NOT_STICKY
    }

    /**
     * 观察注册表：刷新通知内容、按 busy 收放唤醒锁、全部结束时自我了断。
     *
     * 用 collectLatest 而不是 collect：通知刷新是 binder IPC，会话状态在一轮里能变几十次，
     * 慢一拍的旧值没有意义。它还顺带实现了停止缓冲 —— 「没有会话」分支里的 delay 会被
     * 下一个值（新会话冒出来）直接取消。
     */
    private fun observeRegistry() {
        if (observerStarted) return
        observerStarted = true
        serviceScope.launch {
            combine(registry.liveSessions, localServices.services) { sessions, services ->
                sessions to services
            }.collectLatest { (sessions, services) ->
                val live = sessions.filter { it.isLive }
                val runningServices = services.filter {
                    it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
                }
                if (live.isEmpty() && runningServices.isEmpty()) {
                    releaseLocks()
                    delay(STOP_DEBOUNCE_MS)
                    stopForegroundAndSelf()
                    return@collectLatest
                }
                val busy = live.any { it.busy } || runningServices.isNotEmpty()
                if (busy) acquireLocks() else releaseLocks()
                updateNotification(buildNotification(live, runningServices.size))
            }
        }
    }

    // -----------------------------------------------------------------------
    // 唤醒锁（Termux TermuxService.acquireWakeLock / releaseWakeLock）
    // -----------------------------------------------------------------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService<PowerManager>()
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Min:claude-code")
                ?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }.onFailure { Log.w(TAG, "wakeLock acquire failed", it) }
                }
        }
        if (wifiLock == null) {
            // 没有 Wi-Fi 锁的话，深度休眠会断掉正在流式接收的 SSE 连接，
            // 表现是任务跑到一半停住、日志里一串 api_retry。
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService<WifiManager>()
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Min:claude-code")
                ?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire() }.onFailure { Log.w(TAG, "wifiLock acquire failed", it) }
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    // -----------------------------------------------------------------------
    // 通知
    // -----------------------------------------------------------------------

    /** 按注册表**此刻**的样子建通知；onStartCommand 时列表多半已经有内容，别先挂一条"正在启动" */
    private fun currentNotification(): Notification {
        val live = registry.liveSessions.value.filter { it.isLive }
        val svc = localServices.services.value.count {
            it.status == LocalServiceStatus.Running || it.status == LocalServiceStatus.Starting
        }
        return buildNotification(live, svc)
    }

    private fun buildNotification(
        live: List<ClaudeCodeSessionRegistry.LiveSession>,
        serviceCount: Int,
    ): Notification {
        val busy = live.any { it.busy }
        val detail = live.firstOrNull { it.pendingPermissionTool != null }
            ?.let { "等待批准：${it.pendingPermissionTool}" }
            ?: live.firstOrNull { it.busy }?.statusText
            ?: if (serviceCount > 0) "$serviceCount 个本地服务" else null
        val sessionPart = when {
            live.isEmpty() && serviceCount == 0 -> "正在启动…"
            live.isEmpty() -> null
            busy -> "${live.size} 个会话 · 运行中"
            else -> "${live.size} 个会话 · 空闲"
        }
        val servicePart = if (serviceCount > 0) "${serviceCount} 个服务" else null
        val text = listOfNotNull(sessionPart, servicePart).joinToString(" · ")
            .ifBlank { "后台运行中" }
        return NotificationCompat.Builder(this, CLAUDE_CODE_LIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_min)
            .setContentTitle("Min")
            .setContentText(text)
            .apply { detail?.takeIf { it.isNotBlank() }?.let { setSubText(it.take(60)) } }
            .setContentIntent(openPageIntent())
            .addAction(0, "停止全部", stopAllIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        // 没进前台就不发：这条通知的意义是"保活的载体"，前台没起来时把它发出去，
        // 用户只会以为保活在生效
        if (!isForeground) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /**
     * 进入前台。Android 14+ 先试 specialUse；被拒再试 dataSync —— 后者在 Android 15+
     * 有每天 6 小时的上限（到点走 [onTimeout]），但比完全没有保活强。
     *
     * 失败只记状态、不做任何停止动作。IllegalStateException 是「此刻不允许」
     * （App 在后台超过了宽限期），下次还可能成功；其余（SecurityException 之类）
     * 是 ROM 层面的拒绝，记进 [rejectedInProcess]。
     */
    private fun enterForeground(notification: Notification): Boolean {
        val attempts: List<Int?> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            listOf(null)
        }
        var permanent = false
        for (type in attempts) {
            try {
                if (type == null) {
                    startForeground(NOTIFICATION_ID, notification)
                } else {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                }
                isForeground = true
                _keepAlive.value = KeepAlive.Active
                rejectedInProcess = false
                return true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground(type=$type) failed", e)
                if (e !is IllegalStateException) permanent = true
            }
        }
        isForeground = false
        _keepAlive.value = KeepAlive.Rejected
        if (permanent) rejectedInProcess = true
        return false
    }

    private fun openPageIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CLAUDE_CODE, true)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopAllIntent(): PendingIntent {
        val intent = Intent(this, ClaudeCodeForegroundService::class.java)
            .apply { action = ACTION_STOP_ALL }
        return PendingIntent.getService(
            this,
            NOTIFICATION_ID + 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopForegroundAndSelf() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        // 带 startId：注册表里刚又冒出一个会话、新的 start 还在路上时，
        // 这次 stop 会被系统忽略，服务不必死一次再重建
        stopSelf(lastStartId)
    }

    /**
     * Android 15+ 对 dataSync 类型有每天 6 小时的上限（specialUse 没有，所以只有退回
     * dataSync 的机器会到这里）。系统只给几秒钟让服务退出，超时会抛 RemoteServiceException
     * 把整个 App 干掉 —— 但**不动会话**：进程还在前台的话它们照样能跑，杀掉用户跑了
     * 几小时的任务比失去保活糟糕得多。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        releaseLocks()
        _keepAlive.value = KeepAlive.Expired
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        // 被系统拒绝/限时过就保留那个状态，让界面继续提示；正常收尾才回到 Stopped
        if (_keepAlive.value == KeepAlive.Active) _keepAlive.value = KeepAlive.Stopped
        serviceScope.cancel()
        super.onDestroy()
    }
}

/** RouteActivity 收到它就跳到 Claude Code 页 */
const val EXTRA_OPEN_CLAUDE_CODE = "openClaudeCode"
