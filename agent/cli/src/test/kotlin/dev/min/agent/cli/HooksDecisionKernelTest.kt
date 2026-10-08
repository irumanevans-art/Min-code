package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.HostPorts
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.fold
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.ModelMessage
import dev.min.agent.spi.Note
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.tools.TaskCreateTool
import dev.min.agent.tools.TaskUpdateTool
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * hooks 兼容样本里「决策」类样本的端到端版本：把样本的 settings.json 与 scripts 原样装进真会话（真文件系统 + 真 shell），
 * 在内核里**真的触发**对应事件，断言 hook 被调用、输出按表处理。引擎层的逐字段比对仍在 [HooksCompatTest]，
 * 这里只钉「内核在对的时机调了引擎、并按结论行事」。
 */
class HooksDecisionKernelTest {
    private val root: Path = Files.createTempDirectory("min hooks decision 会话")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val ran = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(90_000) { block() } }

    // ---- 样本装进会话 ----

    private fun sampleDir(name: String): Path =
        Paths.get(URI(javaClass.classLoader.getResource("hooks-compat/$name")!!.toString()))

    /** 样本的 scripts 放进项目目录、settings.json 放进用户层（命令里的 `$CLAUDE_PROJECT_DIR` 由内核给） */
    private fun installSample(name: String) {
        val dir = sampleDir(name)
        Files.walk(dir.resolve("scripts")).use { files ->
            files.filter(Files::isRegularFile).forEach { file ->
                val dest = work.resolve("scripts").resolve(dir.resolve("scripts").relativize(file).toString())
                Files.createDirectories(dest.parent)
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.createDirectories(home.resolve(".claude"))
        Files.copy(dir.resolve("settings.json"), home.resolve(".claude/settings.json"), StandardCopyOption.REPLACE_EXISTING)
    }

    /** 测试自己写的 hook 脚本（变体：样本只覆盖一种输出） */
    private fun installScript(event: String, body: String, matcher: String? = null, timeout: Int? = null) {
        val file = Files.createDirectories(work.resolve("scripts")).resolve("$event.sh")
        Files.writeString(file, "#!/bin/sh\n$body\n")
        val command = "sh \\\"\$CLAUDE_PROJECT_DIR/scripts/$event.sh\\\""
        Files.createDirectories(home.resolve(".claude")).resolve("settings.json").also {
            Files.writeString(
                it,
                """{"hooks":{"$event":[{${matcher?.let { m -> "\"matcher\":\"$m\"," } ?: ""}"hooks":[{"type":"command","command":"$command"${timeout?.let { t -> ""","timeout":$t""" } ?: ""}}]}]}}""",
            )
        }
    }

    // ---- 会话脚手架 ----

    private fun answer(vararg parts: dev.min.agent.protocol.Part, stop: StopReason = StopReason.EndTurn): Flow<ModelStreamEvent> = flowOf(
        ModelStreamEvent.Started(null),
        ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts.toList()), Usage(inputTokens = 100, outputTokens = 10), stop, false),
    )

    private class ScriptedModel(private val route: (ModelRequest) -> Flow<ModelStreamEvent>) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> {
            requests += request
            return route(request)
        }
    }

    private fun ModelRequest.allText(): String = messages.joinToString("\n") { m ->
        m.parts.joinToString("\n") { p ->
            when (p) {
                is TextPart -> p.text
                is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                else -> ""
            }
        }
    }

    private fun bashCall(id: String, command: String) = ToolCallPart(CallId(id), "Bash", args("command" to command))

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

    private suspend fun session(model: ModelClient, tools: List<Tool<*>> = listOf(bash)): Pair<SessionRuntime, FileSessionStore> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logDir = Files.createDirectories(root.resolve("logs"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(TEST_PROFILE, model) },
            tools = tools,
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = paths.guestOfHost(home),
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { false },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        return hub.create(paths.guestOfHost(work), TEST_MODEL, PermissionMode.BypassPermissions) to store
    }

    private suspend fun FileSessionStore.events(rt: SessionRuntime): List<SessionEvent> =
        open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    /** 日志即真相：重放日志得到的状态必须等于运行时状态 */
    private suspend fun assertLogIsTruth(store: FileSessionStore, rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private fun HooksRan.isFor(event: String) = this.event == event

    // ---- PostToolBatch（样本 76 / 77） ----

    @Test
    fun `PostToolBatch runs once per batch and its additionalContext reaches the next request (sample 77)`() = run {
        installSample("77-posttoolbatch-decision")
        val model = ScriptedModel { req ->
            if (req.messages.size == 1) answer(bashCall("c1", "echo a"), bashCall("c2", "echo b"), stop = StopReason.ToolUse)
            else answer(TextPart("好"))
        }
        val (rt, store) = session(model)
        val done = rt.say("干活")

        assertEquals("一批两个调用，hook 整批只跑一次", 1, store.events(rt).filterIsInstance<HooksRan>().count { it.isFor("PostToolBatch") })
        assertEquals(listOf("echo a", "echo b"), ran.toList())
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue("上下文在下一次请求前注入", model.requests.last().allText().contains("These files are part of the ledger module."))
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `PostToolBatch stdin carries every call with its tool_response (sample 76 shape)`() = run {
        installScript("PostToolBatch", "cat > \"\$CLAUDE_PROJECT_DIR/batch-input.json\"")
        val model = ScriptedModel { req ->
            if (req.messages.size == 1) answer(bashCall("c1", "echo a"), bashCall("c2", "echo b"), stop = StopReason.ToolUse)
            else answer(TextPart("好"))
        }
        val (rt, _) = session(model)
        rt.say("干活")
        val input = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(work.resolve("batch-input.json"))).let { it as JsonObject }
        assertEquals("PostToolBatch", (input["hook_event_name"] as JsonPrimitive).content)
        val calls = input["tool_calls"] as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("c1", "c2"), calls.map { ((it as JsonObject)["tool_use_id"] as JsonPrimitive).content })
        assertEquals(listOf("ran: echo a", "ran: echo b"), calls.map { ((it as JsonObject)["tool_response"] as JsonPrimitive).content })
        assertEquals("Bash", ((calls[0] as JsonObject)["tool_name"] as JsonPrimitive).content)
    }

    @Test
    fun `PostToolBatch block and continue false stop the loop before the next model call`() = run {
        // decision:block：理由留在对话里（HookContextAdded），回合以 Interrupted(Hook) 收尾，模型只被问过一次
        installScript("PostToolBatch", """cat > /dev/null; printf '%s' '{"decision":"block","reason":"先跑测试再继续"}'""")
        val model = ScriptedModel { _ -> answer(bashCall("c1", "echo a"), stop = StopReason.ToolUse) }
        val (rt, store) = session(model)
        val done = rt.say("干活")
        assertEquals(1, model.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Hook), done.lastTurn!!.outcome)
        assertTrue(store.events(rt).filterIsInstance<dev.min.agent.spi.HookContextAdded>().any { it.event == "PostToolBatch" && it.text.contains("先跑测试再继续") })
        assertLogIsTruth(store, rt)

        // exit 2：stderr 是理由
        installScript("PostToolBatch", "cat > /dev/null; echo '批后检查没过' >&2; exit 2")
        val (rt2, store2) = session(ScriptedModel { _ -> answer(bashCall("c1", "echo a"), stop = StopReason.ToolUse) })
        rt2.say("再来")
        assertTrue(store2.events(rt2).filterIsInstance<dev.min.agent.spi.HookContextAdded>().any { it.text.contains("批后检查没过") })

        // continue:false：同样停，理由进 Note
        installScript("PostToolBatch", """cat > /dev/null; printf '%s' '{"continue":false,"stopReason":"到此为止"}'""")
        val model3 = ScriptedModel { _ -> answer(bashCall("c1", "echo a"), stop = StopReason.ToolUse) }
        val (rt3, store3) = session(model3)
        val done3 = rt3.say("第三次")
        assertEquals(1, model3.requests.size)
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.Hook), done3.lastTurn!!.outcome)
        assertTrue(store3.events(rt3).filterIsInstance<Note>().any { it.text.contains("到此为止") })
    }

    // ---- TaskCompleted（样本 88 / 89） ----

    private val taskTools: List<Tool<*>> = listOf(TaskCreateTool, TaskUpdateTool)

    private fun taskModel(vararg updates: ToolCallPart) = ScriptedModel { req ->
        when (req.messages.size) {
            1 -> answer(ToolCallPart(CallId("t1"), "TaskCreate", args("subject" to "Implement auth", "description" to "login and signup")), stop = StopReason.ToolUse)
            3 -> answer(*updates, stop = StopReason.ToolUse)
            else -> answer(TextPart("好"))
        }
    }

    private fun complete(callId: String = "t2") = ToolCallPart(CallId(callId), "TaskUpdate", args("taskId" to "1", "status" to "completed"))

    @Test
    fun `TaskCompleted exit 2 keeps the task open and feeds stderr back to the model (sample 89)`() = run {
        installSample("89-taskcompleted-decision")
        val model = taskModel(complete())
        val (rt, store) = session(model, taskTools)
        val done = rt.say("做任务")

        val result = done.toolResults.getValue(CallId("t2"))
        assertTrue(result.isError)
        val text = result.content.filterIsInstance<TextPart>().joinToString("") { it.text }
        assertTrue(text, text.contains("Tests not passing. Fix failing tests before completing"))
        assertEquals("任务没有被标完成", TaskStatus.Pending, done.tasks.single().status)
        assertEquals("hook 被调用了一次", 1, store.events(rt).filterIsInstance<HooksRan>().count { it.isFor("TaskCompleted") })
        assertTrue("模型下一轮看到了反馈", model.requests.last().allText().contains("Tests not passing"))
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `TaskCompleted sees the task and a passing hook lets it complete, continue false is ignored`() = run {
        installScript("TaskCompleted", """cat > "${'$'}CLAUDE_PROJECT_DIR/task-input.json"; printf '%s' '{"continue":false,"stopReason":"别停"}'""")
        val (rt, store) = session(taskModel(complete()), taskTools)
        val done = rt.say("做任务")
        val input = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(work.resolve("task-input.json"))) as JsonObject
        assertEquals("1", (input["task_id"] as JsonPrimitive).content)
        assertEquals("Implement auth", (input["task_subject"] as JsonPrimitive).content)
        assertEquals("login and signup", (input["task_description"] as JsonPrimitive).content)
        assertEquals("hook 没拦，任务完成", TaskStatus.Completed, done.tasks.single().status)
        assertEquals("TaskUpdate 触发时 continue:false 被忽略，回合照常完成", TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `TaskCompleted does not fire for other status changes or for an already completed task`() = run {
        installScript("TaskCompleted", "cat > /dev/null")
        val inProgress = ToolCallPart(CallId("t2"), "TaskUpdate", args("taskId" to "1", "status" to "in_progress"))
        val (rt, store) = session(taskModel(inProgress), taskTools)
        rt.say("开始")
        assertEquals(0, store.events(rt).filterIsInstance<HooksRan>().count { it.isFor("TaskCompleted") })

        val again = ScriptedModel { req ->
            when (req.messages.size) {
                1 -> answer(ToolCallPart(CallId("t1"), "TaskCreate", args("subject" to "x", "description" to "y")), stop = StopReason.ToolUse)
                3 -> answer(complete("t2"), stop = StopReason.ToolUse)
                5 -> answer(complete("t3"), stop = StopReason.ToolUse)
                else -> answer(TextPart("好"))
            }
        }
        val (rt2, store2) = session(again, taskTools)
        rt2.say("完成两次")
        assertEquals("第二次标完成时任务已经是完成状态，不再触发", 1, store2.events(rt2).filterIsInstance<HooksRan>().count { it.isFor("TaskCompleted") })
    }

    // ---- PreModelSwitch（样本 112 / 113） ----

    private val opus = ModelRef(TEST_MODEL.providerId, "claude-opus-5")

    @Test
    fun `PreModelSwitch ask is treated as a refusal because the kernel has no prompt (sample 113)`() = run {
        installSample("113-premodelswitch-decision")
        val (rt, store) = session(ScriptedModel { _ -> answer(TextPart("好")) })
        val before = rt.state.value.settings.model
        rt.changeSettings(model = opus)

        assertEquals("模型没换", before, rt.state.value.settings.model)
        val note = store.events(rt).filterIsInstance<Note>().single { it.text.contains("模型没有切换") }
        assertTrue(note.text, note.text.contains("Switching now re-sends about 180k tokens"))
        assertEquals(1, store.events(rt).filterIsInstance<HooksRan>().count { it.isFor("PreModelSwitch") })
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `PreModelSwitch exit 2 blocks, stdin names both models and the source, other settings still apply`() = run {
        installScript("PreModelSwitch", "cat > \"\$CLAUDE_PROJECT_DIR/switch-input.json\"; echo 'Opus 4.6 is retired' >&2; exit 2", matcher = "claude-opus-5")
        val (rt, store) = session(ScriptedModel { _ -> answer(TextPart("好")) })
        val before = rt.state.value.settings.model
        rt.changeSettings(model = opus, mode = PermissionMode.Plan, modelSwitchSource = "picker")

        assertEquals(before, rt.state.value.settings.model)
        assertEquals("模式不归这个 hook 管", PermissionMode.Plan, rt.state.value.settings.mode)
        val input = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(work.resolve("switch-input.json"))) as JsonObject
        assertEquals(before.modelId, (input["from_model"] as JsonPrimitive).content)
        assertEquals("claude-opus-5", (input["to_model"] as JsonPrimitive).content)
        assertEquals("picker", (input["source"] as JsonPrimitive).content)
        assertEquals("PreModelSwitch", (input["hook_event_name"] as JsonPrimitive).content)
        assertTrue(store.events(rt).filterIsInstance<Note>().any { it.text.contains("Opus 4.6 is retired") })
    }

    @Test
    fun `PreModelSwitch lets non-matching targets and allowing hooks through`() = run {
        installScript("PreModelSwitch", "cat > /dev/null; exit 2", matcher = "claude-opus-5")
        val (rt, store) = session(ScriptedModel { _ -> answer(TextPart("好")) })
        val other = ModelRef(TEST_MODEL.providerId, "claude-haiku-5-5")
        rt.changeSettings(model = other)
        assertEquals("matcher 对不上，hook 不跑，切换生效", other, rt.state.value.settings.model)
        assertEquals(0, store.events(rt).filterIsInstance<HooksRan>().count { it.isFor("PreModelSwitch") })
        // 带 [1m] 后缀的目标按规范名匹配（原文），所以会被拦
        rt.changeSettings(model = ModelRef(TEST_MODEL.providerId, "claude-opus-5[1m]"))
        assertEquals(other, rt.state.value.settings.model)

        installScript("PreModelSwitch", """cat > /dev/null; printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreModelSwitch","permissionDecision":"allow"}}'""")
        val (rt2, _) = session(ScriptedModel { _ -> answer(TextPart("好")) })
        rt2.changeSettings(model = opus)
        assertEquals(opus, rt2.state.value.settings.model)
    }

    @Test
    fun `PreModelSwitch timeout blocks the switch`() = run {
        installScript("PreModelSwitch", "cat > /dev/null; sleep 20", timeout = 1)
        val (rt, store) = session(ScriptedModel { _ -> answer(TextPart("好")) })
        val before = rt.state.value.settings.model
        rt.changeSettings(model = opus)
        assertEquals(before, rt.state.value.settings.model)
        assertTrue(store.events(rt).filterIsInstance<Note>().any { it.text.contains("超时") })
    }
}
