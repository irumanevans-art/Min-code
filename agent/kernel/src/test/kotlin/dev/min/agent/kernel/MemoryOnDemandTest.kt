package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.tools.ReadTool
import dev.min.agent.spi.AccessKind
import dev.min.agent.spi.CompactionStarted
import dev.min.agent.spi.CompactionTrigger
import dev.min.agent.spi.ContextCompacted
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryAttached
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.PathAccess
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 按需加载（docs/agent/detail/记忆按需加载与自动记忆设计.md §1）在会话里的端到端样子：
 * 真日志、真的 Read 工具、内存文件系统。折叠与模型视图的细则在 MemoryAttachFoldTest，这里钉行为。
 */
class MemoryOnDemandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val fs = FakeFs()

    /** 换掉会话用的文件系统（默认就是 [fs]）：想让某个操作出意外时用 */
    private var fsOverride: dev.min.agent.spi.WorkspaceFs? = null
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private var counter = 0

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun readCall(id: String, path: String) = ToolCallPart(CallId(id), "Read", args("file_path" to path))

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        KernelDeps(
            clock = base.clock, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(fsOverride ?: fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = base.platform, zone = base.zone, retry = base.retry,
            memoryHome = GuestPath("/root"),
        )
    }

    private suspend fun session(model: ModelClient, tools: List<Tool<*>> = listOf(ReadTool)) =
        SessionRuntime.create(
            store, header, SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default),
            deps(model, tools), scope,
        )

    private suspend fun events(): List<SessionEvent> = store.open(SessionId("s1"))!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    private fun msg() = ClientMsgId("u${++counter}")

    /** 一条请求里的全部文字（工具结果也算）：数 system-reminder 的出现次数用 */
    private fun ModelRequest.plainText(): String = messages.joinToString("\n") { m ->
        m.parts.joinToString("\n") { p ->
            when (p) {
                is TextPart -> p.text
                is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
                else -> ""
            }
        }
    }

    /** 会话创建时的说明：带 paths 的规则在启动时跳过并说明 */
    private suspend fun startupNote() = events().filterIsInstance<MemoryLoaded>().single().notes

    @Test
    fun `a rule with an invalid glob does not fail the turn, and is noted once across reads`() = run {
        fs.put("/workspace/.claude/rules/bad.md", "---\npaths:\n  - \"app/**/[[...slug]]/page.tsx\"\n  - \"[z-a].ts\"\n---\nBad rule.")
        fs.put("/workspace/a.ts", "a")
        fs.put("/workspace/b.ts", "b")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/a.ts"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/b.ts"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读两个文件")))
        rt.awaitIdle()

        assertEquals(TurnOutcome.Completed, rt.state.value.lastTurn?.outcome)
        val notes = events().filterIsInstance<MemoryAttached>().flatMap { it.notes }.filter { it.contains("无效") }
        assertEquals("同一条规则一个会话只说一次", 1, notes.size)
        assertTrue(events().filterIsInstance<MemoryAttached>().all { it.files.isEmpty() })
        assertLogIsTruth(rt)
    }

    /** list 返回的集合一遍历就炸：模拟按需加载里「读盘之外」的意外（attempt 只管 stat / list / read 这几次调用本身） */
    private class ExplodingListFs(private val inner: FakeFs, private val dir: String) : dev.min.agent.spi.WorkspaceFs by inner {
        override suspend fun list(dir: GuestPath): List<dev.min.agent.spi.DirEntry> =
            if (dir.value == this.dir) object : AbstractList<dev.min.agent.spi.DirEntry>() {
                override val size: Int get() = throw IllegalStateException("目录列表炸了")
                override fun get(index: Int): dev.min.agent.spi.DirEntry = throw IllegalStateException("目录列表炸了")
            } else inner.list(dir)
    }

    @Test
    fun `an unexpected failure inside on-demand loading is only a note, never a failed turn`() = run {
        fs.mkdir("/workspace/a/.claude/rules")
        fs.put("/workspace/a/x.ts", "x")
        fsOverride = ExplodingListFs(fs, "/workspace/a/.claude/rules")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/a/x.ts"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/a/x.ts"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读两次")))
        rt.awaitIdle()

        assertEquals("回合照常完成", TurnOutcome.Completed, rt.state.value.lastTurn?.outcome)
        assertTrue(events().filterIsInstance<dev.min.agent.spi.Note>().any { it.text.contains("按需加载记忆失败") })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a same-content sibling is not injected on a later batch just because it was skipped before`() = run {
        fs.put("/workspace/s/CLAUDE.md", "Same text")
        fs.put("/workspace/s/AGENTS.md", "Same text")
        fs.put("/workspace/s/a.ts", "a")
        fs.put("/workspace/s/b.ts", "b")
        fs.put("/workspace/s/c.ts", "c")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/s/a.ts"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/s/b.ts"), stop = StopReason.ToolUse),
            reply(readCall("c3", "/workspace/s/c.ts"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读三个文件")))
        rt.awaitIdle()

        val attached = events().filterIsInstance<MemoryAttached>()
        assertEquals("只有第一批注入，之后每批都不再有", 1, attached.size)
        assertEquals(listOf("/workspace/s/CLAUDE.md"), attached.single().files.map { it.path.value })
        assertEquals(listOf("/workspace/s/AGENTS.md"), attached.single().covered.map { it.value })
        assertTrue(rt.state.value.memoryPathsInContext.any { it.value == "/workspace/s/AGENTS.md" })
        assertEquals(1, "Contents of /workspace/s/".toRegex().findAll(model.requests.last().plainText()).count())
        assertLogIsTruth(rt)
    }

    @Test
    fun `reading a memory file itself does not inject its content a second time`() = run {
        fs.put("/workspace/s/CLAUDE.md", "Same text")
        fs.put("/workspace/s/x.ts", "x")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/s/CLAUDE.md"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/s/x.ts"), stop = StopReason.ToolUse),
            reply(TextPart("done")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读 CLAUDE.md 再读 x.ts")))
        rt.awaitIdle()

        val attached = events().filterIsInstance<MemoryAttached>()
        assertEquals("读 CLAUDE.md 本身那批只记 covered，之后读 x.ts 也不再注入它", 1, attached.size)
        assertTrue(attached.single().files.isEmpty())
        assertEquals(listOf("/workspace/s/CLAUDE.md"), attached.single().covered.map { it.value })
        assertTrue("请求里没有 Contents of（内容只在工具结果里）", "Contents of /workspace/s/CLAUDE.md" !in model.requests.last().plainText())
        assertLogIsTruth(rt)
    }

    @Test
    fun `a read in a subtree attaches nested memory after the tool results`() = run {
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        fs.put("/workspace/src/api/x.ts", "export const x = 1")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/api/x.ts"), stop = StopReason.ToolUse),
            reply(TextPart("读完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读一下 x.ts")))
        rt.awaitIdle()

        val attached = events().filterIsInstance<MemoryAttached>().single()
        assertEquals(listOf("/workspace/src/api/CLAUDE.md"), attached.files.map { it.path.value })
        assertEquals(MemorySource.Nested, attached.files.single().source)
        // 下一次请求里，工具结果之后是包成 system-reminder 的记忆
        val results = model.requests[1].messages.last().parts
        assertTrue(results[0] is ToolResultPart)
        val reminder = (results[1] as TextPart).text
        assertTrue(reminder, reminder.startsWith("<system-reminder>\nContents of /workspace/src/api/CLAUDE.md (project instructions for this directory, loaded because you accessed a file in it):"))
        assertTrue(reminder, reminder.contains("Use the typed client."))
        assertTrue(rt.state.value.memoryPathsInContext.any { it.value == "/workspace/src/api/CLAUDE.md" })
        assertLogIsTruth(rt)
    }

    @Test
    fun `reading another file in the same directory does not attach twice`() = run {
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        fs.put("/workspace/src/api/x.ts", "x")
        fs.put("/workspace/src/api/y.ts", "y")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/api/x.ts"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/src/api/y.ts"), stop = StopReason.ToolUse),
            reply(TextPart("都读完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("把这两个文件读了")))
        rt.awaitIdle()

        assertEquals("同一路径只注入一次", 1, events().count { it is MemoryAttached })
        // 第二条工具结果消息上没有再挂记忆；整份请求里该记忆只出现一次（第一次那条）
        assertTrue(model.requests[2].messages.last().parts.filterIsInstance<TextPart>().none { "Contents of" in it.text })
        assertEquals(1, "Contents of /workspace/src/api/CLAUDE.md".toRegex().findAll(model.requests[2].plainText()).count())
        assertLogIsTruth(rt)
    }

    @Test
    fun `reads outside cwd do not trigger discovery`() = run {
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        fs.put("/outside/f.ts", "code")
        val model = ScriptedModel(
            reply(readCall("c1", "/outside/f.ts"), stop = StopReason.ToolUse),
            reply(TextPart("读完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读 /outside/f.ts")))
        rt.awaitIdle()
        assertTrue(events().none { it is MemoryAttached })
        assertLogIsTruth(rt)
    }

    @Test
    fun `failed calls do not trigger discovery`() = run {
        // 记忆文件在，但读的文件不存在：调用失败，不算「碰到」
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/api/missing.ts"), stop = StopReason.ToolUse),
            reply(TextPart("好吧")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读 missing.ts")))
        rt.awaitIdle()
        assertTrue(events().none { it is MemoryAttached })
        assertLogIsTruth(rt)
    }

    @Test
    fun `directories do not trigger discovery`() = run {
        // Glob / Grep 的搜索根是目录：不算碰到（设计 §1.1）
        fs.put("/workspace/src/api/x.ts", "code")
        val model = ScriptedModel(
            reply(ToolCallPart(CallId("c1"), "Glob", args("path" to "/workspace/src/api")), stop = StopReason.ToolUse),
            reply(TextPart("没有命中")),
        )
        val rt = session(model, tools = listOf(ReadTool, globStub))
        rt.send(msg(), listOf(TextPart("列一下 api")))
        rt.awaitIdle()
        assertTrue(events().none { it is MemoryAttached })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a path rule is attached when a matching file is read`() = run {
        fs.put("/workspace/.claude/rules/db.md", "---\npaths:\n  - \"src/**/*.ts\"\n---\nDB rules.")
        fs.put("/workspace/src/a.ts", "code")
        fs.put("/workspace/README.md", "readme")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/a.ts"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/README.md"), stop = StopReason.ToolUse),
            reply(TextPart("看完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("看下这两个")))
        rt.awaitIdle()

        // 读匹配文件 → 注入（frontmatter 已去掉）；读 README → 不注入
        val attached = events().filterIsInstance<MemoryAttached>().single()
        assertEquals("/workspace/.claude/rules/db.md", attached.files.single().path.value)
        assertEquals(MemorySource.PathRule, attached.files.single().source)
        assertEquals("DB rules.", attached.files.single().content)
        assertTrue(startupNote().single().contains("db.md"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a rule in a subtree matches relative to the subtree root`() = run {
        fs.put("/workspace/src/.claude/rules/api.md", "---\npaths:\n  - \"api/**/*.kt\"\n---\nAPI rules.")
        fs.put("/workspace/src/api/f.kt", "code")
        fs.put("/workspace/src/other/g.kt", "code")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/api/f.kt"), stop = StopReason.ToolUse),
            reply(readCall("c2", "/workspace/src/other/g.kt"), stop = StopReason.ToolUse),
            reply(TextPart("看完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("看下这两个")))
        rt.awaitIdle()

        val attached = events().filterIsInstance<MemoryAttached>().single()
        assertEquals("/workspace/src/.claude/rules/api.md", attached.files.single().path.value)
        assertEquals("API rules.", attached.files.single().content)
        assertLogIsTruth(rt)
    }

    @Test
    fun `memory attached before a compaction is loaded again afterwards`() = run {
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        fs.put("/workspace/src/api/x.ts", "x")
        val model = ScriptedModel(
            reply(readCall("c1", "/workspace/src/api/x.ts"), stop = StopReason.ToolUse),
            reply(TextPart("读完了")),
            reply(TextPart("## 摘要")), // 压缩回合的摘要
            reply(readCall("c2", "/workspace/src/api/x.ts"), stop = StopReason.ToolUse),
            reply(TextPart("又读完了")),
        )
        val rt = session(model)
        rt.send(msg(), listOf(TextPart("读一下")))
        rt.awaitIdle()
        assertEquals(1, events().count { it is MemoryAttached })

        assertTrue(rt.compact())
        rt.state.first { it.lastTurn?.trigger == TurnTrigger.Compact }
        assertEquals(1, events().count { it is ContextCompacted })
        // 压缩把挂靠的消息摘要掉了：路径移出集合
        assertFalse(rt.state.value.memoryPathsInContext.any { it.value == "/workspace/src/api/CLAUDE.md" })

        rt.send(msg(), listOf(TextPart("再读一遍")))
        rt.awaitIdle()
        assertEquals("压缩后重新碰到要重新加载", 2, events().count { it is MemoryAttached })
        assertEquals(1, events().count { it is CompactionStarted })
        assertEquals(CompactionTrigger.Manual, events().filterIsInstance<CompactionStarted>().single().trigger)
        // 最后一条工具结果的消息上又出现了记忆（摘要在它之前，不含旧的那份）
        assertEquals(1, "Contents of /workspace/src/api/CLAUDE.md".toRegex().findAll(model.requests[4].plainText()).count())
        assertLogIsTruth(rt)
    }

    @Test
    fun `a fork shares the parent prefix verbatim when memory was attached`() = run {
        fs.put("/workspace/src/api/CLAUDE.md", "Use the typed client.")
        fs.put("/workspace/src/api/x.ts", "export const x = 1")
        val model = OnDemandRoutingModel { req ->
            val last = req.lastText()
            when {
                "You are a fork" in last -> reply(TextPart("分身报告"))
                "<subagent-report" in last -> reply(TextPart("收到"))
                req.messages.size == 1 -> reply(readCall("c1", "/workspace/src/api/x.ts"), stop = StopReason.ToolUse)
                "已启动" in last -> reply(TextPart("等它回来"))
                else -> reply(
                    TextPart("我分个身去查"),
                    agentCall("c2", "subagent_type" to "fork", "prompt" to "接着查", "description" to "查"),
                    stop = StopReason.ToolUse,
                )
            }
        }
        val hub = SessionHub(store, deps(model, listOf(ReadTool, agentTool)), scope)
        val parent = hub.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        parent.send(msg(), listOf(TextPart("帮我查查")))
        parent.state.first { s ->
            s.turn == null && (s.history.lastOrNull() as? HistoryEntry.Assistant)?.parts?.any { it is TextPart && it.text == "收到" } == true
        }

        val parentFirst = model.requests.first { it.messages.size == 1 }
        val parentSecond = model.requests.first { it.lastText().startsWith("已启动") }
        val fork = model.requests.first { "You are a fork" in it.lastText() }
        assertEquals("系统提示逐字相同", parentFirst.system, fork.system)
        assertEquals("工具表相同、前缀指纹相同", prefixFingerprint(parentFirst), prefixFingerprint(fork))
        // 委派那条助手消息为止的对话逐条相同——包括挂在读文件结果上的那条 system-reminder
        assertEquals(parentSecond.messages.dropLast(1), fork.messages.dropLast(1))
        // 分身的「已加载」集合随继承的记忆事件算出：同一路径不会再注入一次
        val child = hub.get(parent.state.value.children.keys.single())!!
        assertEquals(setOf(GuestPath("/workspace/src/api/CLAUDE.md")), child.state.value.memoryPathsInContext)
        assertLogIsTruth(parent)
        assertLogIsTruth(child)
    }

    /** 会话创建时的说明：带 paths 的规则在启动时跳过并说明（上面没用到的辅助放这，免得悬空） */
    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { p ->
        when (p) {
            is TextPart -> p.text
            is ToolResultPart -> p.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private fun agentCall(id: String, vararg pairs: Pair<String, Any?>) = ToolCallPart(CallId(id), "Agent", args(*pairs))

    /** 只是把入参转给端口的 Agent 工具（SubagentTest 同款） */
    private open class PortTool(override val name: String, private val body: suspend (JsonObject, ToolContext) -> ToolResult) : Tool<JsonObject> {
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 $name"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow { emit(ToolProgress.Done(body(input, ctx))) }
    }

    private val agentTool = PortTool("Agent") { input, ctx ->
        val request = DelegateRequest(
            description = (input["description"] as? JsonPrimitive)?.content ?: "任务",
            prompt = (input["prompt"] as JsonPrimitive).content,
            agentType = (input["subagent_type"] as? JsonPrimitive)?.content,
            background = true,
        )
        when (val r = ctx.agents.delegate(request)) {
            is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${r.child.value}")))
            is DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
            is DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
        }
    }

    /** 只把入参报成路径的 Glob 替身：搜索根是目录，结果永远「没有命中」 */
    private val globStub = object : Tool<JsonObject> {
        override val name = "Glob"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 Glob"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext): PermissionAsk {
            val path = GuestPath((input["path"] as JsonPrimitive).content)
            return PermissionAsk(name, "搜索 $path", paths = listOf(PathAccess(path, AccessKind.Read, ctx.fs.scopeOf(path))))
        }

        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> =
            flow { emit(ToolProgress.Done(ToolResult(listOf(TextPart("没有命中"))))) }
    }
}

/** 路由模型：按请求内容决定回什么（SubagentTest 同款，这里只给分身测试用） */
internal class OnDemandRoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
    val requests = CopyOnWriteArrayList<ModelRequest>()
    override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        requests += request
        emitAll(route(request)(request))
    }
}
