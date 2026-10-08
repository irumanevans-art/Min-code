package dev.min.agent.kernel

import dev.min.agent.testkit.FakeArtifacts
import dev.min.agent.testkit.FakeExec
import dev.min.agent.testkit.FakeFs
import dev.min.agent.testkit.FakeSearch
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.spi.Clock
import dev.min.agent.spi.IdGenerator
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
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionBranched
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 会话分支（[SessionHub.branch]，CC 的 `/branch` 与 `--fork-session`）：整份日志复制成新根会话、
 * 末尾追加 session_branched；两边各自往下聊互不影响；分支首个请求的前缀与原会话分支点逐字相同
 * （时钟由测试拨：原会话第 0 天、分支第 3 天，basis 不冻结就必然对不上）；有回合在跑拒绝；子 agent 不复制。
 */
class BranchSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val now = AtomicLong(0)
    private val ids = AtomicLong(0)
    private var counter = 0

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 时钟由测试拨：原会话建在第 0 天，分支建在第 3 天——分支若用了自己的日期，系统提示就会变 */
    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools).let { base ->
        KernelDeps(
            clock = Clock { now.get() },
            ids = IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = base.models,
            tools = tools,
            host = base.host,
            platform = "test",
            zone = ZoneOffset.UTC,
            retry = base.retry,
        )
    }

    private fun hub(model: ModelClient, tools: List<Tool<*>> = emptyList()) =
        SessionHub(store, deps(model, tools), newScope(), HubLimits())
    private fun msg() = ClientMsgId("u${++counter}")

    private class RoutingModel(private val route: (ModelRequest) -> dev.min.agent.testkit.Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.last(): String = messages.last().parts.joinToString("\n") { part ->
        when (part) {
            is TextPart -> part.text
            is ToolResultPart -> part.content.filterIsInstance<TextPart>().joinToString("") { it.text }
            else -> ""
        }
    }

    private val hang: dev.min.agent.testkit.Step = { flow { awaitCancellation() } }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** 只是把入参转给端口的 Agent / SendMessage（与 SubagentTest 同款） */
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
            description = input.str("description") ?: "任务",
            prompt = input.str("prompt")!!,
            agentType = input.str("subagent_type"),
            background = input.bool("run_in_background") ?: true,
            name = input.str("name"),
        )
        when (val r = ctx.agents.delegate(request)) {
            is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${r.child.value}")))
            is DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
            is DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
        }
    }
    private val sendTool = PortTool("SendMessage") { input, ctx -> ctx.agents.message(input.str("to")!!, input.str("message")!!) }

    private fun call(id: String, tool: String, vararg pairs: Pair<String, Any?>) =
        ToolCallPart(CallId(id), tool, args(*pairs))

    private suspend fun events(id: SessionId): List<SessionEvent> = store.open(id)!!.read().toList().map { it.event }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(msg(), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private val DAY_MS = 24L * 60 * 60 * 1000

    @Test
    fun `branch copies the log verbatim, tags the branch point, and leaves the original untouched`() = run {
        val model = RoutingModel { req -> reply(TextPart("echo:" + req.last())) }
        val h = hub(model)
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        original.say("第一句")
        val before = store.open(original.sessionId)!!.read().toList()
        val beforeEvents = before.map { it.event }
        now.set(3 * DAY_MS) // 分支建在第 3 天
        val branch = h.branch(original.sessionId)
        assertNotEquals(original.sessionId, branch.sessionId)
        // 分支日志 = 原日志逐条相同 + 末尾一条 session_branched 指回原会话与分支点
        val copied = store.open(branch.sessionId)!!.read().toList()
        assertEquals(beforeEvents, copied.map { it.event }.dropLast(1))
        val mark = copied.last().event as SessionBranched
        assertEquals(original.sessionId, mark.from)
        assertEquals("atSeq 是原会话的最后一条", before.last().seq, mark.atSeq)
        // 原会话一个字不动，也不知道自己被分支过
        assertEquals(beforeEvents, store.open(original.sessionId)!!.read().toList().map { it.event })
        assertNull(original.state.value.branched)
        assertNotNull(branch.state.value.branched)
        // 日志即真相：重开分支，还是那个「分支自 …」
        val reopened = SessionRuntime.open(store, branch.sessionId, deps(model, emptyList()), newScope())!!
        assertEquals(original.sessionId, reopened.state.value.branched!!.from)
    }

    @Test
    fun `both sides keep talking independently - each log is its own truth`() = run {
        val model = RoutingModel { req -> reply(TextPart("回答：" + req.last().takeLast(6))) }
        val h = hub(model)
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        original.say("公共的第一句")
        val branch = h.branch(original.sessionId)
        original.say("原会话继续")
        branch.say("分支自己的问题")
        val originalTexts = events(original.sessionId).filterIsInstance<dev.min.agent.spi.InputReceived>()
            .filter { it.origin == dev.min.agent.spi.InputOrigin.User }.map { it.parts.single() as TextPart }
        val branchTexts = events(branch.sessionId).filterIsInstance<dev.min.agent.spi.InputReceived>()
            .filter { it.origin == dev.min.agent.spi.InputOrigin.User }.map { it.parts.single() as TextPart }
        assertEquals(listOf("公共的第一句", "原会话继续"), originalTexts.map { it.text })
        assertEquals(listOf("公共的第一句", "分支自己的问题"), branchTexts.map { it.text })
        // 各自重开各自成立
        for (id in listOf(original.sessionId, branch.sessionId)) {
            val log = store.open(id)!!
            fold(log.header, log.read().toList())
        }
    }

    @Test
    fun `the branch's first request shares the original's prefix byte for byte across a date change`() = run {
        val model = RoutingModel { req ->
            when (req.last()) {
                "你好" -> reply(TextPart("你好，什么事？"))
                "接着说" -> reply(call("c1", "Agent", "prompt" to "查一下", "run_in_background" to false), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model, listOf(agentTool))
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        original.say("你好")
        original.say("接着说")
        val originalLast = model.requests.last()
        now.set(3 * DAY_MS) // 分支建在第 3 天：basis 不冻结，系统提示里的日期就变了
        val branch = h.branch(original.sessionId)
        branch.say("分支的问题")
        val first = model.requests.last()
        assertEquals("系统提示逐字相同（含冻结的日期）", originalLast.system, first.system)
        assertEquals("工具表逐字相同", originalLast.tools, first.tools)
        assertEquals("缓存键沿用原会话", originalLast.cache.key, first.cache.key)
        // 历史前缀也相同：分支首个请求的历史，开头与原会话最后那个请求的历史逐字相同（后面接的是分支自己的新消息）
        assertEquals(originalLast.messages, first.messages.take(originalLast.messages.size))
    }

    @Test
    fun `branch refuses while a turn is running`() = run {
        val model = RoutingModel { hang }
        val h = hub(model)
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        original.send(msg(), listOf(TextPart("跑着")))
        original.state.first { it.turn != null }
        try {
            h.branch(original.sessionId)
            fail("有回合在跑应该拒绝")
        } catch (e: SessionBranchRefused) {
            assertTrue(e.message!!.contains("正在进行中"))
        }
    }

    @Test
    fun `children are not copied and stay read-only to the branch`() = run {
        val model = RoutingModel { req ->
            when {
                req.last() == "派个后台的" -> reply(call("c1", "Agent", "prompt" to "后台任务", "run_in_background" to true, "name" to "后台任务"), stop = StopReason.ToolUse)
                req.last() == "分支里给它发消息" -> reply(call("s1", "SendMessage", "to" to "后台任务", "message" to "hi"), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model, listOf(agentTool, sendTool))
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        original.say("派个后台的")
        original.state.first { it.children.isNotEmpty() }
        // 后台子 agent 的报告会唤醒原会话跑一个通知回合：等它消化完再分支（分支期间有新事件会被拒绝，这是内核的设计）
        original.state.first { it.turn == null && it.lastTurn?.trigger == dev.min.agent.spi.TurnTrigger.Notification }
        val child = original.state.value.children.keys.single()
        val branch = h.branch(original.sessionId)
        // 子会话没有复制：引用还是原来那个 id，磁盘上也没有多出新的子会话日志
        assertEquals(setOf(child), branch.state.value.children.keys)
        assertEquals(3, store.list().size) // 原会话 + 子会话 + 分支
        // 引用只读：从分支给原会话的子 agent 发消息，被归属检查拒掉（不能只是「找不到」——按名字找得到才证明走到了护栏）
        branch.say("分支里给它发消息")
        val outcome = store.open(branch.sessionId)!!.read().toList()
            .map { it.event }.filterIsInstance<dev.min.agent.spi.ToolOutcome>().last()
        val refusal = outcome.result.content.filterIsInstance<TextPart>().joinToString { it.text }
        assertTrue("错误结果要说清归属：$refusal", outcome.result.isError && refusal.contains("属于别的会话"))
        // 原会话那边的子 agent 日志没被分支动过
        val childLog = events(child)
        assertTrue(childLog.filterIsInstance<dev.min.agent.spi.InputReceived>()
            .none { (it.origin as? dev.min.agent.spi.InputOrigin.Parent)?.session == branch.sessionId })
    }

    @Test
    fun `a session that built a team can be branched and the branch opens - team events stay with the original`() = run {
        val model = RoutingModel { reply(TextPart("好")) }
        val h = hub(model)
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val port = h.portFor(original, dev.min.agent.protocol.TurnId("t-test"), CallId("c-test"))
        assertTrue(!port.team(dev.min.agent.spi.TeamOp.Create(listOf(dev.min.agent.spi.TeamMemberSpec("alice", prompt = "x")))).isError)
        port.team(dev.min.agent.spi.TeamOp.Disband(dev.min.agent.spi.TeamDisbandReason.Completed, null))
        // 成员的报告会唤醒负责人跑通知回合：等安静了再分支
        original.awaitIdle()
        original.state.first { it.turn == null && it.inbox.isEmpty() }
        original.awaitIdle()

        val branch = h.branch(original.sessionId)

        // 团队是负责人这个会话自己的状态（负责人字段指着它），不跟着分支走；分支日志里一条 team_* 都没有
        assertTrue(events(branch.sessionId).none { it.javaClass.simpleName.startsWith("Team") })
        assertNull(branch.state.value.team)
        // 分支能重新打开（这是回归点：之前开分支时折叠到 team_created 就抛，留下打不开的会话）
        val reopened = SessionRuntime.open(store, branch.sessionId, deps(model, emptyList()), newScope())!!
        assertNull(reopened.state.value.team)
        assertNotNull(reopened.state.value.branched)
        // 原会话的团队原样还在
        assertNotNull(original.state.value.team)
    }

    @Test
    fun `creating a session whose initial events do not fold leaves no orphan log behind`() = run {
        val model = RoutingModel { reply(TextPart("好")) }
        val h = hub(model)
        val original = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.BypassPermissions)
        val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s_orphan"), 0L)
        val created = store.open(original.sessionId)!!.read().toList().first().event as SessionCreated
        val before = store.list().map { it.sessionId }.toSet()
        // 负责人写着别人的 id：折叠会拒绝（Teams.kt 的 leader 校验）
        val bad = dev.min.agent.spi.TeamCreated("team_x", original.sessionId, listOf(dev.min.agent.spi.TeamMemberSpec("a", prompt = "x")))
        try {
            SessionRuntime.create(store, header, created, deps(model, emptyList()), newScope(), initial = listOf(bad))
            fail("折叠不了的初始事件应当让创建失败")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("负责人"))
        }
        assertEquals("失败的创建不能在磁盘上留下会话", before, store.list().map { it.sessionId }.toSet())
    }
}
