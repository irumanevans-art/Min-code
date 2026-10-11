package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Clock
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.Note
import dev.min.agent.spi.PeerMessage
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolResult
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
 * 按会话的收件口径与待批准消息的生命周期（CC cross-session-messaging「Control inbound messages」「Non-interactive sessions」）：
 * - 每个会话按自己 cwd 下读到的设置（user / project / local）定 crossSessionInbound，同一个宿主里不同目录的会话可以不一样；
 * - 默认规则挂起的消息才会过期（dialogExpiry）；设置里明写的 hold 不过期，等 accept 来了才放；
 * - 会话的权限类变了、设置变了：待批准的消息按新口径重判，放行的进对话并留一条说明，拒收的发件人得到结论；
 * - 会话关闭：还挂着的消息发件人得到「对方已关闭」。
 * 待批准的消息只在内存里：CC 的批准框不落盘、过期就丢，原文没有「重启后还在」这回事。
 */
class PeerInboundTest {
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

    private val fs = FakeFs()
    private val now = AtomicLong(1_000)

    private fun hub(limits: HubLimits = HubLimits(), env: Map<String, String> = emptyMap(), tag: String = ""): SessionHub {
        val deps = testDeps(silent, emptyList(), fs = fs, idTag = tag).copy(memoryHome = GuestPath("/home"), clock = Clock { now.get() }, env = env::get)
        return SessionHub(store, deps, newScope(), limits = limits)
    }

    private fun SessionHub.portOf(rt: SessionRuntime) = portFor(rt, TurnId("t1"), CallId("c1"))

    private suspend fun SessionHub.newRoot(cwd: String, name: String, mode: PermissionMode = PermissionMode.Default) =
        create(GuestPath(cwd), TEST_MODEL, mode, name = name)

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }
    private suspend fun peerInputs(rt: SessionRuntime) = events(rt).filterIsInstance<InputReceived>().filter { it.origin is InputOrigin.Peer }
    private fun text(r: ToolResult) = (r.content.single() as TextPart).text
    private fun inputText(i: InputReceived) = (i.parts.single() as TextPart).text

    private fun user(json: String) = fs.put("/home/.claude/settings.json", json)
    private fun project(cwd: String, json: String) = fs.put("$cwd/.claude/settings.json", json)

    // ---- 按会话的口径 ----

    @Test
    fun `each session reads its own settings, so two directories in one host can differ`() = run {
        project("/refusing", """{"crossSessionInbound":"refuse"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val closed = hub.newRoot("/refusing", "closed")
        val open = hub.newRoot("/open", "open")

        val no = hub.portOf(api).message("closed", "能收吗")
        assertTrue(text(no), no.isError)
        assertTrue(peerInputs(closed).isEmpty())
        assertFalse(hub.portOf(api).message("open", "能收吗").isError)
        assertEquals(1, peerInputs(open).size)
    }

    @Test
    fun `a user accept lets a message through even when the two permission classes differ`() = run {
        user("""{"crossSessionInbound":"accept"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "直接送")
        assertEquals(1, peerInputs(web).size)
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
    }

    @Test
    fun `a user hold holds every message, even between sessions of the same class`() = run {
        user("""{"crossSessionInbound":"hold"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        hub.portOf(api).message("web", "先别送")
        assertTrue(peerInputs(web).isEmpty())
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)
    }

    @Test
    fun `a repository file cannot loosen what the user chose, and cannot open a session the host refuses`() = run {
        user("""{"crossSessionInbound":"refuse"}""")
        project("/b", """{"crossSessionInbound":"accept"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        assertTrue(hub.portOf(api).message("web", "项目想放宽").isError)
        assertTrue(peerInputs(web).isEmpty())

        // 宿主级的 Refuse（HubLimits）同样压过设置文件里的 accept
        val hostRefusing = hub(HubLimits(crossSessionInbound = PeerInboundPolicy.Refuse), tag = "h2-")
        user("""{"crossSessionInbound":"accept"}""")
        val a2 = hostRefusing.newRoot("/a2", "api2")
        val w2 = hostRefusing.newRoot("/b2", "web2")
        assertTrue(hostRefusing.portOf(a2).message("web2", "宿主不收").isError)
        assertTrue(peerInputs(w2).isEmpty())
    }

    // ---- 待批准消息的生命周期 ----

    @Test
    fun `a default-held message expires after the dialog deadline, an explicitly held one does not`() = run {
        user("""{"dialogExpiry":"60s"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "默认挂起")
        now.addAndGet(59_000)
        hub.pumpPeers()
        assertEquals("还没到期限", 1, hub.heldPeerMessages(web.sessionId).size)
        now.addAndGet(2_000)
        hub.pumpPeers()
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        assertTrue(inputText(peerInputs(api).single()).contains("超时"))

        // 设置里明写 hold：不过期（等有 accept 适用时才放）
        user("""{"crossSessionInbound":"hold","dialogExpiry":"60s"}""")
        hub.portOf(api).message("web", "明写 hold")
        now.addAndGet(10 * 60_000)
        hub.pumpPeers()
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)
        assertTrue(peerInputs(web).isEmpty())
    }

    @Test
    fun `dialogExpiry never keeps a default-held message until someone answers`() = run {
        user("""{"dialogExpiry":"never"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "一直等")
        now.addAndGet(24 * 3_600_000L)
        hub.pumpPeers()
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)
    }

    @Test
    fun `when the receiver's permission class changes, held messages are judged again, and released ones leave a note`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "等类别一致")
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)

        // 类别没变：心跳再判一次，仍然挂着
        hub.pumpPeers()
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)
        assertTrue(peerInputs(web).isEmpty())

        web.changeSettings(mode = PermissionMode.Default)
        hub.pumpPeers()
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        assertEquals(1, peerInputs(web).size)
        assertTrue(events(web).any { it is Note && it.text.contains("跨会话") })
        assertEquals("放行的消息仍然带着来历", 1, events(web).filterIsInstance<PeerMessage>().size)
    }

    @Test
    fun `a message held before an accept applies is delivered once it does`() = run {
        user("""{"crossSessionInbound":"hold"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        hub.portOf(api).message("web", "先挂着")
        assertEquals(1, hub.heldPeerMessages(web.sessionId).size)
        user("""{"crossSessionInbound":"accept"}""")
        hub.pumpPeers()
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        assertEquals(1, peerInputs(web).size)
    }

    @Test
    fun `a message held before a refuse applies is dropped and its sender is told, nothing reaches the inbox`() = run {
        user("""{"crossSessionInbound":"hold"}""")
        val hub = hub()
        val api = hub.newRoot("/a", "api")
        val web = hub.newRoot("/b", "web")
        hub.portOf(api).message("web", "先挂着")
        user("""{"crossSessionInbound":"refuse"}""")
        hub.pumpPeers()
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        assertTrue(peerInputs(web).isEmpty())
        assertTrue(events(web).none { it is PeerMessage })
        assertTrue(inputText(peerInputs(api).single()).contains("不接收"))
    }

    @Test
    fun `closing a session tells the senders of its held messages instead of dropping them silently`() = run {
        val hub = hub()
        val api = hub.newRoot("/a", "api", PermissionMode.Default)
        val web = hub.newRoot("/b", "web", PermissionMode.BypassPermissions)
        hub.portOf(api).message("web", "对方要关了")
        assertTrue(hub.close(web.sessionId))
        assertTrue(hub.heldPeerMessages(web.sessionId).isEmpty())
        assertTrue(inputText(peerInputs(api).single()).contains("已关闭"))
    }
}
