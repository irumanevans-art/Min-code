package dev.min.agent.kernel

import dev.min.agent.ext.mcp.*
import dev.min.agent.mcp.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class McpReviewManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val form = args("message" to "confirm", "requestedSchema" to args("type" to "object", "properties" to args()))
    private class Connection(val server: String) : McpConnection {
        override val state = MutableStateFlow<McpConnectionState>(McpConnectionState.Ready(dev.min.agent.mcp.McpServerInfo("2025-11-25", args(), args("prompts" to args(), "resources" to args()), null)))
        override val toolsChanged = emptyFlow<Unit>()
        override val instructions: String? = null
        override suspend fun listTools() = emptyList<McpToolDef>()
        override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?) = McpCallResult(emptyList(), null, false)
        override suspend fun listPrompts(): List<JsonObject> { if (server == "bad") error("failed"); return listOf(args("name" to "p")) }
        var read: suspend () -> Unit = {}
        override suspend fun readResource(uri: String): McpCallResult { read(); return McpCallResult(emptyList(), null, false) }
        override fun stderrTail() = emptyList<String>()
        override suspend fun close() { state.value = McpConnectionState.Closed("closed") }
    }
    private suspend fun case(names: List<String> = listOf("s"), late: Boolean = false, block: suspend (SessionHub, FileSessionStore, MutableMap<String, Connection>, MutableMap<String, suspend (JsonObject?) -> JsonObject>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs().apply { put("/root/.claude.json", args("mcpServers" to JsonObject(names.associateWith { args("command" to "fake") })).toString()) }
            val base = testDeps(ScriptedModel(reply(TextPart("ok")), reply(TextPart("ok"))), emptyList())
            val deps = KernelDeps(base.clock, base.ids, base.models, base.tools, HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts), base.platform, base.zone, memoryHome=GuestPath("/root"))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L }); val hub = SessionHub(store, deps, scope)
            val connections = java.util.concurrent.ConcurrentHashMap<String, Connection>()
            val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (JsonObject?) -> JsonObject>()
            val connector = object : McpConnector {
                override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope): McpConnection = error("interaction required")
                override suspend fun connectWithInteraction(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>, elicit: suspend (JsonObject?) -> JsonObject): McpConnection {
                    if (late) delay(200)
                    handlers[spec.name] = elicit
                    return Connection(spec.name).also { connections[spec.name] = it }
                }
            }
            hub.mcp = McpManager(scope, connector, startupWaitMs=if (late) 10 else 1_000)
            withTimeout(5_000) { block(hub, store, connections, handlers) }
            hub.closeMcpConnections()
        } finally { scope.cancel() }
    }
    @Test fun `prompt discovery isolates each server failure and identifies it`() = runBlocking {
        case(listOf("bad", "good")) { hub, store, _, _ ->
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            assertEquals(listOf("good"), rt.listMcpPrompts().map { it.server })
            assertTrue(store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<Note>().any { "bad" in it.text && "prompts/list" in it.text })
        }
    }
    @Test fun `single session requests overlap but shared connection requests serialize`() = runBlocking {
        case { hub, _, connections, _ -> coroutineScope {
            val a = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            a.listMcpPrompts()
            val entered = AtomicInteger(); val both = CompletableDeferred<Unit>()
            connections.getValue("s").read = { if (entered.incrementAndGet() == 2) both.complete(Unit); both.await() }
            val first = async { a.mcpCaller.readResource("s", "r://1", FakeArtifacts(), "1") }
            val second = async { a.mcpCaller.readResource("s", "r://2", FakeArtifacts(), "2") }
            withTimeout(500) { both.await() }; first.await(); second.await()
            val b = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            val active = AtomicInteger(); val maximum = AtomicInteger()
            connections.getValue("s").read = { val n = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, n) }; delay(50); active.decrementAndGet() }
            val one = async { a.mcpCaller.readResource("s", "r://1", FakeArtifacts(), "3") }
            val two = async { b.mcpCaller.readResource("s", "r://2", FakeArtifacts(), "4") }
            one.await(); two.await(); assertEquals(1, maximum.get())
        } }
    }
    @Test fun `idle elicitation chooses most recently active attached session`() = runBlocking {
        case { hub, _, _, handlers -> coroutineScope {
            val a = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            val b = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            b.send(ClientMsgId("active"), listOf(TextPart("hello"))); b.awaitIdle()
            val request = async { handlers.getValue("s")(form) }
            val pending = withTimeout(500) { hub.pending.first { it.isNotEmpty() }.single() }
            assertEquals(b.sessionId, pending.session); assertTrue(a.state.value.pendingMcpQuestions.isEmpty())
            b.declineQuestion((pending.what as HubPending.What.Question).questionId)
            assertEquals("decline", request.await()["action"]!!.jsonPrimitive.content)
        } }
    }
    @Test fun `delegated children count as attached sessions on shared MCP connections`() = runBlocking {
        case { hub, _, connections, _ -> coroutineScope {
            val parent = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            parent.listMcpPrompts()
            val result = hub.portFor(parent, TurnId("parent"), CallId("delegate")).delegate(DelegateRequest("child", "hello", background=false)) as DelegateResult.Finished
            assertEquals(listOf("s"), hub.mcp.statusOf(result.child).map { it.name })
            val active = AtomicInteger(); val maximum = AtomicInteger()
            connections.getValue("s").read = { val n=active.incrementAndGet(); maximum.updateAndGet { maxOf(it, n) }; delay(50); active.decrementAndGet() }
            val one = async { parent.mcpCaller.readResource("s", "r://1", FakeArtifacts(), "1") }
            val two = async { hub.get(result.child)!!.mcpCaller.readResource("s", "r://2", FakeArtifacts(), "2") }
            one.await(); two.await(); assertEquals(1, maximum.get())
        } }
    }
    @Test fun `late resource capability restages even when tool catalog stays empty`() = runBlocking {
        case(late=true) { hub, store, connections, _ ->
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("one"), listOf(TextPart("hello"))); rt.awaitIdle()
            assertTrue(rt.state.value.mcpTools!!.resourceServers.isEmpty())
            withTimeout(1_000) { while (connections.isEmpty()) delay(5) }
            delay(50)
            assertTrue(rt.state.value.mcpTools!!.resourceServers.isEmpty())
            rt.send(ClientMsgId("two"), listOf(TextPart("hello"))); rt.awaitIdle()
            assertEquals(listOf("s"), rt.state.value.mcpTools!!.resourceServers)
            val log=store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(log.header, log.read().toList()))
        }
    }
}
