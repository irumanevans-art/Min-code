package dev.min.agent.kernel

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.AgentListing
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.PeerMessage
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnTrigger
import java.util.concurrent.atomic.AtomicBoolean

/** 同一个发件会话对同一个收件会话，一分钟之内最多送这么多条（超了是循环或刷屏，不是在聊天） */
const val PEER_BURST_LIMIT = 20

/** 发送窗口的长度（毫秒） */
internal const val PEER_BURST_WINDOW_MS = 60_000L

/** 收件方的待批准队列上限：超了丢最旧的，并告诉发件人 */
const val PEER_HOLD_LIMIT = 100

/** 待批准超过这么久没人点，视为没送达（CC 的 dialogExpiry 默认五分钟） */
const val PEER_HOLD_EXPIRY_MS = 5 * 60_000L

/** 等收件方用户批准的一条消息。它还没进对方的对话，批准前对方的模型看不到它 */
data class HeldPeerMessage(
    val messageId: String,
    val from: SessionId,
    val fromName: String?,
    val text: String,
    val sentAtMs: Long,
)

/** 收件方对一条消息的裁决（纯函数，见 [peerVerdict]） */
internal enum class PeerVerdict { Deliver, Hold, Refuse }

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
private class Incoming(val messageId: String, val from: SessionId, val fromName: String?, val text: String, val sentAtMs: Long)

/**
 * 本机跨会话邮箱（Claude Code 的 SendMessage / ListAgents 到「本机其他会话」那一段）。
 *
 * - 发现：Hub 内的根会话直接看内存；别的进程的会话看登记表（[PeerTransport]）。只列根会话，子 agent 与团队成员仍走原来的路。
 * - 送达：收件方忙 → 作为插话排队（当前这批工具结束后看到）；空闲 → 开一个回合。消息以 [InputOrigin.Peer] 进对话，不是用户的话。
 * - 身份：发件人的名字与「是不是绕过类」都由发件方的 Hub 按它自己的状态填，模型填不了；桌面收件方的名字取自发件人的登记。
 * - 拒收与批准：拒收的消息什么也不写。本机直送时发件人立刻得到原因；跨进程时得到之后送回的结论。
 *
 * 待批准队列与发送窗口只在内存里：待批准的消息没进日志，宿主重启即丢（发件人得到的是「没有送达」，不会悄悄丢）。
 */
internal class PeerMailbox(
    private val hub: SessionHub,
    private val deps: KernelDeps,
    private val transport: PeerTransport?,
    private val policy: () -> PeerInboundPolicy,
) {
    /** 只护这两张内存表；锁内不挂起，投递一律在锁外做 */
    private val guard = Any()
    private val held = LinkedHashMap<SessionId, MutableList<HeldPeerMessage>>()
    private val windows = HashMap<Pair<SessionId, SessionId>, ArrayDeque<Long>>()
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

    /**
     * 按名字或 id 找本机的根会话并送出。返回 null = 没有叫这个名字的会话（调用方照旧报「找不到」）。
     * 重名时不猜：列出各自的 id，让模型用 id 再发。
     */
    suspend fun send(from: SessionRuntime, to: String, text: String): ToolResult? {
        if (from.state.value.origin != null) return null
        val wanted = to.trim()
        val roots = hub.liveRoots()
        val localIds = roots.map { it.sessionId.value }.toSet()
        val locals = roots.filter { it.state.value.name == wanted || it.sessionId.value == wanted }
        val remotes = transport?.entries().orEmpty().filter { it.session !in localIds && (it.name == wanted || it.session == wanted) }
        if (locals.size + remotes.size == 0) return null
        if (locals.size + remotes.size > 1) {
            val rows = locals.map { "「${it.state.value.name ?: "未命名"}」id ${it.sessionId.value} · ${it.state.value.created.cwd.value}" } +
                remotes.map { "「${it.name ?: "未命名"}」id ${it.session} · ${it.cwd}" }
            return error("有 ${rows.size} 个会话叫「$wanted」，请用 id 指定：${rows.joinToString("；")}")
        }
        val targetId = locals.singleOrNull()?.sessionId ?: SessionId(remotes.single().session)
        if (targetId == from.sessionId) return error("不能给自己发消息。")

        val now = deps.clock.nowMs()
        if (!allowBurst(from.sessionId, targetId, now)) {
            return error("发得太快了：同一个会话一分钟内最多 $PEER_BURST_LIMIT 条，请合并成一条，或稍后再发。")
        }
        val msg = Incoming(deps.ids.next("pm"), from.sessionId, from.state.value.name, text, now)
        val senderBypass = from.bypassing()

        val local = locals.singleOrNull()
        if (local != null) {
            return when (peerVerdict(policy(), local.bypassing(), senderBypass)) {
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
        val rt = hub.get(receiver)
        if (approve && rt != null) {
            deliverTo(rt, Incoming(item.messageId, item.from, item.fromName, item.text, item.sentAtMs))
        } else if (approve) {
            outcome(item.from, receiver, null, item.messageId, "对方已关闭，没有送达。", deps.clock.nowMs())
        } else {
            outcome(item.from, receiver, rt?.state?.value?.name, item.messageId, "对方的用户拒收了你的消息，它没有进入对方的对话。", deps.clock.nowMs())
        }
        return true
    }

    /** 待批准的消息（界面列出来让用户批） */
    fun heldFor(receiver: SessionId): List<HeldPeerMessage> = synchronized(guard) { held[receiver].orEmpty().toList() }

    // ---- 收件（别的进程送来的） ----

    /**
     * 取走收件目录里的信封，处理完确认删除。宿主心跳里调用（[SessionHub.startScheduling]），测试直调。
     * 同一时刻只跑一轮：上一轮没完就跳过，免得两轮抢同一批信。
     */
    suspend fun pump() {
        val t = transport ?: return
        if (!pumping.compareAndSet(false, true)) return
        try {
            publishLocal()
            expireHeld()
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
                val msg = Incoming(env.messageId, senderId, sender.name, env.text, env.sentAtMs)
                when (peerVerdict(policy(), rt.bypassing(), env.senderBypass)) {
                    PeerVerdict.Refuse -> outcome(senderId, rt.sessionId, rt.state.value.name, env.messageId, "对方设置为不接收跨会话消息，没有送达。", env.sentAtMs)
                    PeerVerdict.Hold -> hold(rt.sessionId, msg)
                    PeerVerdict.Deliver -> deliverTo(rt, msg)
                }
            }
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
            list += HeldPeerMessage(m.messageId, m.from, m.fromName, m.text, m.sentAtMs)
            if (list.size > PEER_HOLD_LIMIT) list.removeAt(0) else null
        }
        if (dropped != null) {
            outcome(dropped.from, receiver, null, dropped.messageId, "待批准的消息超过上限，它没有送达。", deps.clock.nowMs())
        }
    }

    /** 超时的待批准消息：发件人得到「没有送达」。只在心跳里跑 */
    private suspend fun expireHeld() {
        val now = deps.clock.nowMs()
        val expired = synchronized(guard) {
            val out = mutableListOf<Pair<SessionId, HeldPeerMessage>>()
            for ((receiver, list) in held) {
                val it = list.iterator()
                while (it.hasNext()) {
                    val item = it.next()
                    if (now - item.sentAtMs >= PEER_HOLD_EXPIRY_MS) {
                        it.remove()
                        out += receiver to item
                    }
                }
            }
            out
        }
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

    private fun ok(text: String) = ToolResult(listOf(TextPart(text)))
    private fun error(text: String) = ToolResult(listOf(TextPart(text)), isError = true)
}
