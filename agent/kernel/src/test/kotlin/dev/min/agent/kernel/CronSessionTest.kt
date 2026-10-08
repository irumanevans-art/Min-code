package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelPricing
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
import dev.min.agent.spi.Clock
import dev.min.agent.spi.CronExpiry
import dev.min.agent.spi.CronExpired
import dev.min.agent.spi.CronFired
import dev.min.agent.spi.CronScheduled
import dev.min.agent.spi.CronStatus
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.InputOrigin
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.CronCreateTool
import dev.min.agent.tools.CronDeleteTool
import dev.min.agent.tools.CronListTool
import dev.min.agent.tools.ScheduleWakeupTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 定时调度（M5）：真日志 + 注入的时钟（测试拨表，不睡觉）+ 按请求内容路由的脚本模型。
 * 每个测试结尾都做 `fold(log) == 运行时状态`。
 */
class CronSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    /** 测试时钟：2026-10-08 08:00 UTC。只有测试拨它 */
    private val t0 = 1_791_446_400_000L
    private val now = AtomicLong(t0)
    private val minute = 60_000L
    private val ids = AtomicLong(0)

    /** 每个请求 15 个 token、每百万 1000 美元 → 一次请求 0.015 美元 */
    private val pricing = ModelPricing("USD", inputPerMTok = 1000.0, outputPerMTok = 1000.0, asOf = "2026-10-08")
    private val pricedProfile = TEST_PROFILE.copy(pricing = pricing)

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

    private val probe = object : Tool<JsonObject> {
        override val name = "Probe"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "探针"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "探")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("探到了")))))
        }
    }

    private val tools: List<Tool<*>> = listOf(CronCreateTool, CronDeleteTool, CronListTool, ScheduleWakeupTool, probe)

    private fun deps(model: ModelClient, profile: dev.min.agent.llm.ModelProfile = pricedProfile): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = Clock { now.get() },
            ids = IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ref ->
                check(ref == TEST_MODEL) { "未知模型 $ref" }
                ResolvedModel(profile, model)
            },
            tools = tools,
            host = base.host,
            platform = "test",
            zone = ZoneOffset.UTC,
            retry = base.retry,
        )
    }

    /** 最近建的那个 Hub 的作用域：模拟「进程死了」就是取消它 */
    private var currentScope: CoroutineScope? = null

    private fun hub(model: ModelClient, profile: dev.min.agent.llm.ModelProfile = pricedProfile): SessionHub {
        val scope = newScope().also { currentScope = it }
        return SessionHub(store, deps(model, profile), scope)
    }

    private suspend fun SessionHub.root() = create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    private suspend fun events(id: SessionId): List<SessionEvent> = store.open(id)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(rt: SessionRuntime) {
        val log = store.open(rt.sessionId)!!
        assertEquals(fold(log.header, log.read().toList()), rt.state.value)
    }

    /** 等会话彻底安静：没有回合、收件箱空 */
    private suspend fun settle(rt: SessionRuntime) {
        rt.awaitIdle()
        rt.state.first { it.turn == null && it.inbox.isEmpty() }
        rt.awaitIdle()
    }

    /** 直接把一条定时任务写进日志（创建走工具的那条路另有测试）；编号取下一个 */
    private suspend fun schedule(rt: SessionRuntime, every: Long? = 30 * minute, cron: String? = null, recurring: Boolean = true, budget: Double = 0.05, prompt: String = "检查构建"): String {
        val id = rt.state.value.nextCronId()
        rt.commit(
            listOf(
                CronScheduled(
                    id, prompt, recurring, budget, now.get(),
                    cron = cron, intervalMs = every.takeIf { cron == null },
                    expiresAtMs = if (recurring) now.get() + 7 * 24 * 60 * minute else null,
                ),
            ),
            Durability.Sync,
        )
        return id
    }

    private val answering = RoutingModel { req -> if (req.last().startsWith("[定时任务")) reply(TextPart("收到定时任务")) else reply(TextPart("好")) }

    // ---- 工具这条路：创建 / 列出 / 删除 ----

    @Test
    fun `the model can create, list and delete tasks through the tools`() = run {
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last == "安排" -> reply(
                    ToolCallPart(CallId("k1"), "CronCreate", args("every" to "30m", "prompt" to "检查构建", "max_budget_usd" to 0.1)),
                    ToolCallPart(CallId("k2"), "CronCreate", args("cron" to "0 9 * * 1-5", "prompt" to "晨报", "recurring" to false)),
                    stop = StopReason.ToolUse,
                )
                last.startsWith("已创建定时任务 c1") -> reply(ToolCallPart(CallId("k3"), "CronList", args()), stop = StopReason.ToolUse)
                last.contains("检查构建") && last.contains("晨报") -> reply(ToolCallPart(CallId("k4"), "CronDelete", args("id" to "c1")), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("安排")))
        settle(rt)
        val state = rt.state.value
        assertEquals(listOf("c1", "c2"), state.crons.map { it.id })
        val c1 = state.crons[0]
        assertEquals(CronStatus.Cancelled, c1.status)
        assertEquals(0.1, c1.maxBudgetUsd, 0.0)
        assertEquals(30 * minute, c1.intervalMs)
        assertEquals(t0 + 7 * 24 * 60 * minute, c1.expiresAtMs)
        val c2 = state.crons[1]
        assertTrue(c2.active)
        assertFalse(c2.recurring)
        assertNull("一次性任务不按时间过期", c2.expiresAtMs)
        assertEquals("0 9 * * 1-5", c2.cron)
        assertEquals("不写上限用默认值", 0.05, c2.maxBudgetUsd, 0.0)

        val toolText = state.toolResults.mapValues { (_, r) -> r.content.filterIsInstance<TextPart>().joinToString("") { it.text } }
        assertTrue(toolText.getValue(CallId("k1")).contains("每 30 分钟") && toolText.getValue(CallId("k1")).contains("花费上限 \$0.10"))
        assertTrue(toolText.getValue(CallId("k2")), toolText.getValue(CallId("k2")).contains("下次触发约 2026-10-08 09:00 UTC"))
        val list = toolText.getValue(CallId("k3"))
        assertTrue(list, list.contains("c1｜循环｜每 30 分钟｜单次上限 \$0.10") && list.contains("c2｜一次性｜cron 0 9 * * 1-5｜单次上限 \$0.05") && list.contains("提示词：晨报"))
        assertEquals("已取消定时任务 c1（每 30 分钟）。", toolText.getValue(CallId("k4")))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a self paced loop chains wakeups until it is stopped`() = run {
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last == "开始盯" -> reply(ToolCallPart(CallId("w1"), "ScheduleWakeup", args("delaySeconds" to 120, "prompt" to "继续盯构建", "reason" to "等 CI")), stop = StopReason.ToolUse)
                last.startsWith("[定时任务") && last.contains("继续盯构建") ->
                    reply(ToolCallPart(CallId("w2"), "ScheduleWakeup", args("delaySeconds" to 300, "prompt" to "继续盯构建")), stop = StopReason.ToolUse)
                last == "别盯了" -> reply(ToolCallPart(CallId("w3"), "ScheduleWakeup", args("stop" to true)), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("开始盯")))
        settle(rt)
        val first = rt.state.value.crons.single()
        assertEquals("wakeup", first.source)
        assertFalse(first.recurring)
        assertEquals(120_000L, first.intervalMs)

        now.set(t0 + 121_000)
        h.crons.tick(now.get())
        rt.state.first { s -> s.crons.size == 2 }
        settle(rt)
        val chained = rt.state.value.crons
        assertEquals(CronStatus.Fired, chained[0].status)
        assertTrue("唤醒的那一轮里又安排了下一轮", chained[1].active && chained[1].intervalMs == 300_000L)

        rt.send(ClientMsgId("u2"), listOf(TextPart("别盯了")))
        settle(rt)
        assertEquals(CronStatus.Cancelled, rt.state.value.crons[1].status)
        now.set(t0 + 3_600_000)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals("停了就不再有下一轮", 2, rt.state.value.crons.size)
        assertEquals(1, rt.state.value.crons[0].fires)
        assertLogIsTruth(rt)
    }

    // ---- 触发 ----

    @Test
    fun `a due task fires once the session is idle and the fired input is marked as cron, not as the user`() = run {
        val h = hub(answering)
        val rt = h.root()
        val id = schedule(rt)
        now.set(t0 + 10 * minute)
        h.crons.tick(now.get())
        assertEquals("还没到点", 0, rt.state.value.crons.single().fires)

        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        settle(rt)
        val state = rt.state.value
        assertEquals(1, state.crons.single().fires)
        assertEquals(t0 + 31 * minute, state.crons.single().lastFiredAtMs)
        val user = state.history.filterIsInstance<HistoryEntry.User>().single()
        assertEquals(InputOrigin.Cron(id), user.origin)
        assertTrue("不冒充人发的话", user.origin != InputOrigin.User)
        val text = (user.parts.single() as TextPart).text
        assertTrue(text, text.startsWith("[定时任务 c1 到点触发（每 30 分钟）。这是会话里预设的任务，不是用户此刻发的消息") && text.endsWith("\n检查构建"))
        assertTrue("告诉模型有花费上限", text.contains("\$0.0500"))
        assertEquals("不是回退点：不是人发的话", 0, state.rewindPoints.size)
        val log = events(rt.sessionId)
        val fired = log.indexOfFirst { it is CronFired }
        assertEquals("cron_fired 与输入同一次提交，紧挨着", ClientMsgId("cron:c1:1"), (log[fired + 1] as InputReceived).clientMsgId)
        assertEquals("转录里的分隔线要说是定时任务唤醒的，不是子 agent 报告", TurnTrigger.Cron, log.filterIsInstance<TurnStarted>().single().trigger)
        assertEquals("模型看到了它", 1, answering.requests.count { it.last().startsWith("[定时任务") })
        assertLogIsTruth(rt)

        // 下一次从这次触发往后数：不是从创建往后
        now.set(t0 + 50 * minute)
        h.crons.tick(now.get())
        assertEquals(1, rt.state.value.crons.single().fires)
        now.set(t0 + 62 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(2, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a task that comes due while a turn is running waits and fires as soon as the turn ends`() = run {
        val gate = CompletableDeferred<Unit>()
        val model = RoutingModel { req ->
            when {
                req.last() == "慢活" -> { r -> flow { gate.await(); emitAll(reply(TextPart("慢活干完"))(r)) } }
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        schedule(rt)
        rt.send(ClientMsgId("u1"), listOf(TextPart("慢活")))
        rt.state.first { it.turn?.openRequest != null }
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        assertEquals("忙时不触发", 0, rt.state.value.crons.single().fires)
        assertTrue(events(rt.sessionId).none { it is CronFired })

        gate.complete(Unit)
        // 回合一结束就触发，不用等下一次心跳
        rt.state.first { it.crons.single().fires == 1 }
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertEquals(listOf(InputOrigin.User, InputOrigin.Cron("c1")), rt.state.value.history.filterIsInstance<HistoryEntry.User>().map { it.origin })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a pending input in the inbox also keeps the session busy`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt)
        rt.commit(listOf(InputReceived(ClientMsgId("held"), listOf(TextPart("还没处理")), dev.min.agent.spi.Delivery.Queue)), Durability.Sync)
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        assertEquals(0, rt.state.value.crons.single().fires)
        assertEquals("收件箱里的话没动", 1, rt.state.value.inbox.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `two ticks at once fire the task only once`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt)
        now.set(t0 + 31 * minute)
        val scope = newScope()
        (1..4).map { scope.async { h.crons.tick(now.get()) } }.awaitAll()
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertEquals(1, events(rt.sessionId).count { it is CronFired })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a one shot fires once and is done`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt, every = 20 * minute, recurring = false)
        now.set(t0 + 21 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(CronStatus.Fired, rt.state.value.crons.single().status)
        now.set(t0 + 200 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals("不会再触发", 1, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a cron expression task fires at its wall clock time in the session zone`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt, cron = "30 9 * * *")
        now.set(t0 + 89 * minute) // 09:29
        h.crons.tick(now.get())
        assertEquals(0, rt.state.value.crons.single().fires)
        now.set(t0 + 91 * minute) // 09:31
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    // ---- 过期 ----

    @Test
    fun `a recurring task expires after seven days with a notice`() = run {
        val h = hub(answering)
        val rt = h.root()
        val notices = CopyOnWriteArrayList<CronNotice>()
        newScope().launch { h.cronNotices.collect { notices += it } }
        delay(100)
        schedule(rt)
        now.set(t0 + 7 * 24 * 60 * minute - 1)
        h.crons.tick(now.get())
        settle(rt)
        assertTrue("差一毫秒还没过期（但已经触发过了）", rt.state.value.crons.single().active)
        now.set(t0 + 7 * 24 * 60 * minute)
        h.crons.tick(now.get())
        val task = rt.state.value.crons.single()
        assertEquals(CronStatus.Expired, task.status)
        assertTrue(task.endedBecause!!.contains("满 7 天"))
        assertEquals(CronExpiry.Age, events(rt.sessionId).filterIsInstance<CronExpired>().single().reason)
        assertTrue(events(rt.sessionId).filterIsInstance<Note>().any { it.level == NoteLevel.Warning && it.text.contains("满 7 天") })
        withTimeout(5_000) { while (notices.isEmpty()) delay(20) }
        assertEquals(CronNotice.Kind.Expired, notices.single().kind)
        // 过期之后即使到点也不再触发
        val firesBefore = task.fires
        now.set(t0 + 8 * 24 * 60 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(firesBefore, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    // ---- 重开会话 ----

    private suspend fun restart(first: SessionHub, id: SessionId, at: Long): SessionHub {
        currentScope!!.cancel() // 进程死了
        now.set(at)
        return hub(answering).also { it.open(id) }
    }

    @Test
    fun `reopening restores unexpired tasks and does not catch up on missed fires`() = run {
        val h1 = hub(answering)
        val rt1 = h1.root()
        val id = rt1.sessionId
        schedule(rt1)
        now.set(t0 + 31 * minute)
        h1.crons.tick(now.get())
        settle(rt1)
        assertEquals(1, rt1.state.value.crons.single().fires)

        val h2 = restart(h1, id, t0 + 3 * 24 * 60 * minute)
        val rt2 = h2.get(id)!!
        val restored = rt2.state.value.crons.single()
        assertTrue("未过期的任务恢复了", restored.active)
        assertEquals(1, restored.fires)
        h2.crons.tick(now.get())
        settle(rt2)
        assertEquals("错过的几天不补跑，重开那一刻也不立刻触发", 1, rt2.state.value.crons.single().fires)
        now.set(now.get() + 31 * minute)
        h2.crons.tick(now.get())
        settle(rt2)
        assertEquals("从重开那刻重新数，只触发一次", 2, rt2.state.value.crons.single().fires)
        assertLogIsTruth(rt2)
    }

    @Test
    fun `reopening after seven days expires a recurring task, and a missed one shot is closed not run`() = run {
        val h1 = hub(answering)
        val rt1 = h1.root()
        val id = rt1.sessionId
        schedule(rt1)
        schedule(rt1, every = 20 * minute, recurring = false)
        schedule(rt1, every = 3 * 24 * 60 * minute, recurring = false)

        val h2 = restart(h1, id, t0 + 2 * 24 * 60 * minute)
        val rt2 = h2.get(id)!!
        val byId = rt2.state.value.crons.associateBy { it.id }
        assertTrue(byId.getValue("c1").active)
        assertEquals(CronStatus.Expired, byId.getValue("c2").status)
        assertTrue(byId.getValue("c2").endedBecause!!.contains("不补跑"))
        assertTrue("还没到点的一次性任务仍在", byId.getValue("c3").active)
        assertEquals(0, rt2.state.value.history.size)

        val h3 = restart(h2, id, t0 + 8 * 24 * 60 * minute)
        val rt3 = h3.get(id)!!
        val after = rt3.state.value.crons.associateBy { it.id }
        assertEquals(CronStatus.Expired, after.getValue("c1").status)
        assertTrue(after.getValue("c1").endedBecause!!.contains("满 7 天"))
        assertEquals("一次性任务不按时间过期，但它的时刻已经过了", CronStatus.Expired, after.getValue("c3").status)
        assertLogIsTruth(rt3)
    }

    @Test
    fun `a branch does not inherit the scheduled tasks`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt)
        schedule(rt, cron = "0 9 * * *")
        val branch = h.branch(rt.sessionId)
        assertTrue(branch.state.value.crons.none { it.active })
        assertEquals(2, branch.state.value.crons.size)
        assertEquals("原会话的任务不受影响", 2, rt.state.value.crons.count { it.active })
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(1, rt.state.value.crons.first().fires)
        assertEquals(0, branch.state.value.crons.sumOf { it.fires })
        assertLogIsTruth(branch)
        assertLogIsTruth(rt)
    }

    // ---- 花费上限 ----

    @Test
    fun `a run that spends up to the cap is stopped, the task is cancelled with the reason and the user is told`() = run {
        val model = RoutingModel { req ->
            // 定时运行里模型一直在调工具：不拦它就会无限烧钱（每个请求 0.015 美元）
            if (req.messages.first().parts.any { (it as? TextPart)?.text?.startsWith("[定时任务") == true })
                reply(ToolCallPart(CallId("p${req.messages.size}"), "Probe", args()), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val h = hub(model)
        val rt = h.root()
        val notices = CopyOnWriteArrayList<CronNotice>()
        newScope().launch { h.cronNotices.collect { notices += it } }
        delay(100)
        schedule(rt, budget = 0.04)
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        rt.state.first { it.crons.single().status == CronStatus.Expired }
        settle(rt)

        val state = rt.state.value
        assertEquals("花到 0.045 就停：不会有第四个请求", 3, model.requests.size)
        assertEquals(0.045, state.modelCosts.getValue("USD"), 1e-9)
        val task = state.crons.single()
        assertTrue(task.endedBecause!!, task.endedBecause!!.startsWith("花费上限"))
        val expired = events(rt.sessionId).filterIsInstance<CronExpired>().single()
        assertEquals(CronExpiry.Budget, expired.reason)
        assertTrue(expired.detail!!, expired.detail!!.contains("$0.0450") && expired.detail!!.contains("$0.0400"))
        assertTrue(events(rt.sessionId).filterIsInstance<Note>().any { it.level == NoteLevel.Warning && it.text.contains("花费上限") })
        withTimeout(5_000) { while (notices.isEmpty()) delay(20) }
        assertEquals(CronNotice.Kind.BudgetStopped, notices.single().kind)
        assertNull("回合停下了", state.turn)

        // 任务已取消，再到点也不触发
        now.set(t0 + 3 * 60 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertEquals(3, model.requests.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the cap is per run, so a cheap run leaves the task alive and every fire gets a fresh allowance`() = run {
        val h = hub(answering) // 每次运行只花 0.015
        val rt = h.root()
        schedule(rt, budget = 0.02)
        for (round in 1..3) {
            now.set(now.get() + 31 * minute)
            h.crons.tick(now.get())
            rt.state.first { it.crons.single().fires == round }
            settle(rt)
        }
        val state = rt.state.value
        assertTrue("三次共 0.045，超过单次上限 0.02，但每次都只花 0.015，任务没被停", state.crons.single().active)
        assertEquals(0.045, state.modelCosts.getValue("USD"), 1e-9)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a model without a USD price is not fired because the spend cannot be estimated`() = run {
        val h = hub(answering, TEST_PROFILE)
        val rt = h.root()
        schedule(rt)
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        val task = rt.state.value.crons.single()
        assertEquals(CronStatus.Expired, task.status)
        assertTrue(task.endedBecause!!.contains("没有 USD 定价"))
        assertEquals(CronExpiry.NoPricing, events(rt.sessionId).filterIsInstance<CronExpired>().single().reason)
        assertTrue("没有触发，也没有模型请求", events(rt.sessionId).none { it is CronFired } && answering.requests.isEmpty())
        assertLogIsTruth(rt)
    }

    // ---- 定时器 ----

    @Test
    fun `the scheduler drives the ticks by itself`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt)
        val handle = h.startScheduling(InProcessScheduler(newScope(), periodMs = 20))
        try {
            now.set(t0 + 31 * minute)
            withTimeout(10_000) { rt.state.first { it.crons.single().fires == 1 } }
            settle(rt)
        } finally {
            handle.close()
        }
        assertLogIsTruth(rt)
    }

    @Test
    fun `without a scheduler nothing fires by itself`() = run {
        val h = hub(answering)
        val rt = h.root()
        schedule(rt)
        // 创建任务这次状态变化引发的“空闲检查”要先跑完（那时还没到点），再把表拨到点之后
        delay(300)
        now.set(t0 + 3 * 60 * minute)
        delay(300)
        assertEquals("没有定时器、没有任何状态变化：时间过去了也不会自己触发", 0, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the in process scheduler ticks, survives a failing tick and stops when closed`() = run {
        val ticks = AtomicInteger(0)
        val scheduler = InProcessScheduler(newScope(), periodMs = 10)
        val handle = scheduler.start {
            if (ticks.incrementAndGet() == 2) error("某一次回调失败")
        }
        withTimeout(5_000) { while (ticks.get() < 5) delay(10) }
        handle.close()
        delay(50)
        val stopped = ticks.get()
        delay(100)
        assertEquals("关掉之后不再回调", stopped, ticks.get())
        assertTrue("失败的一次没有停掉定时器", stopped >= 5)
    }
}
