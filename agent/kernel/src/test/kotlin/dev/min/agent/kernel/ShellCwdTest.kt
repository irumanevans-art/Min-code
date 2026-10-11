package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import dev.min.agent.tools.BashTool
import dev.min.agent.tools.WriteTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bash 的 `cd` 跨命令保留（CC tools-reference「What persists between commands」）：
 * 会话目录（权限范围）不动，另记「shell 当前目录」只管后续 Bash 从哪开始。
 * 宿主（Exec）把「命令结束时停在哪」放在 [ExecResult.Exited.endCwd] 里，这里用脚本化的 Exec 逐条喂，
 * 真 shell 的行为（exit、截断、Windows 路径）由 CLI 模块的真子进程用例覆盖。
 */
class ShellCwdTest {
    @get:Rule val tmp = TemporaryFolder()

    private val project = GuestPath("/old")

    private fun fs() = FakeFs().apply {
        mkdir("/old/sub/deep"); mkdir("/old/other"); mkdir("/extra/x"); mkdir("/etc"); mkdir("/outside/inner")
        put("/old/keep", "k"); put("/old/other/keep", "k")
    }

    /** 一条 Bash 调用 = 一个模型步骤；全部调完再回一句话收尾 */
    private fun bashScript(vararg ids: String) = ScriptedModel(
        *ids.map { reply(ToolCallPart(CallId(it), "Bash", args("command" to "cmd-$it")), stop = StopReason.ToolUse) }.toTypedArray(),
        reply(TextPart("done")),
    )

    private class Rig(val hub: SessionHub, val rt: SessionRuntime, val store: FileSessionStore, val exec: FakeExec, val fs: FakeFs, val scope: CoroutineScope) {
        suspend fun say(text: String) {
            val before = rt.state.value.lastTurn?.turnId
            rt.send(ClientMsgId("m${System.nanoTime()}"), listOf(TextPart(text)))
            withTimeout(10_000) { rt.state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before } }
        }
        fun result(id: String) = rt.state.value.toolResults.getValue(CallId(id))
        fun text(id: String) = result(id).displayText()
        /** 每次 Bash 调用时 shell 实际从哪开始 */
        val starts get() = exec.runs.map { it.first.cwd.value }
        suspend fun truth(): SessionRuntime {
            val log = store.open(rt.sessionId)!!
            assertEquals("日志即真相", fold(log.header, log.read().toList()), rt.state.value)
            return rt
        }
    }

    private fun rig(
        model: ScriptedModel,
        mode: PermissionMode = PermissionMode.BypassPermissions,
        env: (String) -> String? = { null },
        canonical: (GuestPath) -> GuestPath = { it },
        tools: List<Tool<*>> = listOf(BashTool(), WriteTool),
        fs: FakeFs = fs(),
        extraEvents: List<SessionEvent> = emptyList(),
        http: HttpHookClient? = null,
        script: (ShellCommand, ExecOptions) -> ExecResult,
        block: suspend Rig.() -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val exec = FakeExec(script)
            val base = testDeps(model, tools)
            val deps = base.copy(
                host = HostPorts(fs, base.host.search, exec, base.host.artifacts, httpHooks = http ?: dev.min.agent.spi.HttpHookClient { dev.min.agent.spi.HttpHookResult.Responded(200, "{}") }),
                env = env, canonicalPath = canonical,
            )
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(project, TEST_MODEL, mode, initial = extraEvents)
            withTimeout(30_000) { Rig(hub, rt, store, exec, fs, scope).block() }
        } finally { scope.cancel() }
    }

    private fun exited(endCwd: String?, out: String = "ok") = ExecResult.Exited(0, out, false, 1, endCwd = endCwd?.let(::GuestPath))

    @Test fun `cd carries over to the next command and session cwd stays put`() = rig(
        bashScript("c0", "c1"), script = { _, _ -> exited("/old/sub") },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old/sub"), starts)
        assertTrue("Bash 要求宿主报告目录", exec.runs.all { it.first.trackCwd })
        assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        assertEquals("会话目录（权限范围）不因 cd 改变", project, rt.state.value.settings.cwd)
        assertEquals(project, rt.state.value.created.cwd)
        assertFalse("没出界就没有说明", text("c0").contains("Shell cwd was reset"))
        assertTrue(rt.state.value.presentation.none { it.record.event is CwdChanged })
        truth()
    }

    @Test fun `cd back to the project root clears the shell cwd`() = rig(
        bashScript("c0", "c1", "c2"),
        script = run { val ends = listOf("/old/sub", "/old", "/old"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old/sub", "/old"), starts)
        assertNull(rt.state.value.shellCwd)
        val events = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<ShellCwdChanged>()
        assertEquals(listOf(GuestPath("/old/sub"), null), events.map { it.cwd })
        truth()
    }

    @Test fun `cd outside project resets to project and appends the note`() = rig(
        bashScript("c0", "c1"),
        script = run { val ends = listOf("/etc", "/old"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old"), starts)
        assertNull(rt.state.value.shellCwd)
        assertTrue(text("c0"), text("c0").trimEnd().endsWith("Shell cwd was reset to /old"))
        assertEquals("原文只在出界那次说", false, text("c1").contains("Shell cwd was reset"))
        truth()
    }

    @Test fun `leaving from a subdirectory to outside resets and tells`() = rig(
        bashScript("c0", "c1", "c2"),
        script = run { val ends = listOf("/old/sub", "/outside/inner", "/old"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old/sub", "/old"), starts)
        assertTrue(text("c1").trimEnd().endsWith("Shell cwd was reset to /old"))
        assertNull(rt.state.value.shellCwd)
        truth()
    }

    @Test fun `dot-dot cannot escape the project`() {
        for (end in listOf("/old/sub/../../etc", "/old/..", "/old/../outside/inner", "/old/sub/../..")) {
            rig(bashScript("c0", "c1"), script = run { val ends = listOf(end, "/old"); var n = 0; { _, _ -> exited(ends[n++]) } }) {
                say("go")
                assertEquals(end, listOf("/old", "/old"), starts)
                assertNull(end, rt.state.value.shellCwd)
                assertTrue(end + " -> " + text("c0"), text("c0").contains("Shell cwd was reset to /old"))
            }
        }
    }

    @Test fun `a prefix lookalike directory is not inside the project`() = rig(
        bashScript("c0", "c1"), script = run { val ends = listOf("/old-evil/x", "/old"); var n = 0; { _, _ -> exited(ends[n++]) } },
        fs = fs().apply { mkdir("/old-evil/x") },
    ) {
        say("go")
        assertNull(rt.state.value.shellCwd)
        assertTrue(text("c0").contains("Shell cwd was reset to /old"))
    }

    @Test fun `symlink leading outside the project is judged by its real path`() = rig(
        bashScript("c0", "c1"),
        canonical = { if (it.value == "/old/link" || it.value.startsWith("/old/link/")) GuestPath("/outside" + it.value.removePrefix("/old/link")) else it },
        script = run { val ends = listOf("/old/link/inner", "/old"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old"), starts)
        assertNull(rt.state.value.shellCwd)
        assertTrue(text("c0").contains("Shell cwd was reset to /old"))
    }

    @Test fun `symlink inside the project is stored by its real path`() = rig(
        bashScript("c0", "c1"),
        canonical = { if (it.value == "/old/link") GuestPath("/old/sub/deep") else it },
        script = run { val ends = listOf("/old/link", "/old/sub/deep"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals("下一条从真实目录开始：别名之后被改指向也不会把 shell 带出去", listOf("/old", "/old/sub/deep"), starts)
        assertEquals(GuestPath("/old/sub/deep"), rt.state.value.shellCwd)
    }

    @Test fun `additional directories count as inside`() = rig(
        bashScript("c0", "c1"),
        extraEvents = listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/extra")))),
        script = run { val ends = listOf("/extra/x", "/extra/x"); var n = 0; { _, _ -> exited(ends[n++]) } },
    ) {
        say("go")
        assertEquals(listOf("/old", "/extra/x"), starts)
        assertEquals(GuestPath("/extra/x"), rt.state.value.shellCwd)
        assertFalse(text("c0").contains("Shell cwd was reset"))
        truth()
    }

    @Test fun `subagent sessions never carry the directory`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = bashScript("c0", "c1")
            val exec = FakeExec { _, _ -> exited("/old/sub") }
            val base = testDeps(model, listOf(BashTool()))
            val deps = base.copy(host = HostPorts(fs(), base.host.search, exec, base.host.artifacts))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val child = SessionRuntime.create(
                store, LogHeader(LOG_FORMAT_VERSION, SessionId("child"), 0),
                SessionCreated(project, TEST_MODEL, PermissionMode.BypassPermissions), deps, scope,
                initial = listOf(SubagentOrigin(SessionId("parent"), SessionId("parent"), CallId("agent"), "general-purpose", false, 1, cacheKey = "child")),
                loadMemory = false, loadSettings = false, loadSkills = false,
            )
            child.receive(ClientMsgId("p"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
            withTimeout(10_000) { child.state.first { it.turn == null && it.lastTurn != null } }
            assertEquals(2, exec.runs.size)
            assertEquals(listOf("/old", "/old"), exec.runs.map { it.first.cwd.value })
            assertTrue("子 agent 不要求宿主报告目录", exec.runs.none { it.first.trackCwd })
            assertNull(child.state.value.shellCwd)
            assertTrue(child.state.value.presentation.none { it.record.event is ShellCwdChanged })
        } finally { scope.cancel() }
    }

    @Test fun `CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR switches carry-over off`() = rig(
        bashScript("c0", "c1"), env = { if (it == "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR") "1" else null },
        script = { _, _ -> exited("/old/sub") },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old"), starts)
        assertTrue(exec.runs.none { it.first.trackCwd })
        assertNull(rt.state.value.shellCwd)
    }

    @Test fun `switch value other than truthy keeps carry-over`() = rig(
        bashScript("c0", "c1"), env = { if (it == "CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR") "0" else null },
        script = { _, _ -> exited("/old/sub") },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old/sub"), starts)
    }

    @Test fun `exit timeout and background do not update the directory`() = rig(
        bashScript("c0", "c1", "c2", "c3", "c4"),
        script = run {
            val results = listOf<ExecResult>(
                exited("/old/sub"),
                exited(null),                                                    // 脚本里 exit / 没有目录可报
                ExecResult.TimedOut("slow", false),
                ExecResult.Backgrounded(ExecHandle("h1", 7, GuestPath("/log")), "so far"),
                // 假装宿主误报：没有 trackCwd 的路径不会走到这里，Exited 带目录才算数；最后一条正常的验证状态仍在
                exited("/old/sub"),
            )
            var n = 0; { _, _ -> results[n++] }
        },
    ) {
        say("go")
        assertEquals(listOf("/old", "/old/sub", "/old/sub", "/old/sub", "/old/sub"), starts)
        assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        val events = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<ShellCwdChanged>()
        assertEquals("只有第一次真正换了目录才写事件", 1, events.size)
    }

    @Test fun `background command itself starts from the shell cwd but never reports`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = ScriptedModel(
                reply(ToolCallPart(CallId("c0"), "Bash", args("command" to "x")), stop = StopReason.ToolUse),
                reply(ToolCallPart(CallId("c1"), "Bash", args("command" to "sleep 100", "run_in_background" to true)), stop = StopReason.ToolUse),
                reply(TextPart("done")),
            )
            val exec = FakeExec { c, _ -> if (c.trackCwd) exited("/old/sub") else ExecResult.Backgrounded(ExecHandle("h", 1, null), "") }
            val base = testDeps(model, listOf(BashTool()))
            val deps = base.copy(host = HostPorts(fs(), base.host.search, exec, base.host.artifacts))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val hub = SessionHub(store, deps, scope)
            val rt = hub.create(project, TEST_MODEL, PermissionMode.BypassPermissions)
            rt.send(ClientMsgId("m"), listOf(TextPart("go")))
            withTimeout(10_000) { rt.state.first { it.turn == null && it.lastTurn != null } }
            assertEquals(listOf("/old", "/old/sub"), exec.runs.map { it.first.cwd.value })
            assertEquals(listOf(true, false), exec.runs.map { it.first.trackCwd })
            assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        } finally { scope.cancel() }
    }

    @Test fun `reopening the session restores the shell directory`() = rig(
        bashScript("c0"), script = { _, _ -> exited("/old/sub") },
    ) {
        say("go")
        assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        truth()
        hub.close(rt.sessionId)
        val reopened = hub.open(rt.sessionId)!!
        assertEquals(GuestPath("/old/sub"), reopened.state.value.shellCwd)
        assertEquals(project, reopened.state.value.settings.cwd)
        val log = store.open(reopened.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), reopened.state.value)
    }

    @Test fun `changing the session directory clears the shell directory`() = rig(
        bashScript("c0"), script = { _, _ -> exited("/old/sub") },
        fs = fs().apply { mkdir("/next") },
    ) {
        say("go")
        assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        rt.changeSettings(cwd = GuestPath("/next"))
        assertNull(rt.state.value.shellCwd)
        assertEquals(GuestPath("/next"), rt.state.value.settings.cwd)
        truth()
    }

    @Test fun `a vanished shell directory refuses the command, resets and says so`() = rig(
        bashScript("c0", "c1"), script = { _, _ -> exited("/old") },
    ) {
        // /gone/dir 在 FakeFs 里不存在：等价于「上一条命令把它带进去之后目录被删了」
        rt.commit(listOf(ShellCwdChanged(GuestPath("/old/gone"))), Durability.Sync)
        say("go")
        assertEquals("目录没了的那条没有执行（按原目录分析过的命令不能换个目录跑）；之后回到会话目录", listOf("/old"), starts)
        assertTrue(text("c0"), text("c0").contains("Shell cwd was reset to /old"))
        assertTrue(result("c0").isError)
        assertFalse(result("c1").isError)
        assertNull(rt.state.value.shellCwd)
        truth()
    }

    @Test fun `shell cwd outside scope at start (additional directory removed) refuses`() = rig(
        bashScript("c0"), script = { _, _ -> exited("/extra/x") },
        extraEvents = listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = listOf(GuestPath("/extra")))),
    ) {
        rt.commit(listOf(ShellCwdChanged(GuestPath("/extra/x"))), Durability.Sync)
        rt.commit(listOf(PermissionRulesLoaded(emptyList(), additionalDirectories = emptyList())), Durability.Sync)
        say("go")
        assertTrue("附加目录没了，shell 不能继续待在里面", exec.runs.isEmpty())
        assertTrue(text("c0").contains("Shell cwd was reset to /old"))
        assertNull(rt.state.value.shellCwd)
    }

    @Test fun `cd into a subdirectory does not narrow the permission scope`() = rig(
        ScriptedModel(
            reply(ToolCallPart(CallId("c0"), "Bash", args("command" to "cd sub")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("w"), "Write", args("file_path" to "/old/other/new.txt", "content" to "x")), stop = StopReason.ToolUse),
            reply(ToolCallPart(CallId("w2"), "Write", args("file_path" to "/old/rootfile.txt", "content" to "y")), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        ),
        mode = PermissionMode.AcceptEdits,
        script = { _, _ -> exited("/old/sub") },
        extraEvents = listOf(PermissionRulesLoaded(listOf(RuleEntry(RuleKindData.Allow, "Bash", "test", null)))),
    ) {
        say("go")
        assertEquals(GuestPath("/old/sub"), rt.state.value.shellCwd)
        assertEquals(project, rt.state.value.settings.cwd)
        // 兄弟目录与项目根的文件：仍在工作区范围内，acceptEdits 直接放行，没有审批
        assertEquals("x", fs.text("/old/other/new.txt"))
        assertEquals("y", fs.text("/old/rootfile.txt"))
        assertTrue(rt.state.value.presentation.none { it.record.event is ApprovalRequested })
    }

    @Test fun `CwdChanged hook fires with old and new directory when a command moves the shell`() {
        val sent = CopyOnWriteArrayList<HttpHookRequest>()
        val client = HttpHookClient { request -> sent += request; HttpHookResult.Responded(200, "{}") }
        rig(
            bashScript("c0", "c1", "c2"), http = client,
            extraEvents = listOf(HooksLoaded(listOf(HookSourceData("user", hookJson("CwdChanged"))))),
            script = run { val ends = listOf("/old/sub", "/old/sub", "/etc"); var n = 0; { _, _ -> exited(ends[n++]) } },
        ) {
            say("go")
            val cwdHooks = sent.filter { it.body.contains("\"hook_event_name\":\"CwdChanged\"") }
            assertEquals("换目录与出界回到项目根各一次；没换就不触发", 2, cwdHooks.size)
            val first = cwdHooks[0].body
            assertTrue(first, first.contains("\"old_cwd\":\"/old\"") && first.contains("\"new_cwd\":\"/old/sub\""))
            assertTrue("通用 cwd 字段跟着 Claude 走（CC 原文）：" + first, first.contains("\"cwd\":\"/old/sub\""))
            val second = cwdHooks[1].body
            assertTrue(second, second.contains("\"old_cwd\":\"/old/sub\"") && second.contains("\"new_cwd\":\"/old\""))
        }
    }

    @Test fun `CwdChanged hook does not fire for subagents`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sent = CopyOnWriteArrayList<HttpHookRequest>()
            val exec = FakeExec { _, _ -> exited("/old/sub") }
            val model = bashScript("c0")
            val base = testDeps(model, listOf(BashTool()))
            val deps = base.copy(host = HostPorts(fs(), base.host.search, exec, base.host.artifacts, httpHooks = HttpHookClient { r -> sent += r; HttpHookResult.Responded(200, "{}") }))
            val store = FileSessionStore(tmp.newFolder().toPath(), { 0L })
            val child = SessionRuntime.create(
                store, LogHeader(LOG_FORMAT_VERSION, SessionId("child"), 0),
                SessionCreated(project, TEST_MODEL, PermissionMode.BypassPermissions), deps, scope,
                initial = listOf(SubagentOrigin(SessionId("parent"), SessionId("parent"), CallId("agent"), "general-purpose", false, 1, cacheKey = "child"),
                    HooksLoaded(listOf(HookSourceData("user", hookJson("CwdChanged"))))),
                loadMemory = false, loadSettings = false, loadSkills = false,
            )
            child.receive(ClientMsgId("p"), listOf(TextPart("task")), Delivery.Queue, InputOrigin.Parent(SessionId("parent")), TurnTrigger.User)
            withTimeout(10_000) { child.state.first { it.turn == null && it.lastTurn != null } }
            assertTrue(sent.none { it.body.contains("CwdChanged") })
        } finally { scope.cancel() }
    }

    private fun hookJson(event: String) = kotlinx.serialization.json.JsonObject(mapOf(
        event to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(mapOf(
            "hooks" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive("http"),
                "url" to kotlinx.serialization.json.JsonPrimitive("https://hooks.example/x"),
            )))),
        )))),
    ))
}
