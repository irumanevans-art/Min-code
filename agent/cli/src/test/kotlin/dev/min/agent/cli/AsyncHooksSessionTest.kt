package dev.min.agent.cli

import dev.min.agent.testkit.*
import dev.min.agent.host.JvmSearch
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.KernelDeps
import dev.min.agent.kernel.ResolvedModel
import dev.min.agent.kernel.SessionHub
import dev.min.agent.kernel.SessionRuntime
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
import dev.min.agent.spi.CommandAccess
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HooksRan
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.Note
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
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
 * 后台 hooks 接进会话（docs/agent/detail/hooks设计.md §async）：`async` 不挡回合、输出在下一个回合送达模型；
 * `asyncRewake` 退出码 2 唤醒空闲会话；会话卸下杀掉后台进程。真文件系统 + 真 shell（Git Bash 把脚本甩进后台），
 * 模型是按内容路由的脚本。
 */
class AsyncHooksSessionTest {
    private val root: Path = Files.createTempDirectory("min async hooks 会话")
    private val work: Path = Files.createDirectories(root.resolve("work"))
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val paths = DesktopPaths(work, root.resolve("scratch"))
    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        root.toFile().deleteRecursively()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private fun shellPath(p: Path) = paths.guestOfHost(p).value

    private fun script(name: String, body: String): String {
        val file = Files.createDirectories(home.resolve(".claude/hooks")).resolve("$name.sh")
        Files.writeString(file, "#!/bin/bash\n$body\n")
        return "bash '${shellPath(file)}'"
    }

    private fun settings(hooks: String) {
        val dir = Files.createDirectories(home.resolve(".claude"))
        Files.writeString(dir.resolve("settings.json"), """{"hooks": $hooks}""")
    }

    /** 同 HooksSessionTest 的 hook()，多一个插进处理器对象的额外字段（async / asyncRewake / timeout） */
    private fun hook(event: String, command: String, matcher: String? = null, extra: String = "") =
        """{"$event": [{${matcher?.let { "\"matcher\": \"$it\", " } ?: ""}"hooks": [{"type": "command", "command": ${JsonPrimitive(command)}$extra}]}]}"""

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
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("ran: ${(input["command"] as JsonPrimitive).content}")))))
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

    private suspend fun session(model: ModelClient, mode: PermissionMode = PermissionMode.BypassPermissions): Triple<SessionHub, SessionRuntime, FileSessionStore> {
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
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps, scope)
        return Triple(hub, hub.create(paths.guestOfHost(work), TEST_MODEL, mode), store)
    }

    private suspend fun FileSessionStore.events(rt: SessionRuntime): List<SessionEvent> = open(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u-${System.nanoTime()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    /** 等后台 hook 完成落盘（HooksRan.background 计数到 n） */
    private suspend fun awaitBackground(store: FileSessionStore, rt: SessionRuntime, n: Int) {
        while (true) {
            val count = store.events(rt).count { (it as? HooksRan)?.background == true }
            if (count >= n) return
            delay(50)
        }
    }

    // ---- async：不挡回合、输出下一回合送达、空闲时等人 ----

    @Test
    fun `async hook 不挡回合，additionalContext 与 systemMessage 在下一个回合才到模型`() = run {
        settings(
            hook(
                "PostToolUse",
                script("after-tests", """
                    cat > /dev/null
                    sleep 2
                    printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"测试都过了"},"systemMessage":"给模型看的说明"}'
                """),
                "Bash",
                extra = ",\"async\":true",
            ),
        )
        val model = RoutingModel { req ->
            when {
                req.lastText().contains("跑") -> reply(bashCall("c1", "make check"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val (_, rt, store) = session(model)
        rt.say("跑测试")
        // 回合已结束而 hook 还在睡：它没挡回合，输出也还没进上下文
        assertEquals("回合结束了，2 秒的 hook 还在跑", 0, store.events(rt).count { (it as? HooksRan)?.background == true })
        assertTrue("空闲会话没有因为 hook 在跑而开着回合", rt.state.value.turn == null)
        awaitBackground(store, rt, 1)
        // 输出只挂成「等下一条人发的消息」，不会自己开回合
        delay(1_000)
        assertTrue(rt.state.value.lastTurn?.trigger == TurnTrigger.User)
        assertTrue(rt.state.value.turn == null)
        // 下一条人发的消息把两段输出都带给模型（systemMessage 在 async 路径同样给模型，不给人看）
        rt.say("结果如何")
        awaitBackground(store, rt, 1)
        val secondAsk = model.requests.first { it.messages.last().let { m -> m.parts.any { (it as? TextPart)?.text?.contains("结果如何") == true } } }
        val plain = secondAsk.messages.joinToString("\n") { m -> m.parts.filterIsInstance<TextPart>().joinToString("") { it.text } }
        assertTrue("additionalContext 送到了模型：$plain", plain.contains("测试都过了"))
        assertTrue("systemMessage 送到了模型：$plain", plain.contains("给模型看的说明"))
        // 日志即真相
        assertEquals(
            rt.state.value,
            dev.min.agent.kernel.fold(store.peek(rt.sessionId)!!.header, store.peek(rt.sessionId)!!.read().toList()),
        )
    }

    @Test
    fun `async hook 的控制字段无效并在 problems 里说明`() = run {
        settings(
            hook(
                "PreToolUse",
                script("late-block", """
                    cat > /dev/null
                    sleep 2
                    printf '%s' '{"decision":"block","reason":"太晚了"}'
                """),
                "Bash",
                extra = ",\"async\":true",
            ),
        )
        val model = RoutingModel { req ->
            when {
                req.lastText().contains("跑") -> reply(bashCall("c1", "echo hi"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        // 调用没有被等、也没有被拦：工具正常执行
        assertTrue(rt.state.value.toolResults.getValue(CallId("c1")).content.isNotEmpty())
        awaitBackground(store, rt, 1)
        val ran = store.events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertEquals("neutral", ran.decision)
        assertTrue("problems 说明控制字段被忽略：${ran.problems}", ran.problems.any { it.contains("控制字段无效") })
        assertTrue("拦不住已经发生过的动作", rt.state.value.toolResults.getValue(CallId("c1")).isError.not())
    }

    // ---- asyncRewake ----

    @Test
    fun `asyncRewake 退出码 2 唤醒空闲会话，stderr 作为系统提醒开新回合`() = run {
        settings(
            hook(
                "PostToolUse",
                script("watch", """
                    cat > /dev/null
                    sleep 1
                    echo 部署脚本挂了 >&2
                    exit 2
                """),
                "Bash",
                extra = ",\"asyncRewake\":true",
            ),
        )
        val model = RoutingModel { req ->
            when {
                // 全等：唤醒输入的文本里也带着「部署」二字，不能 contains
                req.lastText() == "部署" -> reply(bashCall("c1", "deploy.sh"), stop = StopReason.ToolUse)
                else -> reply(TextPart("我去修"))
            }
        }
        val (_, rt, store) = session(model)
        rt.say("部署")
        awaitBackground(store, rt, 1)
        // 空闲会话被唤醒：新回合的触发是 hook，模型看到的是 hook 的 stderr
        rt.state.first { it.lastTurn?.trigger == TurnTrigger.Hook }
        assertEquals(TurnTrigger.Hook, rt.state.value.lastTurn?.trigger)
        // 模型的唤醒请求紧随 TurnStarted 落盘，稍等它出现
        while (model.requests.none { r -> r.messages.last().parts.any { (it as? TextPart)?.text?.contains("部署脚本挂了") == true } }) delay(50)
        val woken = model.requests.last()
        assertTrue("唤醒的输入带着 stderr：${woken.lastText()}", woken.lastText().contains("部署脚本挂了"))
        val wake = store.events(rt).filterIsInstance<InputReceived>().single { it.origin is InputOrigin.Hook }
        assertTrue((wake.origin as InputOrigin.Hook).event == "async_rewake")
        // 日志即真相
        assertEquals(
            rt.state.value,
            dev.min.agent.kernel.fold(store.peek(rt.sessionId)!!.header, store.peek(rt.sessionId)!!.read().toList()),
        )
    }

    @Test
    fun `asyncRewake 退出码 0 不唤醒`() = run {
        settings(
            hook(
                "PostToolUse",
                script("calm", """
                    cat > /dev/null
                    sleep 1
                    printf '%s' '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"跑完了，一切正常"}}'
                """),
                "Bash",
                extra = ",\"asyncRewake\":true",
            ),
        )
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1", "make"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        awaitBackground(store, rt, 1)
        delay(1_500)
        assertEquals("退出码 0 不唤醒：还是原来那个回合", TurnTrigger.User, rt.state.value.lastTurn?.trigger)
        assertTrue(rt.state.value.turn == null)
        // 但上下文照样挂上，等下一条人发的消息
        assertTrue(rt.state.value.pendingHookContexts.isNotEmpty())
    }

    @Test
    fun `asyncRewake 超时被杀且不唤醒`() = run {
        settings(
            hook(
                "PostToolUse",
                script("slow-watch", """
                    cat > /dev/null
                    sleep 20
                """),
                "Bash",
                extra = ",\"asyncRewake\":true,\"timeout\":1",
            ),
        )
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1", "watch"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        awaitBackground(store, rt, 1)
        val ran = store.events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertTrue("超时记进了 problems：${ran.problems}", ran.problems.any { it.contains("超时") })
        delay(1_500)
        assertEquals("超时不唤醒", TurnTrigger.User, rt.state.value.lastTurn?.trigger)
    }

    @Test
    fun `脚本缺失的 asyncRewake 只报一次且不唤醒`() = run {
        settings(hook("PostToolUse", "bash '/nonexistent/watch.sh'", "Bash", extra = ",\"asyncRewake\":true"))
        val model = RoutingModel { req ->
            if (req.lastText().startsWith("ran:")) reply(TextPart("好"))
            else reply(bashCall("c${req.messages.size}", "watch"), stop = StopReason.ToolUse)
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        rt.say("再跑")
        awaitBackground(store, rt, 2)
        val notes = store.events(rt).filterIsInstance<Note>().filter { it.text.contains("没能运行") }
        assertEquals("起不来的 hook 只报一次", 1, notes.size)
        delay(1_000)
        assertEquals("脚本缺失不唤醒", TurnTrigger.User, rt.state.value.lastTurn?.trigger)
    }

    // ---- 关闭杀进程 ----

    /** 用 `bash -lc 'kill -0 <pid>'` 探 marker 里记的进程还活着吗 */
    private fun aliveByMarker(marker: Path): Boolean =
        ProcessBuilder("bash", "-lc", "if kill -0 \$(cat '${shellPath(marker)}') 2>/dev/null; then echo alive; else echo dead; fi")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim() == "alive"

    @Test
    fun `关闭会话杀掉还在跑的后台 hook 进程`() = run {
        val marker = work.resolve("hookpid")
        settings(
            hook(
                "PostToolUse",
                script("long", """
                    cat > /dev/null
                    echo ${'$'}${'$'} > '${shellPath(marker)}'
                    sleep 30
                """),
                "Bash",
                extra = ",\"async\":true",
            ),
        )
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1", "start"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (hub, rt, _) = session(model)
        rt.say("跑")
        // hook 起来了（pid 文件出现）再关；先确认它真的活着——否则「杀掉了」断言是空的
        while (!Files.exists(marker)) delay(50)
        assertEquals("关闭前后台 hook 进程活着（否则下面的断言不成立）", true, aliveByMarker(marker))
        assertTrue(hub.shutdown(rt.sessionId))
        // shutdown 返回时进程应已收尾（cancelAll join 了每个协程的 NonCancellable 清理）
        assertEquals("后台 hook 进程被杀掉", false, aliveByMarker(marker))
    }

    @Test
    fun `进程被外部杀死而退出码没写出时轮询有兜底，不无限等`() = run {
        settings(hook("PostToolUse", "sh", "Bash", extra = ",\"args\":[\"-c\",\"kill -9 \$PPID; sleep 2\"],\"async\":true"))
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1", "start"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        // hook 的 sh 杀了自己的父脚本（写退出码的那位）：code 永远不出现，轮询兜底在存活探测后收尾
        awaitBackground(store, rt, 1)
        val ran = store.events(rt).filterIsInstance<HooksRan>().single { it.background }
        assertTrue("落了说明：${ran.problems}", ran.problems.any { it.contains("没写出退出码") })
        delay(1_000)
        assertEquals("兜底收尾不唤醒", TurnTrigger.User, rt.state.value.lastTurn?.trigger)
    }

    // ---- 输出限长（与同步路径同一把尺） ----

    @Test
    fun `asyncRewake 的唤醒文本超长时存附件给预览`() = run {
        settings(
            hook(
                "PostToolUse",
                script("noisy", """
                    cat > /dev/null
                    sleep 1
                    head -c 30000 /dev/zero | tr '\0' 'x' >&2
                    exit 2
                """),
                "Bash",
                extra = ",\"asyncRewake\":true",
            ),
        )
        val model = RoutingModel { req ->
            when {
                req.lastText() == "跑" -> reply(bashCall("c1", "watch"), stop = StopReason.ToolUse)
                else -> reply(TextPart("我去修"))
            }
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        awaitBackground(store, rt, 1)
        rt.state.first { it.lastTurn?.trigger == TurnTrigger.Hook }
        while (model.requests.none { r -> r.messages.last().parts.any { (it as? TextPart)?.text?.contains("需要你处理") == true } }) delay(50)
        val woken = model.requests.last()
        val wake = woken.lastText()
        assertTrue("超长输出走了附件：$wake", wake.contains("hook 输出共 30000 字"))
        assertTrue("进上下文的是预览不是全文：${wake.length}", wake.length < 3_000)
    }

    // ---- 分流说明不进 problems（M2） ----

    @Test
    fun `同步与后台混批时分流说明不落 Warning`() = run {
        val quick = script("quick", "cat > /dev/null")
        val slow = script("slow", "cat > /dev/null\nsleep 1")
        settings(
            """
            {"PostToolUse": [
              {"matcher": "Bash", "hooks": [
                {"type": "command", "command": ${JsonPrimitive(quick)}},
                {"type": "command", "command": ${JsonPrimitive(slow)}, "async": true}
              ]}
            ]}
            """.trimIndent(),
        )
        val model = RoutingModel { req ->
            if (req.lastText().contains("跑")) reply(bashCall("c1", "echo hi"), stop = StopReason.ToolUse) else reply(TextPart("好"))
        }
        val (_, rt, store) = session(model)
        rt.say("跑")
        awaitBackground(store, rt, 1)
        val notes = store.events(rt).filterIsInstance<Note>()
        assertTrue(
            "「谁走哪条通道」的分流说明不该刷 Warning：$notes",
            notes.none { it.text.contains("后台执行器") || it.text.contains("不在本批") },
        )
    }
}
