package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.PeerMessage
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong

/**
 * 本机跨会话消息（Hub 内两个根会话之间的邮箱）。收件方一律是「不回话的模型」，所以会话停在回合里：
 * 忙与空闲、排队、收件批准都能看得清。
 */
class PeerMailTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val silent = object : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { awaitCancellation() }
    }

    private fun hub(limits: HubLimits = HubLimits()) = SessionHub(store, testDeps(silent, emptyList()), newScope(), limits = limits)

    private fun SessionHub.portOf(rt: SessionRuntime) = portFor(rt, TurnId("t1"), CallId("c1"))

    private suspend fun SessionHub.newRoot(cwd: String, name: String, mode: PermissionMode = PermissionMode.Default) =
        create(GuestPath(cwd), TEST_MODEL, mode, name = name)

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }
    private suspend fun peerInputs(rt: SessionRuntime) = events(rt).filterIsInstance<InputReceived>().filter { it.origin is InputOrigin.Peer }
    private fun text(r: ToolResult) = (r.content.single() as TextPart).text

    @Test
    fun `the roster lists the other live root sessions by name and never the caller`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        val rows = hub.portOf(api).roster().filter { it.kind == AgentListingKind.Peer }
        assertEquals(listOf("web"), rows.map { it.name })
        assertEquals(web.sessionId.value, rows.single().id)
    }

    @Test
    fun `a message by name lands as a peer input with its own event and starts a turn, not a user message`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        val r = hub.portOf(api).message("web", "接口改了")
        assertFalse(text(r), r.isError)

        val inputs = peerInputs(web)
        assertEquals(1, inputs.size)
        assertEquals(InputOrigin.Peer(api.sessionId, "api"), inputs.single().origin)
        assertTrue((inputs.single().parts.single() as TextPart).text.contains("接口改了"))
        val event = events(web).filterIsInstance<PeerMessage>().single()
        assertEquals(api.sessionId, event.from)
        assertEquals("api", event.fromName)
        assertTrue(events(web).filterIsInstance<TurnStarted>().any { it.trigger == TurnTrigger.Notification })
        assertTrue(events(api).none { it is PeerMessage })
    }

    @Test
    fun `a busy receiver takes the message as steering input, queued behind its current batch`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        hub.portOf(api).message("web", "第一句")
        val second = hub.portOf(api).message("web", "第二句")
        assertTrue(text(second), text(second).contains("当前这批工具结束后"))
        assertEquals(2, peerInputs(web).size)
        assertEquals(Delivery.Steer, peerInputs(web).last().delivery)
    }

    @Test
    fun `two sessions that both bypass prompts exchange messages without approval`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.BypassPermissions)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "直接送")
        assertEquals(1, peerInputs(web).size)
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
    }

    @Test
    fun `a message between a bypassing and a prompting session waits for approval and stays out of the inbox`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        val r = hub.portOf(api).message("web", "等我批准")
        assertFalse(text(r), r.isError)
        assertTrue(text(r), text(r).contains("批准"))
        assertTrue(peerInputs(web).isEmpty())

        val held = hub.heldPeerMessages(web.sessionId).single()
        assertEquals("api", held.fromName)
        assertTrue(hub.resolvePeerHold(web.sessionId, held.messageId, approve = true))
        assertEquals(1, peerInputs(web).size)
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
    }

    @Test
    fun `a held message is in the hub's pending list for the receiver, and leaves it once resolved`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "批一下")
        // 界面与通知都读 pending：待批准的消息要和审批、提问排在同一个队列里，才有卡片可点
        val item = withTimeout(5_000) { hub.pending.first { list -> list.any { it.what is HubPending.What.Peer } } }
            .single { it.what is HubPending.What.Peer }
        assertEquals(web.sessionId, item.session)
        assertEquals(web.sessionId, item.root)
        val what = item.what as HubPending.What.Peer
        assertEquals(api.sessionId, what.from)
        assertEquals("api", what.fromName)
        assertEquals("批一下", what.text)

        assertTrue(hub.resolvePeerHold(web.sessionId, what.messageId, approve = false))
        withTimeout(5_000) { hub.pending.first { list -> list.none { it.what is HubPending.What.Peer } } }
    }

    @Test
    fun `without a cross-process transport a held message still expires and the sender is told`() = run {
        // 手机上没有跨进程的登记表（transport = null）：过期也得照样发生，不能因为没有收件目录就永远挂着
        val now = AtomicLong(1_000)
        val hub = SessionHub(store, testDeps(silent, emptyList()).copy(clock = Clock { now.get() }), newScope())
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "过期")
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)

        now.addAndGet(PEER_HOLD_EXPIRY_MS)
        hub.pumpPeers()
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        val notice = peerInputs(api).single()
        assertTrue((notice.parts.single() as TextPart).text.contains("超时"))
        withTimeout(5_000) { hub.pending.first { list -> list.none { it.what is HubPending.What.Peer } } }
    }

    @Test
    fun `a denied message never reaches the inbox and the sender learns the outcome`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "拒绝我")
        val held = hub.heldPeerMessages(web.sessionId).single()
        assertTrue(hub.resolvePeerHold(web.sessionId, held.messageId, approve = false))

        assertTrue(peerInputs(web).isEmpty())
        assertTrue(events(web).none { it is PeerMessage })
        val notice = peerInputs(api).single()
        assertEquals(InputOrigin.Peer(web.sessionId, "web"), notice.origin)
        assertTrue((notice.parts.single() as TextPart).text.contains("拒收"))
    }

    @Test
    fun `a receiver set to refuse gives the sender an explicit answer at once and writes nothing`() = run {
        val hub = hub(HubLimits(crossSessionInbound = PeerInboundPolicy.Refuse))
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        val r = hub.portOf(api).message("web", "没人收")
        assertTrue(r.isError)
        assertTrue(text(r), text(r).contains("不接收"))
        assertTrue(peerInputs(web).isEmpty())
        assertTrue(events(web).none { it is PeerMessage })
    }

    @Test
    fun `an ambiguous name is refused and the answer lists the ids to choose from`() = run {
        val hub = hub()
        val api1 = hub.newRoot("/a", "api")
        val api2 = hub.newRoot("/b", "api")
        val me = hub.newRoot("/c", "me")
        val r = hub.portOf(me).message("api", "你是哪个")
        assertTrue(r.isError)
        assertTrue(text(r), text(r).contains(api1.sessionId.value) && text(r).contains(api2.sessionId.value))
        assertTrue(peerInputs(api1).isEmpty() && peerInputs(api2).isEmpty())
        // 用 id 就指得准
        assertFalse(hub.portOf(me).message(api2.sessionId.value, "用 id").isError)
        assertEquals(1, peerInputs(api2).size)
    }

    @Test
    fun `a subagent cannot message a peer session, and a session cannot message itself`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        hub.newRoot("/b", "web")
        assertTrue(hub.portOf(api).message("api", "自己").isError)
        val child = (hub.portOf(api).delegate(DelegateRequest(description = "查", prompt = "查", background = true)) as DelegateResult.Started).child
        val childRt = hub.get(child)!!
        assertTrue(hub.portOf(childRt).message("web", "子 agent 越级").isError)
    }

    @Test
    fun `a session that closes drops off the roster`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        assertEquals(1, hub.portOf(api).roster().count { it.kind == AgentListingKind.Peer })
        assertTrue(hub.close(web.sessionId))
        assertEquals(0, hub.portOf(api).roster().count { it.kind == AgentListingKind.Peer })
        assertTrue(hub.portOf(api).message("web", "没了").isError)
    }

    @Test
    fun `a sender that talks too fast to one receiver is slowed down, and other receivers are not affected`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        val port = hub.portOf(api)
        repeat(PEER_BURST_LIMIT) { assertFalse(port.message("web", "第 $it 句").isError) }
        val over = port.message("web", "超了")
        assertTrue(over.isError)
        assertTrue(text(over), text(over).contains("太快"))
        assertEquals(PEER_BURST_LIMIT, peerInputs(web).size)
        val other = hub.newRoot("/c", "other")
        assertFalse(port.message("other", "不受影响").isError)
        assertEquals(1, peerInputs(other).size)
    }

    @Test
    fun `the receiver's log folds to its state after peer messages`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        hub.portOf(api).message("web", "一")
        hub.portOf(api).message("web", "二")
        // 回合还在跑（模型不回话），整份状态不必逐字相等；跨会话消息自己的折叠必须与运行时一致。
        // 读日志与读状态之间回合可能刚把收件箱接走（第一条消息开了回合），所以等两边收敛再比，不在半路上比
        var replayed = fold(store.peek(web.sessionId)!!.header, store.peek(web.sessionId)!!.read().toList())
        kotlinx.coroutines.withTimeout(5_000) {
            while (replayed.peerMessages != web.state.value.peerMessages || replayed.inbox != web.state.value.inbox || replayed.seenInputs != web.state.value.seenInputs) {
                kotlinx.coroutines.delay(25)
                val log = store.peek(web.sessionId)!!
                replayed = fold(log.header, log.read().toList())
            }
        }
        assertEquals(web.state.value.peerMessages, replayed.peerMessages)
        assertEquals(2, replayed.peerMessages.size)
        assertEquals(web.state.value.inbox, replayed.inbox)
        assertEquals(web.state.value.seenInputs, replayed.seenInputs)
    }
}
