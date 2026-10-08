package dev.min.agent.kernel

import dev.min.agent.ext.mcp.McpServerSpec
import dev.min.agent.mcp.*
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.*
import dev.min.agent.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class CwdWorktreeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val old = GuestPath("/old")
    private val next = GuestPath("/next")
    private fun fs() = FakeFs().apply {
        put("/old/keep", "old"); put("/next/keep", "next"); put("/extra/keep", "extra"); put("/storage/keep", "device")
    }
    private fun deps(fs: FakeFs, model: ScriptedModel, tools: List<Tool<*>> = emptyList()): KernelDeps {
        val base = testDeps(model, tools)
        val mapped = object : WorkspaceFs by fs {
            override fun scopeOf(path: GuestPath) = when {
                path.value.startsWith("/storage") -> PathScope.Device
                path.value.startsWith("/old") -> PathScope.Workspace
                else -> PathScope.Rootfs
            }
        }
        return KernelDeps(base.clock, base.ids, base.models, tools, HostPorts(mapped, base.host.search, base.host.exec, base.host.artifacts), base.platform, base.zone)
    }
    private suspend fun truth(store: FileSessionStore, rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    @Test fun `cwd grants new workspace removes old implicit access and preserves explicit extras`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = fs(); val model = ScriptedModel(
                reply(ToolCallPart(CallId("new"), "Write", args("file_path" to "/next/new", "content" to "new")), stop = StopReason.ToolUse), reply(TextPart("done")),
                reply(ToolCallPart(CallId("extra"), "Write", args("file_path" to "/extra/new", "content" to "extra")), stop = StopReason.ToolUse), reply(TextPart("done")),
                reply(ToolCallPart(CallId("old"), "Write", args("file_path" to "/old/new", "content" to "old")), stop = StopReason.ToolUse), reply(TextPart("done")),
            )
            val store = FileSessionStore(tmp.root.toPath(), { 0L }); val hub = SessionHub(store, deps(fs, model, listOf(WriteTool)), scope)
            val rt = hub.create(old, TEST_MODEL, PermissionMode.AcceptEdits)
            rt.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/extra")))), Durability.Sync)
            rt.changeSettings(cwd = next)
            rt.send(ClientMsgId("new"), listOf(TextPart("new"))); assertTrue("目录权限决定应在批准之外完成", withTimeoutOrNull(5_000) { rt.awaitIdle(); true } == true)
            assertEquals("new", fs.read(GuestPath("/next/new")).decodeToString())
            assertTrue(rt.state.value.presentation.none { it.record.event is ApprovalRequested })
            rt.send(ClientMsgId("extra"), listOf(TextPart("extra"))); assertTrue("目录权限决定应在批准之外完成", withTimeoutOrNull(5_000) { rt.awaitIdle(); true } == true)
            assertEquals("extra", fs.read(GuestPath("/extra/new")).decodeToString())
            rt.send(ClientMsgId("old"), listOf(TextPart("old")))
            val observed = withTimeoutOrNull(5_000) { rt.state.first { it.turn?.calls?.values?.any { c -> c.phase is CallPhase.AwaitingApproval } == true } }
            assertNotNull("应公布审批，不能越过目录底线", observed)
            val pending = observed!!
            val approval = pending.turn!!.calls.values.mapNotNull { (it.phase as? CallPhase.AwaitingApproval)?.approvalId }.single()
            rt.answerApproval(approval, ApprovalChoice.Deny()); assertTrue("目录权限决定应在批准之外完成", withTimeoutOrNull(5_000) { rt.awaitIdle(); true } == true)
            assertNull(fs.stat(GuestPath("/old/new"))); assertEquals(listOf(GuestPath("/extra")), rt.state.value.additionalDirectories)
            assertEquals(old, rt.state.value.created.cwd); assertEquals(next, rt.state.value.settings.cwd)
            assertTrue(model.requests.last().messages.toString().contains("Working directory changed")); truth(store, rt)
        } finally { scope.cancel() }
    }

    @Test fun `Device scope remains approval floor after cwd change even bypass`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fs = fs(); val model = ScriptedModel(reply(ToolCallPart(CallId("device"), "Write", args("file_path" to "/storage/new", "content" to "device")), stop = StopReason.ToolUse), reply(TextPart("done")))
            val store = FileSessionStore(tmp.root.toPath(), { 0L }); val hub = SessionHub(store, deps(fs, model, listOf(WriteTool)), scope)
            val rt = hub.create(old, TEST_MODEL, PermissionMode.BypassPermissions); rt.changeSettings(cwd = GuestPath("/storage"))
            rt.send(ClientMsgId("device"), listOf(TextPart("device")))
            val observed = withTimeoutOrNull(5_000) { rt.state.first { it.turn?.calls?.values?.any { c -> c.phase is CallPhase.AwaitingApproval } == true } }
            assertNotNull("应公布审批，不能越过目录底线", observed)
            val pending = observed!!
            val approval = pending.turn!!.calls.values.mapNotNull { (it.phase as? CallPhase.AwaitingApproval)?.approvalId }.single()
            rt.answerApproval(approval, ApprovalChoice.Deny()); assertTrue("目录权限决定应在批准之外完成", withTimeoutOrNull(5_000) { rt.awaitIdle(); true } == true)
            assertTrue(rt.state.value.toolResults[CallId("device")]!!.isError); assertNull(fs.stat(GuestPath("/storage/new"))); truth(store, rt)
        } finally { scope.cancel() }
    }

    @Test fun `MCP roots list and notification are published before cwd change returns and reopen agrees`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val managerScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        try {
            val fs = fs().apply { put("/root/.claude.json", """{"mcpServers":{"s":{"command":"unused"}}}""") }
            val base = deps(fs, ScriptedModel()); val d = KernelDeps(base.clock, base.ids, base.models, emptyList(), base.host, base.platform, base.zone, memoryHome = GuestPath("/root"))
            val store = FileSessionStore(tmp.root.toPath(), { 0L }); val hub = SessionHub(store, d, scope)
            val notifications = AtomicInteger(); var roots: () -> List<GuestPath> = { emptyList() }
            val connection = object : McpConnection {
                override val state = MutableStateFlow<McpConnectionState>(McpConnectionState.Ready(dev.min.agent.mcp.McpServerInfo("2025-11-25", JsonObject(emptyMap()), JsonObject(emptyMap()), null)))
                override val toolsChanged = emptyFlow<Unit>(); override val instructions: String? = null
                override suspend fun listTools() = emptyList<McpToolDef>()
                override suspend fun call(tool: String, arguments: JsonObject, onProgress: (McpProgress) -> Unit, idleTimeoutMs: Long, hardTimeoutMs: Long?) = error("unused")
                override fun stderrTail() = emptyList<String>(); override suspend fun close() {}
                override suspend fun rootsChanged() { notifications.incrementAndGet() }
            }
            hub.mcp = McpManager(managerScope, object : McpConnector {
                override suspend fun connect(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope) = connection
                override suspend fun connectWithRoots(spec: McpServerSpec, projectRoot: GuestPath, scope: CoroutineScope, roots: () -> List<GuestPath>): McpConnection {
                    // 记录连接实际收到的 roots supplier，而非只断言管理器的辅助函数。
                    capturedRoots = roots
                    return connection
                }
            })
            val rt = hub.create(old, TEST_MODEL, PermissionMode.Default); scheduler.runCurrent(); rt.applyStagedMcp()
            roots = capturedRoots!!
            rt.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/extra")))), Durability.Sync)
            val before = notifications.get(); rt.changeSettings(cwd = next)
            assertEquals(listOf("/extra", "/next"), roots().map { it.value })
            assertTrue(notifications.get() > before)
            assertEquals(listOf("file:///extra", "file:///next"), rootsResult(roots())["roots"]!!.jsonArray.map { it.jsonObject["uri"]!!.jsonPrimitive.content })
            truth(store, rt); hub.close(rt.sessionId); val reopened = hub.open(rt.sessionId)!!; scheduler.runCurrent()
            assertEquals(next, reopened.state.value.settings.cwd); assertEquals(listOf("/extra", "/next"), hub.mcp.rootsFor(old).map { it.value }); truth(store, reopened)
        } finally { scope.cancel(); managerScope.cancel() }
    }
    private var capturedRoots: (() -> List<GuestPath>)? = null

    @Test fun `isolation rejects main edits git redirects dynamic command and missing cwd`() = runBlocking {
        val fs = fs().apply { put("/old/.claude/worktrees/w/keep", "worktree") }
        val d = deps(fs, ScriptedModel()); val w = WorktreeBinding("w", GuestPath("/old/.claude/worktrees/w"), old, old, pinned = true)
        val h = LogHeader(1, SessionId("s"), 0); val s = fold(h, listOf(EventRecord(1, 0, SessionCreated(w.path, TEST_MODEL, PermissionMode.BypassPermissions)), EventRecord(2, 0, CwdChanged(w.path, w.path, w))))
        for (tool in listOf("Write", "Edit", "NotebookEdit")) assertNotNull(worktreeViolation(s, d, PermissionAsk(tool, "main edit", paths = listOf(PathAccess(GuestPath("/old/file"), AccessKind.Write, PathScope.Workspace)))))
        for (script in listOf("git -C /old status", "command -- git -C /old status", "git -c core.worktree=/old status", "git --config-env=core.worktree=WT status", "cd /old", "printf x > /old/file", "cd /old && git status", "GIT_DIR=/old/.git git status", "git --git-dir=/old/.git status", "git --work-tree=/old status", "${'$'}CMD status", "echo ${'$'}(git -C /old status)"))
            assertNotNull(script, worktreeViolation(s, d, PermissionAsk("Bash", "redirect", commands = listOf(CommandAccess(script)))))
        assertNull(worktreeViolation(s, d, PermissionAsk("Bash", "safe", commands = listOf(CommandAccess("git status")))))
        for (script in listOf("cd -- /old", "cd -P -- /old && git status", "cd ${'$'}MAIN", "cd -"))
            assertNotNull(script, worktreeViolation(s, d, PermissionAsk("Bash", "cd target", commands = listOf(CommandAccess(script)))))
        assertNotNull(worktreeViolation(s.copy(settings = s.settings.copy(cwd = GuestPath("/missing"))), d, PermissionAsk("Bash", "missing")))
    }
}
