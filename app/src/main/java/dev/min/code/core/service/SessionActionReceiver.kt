package dev.min.code.core.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.min.code.AppScope
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.util.cancelNotification
import dev.min.code.util.sendNotification
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.UUID

/** 通知命令只经统一注册表；广播预算内完成应答，回合本身由引擎与前台服务持有。 */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ref = intent.sessionRef() ?: return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        val option = intent.getStringExtra(EXTRA_OPTION)
        if (intent.action !in setOf(ACTION_ANSWER, ACTION_RESUME, ACTION_STOP)) return
        val pending = goAsync()
        val koin = GlobalContext.get()
        val registry = koin.get<AgentSessionRegistry>()
        koin.get<AppScope>().launch {
            try {
                withTimeout(8_500) {
                    val commandId = intent.getStringExtra(EXTRA_COMMAND) ?: return@withTimeout
                    val command = when (intent.action) {
                        ACTION_ANSWER -> {
                            val live = registry.live.value.firstOrNull { it.ref == ref }
                            if (live?.headInteraction?.id != target || !live.keepAlive) {
                                showStale(context, ref, target)
                                return@withTimeout
                            }
                            val card = registry.engine(ref.engine).open(ref.id).getOrNull()?.state?.value
                                ?.interactions?.firstOrNull { it.id == target } as? PendingInteraction.Approval
                            val selected = card?.notificationOptions?.firstOrNull { it.id == option }
                            if (selected == null) {
                                showStale(context, ref, target)
                                return@withTimeout
                            }
                            SessionCommand.Answer(commandId, target, InteractionAnswer.Choose(selected.id,
                                if (selected.kind == ApprovalOption.Kind.Deny) context.getString(R.string.session_notification_deny_note) else null))
                        }
                        ACTION_RESUME -> SessionCommand.Resume(commandId, target)
                        else -> {
                            if (registry.live.value.none { it.ref == ref && it.keepAlive && it.busy }) {
                                showStale(context, ref, target)
                                return@withTimeout
                            }
                            SessionCommand.Interrupt(commandId, expectedTurnId = target)
                        }
                    }
                    val result = if (command is SessionCommand.Resume) {
                        // 「继续」可能要先把进程接回来（proot + node 冷启动十几秒），超出广播预算也不能把续跑半路掐掉：
                        // 放进 AppScope 里自己跑完，这里只等一会儿，等不到就让广播结束（通知会被「继续」之后的状态变化自己撤掉）
                        val job = koin.get<AppScope>().async { registry.submit(ref, command) }
                        withTimeoutOrNull(7_500) { job.await() } ?: run {
                            Log.i("SessionAction", "${intent.action} $ref 还在接回进程，广播先结束")
                            return@withTimeout
                        }
                    } else {
                        registry.submit(ref, command)
                    }
                    Log.i("SessionAction", "${intent.action} $ref result=$result")
                    // 不直接撤同会话的通知：应答期间下一张卡可能已覆盖它。撤销统一归 diff 管。
                    if (result is CommandResult.Stale) showStale(context, ref, target)
                }
            } catch (e: Exception) {
                Log.w("SessionAction", "通知命令未完成：$ref", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_ANSWER = "dev.min.code.action.SESSION_ANSWER"
        private const val ACTION_RESUME = "dev.min.code.action.SESSION_RESUME"
        private const val ACTION_STOP = "dev.min.code.action.SESSION_STOP"
        private const val EXTRA_TARGET = "target_id"
        private const val EXTRA_OPTION = "option_id"
        private const val EXTRA_COMMAND = "command_id"

        internal fun answerIntent(context: Context, ref: SessionRef, interactionId: String, optionId: String): PendingIntent =
            actionIntent(context, ref, ACTION_ANSWER, interactionId, optionId)

        /** 旧组件保留一版，只转发其 extras；没有 requestId 的旧通知由旧接收器补当前队头。 */
        internal fun forwardAnswer(context: Context, ref: SessionRef, interactionId: String, optionId: String) {
            context.sendBroadcast(Intent(context, SessionActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_SESSION_ENGINE, ref.engine.name)
                putExtra(EXTRA_SESSION_ID, ref.id)
                putExtra(EXTRA_TARGET, interactionId)
                putExtra(EXTRA_OPTION, optionId)
                putExtra(EXTRA_COMMAND, UUID.randomUUID().toString())
            })
        }

        internal fun resumeIntent(context: Context, ref: SessionRef, offerId: String): PendingIntent =
            actionIntent(context, ref, ACTION_RESUME, offerId)

        internal fun stopIntent(context: Context, ref: SessionRef, turnId: String): PendingIntent =
            actionIntent(context, ref, ACTION_STOP, turnId)

        private fun actionIntent(context: Context, ref: SessionRef, action: String, target: String, option: String? = null): PendingIntent {
            val intent = Intent(context, SessionActionReceiver::class.java).apply {
                this.action = action
                // 每条交互、每个选项各有身份；旧通知重放不可能变成新交互的按钮。
                data = Uri.Builder().scheme("min-session-action").authority(ref.engine.name)
                    .appendPath(ref.id).appendPath(action).appendPath(target).appendPath(option.orEmpty()).build()
                putExtra(EXTRA_SESSION_ENGINE, ref.engine.name)
                putExtra(EXTRA_SESSION_ID, ref.id)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_OPTION, option)
                putExtra(EXTRA_COMMAND, UUID.randomUUID().toString())
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private suspend fun showStale(context: Context, ref: SessionRef, target: String) {
            // 轻提示另用一个 id，不能覆盖当前的新审批或「继续」通知。
            val id = 9_000_000 + (stableKeyInt("${ref.storageKey}:$target") and 0x7FFFF)
            context.sendNotification(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, id) {
                title = context.engineName(ref.engine)
                content = context.getString(R.string.session_notification_stale)
                category = NotificationCompat.CATEGORY_STATUS
                autoCancel = true
                contentIntent = openSessionIntent(context, ref)
            }
            delay(1_500)
            context.cancelNotification(id)
        }
    }
}
