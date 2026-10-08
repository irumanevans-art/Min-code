package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.CronBudgetStopped
import dev.min.agent.spi.CronExpiry
import dev.min.agent.spi.CronExpired
import dev.min.agent.spi.CronFired
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.CronTask
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 定时任务出了需要告诉用户的事（宿主据此发通知；日志里同时有一条 [Note]）。触发本身不通知——那是用户自己设的 */
data class CronNotice(val session: SessionId, val cronId: String, val kind: Kind, val text: String) {
    enum class Kind {
        /** 任务结束了（7 天到期、一次性错过、连续两次超限暂停） */
        Expired,

        /** 一次运行花到了单次上限，这一次运行被停下；任务本身还在（除非同时是连续第二次，那时通知的是 [Expired]） */
        BudgetStopped,

        /** 这一次到点没触发：任务设了 USD 花费上限，当前模型却没有 USD 价格，上限核对不了。任务还在，价格有了就恢复 */
        Skipped,
    }
}

/**
 * 一次连续超限要多少次才暂停任务。一次超限多半是这次运行碰巧要做的事多（重试、读了大文件），下一次未必；
 * 连着两次说明提示词或上限本身有问题，再无人值守地跑下去只是继续烧钱
 */
internal const val CRON_PAUSE_AFTER_OVERRUNS = 2

/**
 * 定时任务的运行时（SessionHub 持有一个）：到点触发、过期、花费上限。任务**状态**在会话日志的折叠里（[Crons.kt]），
 * 这里没有第二份——只有每个会话一把锁、一个「会话（重新）打开的时刻」和一个盯着会话状态的协程。
 *
 * 触发的条件（Claude Code：会话开着、空闲时才触发）：
 *  - 到点：[tick] 由宿主的 [dev.min.agent.spi.Scheduler] 周期性地叫；
 *  - 空闲：会话没有回合、没有维护、收件箱空（[SessionRuntime.isQuiescent]）。忙的时候到点的任务**排着**：
 *    它仍然「到点」，回合一结束（盯状态的协程发现会话重新空闲）就触发，不用等下一次心跳；
 *  - 一次只触发一条：触发会开回合，会话立刻又忙了，其余到点的任务等下一次空闲。
 *
 * 触发 = 在该会话里写一条来源为 [InputOrigin.Cron] 的输入，与 [CronFired] 同一次提交（前置条件在提交的锁里再判一次空闲）。
 * 它不是人发的话：不过 UserPromptSubmit hook、不记回退点，输入文字里也明说这是预设任务。
 *
 * 花费上限（可选，默认没有，同 Claude Code）：设了上限的任务触发时立一份 [CronRun]，整棵会话树的花费都记到它头上；
 * 每个模型请求发出前 [ModelBilling] 查它，花到了就不再发（请求前拦，所以正常结束的运行不算超限）。超限**只停这一次运行**：
 * 回合以 [InterruptedBy.Budget] 收尾、子 agent / 团队成员一并停下，任务保持进行中；连续 [CRON_PAUSE_AFTER_OVERRUNS] 次才暂停任务。
 * 记账撤销要等整棵树安静（见 [stopForBudget]）。
 */
internal class CronEngine(
    private val hub: SessionHub,
    private val deps: KernelDeps,
    private val scope: CoroutineScope,
    /** 宿主的花费天花板（[HubLimits.cronMaxBudgetUsd]）；null = 没设 */
    private val ceilingUsd: Double? = null,
) {
    private class Attached(val floorMs: Long) {
        /** 每个会话一把锁：触发、过期、花费上限的处理互相串行（同一时刻只有一个在改这个会话的定时任务） */
        val lock = Mutex()
        var watcher: Job? = null
        val idleCheckQueued = AtomicBoolean(false)

        /** 因缺 USD 价格跳过的循环任务：跳过的那一刻起重新数，这一次不补跑（同「错过不补跑」）。只在内存里，重开会话本来就从打开那刻重新数 */
        val skippedAt = ConcurrentHashMap<String, Long>()

        /** 已经为「缺 USD 价格」提示过的任务；这次提示后不再重复，直到它真的触发过一次 */
        val skipNoticed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val attached = ConcurrentHashMap<SessionId, Attached>()
    private val _notices = MutableSharedFlow<CronNotice>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<CronNotice> = _notices.asSharedFlow()

    /**
     * 接上一个根会话（创建、重新打开时）：先恢复——过期的、错过的一次性任务在这里结案——再开始盯着它。
     * 「恢复」不需要把任务从哪里读回来：折叠出的状态里未过期的任务本来就在，这里只是把过期的清掉，
     * 并把 [Attached.floorMs] 定在此刻，让循环任务从这一刻重新数（错过不补跑）
     */
    suspend fun attach(rt: SessionRuntime) {
        if (rt.state.value.origin != null || attached.containsKey(rt.sessionId)) return
        val now = deps.clock.nowMs()
        val entry = Attached(floorMs = now)
        entry.lock.withLock { restore(rt, now) }
        if (attached.putIfAbsent(rt.sessionId, entry) != null) return
        entry.watcher = scope.launch { rt.state.collect { s -> onState(rt, entry, s) } }
    }

    fun detach(id: SessionId) {
        attached.remove(id)?.watcher?.cancel()
    }

    /** 这个会话每个任务“下一次从哪一刻起往后数”的下限（给 CronList 的“下次”用，口径与 [tickLocked] 的触发一致） */
    fun nextFireFloors(id: SessionId): Map<String, Long> {
        val entry = attached[id] ?: return emptyMap()
        val rt = hub.get(id) ?: return emptyMap()
        return rt.state.value.crons.associate { it.id to maxOf(entry.floorMs, entry.skippedAt[it.id] ?: 0L) }
    }

    /** 宿主定时器的一次回调：看每个接着的会话有没有到点的任务 */
    suspend fun tick(nowMs: Long) {
        for (id in attached.keys.toList()) {
            val rt = hub.get(id) ?: continue
            tickSession(rt, nowMs)
        }
    }

    suspend fun tickSession(rt: SessionRuntime, nowMs: Long) {
        val entry = attached[rt.sessionId] ?: return
        entry.lock.withLock { tickLocked(rt, entry, nowMs) }
    }

    // ---- 恢复 ----

    private suspend fun restore(rt: SessionRuntime, now: Long) {
        for (task in rt.state.value.crons.filter { it.active }) {
            val expiry = when {
                task.isPastExpiry(now) -> expired(CronExpiry.Age, null)
                // 一次性任务的时刻在会话没开着时过去了：不补跑，结案（循环任务不同——它从现在重新数）
                !task.recurring && (task.nextFireAt(deps.zone) ?: Long.MAX_VALUE) <= now -> expired(CronExpiry.Missed, null)
                else -> null
            }
            if (expiry != null) expire(rt, task, expiry)
        }
    }

    private fun expired(reason: CronExpiry, detail: String?) = CronExpired("", reason, detail)

    // ---- 触发 ----

    private suspend fun tickLocked(rt: SessionRuntime, entry: Attached, now: Long) {
        for (task in rt.state.value.crons.filter { it.active }) {
            if (task.isPastExpiry(now)) expire(rt, task, expired(CronExpiry.Age, null))
        }
        if (!rt.isQuiescent()) return
        val due = rt.state.value.crons.filter { it.active }
            .mapNotNull { t -> t.nextFireAt(deps.zone, maxOf(entry.floorMs, entry.skippedAt[t.id] ?: 0L))?.takeIf { it <= now }?.let { t to it } }
            .minByOrNull { it.second }?.first ?: return
        fire(rt, entry, due, now)
    }

    private suspend fun fire(rt: SessionRuntime, entry: Attached, task: CronTask, now: Long) {
        val state = rt.state.value
        // 没有上限（任务没设、宿主也没设天花板）就不记账：和 Claude Code 一样不限，花的是用户自己的额度
        val limit = listOfNotNull(task.maxBudgetUsd, ceilingUsd).minOrNull()
        var run: CronRun? = null
        if (limit != null) {
            // 设了 USD 上限却核对不了花费的（当前模型没有 USD 定价，如 CNY 模型、目录外模型）：本次跳过，不触发。
            // 价格不明的模型，上限就是空话。只跳这一次——用户临时切到别的模型不该让一个 7 天的任务永久作废
            val priced = catchingNonCancellation { deps.models.resolve(state.activeModel).profile.pricing }.getOrNull()
            if (priced == null || priced.currency != "USD") {
                skip(rt, entry, task, now, limit)
                return
            }
            run = CronRun(rt.sessionId, task.id, task.fires + 1, limit)
        }
        entry.skipNoticed.remove(task.id)
        val clientMsgId = ClientMsgId("cron:${task.id}:${task.fires + 1}")
        val expectedFires = task.fires
        // 先立好这次运行的记账再提交：提交一落盘回合就可能开始，第一个模型请求就要看得到上限
        rt.cronRun = run
        val delivered = rt.receive(
            clientMsgId,
            listOf(TextPart(firedText(task, limit))),
            Delivery.Queue,
            InputOrigin.Cron(task.id),
            startTurn = TurnTrigger.Cron,
            extra = listOf(CronFired(task.id, now, clientMsgId)),
            // 提交的锁里再判一次：任务还在、没被别的触发抢先、会话确实空闲
            precondition = { s -> rt.isQuiescent() && s.cronTask(task.id)?.let { it.active && it.fires == expectedFires } == true },
        )
        if (!delivered) rt.cronRun = null
    }

    /**
     * 缺 USD 价格，这一次不触发。循环任务从此刻重新数（这一次错过了就是错过了）；一次性任务保持到点，价格有了就在下一次心跳触发。
     * 提示只给一次：Note 进日志、通知用户，之后同一个任务再跳过不重复（任务真的触发过一次后重新计）
     */
    private suspend fun skip(rt: SessionRuntime, entry: Attached, task: CronTask, now: Long, limitUsd: Double) {
        if (task.recurring) entry.skippedAt[task.id] = now
        if (!entry.skipNoticed.add(task.id)) return
        val text = "定时任务 ${task.id}（${task.scheduleText()}）到点了，但当前模型没有 USD 价格，花费上限 ${money(limitUsd)} 核对不了，这一次跳过没有触发。" +
            "任务还在；换回有 USD 价格的模型后" + (if (task.recurring) "下一次到点会照常运行" else "会触发") + "。"
        rt.commit(listOf(Note(NoteLevel.Warning, text)), Durability.Batched)
        _notices.tryEmit(CronNotice(rt.sessionId, task.id, CronNotice.Kind.Skipped, text))
    }

    /** 触发时交给模型的话：明说这是预设的定时任务（不是用户此刻的消息）；设了花费上限的，告诉它上限 */
    private fun firedText(task: CronTask, limitUsd: Double?): String =
        "[定时任务 ${task.id} 到点触发（${task.scheduleText()}）。这是会话里预设的任务，不是用户此刻发的消息，也没有人在看着这次运行：不要向用户提问；" +
            "需要批准的工具调用会停在那里等人（用户会收到通知），别指望立刻得到答复，能避开就避开。" +
            (limitUsd?.let { "本次运行的花费上限是 ${money(it)}，到了会被停止。" } ?: "") +
            "]\n${task.prompt}"

    // ---- 盯着会话状态：回合结束就触发排着的任务，并结束这次运行的记账 ----

    private suspend fun onState(rt: SessionRuntime, entry: Attached, state: SessionState) {
        val idleLooking = state.turn == null && state.inbox.isEmpty()
        if (idleLooking && (rt.cronRun != null || state.crons.any { it.active }) && entry.idleCheckQueued.compareAndSet(false, true)) {
            scope.launch {
                // 状态上看空闲了，回合 Job 可能还没收尾：等它真的结束再判（isQuiescent 看的是 Job）
                rt.awaitIdle()
                entry.idleCheckQueued.set(false)
                entry.lock.withLock {
                    // 这次运行的记账要等整棵树都安静了才结束：根会话答完了，后台子 agent / 团队成员可能还在花钱。
                    // 它们的报告会回到根会话的收件箱，那一下状态变化会再来判一次
                    clearRunIfQuiet(rt)
                    tickLocked(rt, entry, deps.clock.nowMs())
                }
            }
        }
    }

    // ---- 花费上限 ----

    /**
     * [ModelBilling] 发现这次运行已到上限、拦下了一个请求（每次运行只通知一次，见 [CronRun.trip]）。
     * 在引擎自己的协程里收尾——调用方正站在某个会话的模型请求里，不能在那里等锁、停整棵树
     */
    fun tripped(run: CronRun, problem: String) {
        scope.launch { stopForBudget(run, problem) }
    }

    /**
     * 这次运行花到了上限：整棵树停下（被拦的请求本来就让各自的回合以 Budget 收尾，这里补上那些此刻没在发请求的——
     * 在跑长工具的根、后台子 agent），记 cron_budget_stopped + 一条 Note、通知用户。任务保持进行中，
     * 连续 [CRON_PAUSE_AFTER_OVERRUNS] 次才再记一条 cron_expired(budget) 暂停它。
     *
     * 记账**不在这里撤**，留到整棵树真的安静（[clearRunIfQuiet]）：运行已经到上限，期间任何还想发的请求（比如前台等着子 agent 的根，
     * 子 agent 一被停它就醒来要发下一个请求）都该被同一个预检拦下，而不是因为记账先撤了就漏过去、多花一轮
     */
    private suspend fun stopForBudget(run: CronRun, problem: String) {
        val rt = hub.get(run.rootId) ?: return
        val entry = attached[run.rootId] ?: return
        entry.lock.withLock {
            val task = rt.state.value.cronTask(run.cronId)
            if (task != null && task.status != CronStatus.Cancelled && task.status != CronStatus.Expired) recordStop(rt, task, run, problem)
        }
        // 事件落盘之后再停（停整棵树要等各会话收尾，不握着锁）
        for (child in rt.state.value.children.keys) hub.stopTree(child)
        rt.interrupt(InterruptedBy.Budget)
        rt.awaitIdle()
        // 根已经空闲时没有后续的状态变化来触发「树空闲」的判断（盯状态的协程只在根的状态变时才来），这里主动判一次
        entry.lock.withLock { if (rt.cronRun === run) clearRunIfQuiet(rt) }
    }

    /** 这次运行的记账要等整棵树都安静了才结束 */
    private fun clearRunIfQuiet(rt: SessionRuntime) {
        if (rt.cronRun != null && rt.isQuiescent() && hub.treeTurnsIdle(rt.sessionId)) rt.cronRun = null
    }

    private suspend fun recordStop(rt: SessionRuntime, task: CronTask, run: CronRun, problem: String) {
        val consecutive = if (run.fire > 1 && task.stoppedFire == run.fire - 1) task.overruns + 1 else 1
        val pause = task.active && consecutive >= CRON_PAUSE_AFTER_OVERRUNS
        val what = "定时任务 ${task.id}（${task.scheduleText()}）这一次运行被花费上限停下了：$problem。"
        val text = when {
            pause -> "$what 这是连续第 $consecutive 次超限，任务已暂停；要继续请调整提示词或上限后重新创建。"
            task.active -> "$what 任务还在，下次到点会照常再跑一次（连续 $CRON_PAUSE_AFTER_OVERRUNS 次超限才会暂停）。"
            else -> "$what 这是一次性任务 / 唤醒，已经触发过，不会再跑。"
        }
        val events = mutableListOf<SessionEvent>(CronBudgetStopped(task.id, run.fire, problem))
        if (pause) events += CronExpired(task.id, CronExpiry.Budget, "连续 $consecutive 次运行都超过单次上限（最近一次：$problem）")
        events += Note(NoteLevel.Warning, text)
        rt.commitIf(events, Durability.Sync) { s -> s.cronTask(task.id)?.let { it.status == CronStatus.Active || it.status == CronStatus.Fired } == true } ?: return
        _notices.tryEmit(CronNotice(rt.sessionId, task.id, if (pause) CronNotice.Kind.Expired else CronNotice.Kind.BudgetStopped, text))
    }

    /** 结案一条任务：cron_expired + 一条给人看的 Note + 通知 */
    private suspend fun expire(rt: SessionRuntime, task: CronTask, template: CronExpired) {
        val event = template.copy(cronId = task.id)
        val text = "定时任务 ${task.id}（${task.scheduleText()}）已结束：${expiryText(event)}。"
        val events: List<SessionEvent> = listOf(event, Note(NoteLevel.Warning, text))
        rt.commitIf(events, Durability.Sync) { s -> s.cronTask(task.id)?.active == true } ?: return
        _notices.tryEmit(CronNotice(rt.sessionId, task.id, CronNotice.Kind.Expired, text))
    }
}
