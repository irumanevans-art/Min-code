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
import dev.min.code.R
import dev.min.code.core.engine.AgentSessionRegistry
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.InteractionBrief
import dev.min.code.core.engine.InteractionKind
import dev.min.code.core.engine.LiveSessionSummary
import dev.min.code.core.engine.PEER_MESSAGE_APPROVAL
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.ResumeBrief
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.SupervisorEvent
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.supervisorDiff
import dev.min.code.CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID
import dev.min.code.core.claudecode.ClaudeCodeManager
import dev.min.code.core.claudecode.ClaudeCodeSessionRegistry
import dev.min.code.core.claudecode.ClaudeSubscription
import dev.min.code.core.claudecode.subagentTypeName
import dev.min.code.util.cancelNotification
import dev.min.code.util.sendNotification
import org.koin.java.KoinJavaComponent.inject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import dev.min.code.core.session.SessionStatus

/**
 * 会话的「后台管家」：统一注册表保活 + 后台通知（CC / Min / Codex 同一条路径）。
 *
 * 单例，App 启动时构造一次（见 MinApp）。两件事都必须在**页面之外**做 ——
 * 页面被销毁正是这些逻辑最需要生效的时候。
 *
 * ## 1. 保活
 * 观察 [AgentSessionRegistry.anyKeepAlive]：第一个会话起来时拉起
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
class SessionSupervisor(
    private val context: Application,
    appScope: AppScope,
    private val registry: AgentSessionRegistry,
    private val localServices: LocalServiceRegistry,
    private val subscription: ClaudeSubscription,
) {
    private val isForeground = MutableStateFlow(false)

    /**
     * 上一轮观察到的每个会话状态，用来做「边沿触发」——只在状态真的翻转时才发通知。
     *
     * 必须是并发 map：`liveSessions` 的收集跑在 Dispatchers.Default，而进前台的回调跑在
     * 主线程且要遍历 keys。普通 mutableMap 在这两者之间会抛 ConcurrentModificationException，
     * 而且是"切回 App 的瞬间偶发崩溃"这种最难复现的形态。
     */
    /** B0 特征测试保留的旧输入形状；实际 CC / Min 通知用 LiveSessionSummary 与新版 diff。 */
    internal data class ClaudeSeen(
        val busy: Boolean,
        val pendingId: String?,
        val pendingTool: String?,
        val status: SessionStatus,
        val isLive: Boolean,
        val errorMessage: String?,
    )

    private val seen = ConcurrentHashMap<SessionRef, LiveSessionSummary>()

    init {
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            isForeground.value = true
                            // 回到前台就把待审批提醒撤掉，UI 上的 sheet 才是主界面
                            seen.keys.forEach { context.cancelNotification(interactionNotificationId(it)) }
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
            // 会话（含 Codex：它的 app-server 进程活着就是注册表里的 keepAlive 会话）**或**
            // 本地服务 **或** 订阅登录，任一在进行都要保活。
            // 登录那一项：人在浏览器里、App 在后台，proot 里等回调的 CLI 被冻住的话浏览器就卡在跳回来那一步
            combine(
                registry.anyKeepAlive,
                localServices.anyRunning,
                subscription.active,
            ) { live, svc, loggingIn -> live || svc || loggingIn }
                .filter { it }
                .collect { ClaudeCodeForegroundService.start(context) }
        }

        appScope.launch(Dispatchers.Default) {
            registry.live.collect { sessions ->
                for (session in sessions) diff(session)
                // 会话从注册表消失（被回收）时清掉它的残留通知和记忆
                val keys = sessions.map { it.ref }.toSet()
                (seen.keys - keys).forEach { gone ->
                    seen.remove(gone)
                    context.cancelNotification(interactionNotificationId(gone))
                }
            }
        }
    }

    private suspend fun diff(session: LiveSessionSummary) {
        val previous = seen.put(session.ref, session)
        for (event in supervisorDiff(previous, session, isForeground.value)) {
            when (event) {
                is SupervisorEvent.Interaction -> notifyInteraction(session.ref, event.brief)
                // 已经在别处应答了（App 内 sheet、或通知按钮），撤掉提醒
                SupervisorEvent.InteractionCleared ->
                    context.cancelNotification(interactionNotificationId(session.ref))
                SupervisorEvent.ResumeCleared -> context.cancelNotification(sessionDoneNotificationId(session.ref))
                SupervisorEvent.TurnDone -> context.sendNotification(
                    channelId = CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID,
                    notificationId = sessionDoneNotificationId(session.ref),
                ) {
                    title = context.engineName(session.ref.engine)
                    content = notificationBody(session.lastAssistantText, context.getString(R.string.session_notification_done))
                    autoCancel = true
                    useDefaults = true
                    category = NotificationCompat.CATEGORY_MESSAGE
                    contentIntent = openSessionIntent(context, session.ref)
                }
                is SupervisorEvent.Died -> {
                    context.cancelNotification(interactionNotificationId(session.ref))
                    notifyEnded(session.ref, context.getString(R.string.session_notification_ended, context.engineName(session.ref.engine)), event.message, event.resume)
                }
                is SupervisorEvent.TurnIncomplete -> notifyEnded(
                    session.ref, context.getString(R.string.session_notification_incomplete, context.engineName(session.ref.engine)),
                    (event.end as? TurnEnd.Failed)?.message ?: session.errorMessage, event.resume,
                )
            }
        }
    }

    private suspend fun notifyInteraction(ref: SessionRef, brief: InteractionBrief) {
        val options = if (brief.kind == InteractionKind.Approval) {
            val card = registry.engine(ref.engine).open(ref.id).getOrNull()?.state?.value
                ?.interactions?.firstOrNull { it.id == brief.id } as? PendingInteraction.Approval
            card?.notificationOptions.orEmpty()
        } else emptyList()
        context.sendNotification(
            channelId = CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID,
            notificationId = interactionNotificationId(ref),
        ) {
            title = context.getString(
                if (brief.fromSchedule) R.string.session_notification_schedule_confirm else R.string.session_notification_confirm,
                context.engineName(ref.engine),
            )
            // 子 agent 上浮的审批 / 提问 / 计划都写明是谁要的：用户从通知栏看不到会话，不说来源就以为是自己刚发的那句话在等
            val agent = brief.agent
            val agentName = agent?.let { it.name ?: subagentTypeName(it.agentType, context.getString(R.string.agent_type_fork)) }.orEmpty()
            val agentSuffix = if (agent?.background == true) context.getString(R.string.session_notification_background) else ""
            content = when (brief.kind) {
                InteractionKind.Approval ->
                    if (brief.toolName == PEER_MESSAGE_APPROVAL) context.getString(R.string.session_notification_peer)
                    else if (agent != null) context.getString(R.string.session_notification_agent_approval, agentName, agentSuffix, brief.toolName.orEmpty())
                    else context.getString(R.string.session_notification_tool, brief.toolName.orEmpty())
                InteractionKind.Question ->
                    if (agent != null) context.getString(R.string.session_notification_agent_question, agentName, agentSuffix)
                    else context.getString(R.string.session_notification_question)
                InteractionKind.PlanReview ->
                    if (agent != null) context.getString(R.string.session_notification_agent_plan, agentName, agentSuffix)
                    else context.getString(R.string.session_notification_plan)
            }
            // ongoing：这条不是"看过就算"的提醒，会话真的停在这里等它
            ongoing = true
            autoCancel = false
            useDefaults = true
            category = NotificationCompat.CATEGORY_CALL
            contentIntent = openSessionIntent(context, ref)
            // 交互摘要不含完整选项；在协程里读取实际审批卡，只把 notificationSafe 放进通知。
            if (brief.kind == InteractionKind.Approval) {
                options.forEach { option ->
                    addAction(context.getString(if (option.kind == ApprovalOption.Kind.Allow) R.string.session_notification_allow else R.string.session_notification_deny),
                        SessionActionReceiver.answerIntent(context, ref, brief.id, option.id))
                }
            }
        }
    }

    private fun notifyEnded(ref: SessionRef, heading: String, message: String?, resume: ResumeBrief?) {
        context.sendNotification(CLAUDE_CODE_ALERT_NOTIFICATION_CHANNEL_ID, sessionDoneNotificationId(ref)) {
            title = heading
            content = message?.take(160) ?: context.getString(R.string.session_notification_exited)
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_ERROR
            contentIntent = openSessionIntent(context, ref)
            resume?.let { addAction(context.getString(R.string.session_notification_resume), SessionActionReceiver.resumeIntent(context, ref, it.offerId)) }
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
        // 升级前发出的通知没有这一项，按旧行为应答当前那条
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val registry: ClaudeCodeSessionRegistry by inject(ClaudeCodeSessionRegistry::class.java)
        // 挂着的已经换成另一条了就什么都不做：不应答，也**不撤通知** —— 通知 id 按会话算，
        // 这时状态栏上的已经是新那条的提醒（监督器看到 id 变了会重发），撤掉就等于把它也吞了
        val target = requestId ?: registry.get(key)?.state?.value?.pendingPermission?.requestId ?: return
        SessionActionReceiver.forwardAnswer(context, SessionRef(EngineKind.ClaudeCode, key), target,
            if (allow) ApprovalOption.ALLOW else ApprovalOption.DENY)
    }

    companion object {
        private const val ACTION_ANSWER = "dev.min.code.action.CLAUDE_CODE_ANSWER_PERMISSION"
        private const val EXTRA_SESSION_KEY = "session_key"
        private const val EXTRA_ALLOW = "allow"
        private const val EXTRA_REQUEST_ID = "request_id"

        fun intent(context: Context, key: String, requestId: String, allow: Boolean): PendingIntent {
            val intent = Intent(context, ClaudeCodePermissionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_SESSION_KEY, key)
                putExtra(EXTRA_REQUEST_ID, requestId)
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

/** [notificationBody] 的压行：连续空白（含换行）压成一个空格 */
private val WHITESPACE_RUN = Regex("\\s+")

/** 完成通知正文的最大长度，按 String 数（中文一字算一） */
private const val TURN_DONE_BODY_MAX_CHARS = 40

/** [claudeSupervisorDiff] 判出来的事。一次观察可能同时有好几件（比如审批撤了、会话也挂了） */
internal sealed interface ClaudeSupervisorEvent {
    /** 来了一条（或换了一条）待批请求，后台时提醒 */
    data class Permission(val tool: String, val requestId: String) : ClaudeSupervisorEvent
    /** 待批请求没了（已在别处应答），撤提醒 —— 前台也要撤 */
    data object PermissionCleared : ClaudeSupervisorEvent
    data object TurnDone : ClaudeSupervisorEvent
    data class Died(val message: String?) : ClaudeSupervisorEvent
}

/**
 * 「一轮完成」通知的正文：这一轮最后一条助手回复，压成单行再截到 40 字。
 *
 * 换行变空格、连续空白压成一个、trim 之后按 `String.length` 截断（中文一字算一），
 * 截断处不加省略号。没有可用的文本（null / 全是空白 —— 比如整轮只有工具调用或报错）
 * 就退回默认文案，**不发空正文**。纯函数，规则单测钉在 NotificationBodyTest。
 */
internal fun notificationBody(lastAssistantText: String?, fallback: String = "任务已完成"): String {
    val line = lastAssistantText?.replace(WHITESPACE_RUN, " ")?.trim().orEmpty()
    return line.take(TURN_DONE_BODY_MAX_CHARS).ifEmpty { fallback }
}

/**
 * Claude 会话的旧版通知边沿判定，只剩 B0 特征测试用它对拍统一的 [dev.min.code.core.engine.supervisorDiff]；
 * 线上走的是后者（Claude / Min / Codex 一视同仁）。
 *
 * 待批请求比的是 id「换了没有」而不是「有没有」：A 应答完、B 紧接着到，而 liveSessions 的收集
 * 是合并的，中间那一帧 null 可能根本看不到。以前只比「有没有」，状态栏上就一直挂着
 * 「请求使用 A」，按钮按下去应答的却是 B。
 */
internal fun claudeSupervisorDiff(
    previous: SessionSupervisor.ClaudeSeen?,
    current: SessionSupervisor.ClaudeSeen,
    isForeground: Boolean,
): List<ClaudeSupervisorEvent> = buildList {
    if (current.pendingId != null && current.pendingId != previous?.pendingId) {
        if (!isForeground) add(ClaudeSupervisorEvent.Permission(current.pendingTool.orEmpty(), current.pendingId))
    } else if (current.pendingId == null && previous?.pendingId != null) {
        add(ClaudeSupervisorEvent.PermissionCleared)
    }
    if (isForeground || previous == null) return@buildList

    if (previous.busy && !current.busy && current.isLive) add(ClaudeSupervisorEvent.TurnDone)

    val died = previous.status != current.status &&
        (current.status == SessionStatus.Failed || current.status == SessionStatus.Closed)
    if (died) add(ClaudeSupervisorEvent.Died(current.errorMessage))
}

/**
 * 会话 key → 稳定的 32 位整数（SHA-256 前 4 字节）。取代 `hashCode() and 0xFFFF` ——
 * 16 位熵在多会话下撞车不罕见，通知互相覆盖、PendingIntent 串号是安全事故。
 */
internal fun stableKeyInt(key: String): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return (digest[0].toInt() and 0xFF shl 24) or
        (digest[1].toInt() and 0xFF shl 16) or
        (digest[2].toInt() and 0xFF shl 8) or
        (digest[3].toInt() and 0xFF)
}
