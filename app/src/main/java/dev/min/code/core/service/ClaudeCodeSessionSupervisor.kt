package dev.min.code.core.service

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import dev.min.code.AppScope
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.MainActivity
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.util.cancelNotification
import dev.min.code.util.sendNotification
import org.koin.java.KoinJavaComponent.inject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import dev.min.code.core.session.SessionStatus

/**
 * Claude Code 会话的「后台管家」：进程保活 + 后台通知。
 *
 * 单例，App 启动时构造一次（见 MinApp）。两件事都必须在**页面之外**做 ——
 * 页面被销毁正是这些逻辑最需要生效的时候。
 *
 * ## 1. 保活
 * 观察 [ClaudeCodeSessionRegistry.anyLive]：第一个会话起来时拉起
 * [ClaudeCodeForegroundService]。停止不归这里管 —— 服务自己盯着注册表收尾，原因见那边的类注释。
 *
 * ## 2. 后台通知
 * 只在 App 不在前台时发。三类：
 * - **等待审批**（最要紧）：不发的话，切出去之后会话会无声地卡在等待里，
 *   用户以为在跑，实际上它在等一次点击。通知带「允许 / 拒绝」直接应答。
 * - **一轮任务完成**：手机上任务动辄几分钟，用户不会盯着看。
 * - **会话失败 / 进程退出**：否则回到 App 才发现十分钟前就断了。
 *
 * 通知 id 按会话 key 派生，多会话各发各的、互不覆盖。
 */
class ClaudeCodeSessionSupervisor(
    private val context: Application,
    appScope: AppScope,
    private val registry: ClaudeCodeSessionRegistry,
    private val localServices: LocalServiceRegistry,
    private val codex: CodexAppServerManager,
) {
    private val isForeground = MutableStateFlow(false)

    /**
     * 上一轮观察到的每个会话状态，用来做「边沿触发」——只在状态真的翻转时才发通知。
     *
     * 必须是并发 map：`liveSessions` 的收集跑在 Dispatchers.Default，而进前台的回调跑在
     * 主线程且要遍历 keys。普通 mutableMap 在这两者之间会抛 ConcurrentModificationException，
     * 而且是"切回 App 的瞬间偶发崩溃"这种最难复现的形态。
     */
    private data class Seen(
        val busy: Boolean,
        val pendingTool: String?,
        val status: SessionStatus,
    )

    private val seen = ConcurrentHashMap<String, Seen>()

    init {
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            // 回到前台就把待审批提醒撤掉，UI 上的 sheet 才是主界面
                            seen.keys.forEach { context.cancelNotification(permissionNotificationId(it)) }
                        }

                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> {}
                    }
                }
            )
        }

        appScope.launch(Dispatchers.Default) {
            // 只负责「拉起」，停止由服务自己观察注册表完成。
            // 从外面 stopService 有一个致命的时序窗口：会话启动失败得足够快时，stopService
            // 会赶在服务 startForeground() 之前到达，系统直接杀进程
            // （ForegroundServiceDidNotStartInTimeException）。真实发生过。
            // 会话 **或** 本地服务 **或** Codex 任一存活都要保活。
            combine(
                registry.anyLive,
                localServices.anyRunning,
                codex.isLive,
            ) { live, svc, codexLive -> live || svc || codexLive }
                .filter { it }
                .collect { ClaudeCodeForegroundService.start(context) }
        }

        appScope.launch(Dispatchers.Default) {
            registry.liveSessions.collect { sessions ->
                sessions.forEach(::diff)
                // 会话从注册表消失（被回收）时清掉它的残留通知和记忆
                val keys = sessions.map { it.key }.toSet()
                (seen.keys - keys).forEach { gone ->
                    seen.remove(gone)
                    context.cancelNotification(permissionNotificationId(gone))
                }
            }
        }
    }

    private fun diff(session: ClaudeCodeSessionRegistry.LiveSession) {
        val previous = seen[session.key]
        seen[session.key] = Seen(session.busy, session.pendingPermissionTool, session.status)

        // --- 等待审批 -------------------------------------------------------
        val tool = session.pendingPermissionTool
        if (tool != null && previous?.pendingTool == null) {
            if (!isForeground.value) notifyPermission(session.key, tool)
        } else if (tool == null && previous?.pendingTool != null) {
            // 已经在别处应答了（App 内 sheet、或通知按钮），撤掉提醒
            context.cancelNotification(permissionNotificationId(session.key))
        }

        if (isForeground.value) return
        if (previous == null) return

        // --- 一轮跑完 -------------------------------------------------------
        if (previous.busy && !session.busy && session.isLive) {
            context.sendNotification(
                channelId = CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID,
                notificationId = doneNotificationId(session.key),
            ) {
                title = "Claude Code"
                content = "任务已完成"
                autoCancel = true
                useDefaults = true
                category = NotificationCompat.CATEGORY_MESSAGE
                contentIntent = openPageIntent(context)
            }
        }

        // --- 会话挂了 -------------------------------------------------------
        val died = previous.status != session.status &&
            (session.status == SessionStatus.Failed ||
                session.status == SessionStatus.Closed)
        if (died) {
            context.cancelNotification(permissionNotificationId(session.key))
            context.sendNotification(
                channelId = CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID,
                notificationId = doneNotificationId(session.key),
            ) {
                title = "Claude Code 会话已结束"
                content = session.errorMessage?.take(160) ?: "进程已退出"
                autoCancel = true
                useDefaults = true
                useBigTextStyle = true
                category = NotificationCompat.CATEGORY_ERROR
                contentIntent = openPageIntent(context)
            }
        }
    }

    private fun notifyPermission(key: String, tool: String) {
        context.sendNotification(
            channelId = CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID,
            notificationId = permissionNotificationId(key),
        ) {
            title = "Claude Code 需要确认"
            content = "请求使用工具：$tool"
            // ongoing：这条不是"看过就算"的提醒，会话真的停在这里等它
            ongoing = true
            autoCancel = false
            useDefaults = true
            category = NotificationCompat.CATEGORY_CALL
            contentIntent = openPageIntent(context)
            addAction("允许", ClaudeCodePermissionReceiver.intent(context, key, allow = true))
            addAction("拒绝", ClaudeCodePermissionReceiver.intent(context, key, allow = false))
        }
    }

    private companion object {
        /**
         * 通知 id 由会话 key 派生。偏移到两段互不重叠的区间，避免和 App 里其它固定 id
         * （1 / 2001 / 2002 / 2003）撞车。散列用 [stableKeyInt]（SHA-256 前 4 字节，
         * 19 位掩码后仍有 50 万空间）：原来 `hashCode() and 0xFFFF` 只有 16 位熵，
         * 两个会话撞上下位就会把对方的通知覆盖、把权限按钮应答到错误会话上。
         */
        fun permissionNotificationId(key: String): Int = PERMISSION_NOTIFICATION_BASE + (stableKeyInt(key) and KEY_MASK_19)
        fun doneNotificationId(key: String): Int = DONE_NOTIFICATION_BASE + (stableKeyInt(key) and KEY_MASK_19)

        fun openPageIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_CLAUDE_CODE, true)
            }
            return PendingIntent.getActivity(
                context,
                ClaudeCodeForegroundService.NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/**
 * 通知上「允许 / 拒绝」按钮的接收端。
 *
 * 走广播而不是 `PendingIntent.getService`：应答只是往 CLI 的 stdin 写一行，
 * 不需要（也不该）为此拉起一个服务组件。
 */
class ClaudeCodePermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val key = intent.getStringExtra(EXTRA_SESSION_KEY) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val registry: ClaudeCodeSessionRegistry by inject(ClaudeCodeSessionRegistry::class.java)
        registry.answerPermission(key, allow)
        context.cancelNotification(PERMISSION_NOTIFICATION_BASE + (stableKeyInt(key) and KEY_MASK_19))
    }

    companion object {
        private const val ACTION_ANSWER = "dev.min.code.action.CLAUDE_CODE_ANSWER_PERMISSION"
        private const val EXTRA_SESSION_KEY = "session_key"
        private const val EXTRA_ALLOW = "allow"

        fun intent(context: Context, key: String, allow: Boolean): PendingIntent {
            val intent = Intent(context, ClaudeCodePermissionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_SESSION_KEY, key)
                putExtra(EXTRA_ALLOW, allow)
            }
            return PendingIntent.getBroadcast(
                context,
                // allow / deny 必须是两个不同的 requestCode，否则 FLAG_UPDATE_CURRENT
                // 会让后建的那个把先建的 extras 覆盖掉——两个按钮就成了同一个动作。
                // 散列用 stableKeyInt（32 位）：16 位熵时撞车的两个会话会共用一个
                // PendingIntent，权限按钮应答到错误会话上
                stableKeyInt(key) * 2 + if (allow) 1 else 0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

private const val PERMISSION_NOTIFICATION_BASE = 3_000_000
private const val DONE_NOTIFICATION_BASE = 4_000_000

/** 两段通知 id 区间各 19 位（3M/4M 起步，互不重叠，也不撞 App 的固定小 id） */
private const val KEY_MASK_19 = 0x7FFFF

/**
 * 会话 key → 稳定的 32 位整数（SHA-256 前 4 字节）。取代 `hashCode() and 0xFFFF` ——
 * 16 位熵在多会话下撞车不罕见，通知互相覆盖、PendingIntent 串号是安全事故。
 */
private fun stableKeyInt(key: String): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return (digest[0].toInt() and 0xFF shl 24) or
        (digest[1].toInt() and 0xFF shl 16) or
        (digest[2].toInt() and 0xFF shl 8) or
        (digest[3].toInt() and 0xFF)
}
