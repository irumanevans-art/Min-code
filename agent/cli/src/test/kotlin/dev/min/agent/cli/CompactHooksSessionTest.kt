package dev.min.agent.cli

import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.kernel.SessionState
import dev.min.agent.kernel.fold
import dev.min.agent.kernel.HostPorts
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.Part
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.ModelFailureKind
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
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.protocol.Usage
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.args
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 压缩 hooks 接进会话（docs/agent/detail/hooks设计.md §7、压缩设计.md §5）：PreCompact / PostCompact、
 * SessionStart(source = compact)。真文件系统 + 真 shell 跑 hook 脚本；模型按内容路由，回复里报的
 * 输入用量给一个越过摘要线的大数——像真的供应商，让「上次请求的用量」这个基准有意义。
 * 窗口压到 20k（遮蔽线 ≈ 12.3k、摘要线 ≈ 15k），一条长消息就能过线。
 */
class CompactHooksSessionTest {
    private val root: Path = Files.createTempDirectory("min compact hooks 会话")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val hubs = mutableListOf<SessionHub>()
    private val ran = CopyOnWriteArrayList<String>()
    private val ids = AtomicLong(0)
    private val small = TEST_PROFILE.copy(contextWindow = 20_000, maxOutputTokens = 2_000)

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(90_000) { block() } }

    private fun shellPath(p: Path) = paths.guestOfHost(p).value

    /** 写一个 hook 脚本，返回放进 settings 的命令 */
    private fun script(name: String, body: String): String {
        val file = Files.createDirectories(home.resolve(".claude/hooks")).resolve("$name.sh")
        Files.writeString(file, "#!/bin/bash\n$body\n")
        return "bash '${shellPath(file)}'"
    }

    private fun settings(hooks: String) {
        val dir = Files.createDirectories(home.resolve(".claude"))
        Files.writeString(dir.resolve("settings.json"), """{"hooks": $hooks}""")
    }

    private fun hook(event: String, command: String, matcher: String? = null) =
        """{"$event": [{${matcher?.let { "\"matcher\": \"$it\", " } ?: ""}"hooks": [{"type": "command", "command": ${JsonPrimitive(command)}}]}]}"""

    /** [inputTokens]：回复里报的输入用量。要跨过摘要线的大数，或避免触发自动压缩的小数 */
    private fun answer(
        vararg parts: Part,
        stop: StopReason = StopReason.EndTurn,
        inputTokens: Long = 20_000,
    ): (ModelRequest) -> Flow<ModelStreamEvent> = { _ ->
        flowOf(
            ModelStreamEvent.Started(null),
            ModelStreamEvent.Completed(ModelMessage(Role.Assistant, parts.toList()), Usage(inputTokens = inputTokens, outputTokens = 10), stop, false),
        )
    }

    private fun failing(error: Throwable): (ModelRequest) -> Flow<ModelStreamEvent> = { _ -> flow { throw error } }

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

    /** 只把入参转给内核端口的 Agent 工具（照 SubagentTest 的写法；前台等报告，顺序确定） */
    private val agentTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "委派子 agent"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "委派")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            fun str(key: String) = (input[key] as? JsonPrimitive)?.content
            val request = DelegateRequest(
                description = str("description") ?: "任务",
                prompt = str("prompt")!!,
                agentType = str("subagent_type"),
                background = (input["run_in_background"] as? JsonPrimitive)?.content != "false",
            )
            val outcome = when (val result = ctx.agents.delegate(request)) {
                is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${result.child.value}")))
                is DelegateResult.Finished -> ToolResult(listOf(TextPart(result.report)), isError = result.isError)
                is DelegateResult.Rejected -> ToolResult(listOf(TextPart(result.messageForModel)), isError = true)
            }
            emit(ToolProgress.Done(outcome))
        }
    }

    private class RoutingModel(private val route: (ModelRequest) -> ((ModelRequest) -> Flow<ModelStreamEvent>)) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.isSummary() =
        messages.lastOrNull()?.parts?.any { it is TextPart && it.text.startsWith("上下文快满了") } == true

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun ModelRequest.allText(): String = messages.joinToString("\n") { m ->
        m.parts.joinToString("\n") { p -> (p as? TextPart)?.text ?: "" }
    }

    private fun bashCall(id: String, command: String) = ToolCallPart(CallId(id), "Bash", args("command" to command))

    private fun forkCall(id: String, prompt: String) = ToolCallPart(
        CallId(id), "Agent",
        args("subagent_type" to "fork", "prompt" to prompt, "description" to "查构建", "run_in_background" to false),
    )

    private suspend fun session(
        model: ModelClient,
        mode: PermissionMode = PermissionMode.BypassPermissions,
        tools: List<Tool<*>> = listOf(bash),
    ): Pair<SessionRuntime, FileSessionStore> {
        val store = FileSessionStore(root.resolve("sessions"), { System.currentTimeMillis() })
        val logDir = Files.createDirectories(root.resolve("logs"))
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() },
            ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ResolvedModel(small, model) },
            tools = tools,
            host = HostPorts(paths.fs, JvmSearch(paths.fs), LocalExec(paths::hostOf, logDir, paths.guestOfHost(logDir))) { FakeArtifacts() },
            platform = "test",
            zone = ZoneOffset.UTC,
            memoryHome = paths.guestOfHost(home),
            hookShellPath = { guest -> paths.guestOfHost(paths.hostOf(guest)).value },
            trustProjectHooks = { false },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope).also { hubs += it }
        return hub.create(paths.guestOfHost(work), TEST_MODEL, mode) to store
    }

    private suspend fun FileSessionStore.events(rt: SessionRuntime): List<SessionEvent> =
        open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String): SessionState {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun SessionRuntime.compactNow(instructions: String? = null): SessionState {
        val before = state.value.lastTurn?.turnId
        assertTrue(instructions?.let { compact(it) } ?: compact())
        return state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun assertLogIsTruth(store: FileSessionStore, rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    // ---- PreCompact ----

    @Test
    fun `a blocked manual compact ends the turn Completed without touching compaction state`() = run {
        settings(
            hook(
                "PreCompact", script(
                    "block-manual", """
                        cat > pc-input.json
                        grep -q '"trigger":"manual"' pc-input.json || exit 0
                        grep -q '侧重数据库' pc-input.json || exit 0
                        echo "窗口还够用，先别压缩" >&2
                        exit 2
                        """.trimIndent(),
                ), "manual",
            ),
        )
        val model = RoutingModel { answer(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("第一句")
        val done = rt.compactNow("侧重数据库")

        assertEquals("被拦不算失败，界面不给「继续」", TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue("没有压缩开始过", store.events(rt).none { it is CompactionStarted })
        assertTrue(done.compaction == null)
        assertEquals("不计入熔断", 0, done.compactionFailures)
        val note = store.events(rt).filterIsInstance<Note>().single { it.text.contains("压缩被 PreCompact hook 拦下") }
        assertTrue(note.text, note.text.contains("窗口还够用，先别压缩"))
        assertEquals("block", store.events(rt).filterIsInstance<HooksRan>().single { it.event == "PreCompact" }.decision)
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `a blocked automatic compact is skipped and noted once per turn`() = run {
        settings(hook("PreCompact", script("block-auto", """cat > /dev/null; echo "自动压缩已停用" >&2; exit 2"""), "auto"))
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("不该出现的摘要")) // 哨兵：被拦时不会走到
                req.messages.size == 3 -> answer(bashCall("c1", "echo hi"), stop = StopReason.ToolUse)
                else -> answer(TextPart("好"))
            }
        }
        val (rt, store) = session(model)
        rt.say("第一句") // 先有一个完成的回合，第二个回合的大输入才有合法的摘要边界
        val done = rt.say("x".repeat(120_000))

        assertEquals("请求照发，工具照跑", listOf("echo hi"), ran)
        assertEquals("第一回合一次 + 大回合两次（工具前后各一）", 3, model.requests.size)
        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertTrue("没有压缩发生", store.events(rt).none { it is ContextCompacted })
        assertEquals(
            "PreCompact 每次请求前都跑",
            2,
            store.events(rt).filterIsInstance<HooksRan>().count { it.event == "PreCompact" },
        )
        assertEquals(
            "同一回合只提示一次",
            1,
            store.events(rt).filterIsInstance<Note>().count { it.text.contains("自动压缩被 PreCompact hook 拦下：自动压缩已停用") },
        )
        assertEquals(0, done.compactionFailures)
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `a blocked overflow recovery fails the turn with the original overflow error`() = run {
        settings(hook("PreCompact", script("block-auto2", """cat > /dev/null; echo "压缩被策略停用" >&2; exit 2"""), "auto"))
        val overflow = ModelException(ModelFailureKind.ContextOverflow, "prompt is too long", httpStatus = 400, beforeFirstByte = true)
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("不该出现的摘要"))
                req.lastText() == "第一句" -> answer(TextPart("好"))
                else -> failing(overflow)
            }
        }
        val (rt, store) = session(model)
        rt.say("第一句")
        val done = rt.say("第二句")

        val outcome = done.lastTurn!!.outcome as TurnOutcome.Failed
        assertEquals(ModelFailureKind.ContextOverflow, outcome.kind)
        assertTrue("原来的超长错误收尾", outcome.message!!.contains("上下文超长，压缩也没能腾出空间"))
        assertTrue(
            "Note 说明是被 hook 拦的",
            store.events(rt).filterIsInstance<Note>().any { it.text.contains("压缩被 PreCompact hook 拦下：压缩被策略停用") },
        )
        assertTrue(store.events(rt).none { it is ContextCompacted })
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `PreCompact continue false is discarded and compaction proceeds`() = run {
        settings(hook("PreCompact", script("no-stop", """cat > /dev/null; printf '%s' '{"continue":false,"stopReason":"不该生效"}'"""), "manual"))
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("## 目标\n继续。")) else answer(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("第一句")
        val done = rt.compactNow()

        assertEquals(TurnOutcome.Completed, done.lastTurn!!.outcome)
        assertEquals("continue 被丢弃，压缩照常发生", 1, store.events(rt).filterIsInstance<ContextCompacted>().size)
        assertTrue(done.compaction != null)
        assertLogIsTruth(store, rt)
    }

    // ---- PostCompact ----

    @Test
    fun `PostCompact receives the trigger and the compact summary`() = run {
        settings(hook("PostCompact", script("after-compact", "cat > postcompact-stdin.json"), "manual"))
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("## 目标\n把迁移做完。")) else answer(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("第一句")
        rt.compactNow()

        val stdin = Files.readString(work.resolve("postcompact-stdin.json"), Charsets.UTF_8)
        assertTrue(stdin, stdin.contains("\"trigger\":\"manual\""))
        assertTrue(stdin, stdin.contains("\"compact_summary\":\"[较早的对话已压缩"))
        assertTrue(stdin, stdin.contains("把迁移做完。"))
        assertLogIsTruth(store, rt)
    }

    // ---- SessionStart(compact) ----

    @Test
    fun `SessionStart compact context rides the summary into the next request`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"压缩后请先重读 docs/CHANGE.md"}}"""
        settings(hook("SessionStart", script("compact-ctx", "cat > /dev/null; printf '%s' '$ctx'"), "compact"))
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("## 目标\n继续。")) else answer(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("第一句")
        assertFalse(
            "matcher 对 compact 才响，startup 不跑",
            model.requests.single().messages.single().parts.any { (it as? TextPart)?.text?.contains("重读 docs") == true },
        )
        rt.compactNow()
        rt.say("第二句")

        val first = model.requests.last().messages.first()
        assertEquals("摘要 + 挂靠的提醒", 2, first.parts.size)
        assertTrue((first.parts[0] as TextPart).text.startsWith("[较早的对话已压缩"))
        val reminder = (first.parts[1] as TextPart).text
        assertTrue(reminder, reminder.contains("<system-reminder>") && reminder.contains("压缩后请先重读 docs/CHANGE.md"))
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `a second compaction replaces the old summary together with its contexts`() = run {
        settings(
            hook(
                "SessionStart", script(
                    "counting-ctx", """
                        if [ -f compact-marker-second ]; then
                          printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"第二代压缩提醒 BBB"}}'
                        else
                          touch compact-marker-second
                          printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"第一代压缩提醒 AAA"}}'
                        fi
                        """.trimIndent(),
                ), "compact",
            ),
        )
        val model = RoutingModel { req -> if (req.isSummary()) answer(TextPart("## 目标\n继续。")) else answer(TextPart("好")) }
        val (rt, store) = session(model)
        rt.say("第一句")
        rt.compactNow()
        rt.say("第二句")
        rt.compactNow()
        rt.say("第三句")

        val first = (model.requests.last().messages.first().parts[0] as TextPart).text
        assertTrue(first, first.contains("包含上一次压缩的摘要"))
        val whole = model.requests.last().allText()
        assertTrue(whole.contains("第二代压缩提醒 BBB"))
        assertFalse("旧摘要连同它挂的上下文一起被取代", whole.contains("第一代压缩提醒 AAA"))
        val compactions = store.events(rt).filterIsInstance<ContextCompacted>()
        assertEquals(2, compactions.size)
        assertEquals("视图里只剩最新一次压缩挂的上下文", setOf(compactions.last().compactionId), rt.state.value.compactionContexts.keys)
        assertLogIsTruth(store, rt)
    }

    // ---- 分身继承 ----

    @Test
    fun `a fork of a compacted parent keeps the byte-equal prefix with the compaction reminder`() = run {
        val ctx = """{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"压缩后提醒：读 CHANGE 再动手"}}"""
        settings(hook("SessionStart", script("fork-ctx", "cat > /dev/null; printf '%s' '$ctx'"), "compact"))
        val model = RoutingModel { req ->
            when {
                req.isSummary() -> answer(TextPart("## 目标\n继续。"))
                "You are a fork" in req.lastText() -> answer(TextPart("分身完成"), inputTokens = 100)
                req.lastText() == "去分身" -> answer(forkCall("c9", "查构建为什么慢"), stop = StopReason.ToolUse, inputTokens = 100)
                else -> answer(TextPart("好"), inputTokens = 100)
            }
        }
        val (rt, store) = session(model, tools = listOf(bash, agentTool))
        rt.say("第一句")
        rt.compactNow()
        rt.say("去分身")

        val forkRequest = model.requests.single { "You are a fork" in it.lastText() }
        val parentAfterTool = model.requests.last { "分身完成" in it.lastText() && it !== forkRequest }
        assertEquals(
            "分身第一请求与父会话收到报告后的请求共享到委派消息为止的前缀（含摘要与挂靠提醒）",
            parentAfterTool.messages.dropLast(1),
            forkRequest.messages.dropLast(1),
        )
        val summaryAndReminder = forkRequest.messages.first()
        assertTrue((summaryAndReminder.parts[0] as TextPart).text.startsWith("[较早的对话已压缩"))
        assertTrue((summaryAndReminder.parts[1] as TextPart).text.contains("压缩后提醒：读 CHANGE 再动手"))
        assertFalse("被摘要掉的原始对话没有回放进分身", forkRequest.allText().contains("第一句"))

        val fork = rt.state.first { it.children.isNotEmpty() }.children.keys.first()
        val forkRuntime = hubs.single().get(fork)!!
        assertTrue(forkRuntime.state.value.compaction != null)
        // 视图之外还要钉住日志本身：被摘要掉的原始对话不进分身历史（省日志，也免得挂靠校验踩到不存在的消息）
        assertEquals(
            "被摘要掉的原始对话不进分身日志",
            listOf("去分身"),
            forkRuntime.state.value.history.filterIsInstance<HistoryEntry.User>()
                .map { e -> (e.parts.single() as TextPart).text },
        )
        assertEquals(
            "继承的压缩视图带着挂靠上下文",
            listOf("压缩后提醒：读 CHANGE 再动手"),
            forkRuntime.state.value.compactionContexts[forkRuntime.state.value.compaction!!.compactionId],
        )
        assertLogIsTruth(store, rt)
        assertLogIsTruth(store, forkRuntime)
    }
}
