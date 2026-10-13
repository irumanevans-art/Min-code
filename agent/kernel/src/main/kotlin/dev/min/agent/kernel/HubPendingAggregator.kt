package dev.min.agent.kernel

import dev.min.agent.protocol.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [SessionHub] 的三份汇总：在线的会话（[online]）、有回合在跑的会话（[running]）、等人的审批与提问（[pending]）。
 * 每个在线会话挂一个观察协程，状态一变就重算它那一份并重新发布。
 *
 * 并发规则：所有可变状态（[runningIds]、[pendingBySession]、[heldPending] 和三个 StateFlow 的写入）都在同一把 [summaryLock] 里改，
 * 三份汇总因此总是互相一致的。锁里只做内存计算，不调挂起函数、不碰别的锁；
 * 邮箱（[PeerMailbox]）在它自己的锁外调 [republishHeld]，不会两把锁交叉
 *
 * @param isOnline 会话此刻是否还在 Hub 里：待批准的跨会话消息只列收件会话仍在线的（卸下的在打开回来时会重新出现）
 */
internal class HubPendingAggregator(
    private val scope: CoroutineScope,
    private val isOnline: (SessionId) -> Boolean,
) {
    /** 每个在线会话一个：把它的状态汇总进 [online] / [running] / [pending] */
    private val observers = ConcurrentHashMap<SessionId, Job>()
    private val summaryLock = Any()
    private val runningIds = HashSet<SessionId>()
    private val pendingBySession = HashMap<SessionId, List<HubPending>>()

    /** 邮箱里待批准的消息（[HubPending.What.Peer]），按收件会话。由 [republishHeld] 在邮箱变动时刷新；与 [pendingBySession] 同一把锁 */
    private var heldPending: List<HubPending> = emptyList()

    private val _online = MutableStateFlow<Set<SessionId>>(emptySet())
    private val _running = MutableStateFlow<Set<SessionId>>(emptySet())
    private val _pending = MutableStateFlow<List<HubPending>>(emptyList())

    /** 在线（运行时驻留在内存里）的会话 */
    val online: StateFlow<Set<SessionId>> = _online.asStateFlow()

    /** 有回合在跑的会话。前台服务据此保活：非空就别让系统把进程收掉 */
    val running: StateFlow<Set<SessionId>> = _running.asStateFlow()

    /**
     * 所有在线会话里在等人的审批与提问，按发起时间排。子 agent 的也在这里（设计 §7「审批上浮」）：
     * 界面在根会话里内联显示、通知栏按它发通知，答复经 [SessionHub.get] 拿到对应会话的运行时写回去
     */
    val pending: StateFlow<List<HubPending>> = _pending.asStateFlow()

    /**
     * 会话上线：先进 [online]，再开始观察它的状态。[onState] 在每次状态变化、汇总已更新**之后**调一次（锁外），
     * Hub 借它做汇总以外的事（订阅了「空闲时通知我」的检查）
     */
    fun attach(runtime: SessionRuntime, onState: (SessionState) -> Unit) {
        val id = runtime.sessionId
        synchronized(summaryLock) { _online.value = _online.value + id }
        observers[id] = scope.launch {
            runtime.state.collect { s ->
                synchronized(summaryLock) {
                    if (s.turn != null) runningIds += id else runningIds -= id
                    pendingBySession[id] = pendingOf(id, s)
                    _running.value = runningIds.toSet()
                    publishPendingLocked()
                }
                onState(s)
            }
        }
    }

    /** 停止观察（取消协程）。与 [detach] 分两步，因为卸下的其余清理夹在中间，次序保持原样 */
    fun stopObserving(id: SessionId) {
        observers.remove(id)?.cancel()
    }

    /**
     * 会话下线：从三份汇总里去掉。必须在会话已从 Hub 的运行时表里移除**之后**调——
     * 重新发布 [pending] 时按 [isOnline] 过滤待批准的跨会话消息
     */
    fun detach(id: SessionId) {
        synchronized(summaryLock) {
            runningIds -= id
            pendingBySession -= id
            _online.value = _online.value - id
            _running.value = runningIds.toSet()
            publishPendingLocked()
        }
    }

    /**
     * 邮箱的待批准队列变了（来了一封、批了 / 拒了一封、过期了）：重算这一份并发布。
     * [held] 在 [summaryLock] 里取——与会话状态的更新串行，不会拿一份旧队列盖掉新的
     */
    fun republishHeld(held: () -> List<HubPending>) {
        synchronized(summaryLock) {
            heldPending = held()
            publishPendingLocked()
        }
    }

    /** 在 [summaryLock] 里调用：会话状态里的交互 + 邮箱里待批准的消息。收件会话已卸下的不列（打开回来时会重新出现） */
    private fun publishPendingLocked() {
        _pending.value = (pendingBySession.values.flatten() + heldPending.filter { isOnline(it.session) }).sortedBy { it.atMs }
    }

    private fun pendingOf(id: SessionId, s: SessionState): List<HubPending> {
        val t = s.turn
        val origin = s.origin
        fun item(atMs: Long, what: HubPending.What) =
            HubPending(id, origin?.root ?: id, origin?.parent, origin?.agentType, atMs, what)
        val approvals = t?.calls?.values.orEmpty().mapNotNull { call ->
            (call.phase as? CallPhase.AwaitingApproval)?.let { a ->
                item(a.requestedAtMs, HubPending.What.Approval(a.approvalId, call.callId, call.tool, a.ask, a.suggestedRule, a.floorReason))
            }
        }
        val questions = (t?.pendingQuestions.orEmpty() + s.pendingMcpQuestions).map { q -> item(q.askedAtMs, HubPending.What.Question(q.questionId, q.callId, q.questions)) }
        val urls = s.pendingMcpUrls.map { p -> item(p.atMs, HubPending.What.Question(p.request.questionId, p.request.callId ?: MCP_IDLE_CALL, listOf(urlQuestion(p.request)), p.request)) }
        val network = s.networkPending.values.map { item(it.atMs, HubPending.What.Network(it)) }
        return approvals + questions + urls + network
    }
}
