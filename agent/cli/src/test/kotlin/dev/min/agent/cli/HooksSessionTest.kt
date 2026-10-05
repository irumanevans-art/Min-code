package dev.min.agent.cli

import dev.min.agent.testkit.*
import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.CallPhase
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.HostPorts
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputBlocked
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolInputRewritten
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * hooks 接进会话（docs/agent/detail/hooks设计.md §7）：真文件系统 + 真 shell 跑 hook 脚本，模型是按内容路由的脚本。
 * 每个用例一份临时的「家目录」放 `.claude/settings.json` 与 hook 脚本，一份工作区当 cwd。
 */
class HooksSessionTest {
    private val root: Path = Files.createTempDirectory("min hooks 会话")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ran = CopyOnWriteArrayList<String>()
    private val ids = AtomicLong(0)

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private fun shellPath(p: Path) = paths.guestOfHost(p).value

    /** 写一个 hook 脚本，返回放进 settings 的命令 */
    private fun script(name: String, body: String): String {
        val file = Files.createDirectories(home.resolve(".claude/hooks")).resolve("$name.sh")
        Files.writeString(file, "#!/bin/bash\n$body\n")
        return "bash '${shellPath(file)}'"
    }

    private fun settings(hooks: String, project: Boolean = false) {
        val dir = Files.createDirectories((if (project) work else home).resolve(".claude"))
        Files.writeString(dir.resolve("settings.json"), """{"hooks": $hooks}""")
    }

    private fun hook(event: String, command: String, matcher: String? = null) =
        """{"$event": [{${matcher?.let { "\"matcher\": \"$it\", " } ?: ""}"hooks": [{"type": "command", "command": ${JsonPrimitive(command)}}]}]}"""

    /** 像 Bash：按入参报出命令，执行只记下跑过什么 */
    private val bash = object : Tool<JsonObject> {
        override val name = "Bash"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "跑命令"
        override fun parse(raw: JsonObject) =
            if (raw["command"] is JsonPrimitive) ToolInput.Valid(raw) else ToolInput.Invalid("缺 command")
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) =
            PermissionAsk(name, "跑命令", commands = listOf(CommandAccess((input["command"] as JsonPrimitive).content)))
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val command = (input["command"] as JsonPrimitive).content
            ran += command
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran: $command")))))
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun bashCall(id: String, command: String) = ToolCallPart(CallId(id), "Bash", args("command" to command))

    private suspend fun session(model: ModelClient, mode: PermissionMode = PermissionMode.Default, trust: Boolean = false, env: Map<String, String> = emptyMap()): Pair<SessionRuntime, FileSessionStore> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logDir = Files.createDirectories(root.resolve("logs"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(bash),
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = paths.guestOfHost(home),
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { trust },
            env = { env[it] },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps, scope).create(paths.guestOfHost(work), TEST_MODEL, mode) to store
    }

    private suspend fun FileSessionStore.events(rt: SessionRuntime): List<SessionEvent> = open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    // ---- PreToolUse ----

    @Test
    fun `a PreToolUse hook that exits 2 blocks the call and tells the model why`() = run {
        settings(hook("PreToolUse", script("no-rm", """if grep -q '"command":"rm '; then echo "不许删文件" >&2; exit 2; fi"""), "Bash"))
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "rm -rf build"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (rt, store) = session(model, PermissionMode.BypassPermissions)
        val done = rt.say("清理")
        val result = done.toolResults.getValue(CallId("c1"))
        assertTrue(result.isError)
        assertTrue((result.content.single() as TextPart).text.contains("不许删文件"))
        assertTrue("被拦下的命令没有执行", ran.isEmpty())
        assertEquals("block", store.events(rt).filterIsInstance<HooksRan>().single { it.event == "PreToolUse" }.decision)
    }

    @Test
    fun `a PreToolUse allow skips the approval card but never the safety floor`() = run {
        val allow = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}"""
        settings(hook("PreToolUse", script("allow-all", "cat > /dev/null; printf '%s' '$allow'"), "Bash"))
        val model = RoutingModel { req ->
            when (req.lastText()) {
                "建目录" -> reply(bashCall("c1", "mkdir out"), stop = StopReason.ToolUse)
                "清空" -> reply(bashCall("c2", "rm -rf /"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val (rt, store) = session(model)
        rt.say("建目录")
        assertEquals(listOf("mkdir out"), ran)
        assertTrue("hook 放行，不弹审批", store.events(rt).none { it is ApprovalRequested })

        rt.send(ClientMsgId("u-floor"), listOf(TextPart("清空")))
        rt.state.first { it.turn?.calls?.get(CallId("c2"))?.phase is CallPhase.AwaitingApproval }
        assertNotNull(store.events(rt).filterIsInstance<ApprovalRequested>().single().floorReason)
        assertEquals("底线照样问人", listOf("mkdir out"), ran)
    }

    @Test
    fun `a PreToolUse hook can rewrite the input before it runs`() = run {
        val rewrite = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"command":"timeout 60 make"}}}"""
        settings(hook("PreToolUse", script("wrap", "cat > /dev/null; printf '%s' '$rewrite'"), "Bash"))
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "make"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (rt, store) = session(model)
        val done = rt.say("构建")
        assertEquals(listOf("timeout 60 make"), ran)
        assertEquals("timeout 60 make", (store.events(rt).filterIsInstance<ToolInputRewritten>().single().input["command"] as JsonPrimitive).content)
        val original = done.history.filterIsInstance<HistoryEntry.Assistant>().first().toolCalls.single()
        assertEquals("模型看到的原始调用不变", "make", (original.input["command"] as JsonPrimitive).content)
    }

    // ---- PostToolUse ----

    @Test
    fun `PostToolUse feedback rides along with the tool result`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"格式化过了，记得跑测试"}}"""
        settings(hook("PostToolUse", script("after", "cat > /dev/null; printf '%s' '$ctx'"), "Bash"))
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "ls"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (rt, _) = session(model)
        rt.say("看看")
        val toolMessage = model.requests.last().messages.last()
        assertTrue(toolMessage.parts.first() is ToolResultPart)
        val reminder = (toolMessage.parts.last() as TextPart).text
        assertTrue(reminder, reminder.contains("<system-reminder>") && reminder.contains("格式化过了，记得跑测试"))
    }

    // ---- UserPromptSubmit / SessionStart ----

    @Test
    fun `UserPromptSubmit adds context to the message, and exit 2 keeps the prompt out`() = run {
        settings(hook("UserPromptSubmit", script("prompt", """if grep -q '密钥'; then echo "别把密钥发给模型" >&2; exit 2; fi; echo '当前分支：main'""")))
        val model = RoutingModel { reply(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("看看状态")
        val first = model.requests.single().messages.single()
        assertEquals("看看状态", (first.parts.first() as TextPart).text)
        assertTrue((first.parts.last() as TextPart).text.contains("当前分支：main"))

        rt.say("这是我的密钥 sk-123")
        assertEquals("别把密钥发给模型", store.events(rt).filterIsInstance<InputBlocked>().single().reason)
        assertEquals("被拦下的提示词没发给模型", 1, model.requests.size)
        assertTrue(rt.state.value.inbox.isEmpty())
        assertTrue(rt.state.value.history.none { (it as? HistoryEntry.User)?.parts?.any { p -> (p as? TextPart)?.text?.contains("sk-123") == true } == true })
    }

    @Test
    fun `SessionStart context reaches the first message`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"这个仓库用 pnpm"}}"""
        settings(hook("SessionStart", script("start", "cat > /dev/null; printf '%s' '$ctx'"), "startup"))
        val model = RoutingModel { reply(TextPart("好")) }
        val (rt, _) = session(model)
        rt.say("装依赖")
        val first = model.requests.single().messages.single()
        assertTrue((first.parts.last() as TextPart).text.contains("这个仓库用 pnpm"))
    }

    // ---- Stop / continue ----

    @Test
    fun `a Stop hook can send the model back to work, but only eight times in a turn`() = run {
        settings(hook("Stop", script("never-done", """cat > /dev/null; printf '%s' '{"decision":"block","reason":"测试还没跑"}'""")))
        val model = RoutingModel { reply(TextPart("做完了")) }
        val (rt, store) = session(model)
        val done = rt.say("修 bug")
        assertEquals("最初一次 + 被拦 8 次", 9, model.requests.size)
        assertTrue(model.requests[1].lastText().contains("测试还没跑"))
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue(store.events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("这次放行") })
    }

    @Test
    fun `continue false ends the turn and says why`() = run {
        settings(hook("PreToolUse", script("halt", """cat > /dev/null; printf '%s' '{"continue":false,"stopReason":"维护窗口，先别动"}'"""), "Bash"))
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "ls"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (rt, store) = session(model, PermissionMode.BypassPermissions)
        val done = rt.say("看看")
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Hook), done.lastTurn!!.outcome)
        assertTrue(store.events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("维护窗口，先别动") })
        assertTrue(ran.isEmpty())
    }

    // ---- PermissionRequest ----

    @Test
    fun `a PermissionRequest hook answers the approval with a real script`() = run {
        val allow = """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}"""
        settings(hook("PermissionRequest", script("perm-allow", "cat > /dev/null; printf '%s' '$allow'"), "Bash"))
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(bashCall("c1", "mkdir out"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (rt, store) = session(model)
        val done = rt.say("建目录")
        assertEquals("hook 答了 allow，命令直接执行", listOf("mkdir out"), ran)
        assertTrue("替人答的结论落成 ApprovalResolved(by = Hook)", done.toolResults.getValue(CallId("c1")).isError.not())
        val resolved = store.events(rt).filterIsInstance<dev.min.agent.spi.ApprovalResolved>().single()
        assertEquals(dev.min.agent.spi.DecidedBy.Hook("PermissionRequest"), resolved.decidedBy)
    }

    // ---- SessionStart(resume) / SessionEnd ----

    /** 与 [session] 同一套装配，但把 Hub 留给测试用（close / open(resumedByUser)） */
    private suspend fun hubSession(model: ModelClient, trust: Boolean = false): Triple<SessionRuntime, FileSessionStore, SessionHub> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logDir = Files.createDirectories(root.resolve("logs"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = listOf(bash),
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = paths.guestOfHost(home),
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { trust },
            env = { null },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        return Triple(hub.create(paths.guestOfHost(work), TEST_MODEL, PermissionMode.BypassPermissions), store, hub)
    }

    @Test
    fun `resume and session end run their hooks with real scripts`() = run {
        val script = Files.createDirectories(home.resolve(".claude/hooks")).resolve("lifecycle.sh")
        Files.writeString(script, "#!/bin/bash\ncat > /dev/null\necho ran >> lifecycle.txt\n")
        settings(
            """{"SessionStart": [{"matcher": "resume", "hooks": [{"type": "command", "command": "bash '${shellPath(script)}'"}]}],
                "SessionEnd": [{"hooks": [{"type": "command", "command": "bash '${shellPath(script)}'", "timeout": 5}]}]}""",
        )
        val marker = work.resolve("lifecycle.txt")
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val (first, store, hub) = hubSession(model)
        first.say("干活")
        assertTrue(hub.close(first.sessionId))
        assertEquals("close 时 SessionEnd 先跑一遍", 1, Files.readAllLines(marker).size)

        // 用户打开（--resume 的路）：SessionStart(source = resume) 再写一行
        val resumed = hub.open(first.sessionId, resumedByUser = true)!!
        resumed.say("接着")
        assertEquals(2, Files.readAllLines(marker).size)

        hub.open(first.sessionId, resumedByUser = true)
        assertTrue("同一进程第二次打开不跑", Files.readAllLines(marker).size == 2)
        assertTrue(hub.close(first.sessionId, dev.min.agent.kernel.SessionEndReasons.PROMPT_INPUT_EXIT))
        assertEquals("SessionEnd 同一进程只跑一次", 2, Files.readAllLines(marker).size)
        val ran = store.events(first).filterIsInstance<HooksRan>()
        assertTrue(ran.joinToString(), ran.any { it.event == "SessionStart" } && ran.any { it.event == "SessionEnd" })
    }

    @Test
    fun `showing an approval prompt fires a permission_prompt notification`() = run {
        settings(hook("Notification", script("notify", "cat > /dev/null; echo ran >> notify.txt"), "permission_prompt"))
        val model = RoutingModel { req ->
            if (req.messages.size == 1) reply(bashCall("c1", "rm -rf build"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (rt, store) = session(model)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            ConsoleUi(rt, ApprovalAnswer.Ask, java.io.BufferedReader(java.io.StringReader("y\n")), System.err).attach(uiScope)
            val before = rt.state.value.lastTurn?.turnId
            rt.send(ClientMsgId("u-notify"), listOf(TextPart("清理")))
            rt.state.first { it.turn == null && it.lastTurn?.turnId != before }
        } finally {
            uiScope.cancel()
        }
        assertEquals("终端问过人、人答了 y，命令执行", listOf("rm -rf build"), ran)
        assertEquals("Notification", store.events(rt).filterIsInstance<HooksRan>().single().event)
        assertEquals("hook 只因弹卡跑了一次", listOf("ran"), Files.readAllLines(work.resolve("notify.txt")))
    }

    // ---- 信任 ----

    @Test
    fun `project hooks wait for workspace trust while user hooks always run`() = run {
        settings(hook("PreToolUse", script("project-block", """cat > /dev/null; echo "项目 hook" >&2; exit 2"""), "Bash"), project = true)
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "ls"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (untrusted, store) = session(model, PermissionMode.BypassPermissions)
        untrusted.say("看看")
        assertEquals("没信任：项目 hook 不跑", listOf("ls"), ran)
        assertTrue(store.events(untrusted).filterIsInstance<HooksLoaded>().single().notes.single().contains("还没被信任"))

        ran.clear()
        val (trusted, _) = session(RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "ls"), stop = StopReason.ToolUse) else reply(TextPart("好")) }, PermissionMode.BypassPermissions, trust = true)
        trusted.say("看看")
        assertTrue("信任后项目 hook 拦下了", ran.isEmpty())
    }

    private fun rawSettings(json: String, project: Boolean = false) {
        val dir = Files.createDirectories((if (project) work else home).resolve(".claude"))
        Files.writeString(dir.resolve("settings.json"), json)
    }

    @Test
    fun `an untrusted project's disableAllHooks cannot silence the user's gate`() = run {
        settings(hook("PreToolUse", script("user-block", """cat > /dev/null; echo "用户门禁" >&2; exit 2"""), "Bash"))
        rawSettings("""{"disableAllHooks": true}""", project = true)
        val model = RoutingModel { req -> if (req.messages.size == 1) reply(bashCall("c1", "ls"), stop = StopReason.ToolUse) else reply(TextPart("好")) }
        val (rt, store) = session(model, PermissionMode.BypassPermissions)
        val done = rt.say("看看")
        assertTrue("用户的门禁照样拦", ran.isEmpty())
        assertTrue((done.toolResults.getValue(CallId("c1")).content.single() as TextPart).text.contains("用户门禁"))
        assertTrue(store.events(rt).filterIsInstance<HooksLoaded>().single().notes.single().contains("disableAllHooks"))
    }

    @Test
    fun `UserPromptSubmit continue false blocks the input instead of leaving it in the inbox`() = run {
        settings(hook("UserPromptSubmit", script("halt", """cat > /dev/null; printf '%s' '{"continue":false,"stopReason":"先别问"}'""")))
        val model = RoutingModel { reply(TextPart("好")) }
        val (rt, store) = session(model)
        val first = rt.say("你好")
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Hook), first.lastTurn!!.outcome)
        assertTrue("输入不能留在收件箱里", first.inbox.isEmpty())
        assertEquals("先别问", store.events(rt).filterIsInstance<InputBlocked>().single().reason)
        val second = rt.say("第二句")
        assertTrue(second.inbox.isEmpty())
        assertEquals("两条输入各有自己的 InputBlocked", 2, store.events(rt).filterIsInstance<InputBlocked>().size)
        assertTrue("模型一次都没被请求", model.requests.isEmpty())
    }

    private fun alwaysBlockStop() = settings(hook("Stop", script("never-done", """cat > /dev/null; printf '%s' '{"decision":"block","reason":"再检查"}'""")))

    @Test
    fun `CLAUDE_CODE_STOP_HOOK_BLOCK_CAP overrides the cap, non-positive or garbage values are ignored`() = run {
        alwaysBlockStop()
        val capped = RoutingModel { reply(TextPart("做完了")) }
        val (rt, _) = session(capped, env = mapOf("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP" to "2"))
        rt.say("修 bug")
        assertEquals("最初一次 + 被拦 2 次", 3, capped.requests.size)

        for (bad in listOf("0", "-3", "abc", "")) {
            val model = RoutingModel { reply(TextPart("做完了")) }
            val (rt2, _) = session(model, env = mapOf("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP" to bad))
            rt2.say("修 bug")
            assertEquals("值「$bad」忽略，用默认的 8", 9, model.requests.size)
        }
    }

    @Test
    fun `a Stop hook that only adds context keeps the conversation going once, as hook feedback`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"Stop","additionalContext":"跑一下测试再结束"}}"""
        // stop_hook_active 为真时（已经继续过一次）不再给上下文
        settings(hook("Stop", script("stop-ctx", """if grep -q '"stop_hook_active":true'; then exit 0; fi; printf '%s' '$ctx'""")))
        val model = RoutingModel { reply(TextPart("做完了")) }
        val (rt, _) = session(model)
        val done = rt.say("修 bug")
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals("最初一次 + 因上下文继续 1 次", 2, model.requests.size)
        assertTrue(model.requests[1].lastText().contains("跑一下测试再结束"))
        val fed = done.history.filterIsInstance<HistoryEntry.User>().last()
        assertEquals("来源是 Stop hook，界面标成 hook feedback 而不是错误", dev.min.agent.spi.InputOrigin.Hook("Stop"), fed.origin)
    }

    @Test
    fun `context-only continuation counts against the same cap`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"Stop","additionalContext":"再看看"}}"""
        settings(hook("Stop", script("stop-ctx", "cat > /dev/null; printf '%s' '$ctx'")))
        val model = RoutingModel { reply(TextPart("做完了")) }
        val (rt, store) = session(model, env = mapOf("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP" to "3"))
        rt.say("修 bug")
        assertEquals(4, model.requests.size)
        assertTrue(store.events(rt).any { it is dev.min.agent.spi.Note && it.text.contains("这次放行") })
    }
}
