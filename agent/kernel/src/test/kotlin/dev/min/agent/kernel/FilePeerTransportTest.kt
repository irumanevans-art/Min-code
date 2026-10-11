package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentListingKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.PeerMessage
import dev.min.agent.store.FileSessionStore
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Path

/** 桌面多进程的登记表与收件目录：单个进程内的文件口径（不起两个进程，两个实例共用一个目录即可） */
class FilePeerTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val peers: Path by lazy { tmp.root.toPath().resolve("peers") }

    private fun entry(id: String, name: String?) = PeerEntry(session = id, name = name, cwd = "/w", busy = false)
    private fun env(id: String, from: String, to: String, text: String = "伪造") =
        PeerEnvelope(messageId = id, from = from, to = to, text = "你好", senderBypass = false, sentAtMs = 1L)

    @Test
    fun `a message written by one process is taken once by its owner and gone after the ack`() {
        val a = FilePeerTransport(peers, pid = 1111L, alive = { true })
        val b = FilePeerTransport(peers, pid = 2222L, alive = { true })
        a.publish(entry("s_a", "api"))
        b.publish(entry("s_b", "web"))
        assertTrue(a.deliver("s_b", env("m1", "s_a", "s_b")))
        val got = b.take("s_b")
        assertEquals(listOf("m1"), got.map { it.fileId })
        assertEquals("你好", got.single().envelope.text)
        b.ack("s_b", "m1")
        assertTrue(b.take("s_b").isEmpty())
    }

    @Test
    fun `a registry entry of a dead process is dropped together with its inbox`() {
        val dead = FilePeerTransport(peers, pid = 2222L, alive = { true })
        dead.publish(entry("s_d", "gone"))
        val a = FilePeerTransport(peers, pid = 1111L, alive = { it == 1111L })
        a.publish(entry("s_a", "api"))
        assertEquals(listOf("s_a"), a.entries().map { it.session })
        assertFalse(Files.exists(peers.resolve("sessions").resolve("s_d.json")))
        assertFalse(Files.exists(peers.resolve("inbox").resolve("s_d")))
    }

    @Test
    fun `delivering to a session that is not registered writes nothing`() {
        val a = FilePeerTransport(peers, pid = 1111L, alive = { true })
        a.publish(entry("s_a", "api"))
        assertFalse(a.deliver("s_none", env("m1", "s_a", "s_none")))
        assertFalse(Files.exists(peers.resolve("inbox").resolve("s_none")))
    }

    @Test
    fun `a session id that could leave the directory is refused and nothing is written outside it`() {
        val a = FilePeerTransport(peers, pid = 1111L, alive = { true })
        assertThrows(IllegalArgumentException::class.java) { a.publish(entry("../escape", "x")) }
        assertThrows(IllegalArgumentException::class.java) { a.deliver("..\\escape", env("m1", "s_a", "..")) }
        assertFalse(Files.exists(tmp.root.toPath().resolve("escape.json")))
        assertFalse(Files.exists(tmp.root.toPath().resolve("escape")))
    }

    @Test
    fun `the registry and the inboxes are readable and writable by the owner only`() {
        val a = FilePeerTransport(peers, pid = 1111L, alive = { true })
        a.publish(entry("s_a", "api"))
        if (Files.getFileAttributeView(peers, PosixFileAttributeView::class.java) != null) {
            // 目录 0700、文件 0600：组与其他人一个位都没有
            val dirPerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            val filePerms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            for (d in listOf(peers, peers.resolve("inbox"), peers.resolve("inbox").resolve("s_a"))) {
                assertEquals(d.toString(), dirPerms, Files.getPosixFilePermissions(d))
            }
            assertEquals(filePerms, Files.getPosixFilePermissions(peers.resolve("sessions").resolve("s_a.json")))
            return
        }
        // Windows：ACL 里不能有任何人人可读的主体
        val acl = Files.getFileAttributeView(peers, AclFileAttributeView::class.java)
        assumeTrue("需要 ACL 视图", acl != null)
        for (p in listOf(peers, peers.resolve("inbox").resolve("s_a"), peers.resolve("sessions").resolve("s_a.json"))) {
            val names = Files.getFileAttributeView(p, AclFileAttributeView::class.java).acl.map { it.principal().name }
            assertFalse("$p 的 ACL 含 $names", names.any { it.contains("Everyone") || it.contains("Users") || it.contains("Authenticated") })
        }
    }

    @Test
    fun `a half-written file in an inbox is never taken`() {
        val a = FilePeerTransport(peers, pid = 1111L, alive = { true })
        a.publish(entry("s_a", "api"))
        Files.writeString(peers.resolve("inbox").resolve("s_a").resolve("half.json.tmp"), "{\"messageId\":")
        assertTrue(a.take("s_a").isEmpty())
    }
}

/** 两个内核实例（两个进程的替身）共用一个用户目录：登记表与收件目录走真的文件 */
class PeerMailRemoteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private val silent = object : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { awaitCancellation() }
    }

    private val peers by lazy { tmp.root.toPath().resolve("user").resolve("peers") }

    private fun instance(name: String, pid: Long, limits: HubLimits = HubLimits(), alive: (Long) -> Boolean = { true }): Pair<SessionHub, FileSessionStore> {
        val store = FileSessionStore(tmp.root.toPath().resolve(name), { 0L })
        val hub = SessionHub(store, testDeps(silent, emptyList(), idTag = "i${pid}-"), newScope(), limits = limits, peers = FilePeerTransport(peers, pid, alive))
        return hub to store
    }

    private suspend fun SessionHub.newRoot(cwd: String, name: String, mode: PermissionMode = PermissionMode.Default) =
        create(GuestPath(cwd), TEST_MODEL, mode, name = name)

    private suspend fun peerInputs(hub: SessionHub, store: FileSessionStore, rt: SessionRuntime) =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<InputReceived>().filter { it.origin is InputOrigin.Peer }

    private fun SessionHub.portOf(rt: SessionRuntime) = portFor(rt, TurnId("t1"), CallId("c1"))

    @Test
    fun `two instances on one user directory list each other and deliver across the registry`() = run {
        val (a, sa) = instance("a", 1L)
        val (b, sb) = instance("b", 2L)
        val api = a.newRoot("/a", "api")
        val web = b.newRoot("/b", "web")

        val rows = a.portOf(api).roster().filter { it.kind == AgentListingKind.Peer }
        assertEquals(listOf("web"), rows.map { it.name })

        val r = a.portOf(api).message("web", "跨进程")
        assertFalse(r.isError)
        b.pumpPeers()
        val got = peerInputs(b, sb, web)
        assertEquals(1, got.size)
        // 收件方的名字从发件人的登记里取，不信信封里的话
        assertEquals(InputOrigin.Peer(api.sessionId, "api"), got.single().origin)
        assertEquals("跨进程", ((got.single().parts.single()) as TextPart).text.substringAfter("\n"))
        assertTrue(peerInputs(a, sa, api).isEmpty())
    }

    @Test
    fun `a receiver that refuses answers the remote sender with the outcome and writes nothing`() = run {
        val (a, sa) = instance("a", 1L)
        val (b, sb) = instance("b", 2L, HubLimits(crossSessionInbound = PeerInboundPolicy.Refuse))
        val api = a.newRoot("/a", "api")
        val web = b.newRoot("/b", "web")
        assertFalse(a.portOf(api).message("web", "不要").isError)
        b.pumpPeers()
        assertTrue(peerInputs(b, sb, web).isEmpty())
        a.pumpPeers()
        val notice = peerInputs(a, sa, api).single()
        assertEquals(InputOrigin.Peer(web.sessionId, "web"), notice.origin)
        assertTrue((notice.parts.single() as TextPart).text.contains("不接收"))
    }

    @Test
    fun `an envelope from a session that is not registered is dropped, not delivered`() = run {
        val (b, sb) = instance("b", 2L)
        val web = b.newRoot("/b", "web")
        val forger = FilePeerTransport(peers, pid = 3L, alive = { true })
        assertTrue(forger.deliver(web.sessionId.value, env("g1", from = "s_ghost", to = web.sessionId.value)))
        b.pumpPeers()
        assertTrue(peerInputs(b, sb, web).isEmpty())
        assertTrue(forger.take(web.sessionId.value).isEmpty())
    }

    @Test
    fun `the same envelope handled twice is one input`() = run {
        val (a, _) = instance("a", 1L)
        val (b, sb) = instance("b", 2L)
        val api = a.newRoot("/a", "api")
        val web = b.newRoot("/b", "web")
        a.portOf(api).message("web", "只一份")
        b.pumpPeers()
        val mid = sb.peek(web.sessionId)!!.read().toList().map { it.event }.filterIsInstance<PeerMessage>().single().messageId
        // 模拟「投递完、还没确认删除」时宕机：同一封信（同一个 messageId）又进了收件目录
        val again = FilePeerTransport(peers, pid = 3L, alive = { true })
        assertTrue(again.deliver(web.sessionId.value, env(mid, from = api.sessionId.value, to = web.sessionId.value, text = "只一份")))
        b.pumpPeers()
        b.pumpPeers()
        assertEquals(1, peerInputs(b, sb, web).size)
    }

    @Test
    fun `a closed session is withdrawn from the registry`() = run {
        val (a, _) = instance("a", 1L)
        val (b, _) = instance("b", 2L)
        val api = a.newRoot("/a", "api")
        val web = b.newRoot("/b", "web")
        assertEquals(1, a.portOf(api).roster().count { it.kind == AgentListingKind.Peer })
        assertTrue(b.close(web.sessionId))
        assertEquals(0, a.portOf(api).roster().count { it.kind == AgentListingKind.Peer })
    }

    private fun env(id: String, from: String, to: String, text: String = "伪造") =
        PeerEnvelope(messageId = id, from = from, to = to, text = text, senderBypass = false, sentAtMs = 1L)
}
