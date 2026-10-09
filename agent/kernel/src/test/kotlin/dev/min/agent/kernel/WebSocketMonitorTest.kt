package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.DirEntry
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.FileStat
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.MonitorRequest
import dev.min.agent.spi.MonitorStart
import dev.min.agent.spi.MonitorWebSocket
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WebSocketConnection
import dev.min.agent.spi.WebSocketEvent
import dev.min.agent.spi.WebSocketPort
import dev.min.agent.spi.WebSocketRefused
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Monitor 的 WebSocket 源：事件映射、输出文件、结束方式、网络设置的拒绝、不泄露地址里的查询串。连接是脚本化的假端口 */
class WebSocketMonitorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private class MemFs : WorkspaceFs {
        val files = ConcurrentHashMap<String, ByteArray>()
        override suspend fun stat(path: GuestPath): FileStat? = files[path.value]?.let { FileStat(FileKind.File, it.size.toLong(), 0) }
        override suspend fun read(path: GuestPath, offset: Long, limit: Int): ByteArray {
            val b = files.getValue(path.value)
            val from = offset.toInt().coerceAtMost(b.size)
            return b.copyOfRange(from, minOf(b.size, from + limit))
        }
        override suspend fun writeAtomic(path: GuestPath, bytes: ByteArray) { files[path.value] = bytes }
        override suspend fun list(dir: GuestPath): List<DirEntry> = emptyList()
        override fun scopeOf(path: GuestPath) = PathScope.Rootfs
        fun text(path: String) = files[path]?.decodeToString()
    }

    private class FakeConnection : WebSocketConnection {
        val events = Channel<WebSocketEvent>(Channel.UNLIMITED)
        val closed = AtomicBoolean(false)
        override suspend fun next(): WebSocketEvent = events.receive()
        override fun close() { closed.set(true) }
        fun push(e: WebSocketEvent) { events.trySend(e) }
    }

    private class FakePort : WebSocketPort {
        val opened = CopyOnWriteArrayList<Pair<String, List<String>>>()
        val connection = FakeConnection()
        @Volatile var refuse: String? = null
        @Volatile var crash = false
        override suspend fun open(url: String, protocols: List<String>): WebSocketConnection {
            opened += url to protocols
            refuse?.let { throw WebSocketRefused(it) }
            if (crash) error("boom $url")
            return connection
        }
    }

    private class OkModel : ModelClient {
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow { emitAll(reply(TextPart("ok"))(request)) }
    }

    private class Rig(val hub: SessionHub, val fs: MemFs, val port: FakePort)

    private fun rig(settings: String? = null, managed: String? = null, unavailable: Boolean = false): Rig {
        val fs = MemFs()
        settings?.let { fs.files["/root/.claude/settings.json"] = it.toByteArray() }
        managed?.let { fs.files["/etc/claude-code/managed-settings.json"] = it.toByteArray() }
        val port = FakePort()
        val base = testDeps(OkModel(), emptyList())
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = { p -> "${p}_${ids.incrementAndGet()}" },
            models = base.models, tools = emptyList(),
            host = HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts, webSockets = if (unavailable) WebSocketPort.Unavailable else port),
            platform = "test", zone = ZoneOffset.UTC, retry = base.retry,
            memoryHome = if (settings != null || managed != null) GuestPath("/root") else null,
            env = { name -> if (name == "CLAUDE_CODE_DISABLE_AUTO_MEMORY") "1" else null },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return Rig(SessionHub(store, deps, scope), fs, port)
    }

    private suspend fun Rig.session(): SessionRuntime {
        val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.backgroundTimings.pollMs = 15; rt.backgroundTimings.flushIntervalMs = 40; rt.backgroundTimings.flushTickMs = 15
        return rt
    }

    private suspend fun eventually(what: String, timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return; delay(10) }
        throw AssertionError("等不到：$what")
    }

    private fun SessionRuntime.notices(): List<String> =
        (state.value.history.filterIsInstance<HistoryEntry.User>().filter { it.origin is InputOrigin.Task }.map { it.parts } +
            state.value.inbox.filter { it.origin is InputOrigin.Task }.map { it.parts })
            .map { parts -> parts.filterIsInstance<TextPart>().joinToString("") { it.text } }

    private suspend fun SessionRuntime.watch(url: String = "wss://feed.example.com/stream", timeoutMs: Long? = null, protocols: List<String> = emptyList()): MonitorStart =
        background.startMonitor(MonitorRequest("盯行情", timeoutMs, ws = MonitorWebSocket(url, protocols)))

    // ---- 事件 ----

    @Test
    fun `每条文本消息一个事件，多行消息也只算一个，二进制换成占位行`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch(protocols = listOf("v1")) as MonitorStart.Started
        assertEquals(listOf("wss://feed.example.com/stream" to listOf("v1")), r.port.opened.toList())
        r.port.connection.push(WebSocketEvent.Text("tick 1"))
        r.port.connection.push(WebSocketEvent.Text("multi\nline\r\nmessage"))
        r.port.connection.push(WebSocketEvent.Binary(512))
        eventually("三个事件") { rt.background.find(started.taskId)!!.events == 3 }
        eventually("通知里是三行，换行转义成反斜杠 n") {
            rt.notices().any { "tick 1" in it && "multi\\nline\\nmessage" in it && "[binary frame, 512 bytes]" in it }
        }
        val notice = rt.notices().first()
        assertTrue("多行消息没有被拆成几个事件", !notice.contains("\nline\n"))
        assertTrue(notice.contains("task-id=\"${started.taskId}\""))
    }

    @Test
    fun `输出文件是内核写的事件行，模型能 Read`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch() as MonitorStart.Started
        assertEquals("/root/.min/monitors/${started.taskId}.log", started.outputPath!!.value)
        assertEquals("一启动文件就在", "", r.fs.text(started.outputPath!!.value))
        r.port.connection.push(WebSocketEvent.Text("alpha"))
        r.port.connection.push(WebSocketEvent.Text("a\nb"))
        eventually("文件里有事件行") { r.fs.text(started.outputPath!!.value) == "alpha\na\\nb\n" }
        val out = rt.background.taskOutput(started.taskId, false, 0).content.filterIsInstance<TextPart>().first().text
        assertTrue(out.contains("alpha") && out.contains("运行中"))
    }

    @Test
    fun `一行最多给模型 2000 字符，超出的说明见输出文件`() = run {
        val r = rig()
        val rt = r.session()
        rt.watch()
        r.port.connection.push(WebSocketEvent.Text("x".repeat(5_000)))
        eventually("通知") { rt.notices().isNotEmpty() }
        val n = rt.notices().first()
        assertTrue(n.contains("还有 3000 个字符") && !n.contains("x".repeat(2_001)))
    }

    // ---- 结束 ----

    @Test
    fun `对端关闭：把关闭码告诉模型，状态是已结束，连接关了`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch() as MonitorStart.Started
        r.port.connection.push(WebSocketEvent.Text("last"))
        r.port.connection.push(WebSocketEvent.Closed(1001, "going away"))
        eventually("结束") { rt.background.find(started.taskId)!!.status == BackgroundJobStatus.Exited }
        eventually("结局通知") { rt.notices().any { "关闭码 1001" in it && "going away" in it } }
        assertTrue(r.port.connection.closed.get())
        assertTrue("结局通知带着没发出去的最后一条", rt.notices().any { "last" in it })
        assertEquals(1, rt.notices().count { "关闭码" in it })
    }

    @Test
    fun `消息超过 1 MiB：结束 watch，告诉模型原因与建议`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch() as MonitorStart.Started
        r.port.connection.push(WebSocketEvent.TooLarge(2_000_000))
        eventually("失败收尾") { rt.background.find(started.taskId)!!.status == BackgroundJobStatus.Failed }
        eventually("通知") { rt.notices().any { "2000000" in it && "1 MiB" in it && "过滤" in it } }
    }

    @Test
    fun `连接出错（读失败、协议错误）：结束并说明`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch() as MonitorStart.Started
        r.port.connection.push(WebSocketEvent.Failed("协议错误：使用了保留位（没协商扩展）。"))
        eventually("失败收尾") { rt.background.find(started.taskId)!!.status == BackgroundJobStatus.Failed }
        eventually("通知") { rt.notices().any { "保留位" in it } }
    }

    @Test
    fun `到期：连接被关、状态是到期、模型只收到一条通知`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch(timeoutMs = 250) as MonitorStart.Started
        eventually("到期") { rt.background.find(started.taskId)!!.status == BackgroundJobStatus.TimedOut }
        assertTrue(r.port.connection.closed.get())
        eventually("通知") { rt.notices().any { "到了期限" in it } }
        delay(150)
        assertEquals(1, rt.notices().count { "到了期限" in it })
    }

    @Test
    fun `TaskStop 关连接，不再通知`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch() as MonitorStart.Started
        val result = r.hub.portFor(rt, TurnId("t"), CallId("c")).stop(started.taskId)
        assertFalse(result.isError)
        assertTrue(r.port.connection.closed.get())
        assertEquals(BackgroundJobStatus.Stopped, rt.background.find(started.taskId)!!.status)
        delay(150)
        assertTrue(rt.notices().isEmpty())
    }

    @Test
    fun `关会话时 WebSocket 连接也被关掉`() = run {
        val r = rig()
        val rt = r.session()
        rt.watch()
        assertTrue(r.hub.close(rt.sessionId))
        assertTrue(r.port.connection.closed.get())
    }

    // ---- 打不开 ----

    @Test
    fun `宿主拒绝（私网地址、握手失败）：说明原样给模型，不留任务`() = run {
        val r = rig()
        val rt = r.session()
        r.port.refuse = "地址 10.0.0.5 属于私网范围，不能连接。"
        val start = rt.watch()
        assertEquals(MonitorStart.Rejected("地址 10.0.0.5 属于私网范围，不能连接。"), start)
        assertTrue(rt.background.infos.value.isEmpty())
    }

    @Test
    fun `端口抛了意外：只给异常类名，地址里的 token 不外泄`() = run {
        val r = rig()
        val rt = r.session()
        r.port.crash = true
        val start = rt.watch("wss://feed.example.com/s?token=SECRET123") as MonitorStart.Rejected
        assertFalse(start.messageForModel.contains("SECRET123"))
        assertTrue(start.messageForModel.contains("IllegalStateException"))
    }

    @Test
    fun `没有 WebSocket 端口的宿主：说清楚`() = run {
        val r = rig(unavailable = true)
        val rt = r.session()
        val start = rt.watch() as MonitorStart.Rejected
        assertTrue(start.messageForModel.contains("不能打开 WebSocket"))
    }

    @Test
    fun `deniedDomains 里的主机不连，端口根本没被问过`() = run {
        val r = rig(settings = """{"sandbox":{"network":{"deniedDomains":["*.blocked.example"]}}}""")
        val rt = r.session()
        val start = rt.watch("wss://feed.blocked.example/x") as MonitorStart.Rejected
        assertTrue(start.messageForModel.contains("deniedDomains"))
        assertTrue(r.port.opened.isEmpty())
        // 别的主机不受影响
        assertTrue(rt.watch("wss://feed.fine.example/x") is MonitorStart.Started)
    }

    @Test
    fun `WebFetch 的 deny 规则同样挡`() = run {
        val r = rig(settings = """{"permissions":{"deny":["WebFetch(domain:feed.blocked.example)"]}}""")
        val rt = r.session()
        assertTrue(rt.watch("wss://feed.blocked.example/x") is MonitorStart.Rejected)
        assertTrue(r.port.opened.isEmpty())
    }

    @Test
    fun `托管设置只允许名单内的域名时，名单外的主机不连、名单内的连`() = run {
        val r = rig(managed = """{"sandbox":{"network":{"allowManagedDomainsOnly":true,"allowedDomains":["ok.example.com"]}}}""")
        val rt = r.session()
        val denied = rt.watch("wss://other.example.com/x") as MonitorStart.Rejected
        assertTrue(denied.messageForModel.contains("托管设置"))
        assertTrue(r.port.opened.isEmpty())
        assertTrue(rt.watch("wss://ok.example.com/x") is MonitorStart.Started)
    }

    // ---- 不泄露地址里的查询串 ----

    @Test
    fun `展示用的地址只留到路径：任务表里没有查询串`() = run {
        val r = rig()
        val rt = r.session()
        val started = rt.watch("wss://user.example.com:8443/feed/v1?token=SECRET123&x=1") as MonitorStart.Started
        val info = rt.background.find(started.taskId)!!
        assertEquals("wss://user.example.com:8443/feed/v1", info.wsUrl)
        assertEquals("连接用的是完整地址（含 token）", "wss://user.example.com:8443/feed/v1?token=SECRET123&x=1", r.port.opened.single().first)
        r.port.connection.push(WebSocketEvent.Closed(1000, ""))
        eventually("结局通知") { rt.notices().isNotEmpty() }
        assertTrue("通知里也没有 token", rt.notices().none { "SECRET123" in it })
    }
}
