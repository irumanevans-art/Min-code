package dev.min.agent.kernel

import dev.min.agent.spi.*
import dev.min.agent.testkit.*
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class McpRootsTest {
    @Test fun `desktop root mapping returns native Windows file URIs`() = runBlocking {
        val mapped = rootsResult(listOf(GuestPath("/d/proj space")), { "D:\\proj space" })["roots"]!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content
        assertEquals("file:///D:/proj%20space", mapped)
    }
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `roots declare changes and encode current directory and extras`() = runBlocking {
        var dirs = listOf(GuestPath("/项目 space/#"), GuestPath("/extra"))
        val options = ClientConnector(ProcessPort.Unavailable, null, "1").clientOptions("s", roots = { dirs })
        assertEquals(true, options.capabilities["roots"]!!.jsonObject["listChanged"]!!.jsonPrimitive.boolean)
        val first = options.requestHandler("roots/list", null).jsonObject["roots"]!!.jsonArray
        assertEquals(listOf("file:///%E9%A1%B9%E7%9B%AE%20space/%23", "file:///extra"), first.map { it.jsonObject["uri"]!!.jsonPrimitive.content })
        dirs = listOf(GuestPath("/new"))
        assertEquals("file:///new", options.requestHandler("roots/list", null).jsonObject["roots"]!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test fun `shared project roots follow live directory events and detach`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = FakeFs().apply { put("/root/.claude.json", """{"mcpServers":{"s":{"command":"unused"}}}""") }
            val base = testDeps(ScriptedModel(), emptyList())
            val deps = KernelDeps(base.clock, base.ids, base.models, emptyList(), HostPorts(fs, base.host.search, base.host.exec, base.host.artifacts), base.platform, base.zone, memoryHome = GuestPath("/root"))
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            hub.mcp = McpManager(scope, McpConnector { _, _, _ -> error("测试只观察根目录集合") })
            val a = hub.create(GuestPath("/project"), TEST_MODEL, dev.min.agent.protocol.PermissionMode.Default)
            val b = hub.create(GuestPath("/project"), TEST_MODEL, dev.min.agent.protocol.PermissionMode.Default)
            val other = hub.create(GuestPath("/other"), TEST_MODEL, dev.min.agent.protocol.PermissionMode.Default)
            a.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/a"), GuestPath("/shared")))), Durability.Sync)
            b.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/b"), GuestPath("/shared")))), Durability.Sync)
            other.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/secret")))), Durability.Sync)
            assertEquals(listOf("/a", "/b", "/project", "/shared"), hub.mcp.rootsFor(GuestPath("/project")).map { it.value })
            hub.close(a.sessionId)
            assertEquals(listOf("/b", "/project", "/shared"), hub.mcp.rootsFor(GuestPath("/project")).map { it.value })
            val log = store.open(b.sessionId)!!
            assertEquals(fold(log.header, log.read().toList()), b.state.value)
            hub.close(b.sessionId)
            hub.close(other.sessionId)
            Unit
        } finally { scope.cancel() }
    }
}
