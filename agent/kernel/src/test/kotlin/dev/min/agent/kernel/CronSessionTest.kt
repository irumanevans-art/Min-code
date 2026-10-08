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

    /** 只是把入参转给端口的 Agent（与 BranchSessionTest 同款）：前台委派，等子回合结束 */
    private val agentTool = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true, delegates = true)
        override fun description(profile: HarnessProfile) = "委派"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, "委派")
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val request = dev.min.agent.spi.DelegateRequest(
                description = "任务",
                prompt = (input["prompt"] as JsonPrimitive).content,
                background = (input["run_in_background"] as? JsonPrimitive)?.content == "true",
            )
            val result = when (val r = ctx.agents.delegate(request)) {
                is dev.min.agent.spi.DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${r.child.value}")))
                is dev.min.agent.spi.DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
                is dev.min.agent.spi.DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
            }
            emit(ToolProgress.Done(result))
        }
    }

    private val tools: List<Tool<*>> = listOf(CronCreateTool, CronDeleteTool, CronListTool, ScheduleWakeupTool, probe, agentTool)

    private fun deps(model: ModelClient, profile: dev.min.agent.llm.ModelProfile = pricedProfile): KernelDeps {
        val base = testDeps(model, tools)
        return KernelDeps(
            clock = Clock { now.get() },
            ids = IdGenerator { prefix -> "${prefix}_${ids.incrementAndGet()}" },
            models = { ref ->
                check(ref == TEST_MODEL) { "未知模型 $ref" }
                ResolvedModel(profileOverride ?: profile, model)
            },
            tools = tools,
            host = base.host,
            platform = "test",
            zone = ZoneOffset.UTC,
            retry = base.retry,
        )
    }

    /** 测试中途换模型（价格）用：非空时覆盖建 Hub 时给的 profile */
    @Volatile private var profileOverride: dev.min.agent.llm.ModelProfile? = null

    /** 最近建的那个 Hub 的作用域：模拟「进程死了」就是取消它 */
    private var currentScope: CoroutineScope? = null

    private fun hub(model: ModelClient, profile: dev.min.agent.llm.ModelProfile = pricedProfile, limits: HubLimits = HubLimits()): SessionHub {
        val scope = newScope().also { currentScope = it }
        return SessionHub(store, deps(model, profile), scope, limits)
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
    private suspend fun schedule(rt: SessionRuntime, every: Long? = 30 * minute, cron: String? = null, recurring: Boolean = true, budget: Double? = null, prompt: String = "检查构建", source: String? = null): String {
        val id = rt.state.value.nextCronId()
        rt.commit(
            listOf(
                CronScheduled(
                    id, prompt, recurring, budget, now.get(),
                    cron = cron, intervalMs = every.takeIf { cron == null },
                    expiresAtMs = if (recurring) now.get() + 7 * 24 * 60 * minute else null, source = source,
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
        assertEquals(0.1, c1.maxBudgetUsd!!, 0.0)
        assertEquals(30 * minute, c1.intervalMs)
        assertEquals(t0 + 7 * 24 * 60 * minute, c1.expiresAtMs)
        val c2 = state.crons[1]
        assertTrue(c2.active)
        assertFalse(c2.recurring)
        assertNull("一次性任务不按时间过期", c2.expiresAtMs)
        assertEquals("0 9 * * 1-5", c2.cron)
        assertNull("不写上限就是不限，没有默认值", c2.maxBudgetUsd)

        val toolText = state.toolResults.mapValues { (_, r) -> r.content.filterIsInstance<TextPart>().joinToString("") { it.text } }
        assertTrue(toolText.getValue(CallId("k1")).contains("每 30 分钟") && toolText.getValue(CallId("k1")).contains("花费上限 \$0.10"))
        assertTrue(toolText.getValue(CallId("k2")), toolText.getValue(CallId("k2")).contains("下次触发约 2026-10-08 09:00 UTC"))
        val list = toolText.getValue(CallId("k3"))
        assertTrue(list, list.contains("c1｜循环｜每 30 分钟｜单次上限 \$0.10") && list.contains("c2｜一次性｜cron 0 9 * * 1-5｜单次上限 不限｜") && list.contains("提示词：晨报"))
        assertEquals("已取消定时任务 c1（每 30 分钟）。", toolText.getValue(CallId("k4")))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a wakeup chain shares one seven day lifetime from its first wakeup and then ends`() = run {
        val day = 24 * 60 * minute
        val nextDelay = AtomicInteger(300)
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last == "开始盯" -> reply(ToolCallPart(CallId("w${req.messages.size}"), "ScheduleWakeup", args("delaySeconds" to 120, "prompt" to "继续盯构建")), stop = StopReason.ToolUse)
                // 每一轮醒来都再安排下一轮：链会一直续下去，除非被 7 天的寿命拦住
                last.startsWith("[定时任务") -> reply(ToolCallPart(CallId("w${req.messages.size}"), "ScheduleWakeup", args("delaySeconds" to nextDelay.get(), "prompt" to "继续盯构建")), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("开始盯")))
        settle(rt)
        val first = rt.state.value.crons.single()
        assertEquals("第一次安排：寿命从现在起 7 天", t0 + 7 * day, first.expiresAtMs)

        // 一路接力到快满 7 天：每一轮的下一轮都继承同一个到期时刻，而不是各自再活 7 天
        now.set(t0 + 121_000)
        h.crons.tick(now.get())
        rt.state.first { it.crons.size == 2 }
        settle(rt)
        assertEquals("接力出来的下一轮继承到期时刻", t0 + 7 * day, rt.state.value.crons[1].expiresAtMs)
        nextDelay.set(60) // 快到寿命尽头了：再排 300 秒会越过它被拒，这一轮只排 60 秒
        now.set(t0 + 7 * day - 100_000)
        h.crons.tick(now.get())
        rt.state.first { it.crons.size == 3 }
        settle(rt)
        assertEquals(t0 + 7 * day, rt.state.value.crons[2].expiresAtMs)
        assertTrue(rt.state.value.crons[2].active)

        // 满 7 天：最后一轮在到期时刻被收掉（age），链到此为止
        now.set(t0 + 7 * day)
        h.crons.tick(now.get())
        settle(rt)
        val last = rt.state.value.crons[2]
        assertEquals(CronStatus.Expired, last.status)
        assertEquals(CronExpiry.Age, events(rt.sessionId).filterIsInstance<CronExpired>().last().reason)
        assertEquals("不再有下一轮", 3, rt.state.value.crons.size)
        assertLogIsTruth(rt)
    }

    @Test
    fun `scheduling past the end of the chain is refused with a reason instead of silently extending it`() = run {
        val day = 24 * 60 * minute
        val model = RoutingModel { req ->
            val last = req.last()
            when {
                last.startsWith("[定时任务") -> reply(ToolCallPart(CallId("late"), "ScheduleWakeup", args("delaySeconds" to 3600, "prompt" to "还想再盯")), stop = StopReason.ToolUse)
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        // 这条链还剩半小时就满 7 天；醒来的这一轮想再等一小时
        rt.commit(
            listOf(CronScheduled("c1", "继续", false, null, now.get(), intervalMs = 20 * minute, expiresAtMs = now.get() + 30 * minute, source = dev.min.agent.spi.CRON_SOURCE_WAKEUP)),
            Durability.Sync,
        )
        now.set(now.get() + 21 * minute)
        h.crons.tick(now.get())
        settle(rt)
        val text = rt.state.value.toolResults.getValue(CallId("late")).content.filterIsInstance<TextPart>().joinToString("") { it.text }
        assertTrue(text, text.contains("7 天") && text.contains("不再安排下一轮"))
        assertEquals("没有新的唤醒被安排", 1, rt.state.value.crons.size)
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
        assertTrue("没设上限就不提花费上限，只说这是预设任务", !text.contains("花费上限"))
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

    /** 定时运行里模型的脚本：burn = 一直调工具（每个请求 0.015 美元，不拦就无限烧钱）；calm = 一句话结束 */
    private val mode = java.util.concurrent.atomic.AtomicReference("burn")

    private fun ModelRequest.firedRun(): Boolean =
        messages.first().parts.any { (it as? TextPart)?.text?.startsWith("[定时任务") == true }

    private fun burningModel() = RoutingModel { req ->
        if (req.firedRun() && mode.get() == "burn") reply(ToolCallPart(CallId("p${req.messages.size}"), "Probe", args()), stop = StopReason.ToolUse)
        else reply(TextPart("好"))
    }

    /** 拨表到下一次触发并等这次运行收尾；[n] 是任务此刻应有的触发次数 */
    private suspend fun SessionHub.fireOnce(rt: SessionRuntime, n: Int, stops: Boolean = false) {
        now.set(now.get() + 31 * minute)
        crons.tick(now.get())
        rt.state.first { s -> s.crons.first().fires == n }
        // 超限的收尾（记事件、通知）在引擎自己的协程里，回合结束时它可能还没跑完
        if (stops) rt.state.first { s -> s.crons.first().stoppedFire == n }
        settle(rt)
    }

    private fun collectNotices(h: SessionHub): CopyOnWriteArrayList<CronNotice> =
        CopyOnWriteArrayList<CronNotice>().also { list -> newScope().launch { h.cronNotices.collect { list += it } } }

    @Test
    fun `an overrun stops only that run - interrupted by budget, the task stays active and the user is told`() = run {
        val model = burningModel()
        val h = hub(model)
        val rt = h.root()
        val notices = collectNotices(h)
        delay(100)
        schedule(rt, budget = 0.04)
        h.fireOnce(rt, 1, stops = true)

        val state = rt.state.value
        assertEquals("花到 0.045 就停：不会有第四个请求", 3, model.requests.size)
        assertEquals(0.045, state.modelCosts.getValue("USD"), 1e-9)
        assertEquals("回合结局是预算，不是用户中断", dev.min.agent.spi.TurnOutcome.Interrupted(dev.min.agent.spi.InterruptedBy.Budget), state.lastTurn!!.outcome)
        val task = state.crons.single()
        assertTrue("只停这一次运行，任务还在", task.active)
        assertEquals(1, task.overruns)
        val log = events(rt.sessionId)
        val stopped = log.filterIsInstance<dev.min.agent.spi.CronBudgetStopped>().single()
        assertEquals(1, stopped.fire)
        assertTrue(stopped.detail!!, stopped.detail!!.contains("\$0.0450") && stopped.detail!!.contains("\$0.0400"))
        assertTrue(log.none { it is CronExpired })
        assertTrue(log.filterIsInstance<Note>().any { it.level == NoteLevel.Warning && it.text.contains("花费上限") && it.text.contains("这一次运行") })
        withTimeout(5_000) { while (notices.isEmpty()) delay(20) }
        assertEquals(CronNotice.Kind.BudgetStopped, notices.single().kind)
        assertNull("回合停下了", state.turn)

        // 任务没取消：下一次到点照常触发，这次平静地结束
        mode.set("calm")
        h.fireOnce(rt, 2)
        assertEquals(dev.min.agent.spi.TurnOutcome.Completed, rt.state.value.lastTurn!!.outcome)
        assertTrue(rt.state.value.crons.single().active)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a run that finishes on its own is never counted as an overrun even if it cost more than the cap`() = run {
        val h = hub(answering) // 一次回复 0.015，上限 0.01：没有下一个请求可拦，运行正常结束
        val rt = h.root()
        schedule(rt, budget = 0.01)
        h.fireOnce(rt, 1)
        val state = rt.state.value
        assertEquals(dev.min.agent.spi.TurnOutcome.Completed, state.lastTurn!!.outcome)
        assertTrue("正常结束的运行不算超限，任务还在", state.crons.single().active)
        assertEquals(0, state.crons.single().overruns)
        assertTrue(events(rt.sessionId).none { it is dev.min.agent.spi.CronBudgetStopped || it is CronExpired })
        assertLogIsTruth(rt)
    }

    @Test
    fun `two overruns in a row end the task, an overrun after a clean run starts the count over`() = run {
        val h = hub(burningModel())
        val rt = h.root()
        val notices = collectNotices(h)
        delay(100)
        schedule(rt, budget = 0.04)
        h.fireOnce(rt, 1, stops = true)
        assertEquals(1, rt.state.value.crons.single().overruns)
        mode.set("calm")
        h.fireOnce(rt, 2) // 平静的一次
        mode.set("burn")
        h.fireOnce(rt, 3, stops = true)
        assertTrue("超限、正常、超限：不连续，任务还在", rt.state.value.crons.single().active)
        assertEquals("计数从头数", 1, rt.state.value.crons.single().overruns)

        h.fireOnce(rt, 4, stops = true) // 连着第二次超限
        rt.state.first { it.crons.single().status == CronStatus.Expired }
        val task = rt.state.value.crons.single()
        assertEquals(CronStatus.Expired, task.status)
        assertTrue(task.endedBecause!!, task.endedBecause!!.contains("连续 2 次"))
        val expired = events(rt.sessionId).filterIsInstance<CronExpired>().single()
        assertEquals(CronExpiry.Budget, expired.reason)
        withTimeout(5_000) { while (notices.none { it.kind == CronNotice.Kind.Expired }) delay(20) }
        // 暂停之后到点也不触发
        now.set(now.get() + 3 * 60 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(4, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a one shot and a wakeup stopped by the cap leave an event, a note and a notice - never silent`() = run {
        for (source in listOf(null, dev.min.agent.spi.CRON_SOURCE_WAKEUP)) {
            val h = hub(burningModel())
            val rt = h.root()
            val notices = collectNotices(h)
            delay(100)
            schedule(rt, every = 20 * minute, recurring = false, budget = 0.04, source = source)
            now.set(now.get() + 21 * minute)
            h.crons.tick(now.get())
            rt.state.first { it.crons.single().overruns == 1 }
            settle(rt)
            val log = events(rt.sessionId)
            assertEquals("$source", 1, log.count { it is dev.min.agent.spi.CronBudgetStopped })
            assertTrue("$source", log.filterIsInstance<Note>().any { it.level == NoteLevel.Warning && it.text.contains("花费上限") })
            withTimeout(5_000) { while (notices.isEmpty()) delay(20) }
            assertEquals("$source", CronNotice.Kind.BudgetStopped, notices.first().kind)
            assertEquals("$source", CronStatus.Fired, rt.state.value.crons.single().status)
            assertLogIsTruth(rt)
        }
    }

    @Test
    fun `spend of a sub agent counts toward the run and stops the whole tree`() = run {
        val model = RoutingModel { req ->
            val first = (req.messages.first().parts.firstOrNull() as? TextPart)?.text.orEmpty()
            when {
                first.contains("子任务") -> reply(ToolCallPart(CallId("c${req.messages.size}"), "Probe", args()), stop = StopReason.ToolUse) // 子 agent 一直烧
                req.firedRun() && req.messages.size == 1 -> reply(
                    ToolCallPart(CallId("a1"), "Agent", args("prompt" to "子任务", "run_in_background" to false)), stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("好"))
            }
        }
        val h = hub(model)
        val rt = h.root()
        schedule(rt, budget = 0.05)
        now.set(now.get() + 31 * minute)
        h.crons.tick(now.get())
        rt.state.first { it.crons.single().overruns == 1 }
        settle(rt)

        // 根自己只花了 0.015（一个请求），不到 0.05；加上子 agent 的三个请求 0.06 才超——只看根会话的账会放过它
        assertEquals("根 1 + 子 3，第 5 个请求被拦", 4, model.requests.size)
        assertTrue("根会话自己的花费不到上限", rt.state.value.modelCosts.getValue("USD") < 0.05)
        val child = rt.state.value.children.keys.single()
        val childRt = h.get(child)!!
        assertNull("子 agent 也停了", childRt.state.value.turn)
        assertTrue(rt.state.value.crons.single().active)
        assertEquals(dev.min.agent.spi.TurnOutcome.Interrupted(dev.min.agent.spi.InterruptedBy.Budget), rt.state.value.lastTurn!!.outcome)
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
    fun `a task without a cap is never stopped for spending - same as Claude Code`() = run {
        val rounds = AtomicInteger(0)
        val model = RoutingModel { req ->
            // 一次运行里连调 6 次工具：6 × 0.015 = 0.09 美元，远超旧的 0.05 默认值
            if (req.messages.first().parts.any { (it as? TextPart)?.text?.startsWith("[定时任务") == true } && rounds.incrementAndGet() <= 6)
                reply(ToolCallPart(CallId("p${rounds.get()}"), "Probe", args()), stop = StopReason.ToolUse)
            else reply(TextPart("干完了"))
        }
        val h = hub(model)
        val rt = h.root()
        schedule(rt) // 不给上限
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        rt.state.first { it.crons.single().fires == 1 }
        settle(rt)
        val state = rt.state.value
        assertEquals(7, model.requests.size)
        assertEquals(0.105, state.modelCosts.getValue("USD"), 1e-9)
        assertTrue("花多少都不停：任务仍在、回合正常结束", state.crons.single().active)
        assertEquals(dev.min.agent.spi.TurnOutcome.Completed, state.lastTurn!!.outcome)
        assertTrue(events(rt.sessionId).none { it is CronExpired })
        assertLogIsTruth(rt)
    }

    @Test
    fun `a task without a cap fires even when the model has no USD price`() = run {
        val h = hub(answering, TEST_PROFILE)
        val rt = h.root()
        schedule(rt) // 不给上限：没有要核对的东西
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertTrue(rt.state.value.crons.single().active)
        assertLogIsTruth(rt)
    }

    @Test
    fun `the host ceiling caps a task that gave no cap and wins over a larger one the model asked for`() = run {
        val model = RoutingModel { req ->
            if (req.messages.first().parts.any { (it as? TextPart)?.text?.startsWith("[定时任务") == true })
                reply(ToolCallPart(CallId("p${req.messages.size}"), "Probe", args()), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val h = hub(model, limits = HubLimits(cronMaxBudgetUsd = 0.04))
        val rt = h.root()
        schedule(rt, budget = 5.0, prompt = "模型要了 5 美元")
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        rt.state.first { it.crons.single().stoppedFire == 1 }
        settle(rt)
        assertEquals("天花板 0.04：花到 0.045 就停", 3, model.requests.size)
        val text = rt.state.value.history.filterIsInstance<HistoryEntry.User>().single().parts.filterIsInstance<TextPart>().single().text
        assertTrue("告诉模型的是实际生效的上限，不是它要的 5 美元", text.contains("\$0.0400") && !text.contains("5.0"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `a task cap bigger than the ceiling is told to the model as the ceiling at creation time`() = run {
        val model = RoutingModel { req ->
            if (req.last() == "安排") reply(ToolCallPart(CallId("k1"), "CronCreate", args("every" to "30m", "prompt" to "x", "max_budget_usd" to 5.0)), stop = StopReason.ToolUse)
            else reply(TextPart("好"))
        }
        val h = hub(model, limits = HubLimits(cronMaxBudgetUsd = 0.5))
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("安排")))
        settle(rt)
        val text = rt.state.value.toolResults.getValue(CallId("k1")).content.filterIsInstance<TextPart>().joinToString("") { it.text }
        assertTrue(text, text.contains("天花板 \$0.50") && text.contains("\$5.00") && text.contains("无人值守"))
        assertTrue(text, text.contains("不要向用户提问"))
        val list = h.crons.let { rt.state.value.crons.single() }
        assertEquals("任务里存的是模型给的值，天花板在触发时再取小", 5.0, list.maxBudgetUsd!!, 0.0)
    }

    @Test
    fun `a capped task on a model without a USD price skips that run once with a note and stays active`() = run {
        val h = hub(answering, TEST_PROFILE)
        val rt = h.root()
        val notices = collectNotices(h)
        delay(100)
        schedule(rt, budget = 0.1)
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get())
        val task = rt.state.value.crons.single()
        assertTrue("任务不再被永久取消", task.active)
        assertEquals(0, task.fires)
        val log = events(rt.sessionId)
        assertTrue("没有触发，也没有模型请求", log.none { it is CronFired || it is CronExpired } && answering.requests.isEmpty())
        val skipNotes = log.filterIsInstance<Note>().filter { it.level == NoteLevel.Warning && it.text.contains("USD") }
        assertEquals(1, skipNotes.size)
        assertTrue(skipNotes.single().text, skipNotes.single().text.contains("跳过") && skipNotes.single().text.contains("c1"))
        withTimeout(5_000) { while (notices.isEmpty()) delay(20) }
        assertEquals(CronNotice.Kind.Skipped, notices.single().kind)

        // 后面的心跳、后面的几次到点：照样跳过，不重复提示
        for (step in 1..3) {
            now.set(now.get() + 31 * minute)
            h.crons.tick(now.get())
            h.crons.tick(now.get() + 1)
        }
        assertEquals("提示只出一次", 1, events(rt.sessionId).filterIsInstance<Note>().count { it.text.contains("USD") })
        assertEquals(1, notices.size)
        assertEquals(0, rt.state.value.crons.single().fires)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a skipped occurrence is not made up later - once the model is priced the next occurrence runs`() = run {
        val h = hub(answering, TEST_PROFILE)
        val rt = h.root()
        schedule(rt, budget = 0.1)
        now.set(t0 + 31 * minute)
        h.crons.tick(now.get()) // 跳过这次
        assertEquals(0, rt.state.value.crons.single().fires)
        profileOverride = pricedProfile // 用户换回了有价格的模型
        now.set(t0 + 32 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals("错过的不补跑：换回来那一刻不会马上触发", 0, rt.state.value.crons.single().fires)
        now.set(t0 + 62 * minute) // 下一个整点（间隔从跳过那一刻 +31 起数）
        h.crons.tick(now.get())
        settle(rt)
        assertEquals(1, rt.state.value.crons.single().fires)
        assertTrue(rt.state.value.crons.single().active)
        assertLogIsTruth(rt)
    }

    @Test
    fun `a one shot waits for a priced model instead of being cancelled`() = run {
        val h = hub(answering, TEST_PROFILE)
        val rt = h.root()
        schedule(rt, every = 20 * minute, recurring = false, budget = 0.1)
        now.set(t0 + 21 * minute)
        h.crons.tick(now.get())
        assertTrue(rt.state.value.crons.single().active)
        assertEquals(0, rt.state.value.crons.single().fires)
        profileOverride = pricedProfile
        now.set(t0 + 25 * minute)
        h.crons.tick(now.get())
        settle(rt)
        assertEquals("一次性任务没丢，换回有价格的模型后触发", 1, rt.state.value.crons.single().fires)
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
