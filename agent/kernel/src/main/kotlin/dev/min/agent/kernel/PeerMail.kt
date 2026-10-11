package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentListing
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PeerMessage
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** 同一个发件会话对同一个收件会话，一分钟之内最多送这么多条（超了是循环或刷屏，不是在聊天） */
const val PEER_BURST_LIMIT = 20

/** 发送窗口的长度（毫秒） */
internal const val PEER_BURST_WINDOW_MS = 60_000L

/** 收件方的待批准队列上限：超了丢最旧的，并告诉发件人 */
const val PEER_HOLD_LIMIT = 100

/** 待批准超过这么久没人点，视为没送达（CC 的 dialogExpiry 默认五分钟；实际期限按设置，见 [dialogExpiryMsFrom]） */
const val PEER_HOLD_EXPIRY_MS = DEFAULT_DIALOG_EXPIRY_MS

/** 「对方空闲时通知我」的订阅超过这么久还没等到，就取消并告诉订阅的一方（CC：12 小时） */
const val PEER_WATCH_EXPIRY_MS = 12 * 3_600_000L

/** 等收件方用户批准的一条消息。它还没进对方的对话，批准前对方的模型看不到它 */
data class HeldPeerMessage(
    val messageId: String,
    val from: SessionId,
    val fromName: String?,
    val text: String,
    val sentAtMs: Long,
    /** 发件会话当时是不是「绕过权限提示」类：收件方的权限类或设置变了要重判，得记着它 */
    val senderBypass: Boolean = false,
    /** 挂起的时刻：过期从这里算（CC 的批准框从弹出起计时），不是从发出起 */
    val heldAtMs: Long = sentAtMs,
)

/** 收件方对一条消息的裁决（纯函数，见 [peerVerdict]） */
internal enum class PeerVerdict { Deliver, Hold, Refuse }

/** 一个会话此刻适用的收件设置：口径与待批准消息的期限（null = 不过期） */
internal data class PeerReceiveSettings(val policy: PeerInboundPolicy, val expiryMs: Long?)

/**
 * 收件裁决，照 Claude Code 的口径：Auto 下收发两边都是「绕过权限提示」或都不是 → 直接送；两边不同类 → 等收件方的用户批准。
 * 这样「要提示」的会话收到「绕过」会话的话要批准（对方可能替它做了它没权做的事），反过来也一样。
 */
internal fun peerVerdict(policy: PeerInboundPolicy, receiverBypass: Boolean, senderBypass: Boolean): PeerVerdict = when (policy) {
    PeerInboundPolicy.Accept -> PeerVerdict.Deliver
    PeerInboundPolicy.Hold -> PeerVerdict.Hold
    PeerInboundPolicy.Refuse -> PeerVerdict.Refuse
    PeerInboundPolicy.Auto -> if (receiverBypass != senderBypass) PeerVerdict.Hold else PeerVerdict.Deliver
}

/** 会话是不是「绕过权限提示」类（处于计划模式、而计划前是绕过的，也算绕过） */
internal fun SessionRuntime.bypassing(): Boolean {
    val s = state.value.settings
    return s.mode == PermissionMode.BypassPermissions || (s.mode == PermissionMode.Plan && s.modeBeforePlan == PermissionMode.BypassPermissions)
}

/** 一封确定要送进某个会话的消息。本机直送与桌面取回的都整理成这个形状 */
private class Incoming(val messageId: String, val from: SessionId, val fromName: String?, val text: String, val sentAtMs: Long, val senderBypass: Boolean)

/** 一个「对方空闲时通知我」的订阅：记在被盯的会话所在的进程里。[statusAllowed] 为假时通知里不带被盯会话的状态行（它那边是 hold） */
private class IdleWatch(
    val id: String,
    val asker: SessionId,
    val askerName: String?,
    val watched: SessionId,
    val statusAllowed: Boolean,
    val createdMs: Long,
)

/** 找收件会话的结果：没找到 / 找到一个 / 出了错（重名、给自己发） */
private sealed interface PeerTarget {
    data object None : PeerTarget
    class Found(val id: SessionId, val local: SessionRuntime?) : PeerTarget
    class Failed(val result: ToolResult) : PeerTarget
}

/**
 * 会话在跨会话消息里给人看的称呼：改过名用名字；没改过名用人发的第一条消息的开头（与会话列表的标题同一来源），
 * 免得收件方与批准卡上只看到一串 id。只用于显示——按名字寻址仍只认真正的名字（[SessionState.name]）
 */
internal val SessionState.peerLabel: String?
    get() = name ?: history.asSequence()
        .filterIsInstance<HistoryEntry.User>()
        .firstOrNull { it.origin == InputOrigin.User }
        ?.parts?.filterIsInstance<TextPart>()?.joinToString(" ") { it.text }
        ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
        ?.let { if (it.length > PEER_LABEL_MAX) it.take(PEER_LABEL_MAX) + "…" else it }

private const val PEER_LABEL_MAX = 24

/**
 * 本机跨会话邮箱（Claude Code 的 SendMessage / ListAgents 到「本机其他会话」那一段）。
 *
 * - 发现：Hub 内的根会话直接看内存；别的进程的会话看登记表（[PeerTransport]）。只列根会话，子 agent 与团队成员仍走原来的路。
 * - 送达：收件方忙 → 作为插话排队（当前这批工具结束后看到）；空闲 → 开一个回合。消息以 [InputOrigin.Peer] 进对话，不是用户的话。
 * - 身份：发件人的名字与「是不是绕过类」都由发件方的 Hub 按它自己的状态填，模型填不了；桌面收件方的名字取自发件人的登记。
 * - 口径：每个收件会话按自己的设置判（[settings]：crossSessionInbound 与 dialogExpiry，见 [crossSessionInboundFrom]）。
 * - 拒收与批准：拒收的消息什么也不写。本机直送时发件人立刻得到原因；跨进程时得到之后送回的结论。
 *   待批准的消息会随心跳重判（收件方权限类变了、设置变了），默认规则挂起的才会过期，明写 hold 的不过期。
 * - 空闲通知（notify_when_idle）：订阅记在被盯会话所在的进程里，对方空闲或退出时发一次，发完即撤；12 小时没等到就取消并告诉订阅方。
 *
 * 待批准队列、订阅与发送窗口只在内存里：待批准的消息没进日志，宿主重启即丢——CC 的批准框同样不落盘、过期就丢，原文没有「重启后还在」。
 * 会话关闭时还挂着的消息，发件人得到「对方已关闭」，不会悄悄丢；进程被杀的，跨进程发件人得不到结论（与 CC 一致：对方没了就没了）。
 * 待批准的消息经 [onHeldChanged] 并进 Hub 的 `pending`，界面的卡片与通知读那里。
 */
internal class PeerMailbox(
    private val hub: SessionHub,
    private val deps: KernelDeps,
    private val transport: PeerTransport?,
    private val settings: suspend (SessionRuntime) -> PeerReceiveSettings,
    /** 待批准队列变了。在 [guard] 之外调用：Hub 据此把待批准的消息并进 `pending`（界面的卡片、通知都读那里） */
    private val onHeldChanged: () -> Unit = {},
) {
    /** 只护这几张内存表；锁内不挂起，投递一律在锁外做 */
    private val guard = Any()
    private val held = LinkedHashMap<SessionId, MutableList<HeldPeerMessage>>()
    private val windows = HashMap<Pair<SessionId, SessionId>, ArrayDeque<Long>>()
    private val watches = HashMap<Pair<SessionId, SessionId>, IdleWatch>()
    private val pumping = AtomicBoolean(false)

    // ---- 发现 ----

    /** ListAgents 里的本机其他会话。非根会话看不到别人：子 agent 不能跨会话 */
    fun roster(from: SessionRuntime): List<AgentListing> {
        if (from.state.value.origin != null) return emptyList()
        val roots = hub.liveRoots()
        val localIds = roots.map { it.sessionId.value }.toSet()
        val local = roots.filter { it.sessionId != from.sessionId }.map { rt ->
            AgentListing(
                kind = AgentListingKind.Peer,
                name = rt.state.value.name,
                id = rt.sessionId.value,
                state = if (!rt.isQuiescent()) "running" else "idle",
                description = rt.state.value.created.cwd.value,
            )
        }
        val remote = transport?.entries().orEmpty()
            .filter { it.session !in localIds && it.session != from.sessionId.value }
            .map { e -> AgentListing(AgentListingKind.Peer, e.name, e.session, if (e.busy) "running" else "idle", e.cwd) }
        return local + remote
    }

    // ---- 发送 ----

    /** 按名字或 id 找本机的根会话（本 Hub 里的，或别的进程登记的）。重名时不猜：列出各自的 id，让模型用 id 再发 */
    private fun resolve(from: SessionRuntime, to: String): PeerTarget {
        val wanted = to.trim()
        val roots = hub.liveRoots()
        val localIds = roots.map { it.sessionId.value }.toSet()
        val locals = roots.filter { it.state.value.name == wanted || it.sessionId.value == wanted }
        val remotes = transport?.entries().orEmpty().filter { it.session !in localIds && (it.name == wanted || it.session == wanted) }
        if (locals.size + remotes.size == 0) return PeerTarget.None
        if (locals.size + remotes.size > 1) {
            val rows = locals.map { "「${it.state.value.name ?: "未命名"}」id ${it.sessionId.value} · ${it.state.value.created.cwd.value}" } +
                remotes.map { "「${it.name ?: "未命名"}」id ${it.session} · ${it.cwd}" }
            return PeerTarget.Failed(error("有 ${rows.size} 个会话叫「$wanted」，请用 id 指定：${rows.joinToString("；")}"))
        }
        val local = locals.singleOrNull()
        val id = local?.sessionId ?: SessionId(remotes.single().session)
        if (id == from.sessionId) return PeerTarget.Failed(error("不能给自己发消息。"))
        return PeerTarget.Found(id, local)
    }

    /**
     * 按名字或 id 找本机的根会话并送出。返回 null = 没有叫这个名字的会话（调用方照旧报「找不到」）。
     */
    suspend fun send(from: SessionRuntime, to: String, text: String): ToolResult? {
        if (from.state.value.origin != null) return null
        val target = when (val t = resolve(from, to)) {
            PeerTarget.None -> return null
            is PeerTarget.Failed -> return t.result
            is PeerTarget.Found -> t
        }
        val targetId = target.id

        val now = deps.clock.nowMs()
        if (!allowBurst(from.sessionId, targetId, now)) {
            return error("发得太快了：同一个会话一分钟内最多 $PEER_BURST_LIMIT 条，请合并成一条，或稍后再发。")
        }
        val senderBypass = from.bypassing()
        val msg = Incoming(deps.ids.next("pm"), from.sessionId, from.state.value.peerLabel, text, now, senderBypass)

        val local = target.local
        if (local != null) {
            return when (peerVerdict(settings(local).policy, local.bypassing(), senderBypass)) {
                PeerVerdict.Refuse -> error("对方设置为不接收跨会话消息，没有送达。")
                PeerVerdict.Hold -> {
                    hold(local.sessionId, msg)
                    ok("对方的用户要先批准这条消息；批准前它不会进对方的对话，批准结果会告诉你。")
                }
                PeerVerdict.Deliver -> {
                    deliverTo(local, msg)
                    ok(if (!local.isQuiescent()) "已送达，它会在当前这批工具结束后看到。" else "已送达，它开始处理了。")
                }
            }
        }
        // 别的进程的会话：交进它的收件目录，它那边的 Hub 按它的口径判定，结论由它送回来
        val t = transport ?: return error("对方会话已经不在线，没有送达。")
        val env = PeerEnvelope(msg.messageId, PeerEnvelope.Kind.Message, from.sessionId.value, targetId.value, text, senderBypass, now)
        if (!t.deliver(targetId.value, env)) return error("对方会话已经不在线，没有送达。")
        return ok("已交给对方所在的进程；它处理后会把结果告诉你。")
    }

    // ---- 空闲通知（SendMessage 的 notify_when_idle） ----

    /**
     * 请对方空闲（或退出）时给我发一次通知。[text] 非空 = 先送这条消息、再订阅（原文：先送消息，通知之后到）；为空 = 只订阅，
     * 不给对方开回合、不花它的 token，对方此刻已经空闲就马上通知。
     *
     * 只有主会话（根）能订阅、只能订阅本机上自己的其他会话：对队友、子 agent、别的机器的会话整个调用都拒绝（连附带的消息也不送）——
     * 这几类在调用到这里之前由 [SessionHub] 挡掉，这里挡「找不到本机会话」。
     * 任何一边设为 refuse：订阅不成立。原文让被盯的一方不回应、订阅 12 小时后自然失效；Min 当场告诉模型，免得它白等 12 小时
     * （与拒收消息时的做法一致）。任何一边是 hold：照常订阅，但被盯会话的状态行不带进通知（订阅方那边 hold 时通知只进记录、不交给模型）。
     */
    suspend fun watch(from: SessionRuntime, to: String, text: String?): ToolResult {
        if (from.state.value.origin != null) return error("只有主会话能订阅对方的空闲通知。")
        val target = when (val t = resolve(from, to)) {
            PeerTarget.None -> return error("找不到本机上叫「${to.trim()}」的会话。空闲通知只能订阅你自己在本机的其他会话（队友、子 agent、别的机器上的会话不行），整个调用没有执行。")
            is PeerTarget.Failed -> return t.result
            is PeerTarget.Found -> t
        }
        if (settings(from).policy == PeerInboundPolicy.Refuse) {
            return error("你这边设置为不接收跨会话消息，订阅了也收不到通知；没有订阅，也没有发送。")
        }
        val askerBypass = from.bypassing()
        val local = target.local
        if (local != null && peerVerdict(settings(local).policy, local.bypassing(), askerBypass) == PeerVerdict.Refuse) {
            return error("对方设置为不接收跨会话消息，没有订阅，也没有发送。")
        }
        var sent: ToolResult? = null
        if (!text.isNullOrBlank()) {
            sent = send(from, to, text) ?: return error("找不到本机上叫「${to.trim()}」的会话。")
            if (sent.isError) return sent
        }
        val prefix = sent?.let { it.content.filterIsInstance<TextPart>().joinToString("") { p -> p.text } + "\n" }.orEmpty()
        val now = deps.clock.nowMs()
        if (local != null) {
            val statusAllowed = peerVerdict(settings(local).policy, local.bypassing(), askerBypass) == PeerVerdict.Deliver
            addWatch(IdleWatch(deps.ids.next("pw"), from.sessionId, from.state.value.peerLabel, local.sessionId, statusAllowed, now))
            checkIdle(local.sessionId)
            return ok(prefix + "已订阅：对方下次空闲或退出时你会收到一条通知（最多等 12 小时）。" +
                if (statusAllowed) "" else "对方设为需要批准，通知里不会带它的状态行。")
        }
        val t = transport ?: return error("对方会话已经不在线，没有订阅。")
        val env = PeerEnvelope(deps.ids.next("pw"), PeerEnvelope.Kind.Watch, from.sessionId.value, target.id.value, "", askerBypass, now)
        if (!t.deliver(target.id.value, env)) return error("对方会话已经不在线，没有订阅。")
        return ok(prefix + "已把订阅交给对方所在的进程：它下次空闲或退出时你会收到一条通知（最多等 12 小时）。")
    }

    private fun addWatch(w: IdleWatch) {
        synchronized(guard) { watches[w.asker to w.watched] = w }
    }

    /** 这个会话有没有人盯着它（Hub 的状态观察者据此决定要不要等它空闲） */
    fun isWatched(id: SessionId): Boolean = synchronized(guard) { watches.keys.any { it.second == id } }

    /** 被盯的会话 [id] 此刻空闲就把通知发出去（每个订阅只发一次） */
    suspend fun checkIdle(id: SessionId) {
        val rt = hub.get(id) ?: return
        if (!rt.isQuiescent()) return
        val due = synchronized(guard) { watches.filterKeys { it.second == id }.values.toList() }
        for (w in due) fire(rt, w, exited = false)
    }

    /** 心跳：所有被盯的会话里已经空闲的，通知发出去 */
    private suspend fun checkAllIdle() {
        val ids = synchronized(guard) { watches.keys.map { it.second }.toSet() }
        for (id in ids) checkIdle(id)
    }

    private suspend fun fire(watched: SessionRuntime, w: IdleWatch, exited: Boolean) {
        val removed = synchronized(guard) { watches.remove(w.asker to w.watched) === w }
        if (!removed) return
        val name = watched.state.value.peerLabel
        val state = watched.state.value
        val status = if (!w.statusAllowed) null else
            (state.history.lastOrNull() as? HistoryEntry.Assistant)?.parts?.filterIsInstance<TextPart>()?.joinToString("") { it.text }
                ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(100)
        val at = Instant.ofEpochMilli(deps.clock.nowMs()).atZone(deps.zone).toLocalTime().withNano(0)
        val body = buildString {
            append("会话「${name ?: "未命名"}」")
            append(if (exited) "退出了（$at）" else "空闲了（回合于 $at 结束）")
            if (status != null) append("：").append(status)
        }
        val watchedBypass = watched.bypassing()
        val askerLocal = hub.get(w.asker)
        if (askerLocal != null) {
            deliverIdleNotice(askerLocal, w.id, watched.sessionId, name, body, watchedBypass)
            return
        }
        transport?.deliver(w.asker.value, PeerEnvelope(w.id, PeerEnvelope.Kind.IdleNotice, watched.sessionId.value, w.asker.value, body, watchedBypass, deps.clock.nowMs()))
    }

    /** 订阅方收到通知：按它自己的口径——accept / 同类 → 交给模型（开回合）；hold → 只在记录里留一行、不交给模型；refuse → 丢 */
    private suspend fun deliverIdleNotice(asker: SessionRuntime, watchId: String, from: SessionId, fromName: String?, body: String, fromBypass: Boolean) {
        when (peerVerdict(settings(asker).policy, asker.bypassing(), fromBypass)) {
            PeerVerdict.Refuse -> Unit
            PeerVerdict.Hold -> asker.commit(listOf(Note(NoteLevel.Info, "跨会话通知（设置为需要批准，没有交给模型）：$body")), Durability.Batched)
            PeerVerdict.Deliver -> asker.receive(
                ClientMsgId("peer-idle:$watchId"),
                listOf(TextPart(idleHeading(fromName) + body)),
                Delivery.Steer,
                InputOrigin.Peer(from, fromName),
                TurnTrigger.Notification,
            )
        }
    }

    /** 订阅超过 12 小时没有等到：撤掉，告诉订阅的一方 */
    private suspend fun expireWatches() {
        val now = deps.clock.nowMs()
        val expired = synchronized(guard) {
            val out = watches.values.filter { now - it.createdMs >= PEER_WATCH_EXPIRY_MS }
            out.forEach { watches.remove(it.asker to it.watched) }
            out
        }
        for (w in expired) {
            outcome(w.asker, w.watched, hub.get(w.watched)?.state?.value?.peerLabel, w.id, "订阅的空闲通知等了 12 小时也没有等到，已取消。", now)
        }
    }

    // ---- 批准 ----

    /**
     * 收件方的用户批准或拒收一条待批准的消息。批准：进对话；拒收：什么也不写，发件人得到结论。
     * 返回 false = 没有这条待批准的消息（已经处理过、已超时或从没有过）。
     */
    suspend fun resolveHold(receiver: SessionId, messageId: String, approve: Boolean): Boolean {
        val item = synchronized(guard) {
            val list = held[receiver] ?: return false
            val hit = list.firstOrNull { it.messageId == messageId } ?: return false
            list.remove(hit)
            hit
        }
        onHeldChanged()
        val rt = hub.get(receiver)
        if (approve && rt != null) {
            deliverTo(rt, Incoming(item.messageId, item.from, item.fromName, item.text, item.sentAtMs, item.senderBypass))
        } else if (approve) {
            outcome(item.from, receiver, null, item.messageId, "对方已关闭，没有送达。", deps.clock.nowMs())
        } else {
            outcome(item.from, receiver, rt?.state?.value?.peerLabel, item.messageId, "对方的用户拒收了你的消息，它没有进入对方的对话。", deps.clock.nowMs())
        }
        return true
    }

    /** 待批准的消息（界面列出来让用户批） */
    fun heldFor(receiver: SessionId): List<HeldPeerMessage> = synchronized(guard) { held[receiver].orEmpty().toList() }

    /**
     * 会话要关了：还挂着的待批准消息，发件人得到「对方已关闭」；盯着它的订阅发一条「退出了」；它自己订阅的作废。
     * 在卸下之前调用（[SessionHub.close] / [SessionHub.delete]），此时它的日志还能写、发件人若在本 Hub 里也还在线
     */
    suspend fun sessionClosing(rt: SessionRuntime) {
        val id = rt.sessionId
        val items = synchronized(guard) { held.remove(id).orEmpty() }
        if (items.isNotEmpty()) onHeldChanged()
        for (item in items) outcome(item.from, id, rt.state.value.peerLabel, item.messageId, "对方已关闭，没有送达。", deps.clock.nowMs())
        val ending = synchronized(guard) { watches.values.filter { it.watched == id } }
        for (w in ending) fire(rt, w, exited = true)
        synchronized(guard) { watches.keys.removeAll { it.first == id } }
    }

    /** 全部收件会话的待批准消息（Hub 并进 `pending` 用） */
    fun heldAll(): Map<SessionId, List<HeldPeerMessage>> = synchronized(guard) { held.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() } }

    // ---- 收件（别的进程送来的） ----

    /**
     * 心跳：重判待批准的消息、过期、订阅的空闲检查，然后取走收件目录里的信封，处理完确认删除。
     * 宿主心跳里调用（[SessionHub.startScheduling] / [SessionHub.startPeerHeartbeat]），测试直调。
     * 同一时刻只跑一轮：上一轮没完就跳过，免得两轮抢同一批信。
     */
    suspend fun pump() {
        if (!pumping.compareAndSet(false, true)) return
        try {
            // 前几步不看 transport：手机上没有跨进程收件目录，本 Hub 里的会话之间也会有待批准的消息与订阅
            reevaluateHeld()
            expireHeld()
            expireWatches()
            checkAllIdle()
            val t = transport ?: return
            publishLocal()
            for (rt in hub.liveRoots()) {
                for (taken in t.take(rt.sessionId.value)) {
                    handleRemote(rt, taken.envelope)
                    t.ack(rt.sessionId.value, taken.fileId)
                }
            }
        } finally {
            pumping.set(false)
        }
    }

    /** 把本进程的根会话登记上（名字、忙不忙）。改名、回合起止之后下一次心跳就更新 */
    fun publishLocal() {
        val t = transport ?: return
        for (rt in hub.liveRoots()) t.publish(entryOf(rt))
    }

    fun publish(rt: SessionRuntime) {
        transport?.publish(entryOf(rt))
    }

    fun withdraw(id: SessionId) {
        transport?.withdraw(id.value)
    }

    private fun entryOf(rt: SessionRuntime) = PeerEntry(
        session = rt.sessionId.value,
        name = rt.state.value.name,
        cwd = rt.state.value.created.cwd.value,
        busy = !rt.isQuiescent(),
    )

    private suspend fun handleRemote(rt: SessionRuntime, env: PeerEnvelope) {
        // 信封里的收件人必须就是这个收件目录的主人；发件人必须仍登记在线。身份取登记表，不信信封里的话
        if (env.to != rt.sessionId.value) return
        val sender = transport?.entries()?.firstOrNull { it.session == env.from } ?: return
        val senderId = SessionId(sender.session)
        when (env.kind) {
            PeerEnvelope.Kind.Outcome -> deliverOutcomeLocal(rt, env.messageId, senderId, sender.name, env.text, env.sentAtMs)
            PeerEnvelope.Kind.Message -> {
                val msg = Incoming(env.messageId, senderId, sender.name, env.text, env.sentAtMs, env.senderBypass)
                when (peerVerdict(settings(rt).policy, rt.bypassing(), env.senderBypass)) {
                    PeerVerdict.Refuse -> outcome(senderId, rt.sessionId, rt.state.value.peerLabel, env.messageId, "对方设置为不接收跨会话消息，没有送达。", env.sentAtMs)
                    PeerVerdict.Hold -> hold(rt.sessionId, msg)
                    PeerVerdict.Deliver -> deliverTo(rt, msg)
                }
            }
            PeerEnvelope.Kind.Watch -> {
                // 别的进程的会话请我（被盯的一方）空闲时通知它
                val verdict = peerVerdict(settings(rt).policy, rt.bypassing(), env.senderBypass)
                if (verdict == PeerVerdict.Refuse) {
                    outcome(senderId, rt.sessionId, rt.state.value.peerLabel, env.messageId, "对方设置为不接收跨会话消息，没有订阅。", env.sentAtMs)
                } else {
                    addWatch(IdleWatch(env.messageId, senderId, sender.name, rt.sessionId, verdict == PeerVerdict.Deliver, deps.clock.nowMs()))
                    checkIdle(rt.sessionId)
                }
            }
            PeerEnvelope.Kind.IdleNotice -> deliverIdleNotice(rt, env.messageId, senderId, sender.name, env.text, env.senderBypass)
        }
    }

    // ---- 投递 ----

    /** 进对话：与团队邮件同一个写法——输入（origin = peer）与 [PeerMessage] 同一次提交，clientMsgId 去重 */
    private suspend fun deliverTo(to: SessionRuntime, m: Incoming) {
        to.receive(
            ClientMsgId("peer:${m.messageId}"),
            listOf(TextPart(heading(m.from, m.fromName) + m.text)),
            Delivery.Steer,
            InputOrigin.Peer(m.from, m.fromName),
            TurnTrigger.Notification,
            extra = listOf(PeerMessage(m.messageId, m.from, m.fromName, m.text, m.sentAtMs)),
        )
    }

    /** 结论进发件人的收件箱。它不是对方的话，所以不写 [PeerMessage]；同一封结论的去重靠 messageId */
    private suspend fun deliverOutcomeLocal(rt: SessionRuntime, messageId: String, fromId: SessionId, fromName: String?, text: String, sentAtMs: Long) {
        rt.receive(
            ClientMsgId("peer-outcome:$messageId"),
            listOf(TextPart(outcomeHeading(fromName) + text)),
            Delivery.Steer,
            InputOrigin.Peer(fromId, fromName),
            TurnTrigger.Notification,
        )
    }

    /** 把结论送回发件人：发件人在本进程就直接写，在别的进程就交进它的收件目录 */
    private suspend fun outcome(to: SessionId, fromId: SessionId, fromName: String?, messageId: String, text: String, sentAtMs: Long) {
        val local = hub.get(to)
        if (local != null) {
            deliverOutcomeLocal(local, messageId, fromId, fromName, text, sentAtMs)
            return
        }
        transport?.deliver(to.value, PeerEnvelope(messageId, PeerEnvelope.Kind.Outcome, fromId.value, to.value, text, false, sentAtMs))
    }

    /** 放进待批准队列。同一封（messageId）只留一份；超了丢最旧的，并告诉它的发件人 */
    private suspend fun hold(receiver: SessionId, m: Incoming) {
        val dropped = synchronized(guard) {
            val list = held.getOrPut(receiver) { mutableListOf() }
            if (list.any { it.messageId == m.messageId }) return
            list += HeldPeerMessage(m.messageId, m.from, m.fromName, m.text, m.sentAtMs, m.senderBypass, heldAtMs = deps.clock.nowMs())
            if (list.size > PEER_HOLD_LIMIT) list.removeAt(0) else null
        }
        onHeldChanged()
        if (dropped != null) {
            outcome(dropped.from, receiver, null, dropped.messageId, "待批准的消息超过上限，它没有送达。", deps.clock.nowMs())
        }
    }

    /**
     * 待批准的消息按收件方**此刻**的口径重判（CC：设置变了、权限类变了，held 的消息重新过一遍规则）：
     * 现在该送的进对话，现在该拒的发件人得到结论，仍该挂着的原样留着。放行或拒收了的在收件方日志里留一条说明（只给人看）。
     * 批准、拒收仍然只有收件方的用户能做（[resolveHold]）——这里放行靠的是规则变了，不是消息自己说了什么。
     */
    private suspend fun reevaluateHeld() {
        val receivers = synchronized(guard) { held.filterValues { it.isNotEmpty() }.keys.toList() }
        for (receiver in receivers) {
            val rt = hub.get(receiver) ?: continue
            val policy = settings(rt).policy
            val bypass = rt.bypassing()
            val release = mutableListOf<HeldPeerMessage>()
            val reject = mutableListOf<HeldPeerMessage>()
            synchronized(guard) {
                val list = held[receiver] ?: return@synchronized
                val it = list.iterator()
                while (it.hasNext()) {
                    val item = it.next()
                    when (peerVerdict(policy, bypass, item.senderBypass)) {
                        PeerVerdict.Deliver -> { it.remove(); release += item }
                        PeerVerdict.Refuse -> { it.remove(); reject += item }
                        PeerVerdict.Hold -> Unit
                    }
                }
            }
            if (release.isNotEmpty() || reject.isNotEmpty()) onHeldChanged()
            for (item in release) deliverTo(rt, Incoming(item.messageId, item.from, item.fromName, item.text, item.sentAtMs, item.senderBypass))
            for (item in reject) outcome(item.from, receiver, rt.state.value.peerLabel, item.messageId, "对方设置为不接收跨会话消息，没有送达。", deps.clock.nowMs())
            if (release.isNotEmpty() || reject.isNotEmpty()) {
                val parts = listOfNotNull(
                    release.size.takeIf { it > 0 }?.let { "$it 条按现在的设置或权限模式放行了" },
                    reject.size.takeIf { it > 0 }?.let { "$it 条被拒收了" },
                )
                rt.commit(listOf(Note(NoteLevel.Info, "等待批准的跨会话消息：${parts.joinToString("，")}。")), Durability.Batched)
            }
        }
    }

    /**
     * 超时的待批准消息：发件人得到「没有送达」。只有**默认规则**挂起的才过期（设置里明写 hold 的不过期，等 accept 来了才放），
     * 期限取收件会话的 dialogExpiry（默认 5 分钟，`never` 不过期）。只在心跳里跑
     */
    private suspend fun expireHeld() {
        val now = deps.clock.nowMs()
        val receivers = synchronized(guard) { held.filterValues { it.isNotEmpty() }.keys.toList() }
        val expired = mutableListOf<Pair<SessionId, HeldPeerMessage>>()
        for (receiver in receivers) {
            // 收件会话已经不在 Hub 里（卸下）：按默认口径与默认期限
            val s = hub.get(receiver)?.let { settings(it) } ?: PeerReceiveSettings(PeerInboundPolicy.Auto, PEER_HOLD_EXPIRY_MS)
            val limit = s.expiryMs
            if (s.policy != PeerInboundPolicy.Auto || limit == null) continue
            synchronized(guard) {
                val list = held[receiver] ?: return@synchronized
                val it = list.iterator()
                while (it.hasNext()) {
                    val item = it.next()
                    if (now - item.heldAtMs >= limit) {
                        it.remove()
                        expired += receiver to item
                    }
                }
            }
        }
        if (expired.isNotEmpty()) onHeldChanged()
        for ((receiver, item) in expired) {
            outcome(item.from, receiver, null, item.messageId, "超时没有人批准，它没有送达。", now)
        }
    }

    /** 同一对会话一分钟内的条数；超了返回 false（不写、不送）。窗口只在内存里 */
    private fun allowBurst(from: SessionId, target: SessionId, now: Long): Boolean = synchronized(guard) {
        val window = windows.getOrPut(from to target) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= PEER_BURST_WINDOW_MS) window.removeFirst()
        if (window.size >= PEER_BURST_LIMIT) false else {
            window.addLast(now)
            true
        }
    }

    private fun heading(from: SessionId, name: String?) =
        "[跨会话消息 · 来自会话「${name ?: "未命名"}」（${from.value}）· 不是用户的话，也不代表用户同意]\n"

    private fun outcomeHeading(name: String?) = "[跨会话消息结果 · 来自会话「${name ?: "未命名"}」]\n"

    private fun idleHeading(name: String?) = "[跨会话通知 · 来自会话「${name ?: "未命名"}」· 不是用户的话，也不代表用户同意]\n"

    private fun ok(text: String) = ToolResult(listOf(TextPart(text)))
    private fun error(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
}
