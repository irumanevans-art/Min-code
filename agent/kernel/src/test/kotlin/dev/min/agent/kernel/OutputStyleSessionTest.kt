package dev.min.agent.kernel

import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.reply
import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Note
import dev.min.agent.spi.OutputStyleLoaded
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.AgentTool
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * output styles 接进会话：会话创建时按 `outputStyle` 读一次、快照冻结进日志（[OutputStyleLoaded]），
 * 系统提示只由快照拼（改设置只对新会话生效）；分身继承快照（前缀与父会话逐字相同），普通子 agent 不用样式。
 */
class OutputStyleSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val ids = AtomicLong(0)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private val HOME = GuestPath("/home/user")

    private fun deps(model: ModelClient, fs: FakeFs): KernelDeps = KernelDeps(
        clock = { 1_000 },
        ids = { prefix -> "${prefix}_${ids.incrementAndGet()}" },
        models = { ResolvedModel(TEST_PROFILE, model) },
        tools = listOf(dev.min.agent.tools.AgentTool),
        host = HostPorts(fs, FakeSearch(), FakeExec(), { FakeArtifacts() }),
        platform = "test",
        zone = ZoneOffset.UTC,
        memoryHome = HOME,
    )

    /** settings.local.json 指定样式 + 用户目录放一份样式文件 */
    private fun FakeFs.withSettings(style: String?, body: String = "按这个风格来。"): FakeFs {
        if (style != null) put("/workspace/.claude/settings.local.json", """{"outputStyle": "$style"}""")
        if (style != null && style !in setOf("Default", "Explanatory", "Learning")) {
            put("/home/user/.claude/output-styles/$style.md", "---\nname: $style\n---\n$body")
        }
        return this
    }

    private suspend fun session(model: ModelClient, fs: FakeFs): SessionRuntime {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, SessionId("s_${ids.incrementAndGet()}"), 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions),
            deps(model, fs), scope,
        )
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> =
        store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${ids.incrementAndGet()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    @Test
    fun `a style from settings reaches the request and freezes for the session`() = run {
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val fs = FakeFs().withSettings("Terse")
        val rt = session(model, fs)
        rt.say("干活")
        val first = model.requests.single()
        assertTrue(first.system.joinToString("\n").contains("# Output style: Terse"))
        assertTrue(first.system.joinToString("\n").contains("按这个风格来。"))
        assertFalse("自定义样式默认不带工程指令", first.system[0].contains("Read a file before you edit it"))
        assertEquals("快照进了日志", "Terse", events(rt).filterIsInstance<OutputStyleLoaded>().single().name)

        // 会话中途改设置（磁盘上的 settings / 样式文件都换掉）：本会话的请求不变（提示缓存的前缀纪律）
        fs.put("/workspace/.claude/settings.local.json", """{"outputStyle": "Learning"}""")
        fs.put("/home/user/.claude/output-styles/Terse.md", "---\nname: Terse\n---\n改成别的正文")
        rt.say("再来")
        assertEquals("第二个请求与第一个逐字相同", model.requests[0].system, model.requests[1].system)
    }

    @Test
    fun `Default and unset write no event and keep the prompt unchanged`() = run {
        val plain = RoutingModel { req -> reply(TextPart("好")) }
        val rt = session(plain, FakeFs())
        rt.say("干活")
        assertTrue(events(rt).none { it is OutputStyleLoaded })
        assertFalse(model_hasStyle(plain))

        val explicit = RoutingModel { req -> reply(TextPart("好")) }
        val fs = FakeFs().withSettings("Default")
        val rt2 = session(explicit, fs)
        rt2.say("干活")
        assertTrue("Default 不写事件", events(rt2).none { it is OutputStyleLoaded })
        assertFalse(model_hasStyle(explicit))
    }

    private fun model_hasStyle(model: RoutingModel): Boolean =
        model.requests.single().system.any { it.contains("# Output style:") }

    @Test
    fun `a missing style falls back to Default with a note`() = run {
        val model = RoutingModel { req -> reply(TextPart("好")) }
        val fs = FakeFs().apply { put("/workspace/.claude/settings.local.json", """{"outputStyle": "ghost"}""") }
        val rt = session(model, fs)
        rt.say("干活")
        assertFalse(model_hasStyle(model))
        val note = events(rt).filterIsInstance<Note>().single()
        assertTrue(note.text, note.text.contains("ghost") && note.text.contains("Default"))
        assertTrue(events(rt).none { it is OutputStyleLoaded })
    }

    @Test
    fun `a fork inherits the snapshot so its prefix matches the parent exactly`() = run {
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "分身一个") {
                reply(
                    ToolCallPart(
                        CallId("c1"), "Agent",
                        dev.min.agent.testkit.args("description" to "接着干", "prompt" to "把剩下的做完", "subagent_type" to "fork", "run_in_background" to false),
                    ),
                    stop = StopReason.ToolUse,
                )
            } else reply(TextPart("好"))
        }
        val fs = FakeFs().withSettings("Terse")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps(model, fs), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("分身一个")
        rt.state.first { it.turn == null }
        val child = rt.state.value.children.keys.single()
        assertEquals("分身日志里有同一条样式快照", "Terse", store.peek(child)!!.read().toList().map { it.event }.filterIsInstance<OutputStyleLoaded>().single().name)
        val parentRequest = model.requests.first()
        // 分身指令在工具结果消息里（那条 Agent 调用的结果换成了指令正文）
        fun hasForkDirective(req: ModelRequest) = req.messages.any { m ->
            m.parts.any { p ->
                (p as? TextPart)?.text?.contains("You are a fork") == true ||
                    (p as? dev.min.agent.protocol.ToolResultPart)?.content?.any { c -> (c as? TextPart)?.text?.contains("You are a fork") == true } == true
            }
        }
        val forkRequest = model.requests.first { it !== parentRequest && hasForkDirective(it) }
        assertEquals("分身首请求的系统提示与父会话逐字相同", parentRequest.system, forkRequest.system)
    }

    @Test
    fun `a normal subagent does not use the style`() = run {
        val model = RoutingModel { req ->
            val last = req.messages.last().parts.filterIsInstance<TextPart>().joinToString("") { it.text }
            if (last == "委派一个") {
                reply(
                    ToolCallPart(
                        CallId("c1"), "Agent",
                        dev.min.agent.testkit.args("description" to "看看", "prompt" to "去看看", "subagent_type" to "Explore"),
                    ),
                    stop = StopReason.ToolUse,
                )
            } else reply(TextPart("好"))
        }
        val fs = FakeFs().withSettings("Terse")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val hub = SessionHub(store, deps(model, fs), scope)
        val rt = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.say("委派一个")
        val child = rt.state.value.children.keys.single()
        rt.state.first { s -> s.children.getValue(child).reportedTurns.isNotEmpty() && s.turn == null }
        val childRequest = model.requests.first { req ->
            req.messages.first().parts.filterIsInstance<TextPart>().joinToString("") { it.text } == "去看看"
        }
        assertTrue(
            "普通子 agent 的请求里没有样式段（它有自己的系统提示）",
            childRequest.system.none { it.contains("# Output style:") },
        )
        val childEvents = store.peek(child)!!.read().toList().map { it.event }
        assertTrue("子会话日志里没有样式快照", childEvents.none { it is OutputStyleLoaded })
    }
}
