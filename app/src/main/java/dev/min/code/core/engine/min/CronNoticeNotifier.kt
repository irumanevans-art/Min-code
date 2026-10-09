package dev.min.code.core.engine.min

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.min.agent.kernel.CronNotice
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.R
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.service.openSessionIntent
import dev.min.code.core.service.stableKeyInt
import dev.min.code.util.sendNotification

/**
 * 定时任务出了需要告诉用户的事（7 天到期、一次运行被花费上限停下、因缺 USD 价格跳过、连续超限暂停）时发一条系统通知。
 * 内核的 [CronNotice] 流本来只有日志里一条 Note——人不在会话页就看不到：无人值守的任务停了，用户得知道。
 * 触发本身不通知（那是用户自己设的）。点通知回到那个会话。
 */
internal class CronNoticeNotifier(private val context: Context, private val text: CronText = ResCronText(context)) {
    fun notify(notice: CronNotice) {
        val ref = SessionRef(EngineKind.MinAgent, notice.session.value)
        context.sendNotification(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, cronNoticeNotificationId(ref, notice.cronId)) {
            title = context.getString(cronNoticeTitleRes(notice.kind), notice.cronId)
            // 正文由内核给的结构化事实按界面语言组句（日志里那句中文只在缺事实时兜底）；通知栏放不下就折叠，点开看完整
            content = cronNoticeBody(notice, text)
            useBigTextStyle = true
            autoCancel = true
            category = NotificationCompat.CATEGORY_STATUS
            contentIntent = openSessionIntent(context, ref)
        }
    }
}

/** 同一个任务的后续通知覆盖前一条（例如先“被停”后“暂停”），不同任务 / 会话互不覆盖 */
internal fun cronNoticeNotificationId(ref: SessionRef, cronId: String): Int =
    9_000_000 + (stableKeyInt(ref.storageKey + "#cron:" + cronId) and 0x7FFFF)

internal fun cronNoticeTitleRes(kind: CronNotice.Kind): Int = when (kind) {
    CronNotice.Kind.Expired -> R.string.cron_notice_ended
    CronNotice.Kind.BudgetStopped -> R.string.cron_notice_budget_stopped
    CronNotice.Kind.Skipped -> R.string.cron_notice_skipped
}
