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
import dev.min.agent.spi.TaskPatch
import dev.min.agent.spi.TaskStatus
import dev.min.agent.spi.TeamTaskDeleted
import dev.min.agent.spi.TeamTaskCreated
import dev.min.agent.spi.TeamTaskUpdated
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
import dev.min.agent.protocol.ModelRef
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
        assertTrue(brief.contains("bob") && brief.contains("TaskList") && brief.contains("做 alice 的活"))

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
        val created = port.team(TeamOp.Create(listOf(TeamMemberSpec("alice", prompt = "x"))))
        assertFalse(created.isError)
        assertTrue("不再指向已经删掉的 TeamTask 工具", !created.text().contains("TeamTask") && created.text().contains("TaskCreate"))
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
        assertLogIsTruth(leader)
    }

    // ---- 成员的模型 ----

    private val altModel = dev.min.agent.protocol.ModelRef("test", "alt")

    /** 认得 TEST_MODEL 和 test/alt 两个模型的 deps（alt 与默认模型同一个脚本客户端，只是 ref 不同） */
    private fun altDeps(model: ModelClient, tools: List<Tool<*>>): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = base.clock, ids = base.ids,
            models = { ref ->
                check(ref == TEST_MODEL || ref == altModel) { "未知模型 $ref" }
                ResolvedModel(dev.min.agent.testkit.TEST_PROFILE.copy(ref = ref), model)
            },
            tools = tools, host = base.host, platform = "test", zone = base.zone, retry = base.retry,
        )
    }

    @Test
    fun `a member can run on its own model and the rest follow the leader`() = run {
        val h = SessionHub(store, altDeps(leaderBuilds("alice", "bob") { _, _ -> hang }, listOf(teamCreate, write)), newScope(), HubLimits())
        val leader = h.root()
        val result = portOf(h, leader).team(
            TeamOp.Create(listOf(TeamMemberSpec("alice", prompt = "x", model = "alt"), TeamMemberSpec("bob", prompt = "y"))),
        )
        assertFalse(result.text(), result.isError)
        val members = leader.members(h)
        assertEquals("alice 用自己指定的模型", altModel, members.getValue("alice").state.value.settings.model)
        assertEquals("bob 没指定就跟负责人", TEST_MODEL, members.getValue("bob").state.value.settings.model)
        assertLogIsTruth(leader)
    }

    @Test
    fun `an unknown member model rolls the team back with the reason`() = run {
        val h = hub(leaderBuilds("alice"))
        val leader = h.root()
        val result = portOf(h, leader).team(TeamOp.Create(listOf(TeamMemberSpec("alice", prompt = "x", model = "no-such-model"))))
        assertTrue(result.isError)
        assertTrue(result.text(), result.text().contains("不认识模型") && result.text().contains("no-such-model"))
        assertEquals(TeamDisbandReason.Error, leader.state.value.team!!.disbanded!!.reason)
        assertLogIsTruth(leader)
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
    fun `raising the concurrency limit while the team runs lets the waiting members start - the setting is live`() = run {
        val h = hub(leaderBuilds("a", "b", "c") { _, _ -> hang }, HubLimits(teamMaxConcurrent = 1))
        val (_, members) = readyTeam(h, "a", "b", "c")
        members.getValue("a").state.first { it.turn != null }
        delay(200)
        assertEquals(1, members.values.count { it.state.value.turn != null })
        h.updateHostLimits(teamMaxConcurrent = 3)
        members.getValue("b").state.first { it.turn != null }
        members.getValue("c").state.first { it.turn != null }
        assertEquals(3, h.teams.runningCount())
        // 调低不杀正在跑的：只是之后不再放新的
        h.updateHostLimits(teamMaxConcurrent = 2)
        delay(200)
        assertEquals(3, members.values.count { it.state.value.turn != null })
        try {
            h.updateHostLimits(teamMaxConcurrent = 9)
            org.junit.Assert.fail("超过硬上限 8")
        } catch (expected: IllegalArgumentException) {
            assertEquals("被拒的更新不改变现状", 2, h.limits.teamMaxConcurrent)
        }
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
        // 进程死了。cancel 不等收尾：被取消的成员回合在 NonCancellable 里还要写 TurnEnded，必须等它写完再「重启」，
        // 否则新进程读到的日志和这条收尾事件抢同一个 seq（偶发 "seq 不连续"）
        scopes.first().coroutineContext[kotlinx.coroutines.Job]!!.apply { cancel(); join() }

        val model2 = leaderBuilds("a", "b") { n, _ -> reply(TextPart("$n 做完了")) }
        val h2 = hub(model2, HubLimits(teamMaxConcurrent = 1))
        val reopened = h2.open(leader.sessionId)!!
        val b = h2.open(bId)!!
        delay(400)
        assertNull("b 在崩溃前排着队，重启后不自动开跑", b.state.value.lastTurn)
        assertEquals(1, b.state.value.inbox.size)
        assertEquals(0, model2.requests.count { it.memberName() == "b" })
        // 内存里的排队队伍随进程没了：它在日志里还躺着开场任务，但没有任何东西会放行它——不能再显示成「排队中」骗人
        assertEquals(TeamMemberStatus.Phase.Stalled, h2.teamMembers(reopened.sessionId).single { it.name == "b" }.phase)
        assertEquals("a 的回合死在中途，已被收尾，不算搁浅", TeamMemberStatus.Phase.Idle, h2.teamMembers(reopened.sessionId).single { it.name == "a" }.phase)
        // 由人 / 负责人决定：放行搁浅的成员，照常过名额
        assertEquals(1, h2.resumeTeamMembers(reopened.sessionId))
        b.state.first { it.lastTurn != null && it.turn == null }
        assertEquals(1, model2.requests.count { it.memberName() == "b" })
        assertEquals("放行之后它是真的跑过了", TeamMemberStatus.Phase.Idle, h2.teamMembers(reopened.sessionId).single { it.name == "b" }.phase)
        assertEquals("已经没有搁浅的了", 0, h2.resumeTeamMembers(reopened.sessionId))
        assertLogIsTruth(b)
    }

    @Test
    fun `a genuinely waiting member shows as queued and is not counted as stalled`() = run {
        val h = hub(leaderBuilds("a", "b") { n, _ -> if (n == "a") hang else reply(TextPart("$n 做完了")) }, HubLimits(teamMaxConcurrent = 1))
        val (leader, members) = readyTeam(h, "a", "b")
        members.getValue("a").state.first { it.turn != null }
        members.getValue("b").state.first { it.inbox.isNotEmpty() }
        val phases = h.teamMembers(leader.sessionId).associate { it.name to it.phase }
        assertEquals(TeamMemberStatus.Phase.Queued, phases["b"])
        assertEquals("队伍里的不需要放行", 0, h.resumeTeamMembers(leader.sessionId))
    }

    @Test
    fun `eight is a hard cap - a bigger setting is clamped and there is no confirmation path`() {
        assertEquals(TeamLimits.Setting.Accepted(3), TeamLimits.resolve(null))
        assertEquals(TeamLimits.Setting.Accepted(8), TeamLimits.resolve(8))
        val clamped = TeamLimits.resolve(9) as TeamLimits.Setting.Accepted
        assertEquals("超过 8 不再问用户，直接按 8", 8, clamped.value)
        assertTrue(clamped.note!!.contains("硬上限 8"))
        assertEquals(8, (TeamLimits.resolve(1000) as TeamLimits.Setting.Accepted).value)
        assertTrue(TeamLimits.resolve(0) is TeamLimits.Setting.Invalid)
        assertTrue(TeamLimits.resolve(-1) is TeamLimits.Setting.Invalid)
    }

    @Test
    fun `the hub refuses a limit above the hard cap instead of silently running more members`() {
        try {
            HubLimits(teamMaxConcurrent = 9)
            org.junit.Assert.fail("9 超过硬上限 8")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("8"))
        }
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

    private fun taskCreate(subject: String, description: String = "") = TeamOp.TaskCreate(subject, description, null, JsonObject(emptyMap()))
    private fun taskUpdate(id: String, status: TaskStatus? = null, owner: String? = null) = TeamOp.TaskUpdate(id, TaskPatch(status = status, owner = owner))

    @Test
    fun `members change only their own tasks and the leader changes any`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice", "bob")
        val lead = portOf(h, leader)
        val alice = portOf(h, members.getValue("alice"))
        val bob = portOf(h, members.getValue("bob"))

        assertFalse(lead.team(taskCreate("接口设计", "定义 REST")).isError)
        assertFalse("成员也能提任务", bob.team(taskCreate("补测试")).isError)
        assertFalse(alice.team(taskUpdate("1", TaskStatus.InProgress, owner = "alice")).isError)

        // 违规：bob 完成 alice 的、抢 alice 的、替别人认领、陌生人操作——全被拒，状态不变，负责人日志里各留一条说明
        val notesBefore = leader.state.value.presentation.count { it.record.event is Note }
        assertTrue(bob.team(taskUpdate("1", TaskStatus.Completed)).isError)
        assertTrue(bob.team(taskUpdate("1", owner = "bob")).isError)
        assertTrue(bob.team(taskUpdate("2", owner = "alice")).isError)
        assertTrue("不存在的任务", lead.team(taskUpdate("99", TaskStatus.Completed)).isError)
        val task1 = leader.state.value.team!!.task("1")!!
        assertEquals(TaskStatus.InProgress, task1.status)
        assertEquals("alice", task1.owner)
        assertNull(leader.state.value.team!!.task("2")!!.owner)
        val notes = leader.state.value.presentation.map { it.record.event }.filterIsInstance<Note>().map { it.text }
        assertEquals(notesBefore + 4, leader.state.value.presentation.count { it.record.event is Note })
        assertTrue(notes.any { it.contains("发起人 bob") && it.contains("只能改自己认领的任务") })
        assertTrue(notes.any { it.contains("不能抢") })

        assertFalse(alice.team(taskUpdate("1", TaskStatus.Completed)).isError)
        // 成员可以释放自己的认领；负责人改派、代办
        assertFalse(bob.team(taskUpdate("2", owner = "bob")).isError)
        assertFalse(bob.team(taskUpdate("2", owner = "")).isError)
        assertNull(leader.state.value.team!!.task("2")!!.owner)
        assertFalse(lead.team(taskUpdate("2", owner = "bob")).isError)
        assertFalse(lead.team(taskUpdate("2", TaskStatus.Completed)).isError)
        val team = leader.state.value.team!!
        assertEquals(TaskStatus.Completed, team.task("1")!!.status)
        assertEquals(TaskStatus.Completed, team.task("2")!!.status)
        assertEquals("bob", team.task("2")!!.owner)
        // 被拒的转换一条事件都没写：2 条创建 + 认领 + alice 完成 + bob 认领 + 释放 + 改派 + 代办完成
        val teamEvents = events(leader.sessionId).filter { it is TeamTaskCreated || it is TeamTaskUpdated || it is TeamTaskDeleted }
        assertEquals(8, teamEvents.size)
        assertLogIsTruth(leader)
    }

    @Test
    fun `the task tools of every team member read and write the one shared list`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice", "bob")
        val aliceRt = members.getValue("alice")
        // 负责人、成员、成员各自看到的是同一份团队清单，而不是个人清单
        portOf(h, leader).team(taskCreate("接口设计"))
        for ((name, rt) in listOf(TEAM_LEAD to leader, "alice" to aliceRt, "bob" to members.getValue("bob"))) {
            val board = rt.visibleTaskBoard()
            assertEquals(name, board.team!!.viewer)
            assertEquals(listOf("1"), board.tasks.map { it.id })
            assertEquals("2", board.nextId)
        }
        assertTrue("个人清单是空的，没被写进去", leader.state.value.tasks.isEmpty() && aliceRt.state.value.tasks.isEmpty())
        // 成员的子 agent 不在团队里，看到的是自己那份
        val sub = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        assertNull(sub.visibleTaskBoard().team)
        // 解散后各自回到个人清单
        portOf(h, leader).team(TeamOp.Disband(TeamDisbandReason.Completed, null))
        assertNull(leader.visibleTaskBoard().team)
        assertEquals(emptyList<String>(), leader.visibleTaskBoard().tasks.map { it.id })
        // 解散让成员停下，它们的“没做完”报告到齐之后日志才不再变，比较折叠才不会和写入撞车
        leader.state.first { it.inbox.size == 2 }
        assertLogIsTruth(leader)
    }

    @Test
    fun `a session without a team cannot touch a task list`() = run {
        val h = hub(leaderBuilds("alice") { _, _ -> hang })
        val (leader, members) = readyPaused(h, "alice")
        val aliceRt = members.getValue("alice")
        val stranger = h.create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        val result = portOf(h, stranger).team(taskCreate("x"))
        assertTrue(result.isError)
        assertTrue(result.text().contains("还没有团队"))
        assertTrue(aliceRt.state.value.team == null)
        assertEquals(0, leader.state.value.team!!.tasks.size)
        assertLogIsTruth(stranger)
        assertLogIsTruth(leader)
    }

    @Test
    fun `a task result shows the whole list with owners`() = run {
        val h = hub(leaderBuilds("alice", "bob") { _, _ -> hang })
        val (leader, _) = readyPaused(h, "alice", "bob")
        val text = portOf(h, leader).team(taskCreate("接口设计")).text()
        assertTrue(text, text.contains("#1「接口设计」") && text.contains("#1 接口设计（待认领）"))
        val claimed = portOf(h, leader).team(taskUpdate("1", owner = "alice")).text()
        assertTrue(claimed, claimed.contains("#1 接口设计（已认领 · alice）"))
        assertLogIsTruth(leader)
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

    /** 两个成员做完开场任务、负责人消化完两份报告之后的安静状态 */
    private suspend fun quietTeam(h: SessionHub, model: RoutingModel): Pair<SessionRuntime, Map<String, SessionRuntime>> {
        val leader = h.root()
        leader.send(ClientMsgId("u1"), listOf(TextPart("组个团队")))
        leader.state.first { s ->
            s.team?.members?.all { it.session != null } == true &&
                s.history.count { it is HistoryEntry.User && (it.origin as? InputOrigin.Agent)?.kind == AgentInputKind.Report } == 2
        }
        settle(leader)
        return leader to leader.members(h)
    }

    @Test
    fun `a turn started by a peer message reports to the leader inbox without waking it, a leader message still does`() = run {
        val model = leaderBuilds("alice", "bob")
        val h = hub(model)
        val (leader, members) = quietTeam(h, model)
        val bob = members.getValue("bob")
        val turnsBefore = leader.state.value.lastTurn!!.turnId
        val bobTurnsBefore = bob.state.value.lastTurn!!.turnId

        // 队友给 bob 发消息：bob 跑一轮，报告进负责人的收件箱，但不叫醒负责人（否则成员之间每聊一句都烧负责人一整轮）
        assertFalse(portOf(h, members.getValue("alice")).message("bob", "接口定好了").isError)
        bob.state.first { it.lastTurn?.turnId != bobTurnsBefore && it.turn == null }
        leader.state.first { s -> s.inbox.any { (it.origin as? InputOrigin.Agent)?.session == bob.sessionId } }
        delay(400)
        assertEquals("负责人没有被叫醒", turnsBefore, leader.state.value.lastTurn!!.turnId)
        assertNull(leader.state.value.turn)
        assertEquals("报告躺在收件箱里，负责人下次开回合时一并看到", 1, leader.state.value.inbox.size)
        assertLogIsTruth(leader)

        // 对照：负责人自己给 bob 发的消息，它在等答复——这一轮的报告照常唤醒负责人
        val bobAfterPeer = bob.state.value.lastTurn!!.turnId
        assertFalse(portOf(h, leader).message("bob", "进度如何").isError)
        bob.state.first { it.lastTurn?.turnId != bobAfterPeer && it.turn == null }
        leader.state.first { it.lastTurn?.turnId != turnsBefore }
        settle(leader)
        assertEquals("负责人醒来把积压的两份报告一起消化了", 0, leader.state.value.inbox.size)
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
        // 进程死了。cancel 不等收尾：被取消的成员回合在 NonCancellable 里还要写 TurnEnded，必须等它写完再「重启」，
        // 否则新进程读到的日志和这条收尾事件抢同一个 seq（偶发 "seq 不连续"）
        scopes.first().coroutineContext[kotlinx.coroutines.Job]!!.apply { cancel(); join() } // 邮件还没被读

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
        assertLogIsTruth(leader)
        members.values.forEach { assertLogIsTruth(it) }
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
        assertTrue(portOf(h, leader).team(taskCreate("x")).isError)
        assertTrue("解散后不能重复解散", portOf(h, leader).team(TeamOp.Disband(TeamDisbandReason.Completed, null)).isError)
        // 解散后可以建新团队
        assertFalse(portOf(h, leader).team(TeamOp.Create(listOf(TeamMemberSpec("c", prompt = "x")))).isError)
        leader.awaitIdle()
        assertLogIsTruth(leader)
    }

    @Test
    fun `after a disband, messaging old members still goes through the concurrency slots`() = run {
        val h = hub(leaderBuilds("a", "b", "c") { _, _ -> hang }, HubLimits(teamMaxConcurrent = 1))
        val (leader, members) = readyTeam(h, "a", "b", "c")
        members.getValue("a").state.first { it.turn != null }
        assertFalse(portOf(h, leader).team(TeamOp.Disband(TeamDisbandReason.Completed, null)).isError)
        members.values.forEach { it.awaitIdle() }
        assertEquals(0, h.teams.runningCount())

        // 解散后负责人给三个老成员各发一句：没有团队了，但名额还在——同一时刻只能有 1 个在跑
        for (name in listOf("a", "b", "c")) assertFalse(portOf(h, leader).message(name, "再看一眼").isError)
        members.getValue("a").state.first { it.turn != null }
        delay(400)
        val running = members.values.count { it.state.value.turn != null }
        assertEquals("teamMaxConcurrent=1：只有一个在跑，另外两个排队（收件箱里有这句话、没开回合）", 1, running)
        assertEquals(1, h.teams.runningCount())
        val queued = members.values.filter { it.state.value.turn == null }
        assertEquals(2, queued.size)
        // 收件箱里有两条：没来得及开跑的开场任务（解散时它们还在排队）和这句新话，都还没处理
        assertTrue("排队成员的收件箱：${queued.map { it.state.value.inbox.size }}", queued.all { m -> m.state.value.inbox.size == 2 && m.state.value.inbox.last().parts.any { (it as? TextPart)?.text == "再看一眼" } })
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
        assertLogIsTruth(leader)
        assertLogIsTruth(members.getValue("a"))
    }
}
