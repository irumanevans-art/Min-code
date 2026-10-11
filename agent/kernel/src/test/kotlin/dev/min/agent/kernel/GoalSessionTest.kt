package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Durability
import dev.min.agent.spi.Exec
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.ExecOptions
import dev.min.agent.spi.ExecResult
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalResumed
import dev.min.agent.spi.Tool
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HooksDisabledBy
import dev.min.agent.spi.HooksLoaded
import dev.min.agent.spi.ModelCharged
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ShellCommand
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.READ_ONLY
import dev.min.agent.testkit.FakeTool
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * `/goal` 接进回合收尾与生命周期（TurnRunner.goalContinues、GoalControl、GoalCheckIns）：脚本化的模型既当主回合又当评估器
 * （按 system 认出评估请求）。时间用可手动拨动的时钟，check-in 的节奏靠它断言。
 */
class GoalSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 当前测试用的会话存放目录（同一个测试里要开多个 hub 时各换一个，免得会话 id 重名） */
    private var customRoot: java.nio.file.Path? = null
    private val root: java.nio.file.Path get() = customRoot ?: tmp.root.toPath()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }
    }

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { (it as? TextPart)?.text.orEmpty() }

    private fun ModelRequest.allText(): String = messages.joinToString("\n") { m -> m.parts.joinToString("") { (it as? TextPart)?.text.orEmpty() } }

    private fun isEvaluator(req: ModelRequest) = req.system.any { it.contains("hook evaluator") }

    private fun isCompaction(req: ModelRequest) = req.lastText().contains("压缩成一份摘要")

    /** 宿主进程替身：所有进程都说还活着，让后台命令一直「在跑」（check-in 与评估的推迟靠它） */
    private object AliveExec : Exec {
        override suspend fun run(command: ShellCommand, options: ExecOptions): ExecResult = error("unused")
        override suspend fun kill(handle: ExecHandle) = Unit
        override suspend fun isAlive(handle: ExecHandle): Boolean? = true
    }

    /** 测试用的内核依赖：时钟可拨、重试不等真的时间 */
    private fun kernelDeps(model: ModelClient, exec: Exec?, clock: AtomicLong, tools: List<Tool<*>> = emptyList()): KernelDeps {
        val base = dev.min.agent.testkit.testDeps(model, emptyList())
        return KernelDeps(
            clock = Clock { clock.get() }, ids = base.ids, models = base.models, tools = tools,
            host = HostPorts(base.host.fs, base.host.search, exec ?: base.host.exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
            retry = RetryPolicy(maxAttempts = 5, baseDelayMs = 1, maxDelayMs = 5),
        )
    }

    private fun hub(model: ModelClient, exec: Exec? = null, clock: AtomicLong = AtomicLong(1_000_000L), deps: KernelDeps? = null): SessionHub {
        val store = FileSessionStore(root, { 0L })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps ?: kernelDeps(model, exec, clock), scope)
    }

    /** 从日志读出的全部事件（不是内存里的折叠结果）：断言「写下了什么」用它 */
    private suspend fun SessionRuntime.events(): List<SessionEvent> =
        FileSessionStore(root, { 0L }).peek(sessionId)!!.read().toList().map { it.event }

    /** 开一个回合（[start] 里触发）并等它结束：只认新开的那一回合，不会把上一回合的结束当成这一回合的 */
    private suspend fun <T> SessionRuntime.turnAfter(start: suspend () -> T): T {
        val before = state.value.lastTurn?.turnId
        val result = start()
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
        return result
    }

    /**
     * 心跳叫一次 check-in，并等它开的那一回合结束。上一回合刚结束时 turnJob 可能还没退出（isQuiescent 暂时为假），
     * 所以没送成就隔一会儿再叫，直到 check-in 的计数动了为止
     */
    private suspend fun SessionRuntime.tickAndWaitForCheckIn(deps: KernelDeps) {
        val before = state.value.lastTurn?.turnId
        val counted = state.value.goal.checkIns
        withTimeout(10_000) {
            while (state.value.goal.checkIns == counted) {
                GoalCheckIns(deps).tick(listOf(this@tickAndWaitForCheckIn))
                delay(20)
            }
        }
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    private suspend fun SessionRuntime.say(text: String) = turnAfter { send(ClientMsgId("u${System.nanoTime()}"), listOf(TextPart(text))) }

    /** 主回合：第一次说「开始」时做了一半；收到「还差测试」的指引后做完。评估器的回复由 [verdicts] 按顺序给 */
    private fun model(verdicts: List<String>): RoutingModel {
        val left = ConcurrentLinkedQueue(verdicts)
        return RoutingModel { req ->
            when {
                isEvaluator(req) -> reply(TextPart(left.poll() ?: """{"ok": true, "reason": "没有更多结论"}"""), usage = Usage(inputTokens = 7))
                isCompaction(req) -> reply(TextPart("摘要要点：已经做了一半"))
                req.lastText().contains("还差测试") -> reply(TextPart("做完了"))
                else -> reply(TextPart("做了一半"))
            }
        }
    }

    private fun SessionRuntime.commitGoal(condition: String) = runBlocking { commit(listOf(GoalSet(condition)), Durability.Sync) }

    @Test
    fun `a goal that is not met keeps the session going and is met on the next evaluation`() = run {
        val model = model(listOf("""{"ok": false, "reason": "还差测试"}""", """{"ok": true, "reason": "测试全绿"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("测试全绿")
        rt.say("开始")
        assertTrue("模型看到了指引", model.requests.any { !isEvaluator(it) && it.lastText().contains("还差测试") })
        val goal = rt.state.value.goal
        assertNull(goal.condition)
        assertEquals(GoalEndReason.Achieved, goal.lastEnded?.reason)
        assertEquals(2, goal.lastEnded?.turns)
        assertEquals("测试全绿", goal.lastEnded?.condition)
    }

    @Test
    fun `an impossible verdict ends the goal and records it as impossible, with the reason`() = run {
        val model = model(listOf("""{"ok": false, "impossible": true, "reason": "依赖的服务已经下线"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("调通下线的服务")
        rt.say("开始")
        val goal = rt.state.value.goal
        assertNull(goal.condition)
        assertEquals(GoalEndReason.Impossible, goal.lastEnded?.reason)
        assertEquals("依赖的服务已经下线", goal.lastEnded?.lastReason)
        assertEquals(1, model.requests.count { !isEvaluator(it) })
    }

    @Test
    fun `the evaluator reads the whole conversation so far, including the earliest user message`() = run {
        val model = model(listOf("""{"ok": false, "reason": "还差测试"}""", """{"ok": true, "reason": "好"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("测试全绿")
        rt.say("第一句：先把登录模块的测试修好")
        val evaluations = model.requests.filter { isEvaluator(it) }
        assertTrue(evaluations.size >= 2)
        val last = evaluations.last().allText()
        assertTrue("评估器能看到更早的用户消息", last.contains("第一句：先把登录模块的测试修好"))
        assertTrue("也能看到助手的回答", last.contains("做了一半"))
        assertTrue("对话在 conversation 字段里", last.contains("conversation"))
    }

    @Test
    fun `setting a goal while idle starts a turn with the condition as the directive`() = run {
        val model = model(listOf("""{"ok": true, "reason": "好"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        val result = rt.turnAfter { rt.setGoal("测试全绿") }
        assertEquals(GoalSetResult.Set(startedTurn = true), result)
        assertEquals("测试全绿", model.requests.first { !isEvaluator(it) }.lastText())
        assertNull(rt.state.value.goal.condition)
    }

    @Test
    fun `setting a goal while a turn runs only sets it, and it is evaluated when that turn ends`() = run {
        val gate = CompletableDeferred<Unit>()
        val model = RoutingModel { req ->
            if (isEvaluator(req)) reply(TextPart("""{"ok": true, "reason": "好"}"""))
            else { _ -> flow { gate.await(); emitAll(reply(TextPart("慢慢做"))(req)) } }
        }
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("busy"), listOf(TextPart("先干这个")))
        rt.state.first { it.turn != null }
        assertEquals(GoalSetResult.Set(startedTurn = false), rt.setGoal("测试全绿"))
        assertEquals("测试全绿", rt.state.value.goal.condition)
        gate.complete(Unit)
        rt.state.first { it.turn == null && it.lastTurn != null }
        assertEquals("忙的时候不另开回合：整段只有这一个主回合的请求", 1, model.requests.count { !isEvaluator(it) })
        assertNull("回合结束时才评估，满足了就清掉", rt.state.value.goal.condition)
    }

    @Test
    fun `a background subagent still working defers the evaluation, and its report brings the evaluation back`() = run {
        // CC：「If a subagent or a background shell command is still running when a turn ends, Claude Code skips the evaluation」。
        // 只看后台命令的话，主回合把活派给后台子 agent 就收尾，评估器在活还没干完时就被问、甚至判成已达成
        val childMayFinish = CompletableDeferred<Unit>()
        var evaluations = 0
        val model = RoutingModel { req ->
            when {
                isEvaluator(req) -> { evaluations++; reply(TextPart("""{"ok": true, "reason": "完成"}""")) }
                req.messages.size == 1 && req.lastText().contains("慢活") -> { r -> flow { childMayFinish.await(); emitAll(reply(TextPart("慢活做完了"))(r)) } }
                req.messages.last().parts.any { it is dev.min.agent.protocol.ToolResultPart } -> reply(TextPart("派出去了，等它回报"))
                req.lastText().contains("开始") -> reply(
                    ToolCallPart(CallId("a1"), "Agent", kotlinx.serialization.json.buildJsonObject {
                        put("description", kotlinx.serialization.json.JsonPrimitive("慢活"))
                        put("prompt", kotlinx.serialization.json.JsonPrimitive("慢活"))
                        put("run_in_background", kotlinx.serialization.json.JsonPrimitive(true))
                    }),
                    stop = StopReason.ToolUse,
                )
                else -> reply(TextPart("收到回报，活干完了"))
            }
        }
        val deps = kernelDeps(model, null, AtomicLong(1_000_000L), tools = listOf(dev.min.agent.tools.AgentToolOf(emptyList())))
        val rt = hub(model, deps = deps).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("慢活做完")
        rt.say("开始")
        assertEquals("子 agent 还在干活：这一回合不该评估", 0, evaluations)
        assertEquals("慢活做完", rt.state.value.goal.condition)

        childMayFinish.complete(Unit)
        withTimeout(10_000) { rt.state.first { it.goal.condition == null } }
        assertEquals(1, evaluations)
        assertEquals(GoalEndReason.Achieved, rt.state.value.goal.lastEnded?.reason)
    }

    @Test
    fun `three turns in a row without tool use stop the loop with a warning and keep the goal`() = run {
        val model = model(List(20) { """{"ok": false, "reason": "还差测试"}""" })
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        // 第 1、2 回合各评估一次（没有工具调用，连续数到 2）；第 3 回合连续数到 3，停下、不再评估（不烧钱）
        assertEquals(2, model.requests.count { isEvaluator(it) })
        assertEquals("A", rt.state.value.goal.condition)
        assertTrue(rt.events().any { it is Note && it.level == NoteLevel.Warning && it.text.contains("没有调用工具") })
    }

    @Test
    fun `turns that call tools keep the goal going past the no-tool limit`() = run {
        // 主模型奇数次请求调工具、偶数次回话：每次评估前都有一批工具调用，连续无工具回合数不会到 3，评估一直照常进行
        val probe = FakeTool("Probe", READ_ONLY) { ToolResult(listOf(TextPart("ok"))) }
        var evaluations = 0
        var mainRequests = 0
        val model = RoutingModel { req ->
            when {
                isEvaluator(req) -> { evaluations++; reply(TextPart(if (evaluations < 5) """{"ok": false, "reason": "接着干"}""" else """{"ok": true, "reason": "完成"}""")) }
                mainRequests++ % 2 == 0 -> reply(ToolCallPart(CallId("c${mainRequests}"), "Probe", kotlinx.serialization.json.JsonObject(emptyMap())), stop = StopReason.ToolUse)
                else -> reply(TextPart("这一步做完了"))
            }
        }
        val rt = hub(model, deps = kernelDeps(model, null, AtomicLong(1_000_000L), listOf(probe))).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("很长的任务")
        rt.say("开始")
        assertEquals(5, evaluations)
        assertNull(rt.state.value.goal.condition)
    }

    @Test
    fun `an evaluation that cannot be read keeps the goal and ends the turn`() = run {
        val model = model(listOf("这不是 JSON"))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("测试全绿")
        rt.say("开始")
        val goal = rt.state.value.goal
        assertEquals("测试全绿", goal.condition)
        assertEquals(0, goal.turns)
        assertEquals(1, model.requests.count { !isEvaluator(it) })
    }

    @Test
    fun `hooks switched off refuse the goal and nothing is set or run`() = run {
        for (reason in HooksDisabledBy.values()) {
            customRoot = tmp.newFolder().toPath()
            val model = model(listOf("""{"ok": true, "reason": "好"}"""))
            val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
            rt.commit(listOf(HooksLoaded(emptyList(), emptyList(), null, reason)), Durability.Sync)
            val result = rt.setGoal("A")
            assertTrue("拒绝并给出原因：$reason", result is GoalSetResult.Refused && result.reason.contains("不可用"))
            assertNull(rt.state.value.goal.condition)
            assertTrue(model.requests.isEmpty())
            assertFalse(rt.events().any { it is GoalSet })
        }
    }

    @Test
    fun `an unrecoverable error clears the goal with its reason and is not retried`() = run {
        var mainRequests = 0
        val model = RoutingModel { req ->
            if (isEvaluator(req)) reply(TextPart("""{"ok": true, "reason": "好"}"""))
            else { _ -> flow<ModelStreamEvent> { mainRequests++; throw ModelException(ModelFailureKind.Auth, "认证失效", httpStatus = 401, beforeFirstByte = true) } }
        }
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        assertNull(rt.state.value.goal.condition)
        assertEquals(GoalEndReason.Error, rt.state.value.goal.lastEnded?.reason)
        assertTrue(rt.state.value.goal.lastEnded?.detail?.contains("认证失效") == true)
        assertEquals(1, mainRequests)
        assertTrue(rt.events().any { it is Note && it.text.startsWith("Goal cleared after an unrecoverable error") })
    }

    @Test
    fun `a retryable error is retried three times then the goal pauses, and the next message resumes it`() = run {
        var failures = 4
        val model = RoutingModel { req ->
            when {
                isEvaluator(req) -> reply(TextPart("""{"ok": true, "reason": "好"}"""))
                failures > 0 -> { _ -> flow<ModelStreamEvent> { failures--; throw ModelException(ModelFailureKind.Overloaded, "过载", beforeFirstByte = true) } }
                else -> reply(TextPart("做完了"))
            }
        }
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        assertEquals("第一次加三次重试，共 4 次请求", 4, model.requests.count { !isEvaluator(it) })
        assertEquals("A", rt.state.value.goal.condition)
        assertTrue(rt.state.value.goal.paused!!.contains("过载"))
        assertTrue(rt.events().any { it is Note && it.text.startsWith("Goal still active") })
        // 用户下一句话：暂停解除，目标接着评估（这次模型成功，评估通过）
        rt.say("继续")
        assertNull(rt.state.value.goal.paused)
        assertNull(rt.state.value.goal.condition)
    }

    @Test
    fun `a rate limit pauses the goal at once without retrying`() = run {
        var requests = 0
        val model = RoutingModel { req ->
            if (isEvaluator(req)) reply(TextPart("""{"ok": true, "reason": "好"}"""))
            else { _ -> flow<ModelStreamEvent> { requests++; throw ModelException(ModelFailureKind.RateLimited, "速率限制", beforeFirstByte = true) } }
        }
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        assertEquals(1, requests)
        assertTrue(rt.state.value.goal.paused!!.contains("速率限制"))
        assertEquals("A", rt.state.value.goal.condition)
    }

    @Test
    fun `an idle check-in lands in the inbox, starts a turn, and waits double each time`() = run {
        val clock = AtomicLong(1_000_000L)
        val model = model(listOf())
        val deps = kernelDeps(model, AliveExec, clock)
        val rt = hub(model, deps = deps).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.background.adoptShell(ExecHandle("sh-goal", 1, GuestPath("/root/.min/exec-logs/sh-goal.log")), "sleep 999", null)
        rt.say("开始")
        // 回合结束时后台还在跑：推迟评估，挂起时刻记下
        assertEquals(1_000_000L, rt.state.value.goal.waitSinceMs)
        assertTrue(model.requests.none { isEvaluator(it) })
        val minute = 60_000L
        // 不到 30 分钟：不送
        clock.set(1_000_000L + 29 * minute)
        GoalCheckIns(deps).tick(listOf(rt))
        assertEquals(0, rt.state.value.goal.checkIns)
        // 满 30 分钟：送进收件箱，开一回合，模型看到的是 check-in 的话
        clock.set(1_000_000L + 30 * minute)
        rt.tickAndWaitForCheckIn(deps)
        assertTrue(model.requests.any { it.allText().contains("后台还有在跑的工作") })
        assertEquals(1, rt.state.value.goal.checkIns)
        assertEquals(1, rt.state.value.goal.idleCheckIns)
        assertTrue(rt.events().any { it is dev.min.agent.spi.GoalCheckIn && it.idle })
        // 第二次要等两倍（从第一次送出的时刻数）：+30 分钟时不送，+60 分钟时送
        clock.set(1_000_000L + 30 * minute + 59 * minute)
        GoalCheckIns(deps).tick(listOf(rt))
        assertEquals(1, rt.state.value.goal.checkIns)
        clock.set(1_000_000L + 30 * minute + 60 * minute)
        rt.tickAndWaitForCheckIn(deps)
        assertEquals(2, rt.state.value.goal.checkIns)
        assertEquals(2, rt.state.value.goal.idleCheckIns)
    }

    @Test
    fun `idle check-ins stop at three until the user speaks`() = run {
        val clock = AtomicLong(1_000_000L)
        val model = model(listOf())
        val deps = kernelDeps(model, AliveExec, clock)
        val rt = hub(model, deps = deps).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.background.adoptShell(ExecHandle("sh-goal", 1, GuestPath("/root/.min/exec-logs/sh-goal.log")), "sleep 999", null)
        rt.say("开始")
        val minute = 60_000L
        // 间隔：30 分钟、60 分钟、120 分钟之后各一次；之后不送
        var at = 1_000_000L
        for (gap in listOf(30L, 60L, 120L)) {
            at += gap * minute
            clock.set(at)
            rt.tickAndWaitForCheckIn(deps)
        }
        assertEquals(3, rt.state.value.goal.idleCheckIns)
        assertTrue("第三次说明空闲 check-in 暂停", rt.events().any { it is Note && it.text.contains("发满 3 次") })
        clock.set(at + 240 * minute)
        GoalCheckIns(deps).tick(listOf(rt))
        assertEquals("第四次空闲 check-in 不送", 3, rt.state.value.goal.idleCheckIns)
        // 用户说话：计数清零，又能送
        rt.say("你还在吗")
        assertEquals(0, rt.state.value.goal.idleCheckIns)
    }

    @Test
    fun `reopening a session with an active goal writes goal_resumed and clears the counts`() = run {
        val clock = AtomicLong(1_000_000L)
        val model = model(List(5) { """{"ok": false, "reason": "还差测试"}""" })
        val hub = hub(model, clock = clock)
        val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("测试全绿")
        rt.say("开始")
        assertTrue("重开前评估过", rt.state.value.goal.turns > 0)
        val reopened = hub(model, clock = clock).open(rt.sessionId, resumedByUser = true)!!
        val goal = reopened.state.value.goal
        assertEquals("测试全绿", goal.condition)
        assertEquals("重开后回合数从 0 数", 0, goal.turns)
        assertEquals(0, goal.checkIns)
        assertTrue(reopened.events().any { it is GoalResumed })
    }

    @Test
    fun `reopening a session whose goal was achieved does not resume it`() = run {
        val model = model(listOf("""{"ok": true, "reason": "好"}"""))
        val clock = AtomicLong(1_000_000L)
        val rt = hub(model, clock = clock).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        assertNull(rt.state.value.goal.condition)
        val reopened = hub(model, clock = clock).open(rt.sessionId, resumedByUser = true)!!
        assertNull(reopened.state.value.goal.condition)
        assertFalse(reopened.events().any { it is GoalResumed })
    }

    @Test
    fun `compaction keeps the goal and the evaluation still runs on the compacted conversation`() = run {
        var satisfied = false
        val model = RoutingModel { req ->
            when {
                isEvaluator(req) -> reply(TextPart(if (satisfied) """{"ok": true, "reason": "好"}""" else """{"ok": false, "reason": "接着干"}"""), usage = Usage(inputTokens = 7))
                isCompaction(req) -> reply(TextPart("摘要要点：已经做了一半"))
                else -> reply(TextPart("做了一半"))
            }
        }
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("第一句：开工")
        assertEquals("没满足、连续无工具停下，目标保留", "A", rt.state.value.goal.condition)
        rt.turnAfter { assertTrue(rt.compact()) }
        assertEquals("压缩之后目标还在", "A", rt.state.value.goal.condition)
        satisfied = true
        rt.say("继续")
        assertNull(rt.state.value.goal.condition)
        val evaluations = model.requests.filter { isEvaluator(it) }
        assertTrue("压缩后评估照常", evaluations.isNotEmpty())
        assertTrue("评估看到的是压缩后的摘要", evaluations.last().allText().contains("摘要要点"))
    }

    @Test
    fun `evaluation requests are billed through the same ledger as the turn`() = run {
        val model = model(listOf("""{"ok": true, "reason": "好"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("A")
        rt.say("开始")
        val charged = rt.events().filterIsInstance<ModelCharged>()
        assertTrue("评估的用量落了账：${charged.map { it.usage.inputTokens }}", charged.any { it.usage.inputTokens == 7L })
    }

    @Test
    fun `folding the log gives back the same goal state as the running session`() = run {
        val model = model(listOf("""{"ok": false, "reason": "还差测试"}""", """{"ok": true, "reason": "好"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.commitGoal("测试全绿")
        rt.say("开始")
        rt.commitGoal("又一个目标")
        val store = FileSessionStore(root, { 0L })
        val log = store.peek(rt.sessionId)!!
        val folded = dev.min.agent.kernel.fold(log.header, log.read().toList())
        assertEquals(rt.state.value.goal, folded.goal)
    }
}
