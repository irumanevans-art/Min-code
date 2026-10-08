package dev.min.agent.kernel

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
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.AgentInputKind
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.TEAM_ALL
import dev.min.agent.spi.TEAM_LEAD
import dev.min.agent.spi.TeamCreated
import dev.min.agent.spi.TeamDisbandReason
import dev.min.agent.spi.TeamDisbanded
import dev.min.agent.spi.TeamMemberJoined
import dev.min.agent.spi.TeamMemberSpec
import dev.min.agent.spi.TeamMessage
import dev.min.agent.spi.TeamOp
import dev.min.agent.spi.TeamTaskClaimed
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskDone
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * agent 团队（docs/agent/detail/agent团队与邮箱设计.md §六）：真日志 + 按请求内容路由的脚本模型。
 * 成员是子会话、邮箱是日志事件、任务清单从负责人日志折叠；每个测试结尾都做 `fold(log) == 运行时状态`。
 */
class TeamSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
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

    /** 成员的开场输入里写着「成员「名字」」：模型据此认出这是哪个成员在请求 */
    private fun ModelRequest.memberName(): String? {
        val first = messages.firstOrNull()?.parts?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return null
        return Regex("成员「([^」]+)」").find(first)?.groupValues?.get(1)
    }

    private val hang: Step = { flow { awaitCancellation() } }

    /** 要人批准的写工具 */
    private val write = object : Tool<JsonObject> {
        override val name = "Write"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits()
        override fun description(profile: HarnessProfile) = "写"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "写一个文件")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("写好了")))))
        }
    }

    /** 把入参转给端口的 TeamCreate：真正的工具在 :agent:tools，这里测内核 */
    private val teamCreate = object : Tool<JsonObject> {
        override val name = "TeamCreate"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, delegates = true)
        override fun description(profile: HarnessProfile) = "建团队"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "建团队")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val specs = input.getValue("members").jsonArray.map {
                val o = it.jsonObject
                TeamMemberSpec(o.getValue("name").jsonPrimitive.content, o["agent_type"]?.jsonPrimitive?.content, o.getValue("prompt").jsonPrimitive.content)
            }
            emit(ToolProgress.Done(ctx.agents.team(TeamOp.Create(specs))))
        }
    }

    private fun createCall(vararg names: String) = ToolCallPart(
        CallId("tc1"), "TeamCreate",
        JsonObject(mapOf("members" to JsonArray(names.map { n -> args("name" to n, "prompt" to "做 $n 的活") }))),
    )

    private fun deps(model: ModelClient, tools: List<Tool<*>>) = testDeps(model, tools)

    private fun hub(model: ModelClient, limits: HubLimits = HubLimits(), tools: List<Tool<*>> = listOf(teamCreate, write), s: SessionStore = store) =
        SessionHub(s, deps(model, tools), newScope(), limits)

    private suspend fun SessionHub.root(mode: PermissionMode = PermissionMode.Default) = create(GuestPath("/workspace"), TEST_MODEL, mode)

    private suspend fun events(id: SessionId): List<SessionEvent> = store.open(id)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    /** 模型脚本：负责人第一次请求建团队，之后只回「好」；成员按 [member] 回 */
    private fun leaderBuilds(vararg names: String, member: (String, ModelRequest) -> Step = { n, _ -> reply(TextPart("$n 做完了")) }) = RoutingModel { req ->
        val who = req.memberName()
        when {
            who != null -> member(who, req)
            req.messages.size == 1 && req.last() == "组个团队" -> reply(TextPart("建团队"), createCall(*names), stop = StopReason.ToolUse)
            else -> reply(TextPart("好"))
        }
    }

    private suspend fun SessionRuntime.members(h: SessionHub): Map<String, SessionRuntime> =
        state.value.team!!.members.associate { it.name to h.get(it.session!!)!! }

    /** 等会话彻底安静：没有回合、收件箱空（报告 / 邮件触发的连环回合都跑完） */
    private suspend fun settle(rt: SessionRuntime) {
        rt.awaitIdle()
        rt.state.first { it.turn == null && it.inbox.isEmpty() }
        rt.awaitIdle()
    }

    private fun portOf(h: SessionHub, rt: SessionRuntime) = h.portFor(rt, TurnId("t-test"), CallId("c-test"))

    // ---- 建团队 ----

    @Test
    fun `a leader builds a team, members are child sessions and their reports go to the leader`() = run {
        val model = leaderBuilds("alice", "bob")
        val h = hub(model)
        val leader = h.root()
        leader.send(ClientMsgId("u1"), listOf(TextPart("组个团队")))
        // 两份报告都进了负责人的对话
        leader.state.first { s ->
            s.turn == null && s.team != null &&
                (s.history.filterIsInstance<HistoryEntry.User>() + s.inbox.map { HistoryEntry.User(dev.min.agent.protocol.MessageId("p"), it.parts, it.origin) })
                    .count { (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report } == 2
        }
        settle(leader)
        val team = leader.state.value.team!!
        assertEquals(listOf("alice", "bob"), team.members.map { it.name })
        assertTrue(team.members.all { it.session != null })
        assertEquals("成员就是负责人的子会话", team.members.mapNotNull { it.session }.toSet(), leader.state.value.children.keys)
        val members = leader.members(h)
        members.values.forEach { m ->
            assertEquals(leader.sessionId, m.state.value.origin!!.parent)
            assertEquals(leader.sessionId, m.state.value.origin!!.root)
            assertEquals(leader.sessionId, store.open(m.sessionId)!!.header.parent)
        }
        // 成员的开场输入给齐了身份、队友和协作方式
        val brief = model.requests.first { it.memberName() == "alice" }.messages.first().parts.filterIsInstance<TextPart>().first().text
        assertTrue(brief.contains("bob") && brief.contains("TeamTask") && brief.contains("做 alice 的活"))

        val log = events(leader.sessionId)
        val kinds = log.filter { it is TeamCreated || it is TeamMemberJoined }.map { it::class.simpleName }
        assertEquals(listOf("TeamCreated", "TeamMemberJoined", "TeamMemberJoined"), kinds)
        assertLogIsTruth(leader)
        members.values.forEach { assertLogIsTruth(it) }
    }

    @Test
    fun `a team needs at least valid members and only one team is active at a time`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val leader = h.root()
        val port = portOf(h, leader)
        assertTrue(port.team(TeamOp.Create(emptyList())).isError)
        assertTrue(port.team(TeamOp.Create(listOf(TeamMemberSpec("lead", prompt = "x")))).isError)
        assertNull("被拒的建团队不留下事件", leader.state.value.team)
        assertFalse(port.team(TeamOp.Create(listOf(TeamMemberSpec("alice", prompt = "x")))).isError)
        val second = port.team(TeamOp.Create(listOf(TeamMemberSpec("carol", prompt = "y"))))
        assertTrue(second.isError)
        assertTrue((second.content.single() as TextPart).text.contains("已经有一个进行中的团队"))
        assertEquals(1, events(leader.sessionId).count { it is TeamCreated })
        assertLogIsTruth(leader)
    }

    @Test
    fun `an unknown member type rolls the team back instead of leaving half a team`() = run {
        val model = leaderBuilds("alice")
        val h = hub(model)
        val leader = h.root()
        val result = portOf(h, leader).team(
            TeamOp.Create(listOf(TeamMemberSpec("alice", prompt = "x"), TeamMemberSpec("bob", agentType = "NoSuchType", prompt = "y"))),
        )
        assertTrue(result.isError)
        val team = leader.state.value.team!!
        assertEquals(TeamDisbandReason.Error, team.disbanded!!.reason)
        assertTrue(team.disbanded!!.note!!.contains("bob"))
        assertEquals("已建好的成员不会开跑", 0, h.teams.runningCount())
        delay(200)
        assertTrue("没有任何成员发过模型请求", model.requests.none { it.memberName() != null })
        assertLogIsTruth(leader)
    }

    @Test
    fun `team members cannot be forks`() = run {
        val h = hub(leaderBuilds("alice"))
        val leader = h.root()
        val result = portOf(h, leader).team(TeamOp.Create(listOf(TeamMemberSpec("alice", agentType = "fork", prompt = "x"))))
        assertTrue(result.isError)
        assertNull(leader.state.value.team)
    }

    // ---- 并发名额 ----

    @Test
    fun `at most teamMaxConcurrent members run at once and the fourth waits for a slot`() = run {
        val names = listOf("a", "b", "c", "d")
        val gates = names.associateWith { CompletableDeferred<Unit>() }
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val model = leaderBuilds(*names.toTypedArray()) { n, req ->
            { r ->
                flow {
                    // updateAndGet 的函数可能被重试，里面不能有副作用：先自增再取最大
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    try {
                        gates.getValue(n).await()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    emitAll(reply(TextPart("$n 做完了"))(r))
                }
            }
        }
        val h = hub(model, HubLimits(teamMaxConcurrent = 3))
        val leader = h.root()
        leader.send(ClientMsgId("u1"), listOf(TextPart("组个团队")))
        leader.state.first { s -> s.team?.members?.all { it.session != null } == true }
        val members = leader.members(h)
        // 前三个起来了，第四个在排队：收件箱里有开场任务、一个回合都没开过
        members.getValue("a").state.first { it.turn != null }
        members.getValue("b").state.first { it.turn != null }
        members.getValue("c").state.first { it.turn != null }
        delay(300)
        val d = members.getValue("d").state.value
        assertNull(d.turn)
        assertNull(d.lastTurn)
        assertEquals(1, d.inbox.size)
        assertEquals(3, h.teams.runningCount())
        val phases = h.teamMembers(leader.sessionId).associate { it.name to it.phase }
        assertEquals(TeamMemberStatus.Phase.Queued, phases["d"])
        assertEquals(TeamMemberStatus.Phase.Running, phases["a"])
        assertEquals(1, model.requests.count { it.memberName() == "a" })
        assertEquals("第四个还没发过请求", 0, model.requests.count { it.memberName() == "d" })

        // 前一个结束，第四个才开始
        gates.getValue("a").complete(Unit)
        members.getValue("d").state.first { it.turn != null || it.lastTurn != null }
        listOf("b", "c", "d").forEach { gates.getValue(it).complete(Unit) }
        names.forEach { n -> members.getValue(n).state.first { it.lastTurn != null && it.turn == null } }
        assertTrue("同一时刻在跑的成员从没超过 3 个（实际最多 ${maxInFlight.get()}）", maxInFlight.get() <= 3)
        assertEquals(3, maxInFlight.get())
        leader.state.first { s -> s.inbox.size + s.history.count { it is HistoryEntry.User && (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report } >= 4 }
        settle(leader)
        assertLogIsTruth(leader)
        members.values.forEach { assertLogIsTruth(it) }
    }

    @Test
    fun `a queued member does not start by itself after a restart`() = run {
        val model1 = leaderBuilds("a", "b") { n, _ -> if (n == "a") hang else reply(TextPart("$n 做完了")) }
        val h1 = hub(model1, HubLimits(teamMaxConcurrent = 1))
        val leader = h1.root()
        leader.send(ClientMsgId("u1"), listOf(TextPart("组个团队")))
        leader.state.first { s -> s.team?.members?.all { it.session != null } == true }
        val bId = leader.state.value.team!!.member("b")!!.session!!
        h1.get(bId)!!.state.first { it.inbox.isNotEmpty() }
        h1.get(leader.state.value.team!!.member("a")!!.session!!)!!.state.first { it.turn != null }
        scopes.first().cancel() // 进程死了

        val model2 = leaderBuilds("a", "b") { n, _ -> reply(TextPart("$n 做完了")) }
        val h2 = hub(model2, HubLimits(teamMaxConcurrent = 1))
        val reopened = h2.open(leader.sessionId)!!
        val b = h2.open(bId)!!
        delay(400)
        assertNull("b 在崩溃前排着队，重启后不自动开跑", b.state.value.lastTurn)
        assertEquals(1, b.state.value.inbox.size)
        assertEquals(0, model2.requests.count { it.memberName() == "b" })
        assertEquals(TeamMemberStatus.Phase.Queued, h2.teamMembers(reopened.sessionId).single { it.name == "b" }.phase)
    }

    @Test
    fun `team limits setting asks for confirmation above eight`() {
        assertEquals(TeamLimits.Setting.Accepted(3), TeamLimits.resolve(null))
        assertEquals(TeamLimits.Setting.Accepted(8), TeamLimits.resolve(8))
        assertEquals(TeamLimits.Setting.NeedsConfirmation(9), TeamLimits.resolve(9))
        assertEquals(TeamLimits.Setting.Accepted(9), TeamLimits.resolve(9, confirmedAboveCap = true))
        assertTrue(TeamLimits.resolve(0) is TeamLimits.Setting.Invalid)
    }

    // ---- 任务清单 ----

    private suspend fun readyTeam(h: SessionHub, vararg names: String): Pair<SessionRuntime, Map<String, SessionRuntime>> {
        val leader = h.root()
        val result = portOf(h, leader).team(TeamOp.Create(names.map { TeamMemberSpec(it, prompt = "做 $it 的活") }))
        assertFalse((result.content.single() as TextPart).text, result.isError)
        return leader to leader.members(h)
    }

    /** 成员都停在第一个模型请求上（模型脚本让它们挂住）：它们不再写日志、也不给负责人交报告，后面读日志才不会和写入撞车 */
    private suspend fun readyPaused(h: SessionHub, vararg names: String): Pair<SessionRuntime, Map<String, SessionRuntime>> {
        val ready = readyTeam(h, *names)
        ready.second.values.forEach { m -> m.state.first { it.turn?.openRequest != null } }
        return ready
    }

    private fun ToolResult.text() = content.filterIsInstance<TextPart>().joinToString("") { it.text }

    @Test
    fun `members change only their own tasks and the leader changes any`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice", "bob")
        val lead = portOf(h, leader)
        val alice = portOf(h, members.getValue("alice"))
        val bob = portOf(h, members.getValue("bob"))

        assertFalse(lead.team(TeamOp.TaskCreate("接口设计", "定义 REST")).isError)
        assertFalse("成员也能提任务", bob.team(TeamOp.TaskCreate("补测试", "")).isError)
        assertFalse(alice.team(TeamOp.TaskClaim("1", null)).isError)

        // 违规：bob 完成 alice 的、抢 alice 的、替别人认领、陌生人操作——全被拒，状态不变，负责人日志里各留一条说明
        val notesBefore = leader.state.value.presentation.count { it.record.event is Note }
        assertTrue(bob.team(TeamOp.TaskDone("1", "我做完了")).isError)
        assertTrue(bob.team(TeamOp.TaskClaim("1", null)).isError)
        assertTrue(bob.team(TeamOp.TaskClaim("2", "alice")).isError)
        assertTrue("不存在的任务", lead.team(TeamOp.TaskDone("99", null)).isError)
        val task1 = leader.state.value.team!!.task("1")!!
        assertEquals(TeamTaskStatus.Claimed, task1.status)
        assertEquals("alice", task1.owner)
        assertNull(leader.state.value.team!!.task("2")!!.owner)
        val notes = leader.state.value.presentation.map { it.record.event }.filterIsInstance<Note>().map { it.text }
        assertEquals(notesBefore + 4, leader.state.value.presentation.count { it.record.event is Note })
        assertTrue(notes.any { it.contains("发起人 bob") && it.contains("只能完成自己认领的任务") })
        assertTrue(notes.any { it.contains("不能抢") })

        assertFalse(alice.team(TeamOp.TaskDone("1", "接口定好了")).isError)
        // 负责人改派、代办
        assertFalse(lead.team(TeamOp.TaskClaim("2", "bob")).isError)
        assertFalse(lead.team(TeamOp.TaskDone("2", "负责人代为确认")).isError)
        val team = leader.state.value.team!!
        assertEquals(TeamTaskStatus.Done, team.task("1")!!.status)
        assertEquals("alice", team.task("1")!!.doneBy)
        assertEquals(TEAM_LEAD, team.task("2")!!.doneBy)
        // 被拒的转换一条事件都没写
        val teamEvents = events(leader.sessionId).filter { it is TeamTaskCreated || it is TeamTaskClaimed || it is TeamTaskDone }
        assertEquals(6, teamEvents.size)
        assertLogIsTruth(leader)
    }

    @Test
    fun `a session without a team cannot touch a task list`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice")
        val aliceRt = members.getValue("alice")
        val stranger = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        val result = portOf(h, stranger).team(TeamOp.TaskCreate("x", ""))
        assertTrue(result.isError)
        assertTrue(result.text().contains("还没有团队"))
        assertTrue(aliceRt.state.value.team == null)
        assertEquals(0, leader.state.value.team!!.tasks.size)
    }

    @Test
    fun `team status lists members and the task list`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, _) = readyPaused(h, "alice", "bob")
        portOf(h, leader).team(TeamOp.TaskCreate("接口设计", ""))
        val text = portOf(h, leader).team(TeamOp.Status).text()
        assertTrue(text, text.contains("alice") && text.contains("bob") && text.contains("#1 接口设计（待认领）"))
    }

    // ---- 嵌套限制 ----

    @Test
    fun `a member cannot open a team of its own`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice")
        val alice = members.getValue("alice")
        val result = portOf(h, alice).team(TeamOp.Create(listOf(TeamMemberSpec("zed", prompt = "x"))))
        assertTrue(result.isError)
        assertTrue(result.text().contains("成员不能再开团队"))
        assertNull(alice.state.value.team)
        assertTrue(events(alice.sessionId).none { it is TeamCreated })
        assertEquals(1, leader.state.value.team!!.members.size)
        assertLogIsTruth(alice)
    }

    // ---- 邮箱 ----

    private suspend fun pausedTeam(h: SessionHub): Pair<SessionRuntime, Map<String, SessionRuntime>> {
        val (leader, members) = readyTeam(h, "alice", "bob")
        // 让成员停在第一个请求上：收到的邮件留在收件箱里，不会被马上读走
        members.getValue("alice").state.first { it.turn?.openRequest != null }
        members.getValue("bob").state.first { it.turn?.openRequest != null }
        return leader to members
    }

    @Test
    fun `a team message lands in the recipient log with the inbox entry in the same commit`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, members) = pausedTeam(h)
        val bob = members.getValue("bob")

        val result = portOf(h, members.getValue("alice")).message("bob", "接口定好了")
        assertFalse(result.content.single().let { (it as TextPart).text }, result.isError)

        val log = events(bob.sessionId)
        val mailAt = log.indexOfFirst { it is TeamMessage }
        val mail = log[mailAt] as TeamMessage
        assertEquals("alice", mail.from)
        assertEquals("bob", mail.to)
        assertFalse(mail.broadcast)
        val input = log[mailAt + 1] as InputReceived
        assertEquals("紧跟着的就是同一封邮件的收件箱输入，与 SendMessage 同一条路", ClientMsgId("team:${mail.teamId}:${mail.messageId}"), input.clientMsgId)
        assertEquals(InputOrigin.Agent(members.getValue("alice").sessionId, AgentInputKind.Message), input.origin)
        assertTrue((input.parts.single() as TextPart).text.contains("接口定好了"))
        assertEquals("bob 的折叠状态里有这封邮件", listOf("接口定好了"), bob.state.value.teamMail.map { it.text })
        assertEquals(1, bob.state.value.inbox.size)
        assertLogIsTruth(bob)
        assertLogIsTruth(leader)
    }

    @Test
    fun `a message to the leader wakes it, and to main from a member means the leader`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val (leader, members) = pausedTeam(h).let { (l, m) -> l to m }
        leader.awaitIdle()
        val before = leader.state.value.lastTurn?.turnId
        assertFalse(portOf(h, members.getValue("alice")).message("main", "遇到问题了").isError)
        leader.state.first { it.lastTurn?.turnId != before && it.turn == null }
        val mail = leader.state.value.teamMail.single()
        assertEquals("alice", mail.from)
        val promoted = leader.state.value.history.filterIsInstance<HistoryEntry.User>().last()
        assertTrue((promoted.parts.single() as TextPart).text.contains("遇到问题了"))
        assertLogIsTruth(leader)
    }

    @Test
    fun `broadcasting reaches everyone except the sender`() = run {
        val h = hub(leaderBuilds("alice", "bob", "carol") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice", "bob", "carol").let { (l, m) -> l to m }
        for (m in members.values) m.state.first { it.turn?.openRequest != null }
        assertFalse(portOf(h, members.getValue("alice")).message(TEAM_ALL, "接口改了").isError)
        listOf("bob", "carol").forEach { n ->
            val rt = members.getValue(n)
            val mail = rt.state.value.teamMail.single()
            assertTrue(mail.broadcast)
            assertEquals("alice", mail.from)
        }
        assertTrue("发件人自己不收", members.getValue("alice").state.value.teamMail.isEmpty())
        assertEquals("负责人也收到", 1, leader.state.value.teamMail.size)
        leader.awaitIdle()
        assertLogIsTruth(leader)
        members.values.forEach { assertLogIsTruth(it) }
    }

    @Test
    fun `mail survives a restart and is read exactly once`() = run {
        val h1 = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, members) = pausedTeam(h1)
        val bobId = members.getValue("bob").sessionId
        portOf(h1, members.getValue("alice")).message("bob", "接口定好了")
        assertEquals(1, members.getValue("bob").state.value.inbox.size)
        scopes.first().cancel() // 进程死了，邮件还没被读

        // 新进程：bob 重开后收件箱里的邮件还在
        val model2 = RoutingModel { req -> if (req.memberName() == "bob") reply(TextPart("读到了")) else reply(TextPart("好")) }
        val h2 = hub(model2)
        h2.open(leader.sessionId)
        val bob = h2.open(bobId)!!
        val reopened = bob.state.value
        assertEquals("重开后邮件仍在收件箱", 1, reopened.inbox.size)
        assertTrue((reopened.inbox.single().parts.single() as TextPart).text.contains("接口定好了"))
        assertEquals(1, reopened.teamMail.size)

        // 读走它：只进对话一次，也不会再留在收件箱里
        bob.runInbox()
        bob.state.first { it.lastTurn != null && it.turn == null && it.inbox.isEmpty() }
        val users = bob.state.value.history.filterIsInstance<HistoryEntry.User>()
        assertEquals(1, users.count { u -> u.parts.any { (it as? TextPart)?.text?.contains("接口定好了") == true } })
        val seen = model2.requests.single { it.memberName() == "bob" }
        assertEquals(1, seen.messages.count { m -> m.parts.any { (it as? TextPart)?.text?.contains("接口定好了") == true } })
        // 重放同一个输入 id 不会再收一次
        val clientId = (events(bobId).filterIsInstance<InputReceived>().single { it.origin is InputOrigin.Agent }).clientMsgId
        assertFalse(bob.receive(clientId, listOf(TextPart("重发")), dev.min.agent.spi.Delivery.Steer, InputOrigin.Agent(leader.sessionId, AgentInputKind.Message), startTurn = null))
        assertLogIsTruth(bob)
    }

    @Test
    fun `plain sub-agent messaging still works for sessions outside any team`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice")
        // 负责人给一个自己名下、不叫成员名的目标发消息：团队邮箱不接，落回子 agent 路径（找不到就是原来的错误）
        val result = portOf(h, leader).message("nobody", "你好")
        assertTrue(result.isError)
        assertTrue(result.text().contains("没有叫「nobody」的子 agent"))
        assertEquals(0, leader.state.value.teamMail.size)
    }

    // ---- 权限不绕过 ----

    @Test
    fun `a member's approval hangs on the root tree and only the member's own runtime can answer it`() = run {
        val model = leaderBuilds("alice") { _, req ->
            if (req.messages.size == 1) reply(ToolCallPart(CallId("w1"), "Write", JsonObject(emptyMap())), stop = StopReason.ToolUse)
            else reply(TextPart("写完了"))
        }
        val h = hub(model)
        val (leader, members) = readyTeam(h, "alice")
        val alice = members.getValue("alice")
        val pending = h.pending.first { list -> list.any { it.session == alice.sessionId } }.single { it.session == alice.sessionId }
        assertEquals("审批挂在根会话的树上", leader.sessionId, pending.root)
        assertEquals(leader.sessionId, pending.parent)
        val approval = pending.what as HubPending.What.Approval
        assertEquals("Write", approval.tool)
        // 负责人不能替成员批：它的运行时里没有这次审批
        assertFalse(leader.answerApproval(approval.approvalId, ApprovalChoice.Once))
        assertTrue("alice 还在等", alice.state.value.turn!!.calls.values.any { it.phase is CallPhase.AwaitingApproval })
        assertTrue(alice.answerApproval(approval.approvalId, ApprovalChoice.Once))
        alice.state.first { it.lastTurn != null && it.turn == null }
        assertLogIsTruth(alice)
        // 报告交给负责人（唤醒它跑一回合）之后才读它的日志
        leader.state.first { s -> s.history.any { it is HistoryEntry.User && (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report } }
        settle(leader)
        assertLogIsTruth(leader)
    }

    // ---- 解散 ----

    @Test
    fun `disbanding stops the members, empties the queue and freezes the task list`() = run {
        val h = hub(leaderBuilds("a", "b") { _, _ -> hang }, HubLimits(teamMaxConcurrent = 1))
        val (leader, members) = readyTeam(h, "a", "b")
        members.getValue("a").state.first { it.turn != null }
        val result = portOf(h, leader).team(TeamOp.Disband(TeamDisbandReason.UserEnded, "不做了"))
        assertFalse(result.text(), result.isError)
        assertNull("成员停下了", members.getValue("a").state.value.turn)
        assertEquals(0, h.teams.runningCount())
        delay(200)
        assertNull("排队的 b 没有因为 a 的名额放出来而开跑", members.getValue("b").state.value.lastTurn)
        assertNull(members.getValue("b").state.value.turn)
        val team = leader.state.value.team!!
        assertEquals(TeamDisbandReason.UserEnded, team.disbanded!!.reason)
        assertTrue(portOf(h, leader).team(TeamOp.TaskCreate("x", "")).isError)
        assertTrue("解散后不能重复解散", portOf(h, leader).team(TeamOp.Disband(TeamDisbandReason.Completed, null)).isError)
        // 解散后可以建新团队
        assertFalse(portOf(h, leader).team(TeamOp.Create(listOf(TeamMemberSpec("c", prompt = "x")))).isError)
        leader.awaitIdle()
        assertLogIsTruth(leader)
    }

    @Test
    fun `only the leader can disband`() = run {
        val h = hub(leaderBuilds("a") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "a")
        val result = portOf(h, members.getValue("a")).team(TeamOp.Disband(TeamDisbandReason.UserEnded, null))
        assertTrue(result.isError)
        assertTrue(leader.state.value.team!!.active)
        assertNotNull(leader.state.value.presentation.map { it.record.event }.filterIsInstance<Note>().singleOrNull { it.level == NoteLevel.Warning && it.text.contains("只有负责人能解散团队") })
        members.getValue("a").interrupt()
    }
}
