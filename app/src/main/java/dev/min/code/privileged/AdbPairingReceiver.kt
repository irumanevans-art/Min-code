package dev.min.code.privileged

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.min.code.AppScope
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.R
import dev.min.code.util.cancelNotification
import dev.min.code.util.hasNotificationPermission
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/**
 * 通知栏行内输入配对码——单机自配对的唯一稳妥路。
 *
 * 系统的「使用配对码配对设备」对话框只在它前台时配对服务才活着。单台设备上
 * app 一到前台、对话框就没了，配对码随之作废。绕法和 Shizuku 一样：下拉通知栏
 * 盖在对话框上（对话框只是 pause，配对服务还在），在通知的回复框里把
 * 「端口 空格 六位码」打进来，后台直接配对，不用把对话框切走。
 *
 * 端口也让用户填：对话框里本来就显示了 IP 和端口，而 vivo 等厂商的 adbd 不发
 * mDNS 广播，扫也扫不到配对端口。
 */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAIR) return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString().orEmpty()

        val (port, code) = parse(reply) ?: run {
            // 没认出来，重发通知让用户按格式再来一次
            Log.w(TAG, "parse failed: <$reply>")
            post(context, hint = context.getString(R.string.adb_pair_notif_bad))
            return
        }
        Log.i(TAG, "reply parsed: port=$port code.len=${code.length}")
        // 别急着撤通知：先把它更新成「配对中」，让用户在通知栏就看到在动，成/败再改文案
        post(context, hint = "正在配对 $port …请保持无线调试对话框开着")

        val starter: PrivilegedStarter by inject(PrivilegedStarter::class.java)
        val client: PrivilegedClient by inject(PrivilegedClient::class.java)
        val appScope: AppScope by inject(AppScope::class.java)
        val pending = goAsync()
        appScope.launch {
            try {
                val ok = starter.pairAndStart(code, port)
                if (ok) {
                    Log.i(TAG, "pairAndStart ok")
                    context.cancelNotification(NOTIFICATION_ID)
                } else {
                    val why = client.lastError ?: "请重开配对对话框拿新码再试"
                    Log.w(TAG, "pairAndStart failed: $why")
                    post(context, hint = "失败：$why")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AdbPairingRecv"
        private const val ACTION_PAIR = "dev.min.code.action.ADB_PAIR"
        private const val KEY_REPLY = "adb_pair_reply"
        const val NOTIFICATION_ID = 6_000_001

        /**
         * 从「端口 空格 六位码」的自由输入里抠出端口和配对码。
         * 宽松点：抓所有数字段，六位的当配对码，1024..65535 里的另一段当端口。
         */
        private fun parse(text: String): Pair<Int, String>? {
            val groups = Regex("\\d+").findAll(text).map { it.value }.toList()
            val code = groups.firstOrNull { it.length == 6 } ?: return null
            val port = groups.firstOrNull { it !== code && it.toIntOrNull() in 1024..65535 }
                ?.toIntOrNull() ?: return null
            return port to code
        }

        /** 发（或重发）配对通知。app 里点「用通知栏配对」时调，onReceive 认不出时也调。 */
        @SuppressLint("MissingPermission") // 下面 hasNotificationPermission 已判
        fun post(context: Context, hint: String? = null): Boolean {
            if (!context.hasNotificationPermission()) return false
            val remoteInput = RemoteInput.Builder(KEY_REPLY)
                .setLabel(context.getString(R.string.adb_pair_notif_reply))
                .build()
            val replyIntent = Intent(context, AdbPairingReceiver::class.java).setAction(ACTION_PAIR)
            val replyPending = PendingIntent.getBroadcast(
                context,
                0,
                replyIntent,
                // RemoteInput 要把用户输入回填进 intent，必须 MUTABLE
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = NotificationCompat.Action.Builder(
                0,
                context.getString(R.string.adb_pair_notif_reply),
                replyPending,
            ).addRemoteInput(remoteInput).build()

            val text = hint ?: context.getString(R.string.adb_pair_notif_text)
            val notification = NotificationCompat.Builder(context, CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_min)
                .setContentTitle(context.getString(R.string.adb_pair_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(action)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            return true
        }
    }
}
