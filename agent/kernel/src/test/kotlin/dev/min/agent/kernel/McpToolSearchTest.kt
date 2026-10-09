package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.*
import dev.min.agent.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class McpToolSearchTest {
    @Test fun `initial ToolSearch denial stays frozen when later rules are removed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(ScriptedModel(), emptyList())
            val deps = KernelDeps(base.clock, base.ids, base.models, base.tools, base.host, base.platform, base.zone, env={ "true" })
            val rt = SessionRuntime.create(FileSessionStore(tmp.newFolder().toPath(), { 0L }), LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope, initial=listOf(PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Deny, "ToolSearch", "user")))))
            rt.loadMcp(McpServersLoaded(emptyList()), snapshot)
            assertTrue(rt.state.value.mcpToolSearch!!.denied); assertFalse(rt.state.value.mcpToolSearch!!.enabled)
            rt.commit(listOf(PermissionRulesLoaded(emptyList())), Durability.Sync)
            rt.stageMcp(null, snapshot); rt.applyStagedMcp()
            assertTrue(rt.state.value.mcpToolSearch!!.denied); assertFalse(rt.state.value.mcpToolSearch!!.enabled)
        } finally { scope.cancel() }
    }
    @get:Rule val tmp = TemporaryFolder()
    private fun tool(name: String, always: Boolean = false, description: String = "database $name") = McpToolEntry("mcp__db__$name", "db", name, description, args("type" to "object"), alwaysLoad=always)
    private fun call(id: String, query: String) = ToolCallPart(CallId(id), "ToolSearch", args("query" to query))
    private val snapshot = McpToolsSnapshot(listOf(tool("bootstrap", true), tool("find"), tool("write")), mapOf("db" to "数据库说明"))

    @Test fun `mode honors exact threshold auto percent overrides and deny`() {
        val tokens = selectMcpSearchMode(snapshot, 1_000, "true", false).estimatedTokens
        assertTrue(selectMcpSearchMode(snapshot, tokens * 10, null, false).enabled)
        assertTrue(selectMcpSearchMode(snapshot, tokens * 10 - 1, "auto", false).enabled)
        assertTrue(selectMcpSearchMode(snapshot, tokens * 10, "auto:5", false).enabled)
        assertFalse(selectMcpSearchMode(snapshot, 1, "false", false).enabled)
        assertTrue(selectMcpSearchMode(snapshot, Int.MAX_VALUE, "true", false).enabled)
        assertTrue(selectMcpSearchMode(McpToolsSnapshot(emptyList(), emptyMap()), Int.MAX_VALUE, "true", false).enabled)
        assertFalse(selectMcpSearchMode(snapshot, 1, "true", true).enabled)
        assertEquals(10.0, selectMcpSearchMode(snapshot, 1_000, "auto:invalid", false).thresholdPercent, 0.0)
    }

    @Test fun `ranking is deterministic caps at five and supports precise selection`() {
        val tools = (1..9).map { tool("t$it") } + tool("find", description="none")
        assertEquals("mcp__db__find", searchMcpTools(tools, "find").first().name)
        assertEquals(5, searchMcpTools(tools, "database").size)
        assertEquals(listOf("mcp__db__t2", "mcp__db__t1"), searchMcpTools(tools, "select:mcp__db__t2,mcp__db__t1").map { it.name })
        assertTrue(searchMcpTools(tools, "unknown").isEmpty())
    }

    @Test fun `late catalog reevaluates frozen strategy only when staged snapshot applies`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val base = testDeps(ScriptedModel(), emptyList()); var env = "auto:0.01"
            val deps = KernelDeps(base.clock, base.ids, base.models, base.tools, base.host, base.platform, base.zone, env = { if (it == "ENABLE_TOOL_SEARCH") env else null })
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("late"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            rt.loadMcp(McpServersLoaded(emptyList()), McpToolsSnapshot(emptyList(), emptyMap()))
            assertFalse(rt.state.value.mcpToolSearch!!.enabled)
            env = "false"
            rt.stageMcp(null, snapshot)
            assertFalse(rt.state.value.mcpToolSearch!!.enabled)
            rt.applyStagedMcp()
            assertTrue(rt.state.value.mcpToolSearch!!.enabled)
            assertEquals("auto:0.01", rt.state.value.mcpToolSearch!!.strategy)
            rt.stageMcp(null, McpToolsSnapshot(emptyList())); rt.applyStagedMcp()
            assertTrue(rt.state.value.mcpToolSearch!!.enabled)
            val log = store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(log.header, log.read().toList()))
        } finally { scope.cancel() }
    }

    @Test fun `search appends on next request and logs preserve order after compact and reopen`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(call("q1", "select:mcp__db__write"), stop=StopReason.ToolUse), reply(call("q2", "select:mcp__db__find"), stop=StopReason.ToolUse), reply(TextPart("done")), reply(TextPart("摘要")), reply(TextPart("reopened")))
            val probe = FakeTool("Probe", READ_ONLY) { ToolResult(listOf(TextPart("ok"))) }
            val base = testDeps(model, listOf(probe)); var env = "true"
            val deps = KernelDeps(base.clock, base.ids, base.models, base.tools, base.host, base.platform, base.zone, env = { if (it == "ENABLE_TOOL_SEARCH") env else null })
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope)
            rt.loadMcp(McpServersLoaded(emptyList()), snapshot)
            rt.send(ClientMsgId("u"), listOf(TextPart("search"))); rt.awaitIdle()
            val requests = model.requests.toList()
            assertEquals(listOf("Probe", "mcp__db__bootstrap", "ToolSearch"), requests[0].tools.map { it.name })
            assertEquals(requests[0].tools, requests[1].tools.take(requests[0].tools.size))
            assertEquals(requests[1].tools, requests[2].tools.take(requests[1].tools.size))
            assertEquals(listOf("mcp__db__write", "mcp__db__find"), requests[2].tools.takeLast(2).map { it.name })
            assertTrue(requests[0].system.joinToString("\n").contains("mcp__db__write"))
            val log = store.peek(rt.sessionId)!!; val records = log.read().toList()
            assertEquals(listOf(listOf("mcp__db__write"), listOf("mcp__db__find")), records.map { it.event }.filterIsInstance<McpToolsLoaded>().map { it.names })
            records.filter { it.event is McpToolsLoaded }.forEach { loaded -> assertTrue(records.first { it.seq == loaded.seq-1 }.event is ToolOutcome) }
            assertEquals(rt.state.value, fold(log.header, records))
            assertTrue(rt.compact()); rt.awaitIdle()
            assertEquals(listOf("mcp__db__write", "mcp__db__find"), rt.state.value.loadedMcpTools)
            env = "false"
            val reopened = SessionRuntime.open(store, rt.sessionId, deps, scope)!!
            reopened.send(ClientMsgId("again"), listOf(TextPart("again"))); reopened.awaitIdle()
            assertEquals(requests[2].tools, model.requests.last().tools)
            assertEquals(reopened.state.value, fold(log.header, log.read().toList()))
        } finally { scope.cancel() }
    }

    @Test fun `late ToolSearch deny cannot rearrange the frozen enabled catalog`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val deps = testDeps(ScriptedModel(), emptyList())
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val rt = SessionRuntime.create(store, LogHeader(LOG_FORMAT_VERSION, SessionId("s"), 0), SessionCreated(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions), deps, scope,
                initial=listOf(snapshot, McpToolSearchConfigured(true, 1, 1, 10.0), PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Deny, "ToolSearch", "user")))))
            assertEquals(listOf("mcp__db__bootstrap", "ToolSearch"), availableTools(rt.state.value, deps).map { it.name })
            val log = store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(log.header, log.read().toList()))
        } finally { scope.cancel() }
    }

    @Test fun `spawned child inherits loaded tools mode and ordering`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(reply(TextPart("child")))
            val deps = testDeps(model, emptyList())
            val store = FileSessionStore(tmp.root.toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(GuestPath("/w"), TEST_MODEL, PermissionMode.BypassPermissions,
                initial=listOf(snapshot, McpToolSearchConfigured(true, 1, 1, 10.0), McpToolsLoaded(listOf("mcp__db__write", "mcp__db__find"))))
            val result = hub.portFor(rt, TurnId("test-parent"), CallId("delegate")).delegate(DelegateRequest("child", "hello", background=false))
            assertTrue(result is DelegateResult.Finished)
            val child = (result as DelegateResult.Finished).child
            val log = store.peek(child)!!; val state = fold(log.header, log.read().toList())
            assertEquals(rt.state.value.mcpToolSearch, state.mcpToolSearch)
            assertEquals(rt.state.value.loadedMcpTools, state.loadedMcpTools)
            assertEquals(availableTools(rt.state.value, deps).map { it.name }, model.requests.single().tools.map { it.name })
            assertEquals(state, hub.open(child)!!.state.value)
            val parentLog = store.peek(rt.sessionId)!!; assertEquals(rt.state.value, fold(parentLog.header, parentLog.read().toList()))
        } finally { scope.cancel() }
    }
}
