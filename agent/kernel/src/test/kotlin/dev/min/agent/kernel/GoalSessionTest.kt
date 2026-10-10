package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GoalEndReason
import dev.min.agent.spi.GoalSet
import dev.min.agent.spi.GuestPath
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `/goal` 接进回合收尾（TurnRunner.goalContinues）：脚本化的模型既当主回合又当评估器（按 system 认出评估请求）。
 * 满足 / 判定不可能 → 目标清掉并记下结局；没满足 → 理由交回模型、回合继续；评估出错 → 目标保留。
 */
class GoalSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

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

    private fun ModelRequest.lastText(): String = messages.last().parts.joinToString("\n") { (it as? TextPart)?.text.orEmpty() }

    private fun isEvaluator(req: ModelRequest) = req.system.any { it.contains("hook evaluator") }

    private fun hub(model: ModelClient): SessionHub {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val base = dev.min.agent.testkit.testDeps(model, emptyList())
        val deps = KernelDeps(
            clock = { System.currentTimeMillis() }, ids = base.ids, models = base.models, tools = emptyList(),
            host = dev.min.agent.kernel.HostPorts(base.host.fs, base.host.search, base.host.exec, base.host.artifacts),
            platform = "test", zone = ZoneOffset.UTC, memoryHome = GuestPath("/root"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return SessionHub(store, deps, scope)
    }

    private suspend fun SessionRuntime.say(text: String) {
        val before = state.value.lastTurn?.turnId
        send(ClientMsgId("u${System.nanoTime()}"), listOf(TextPart(text)))
        state.first { it.turn == null && it.lastTurn != null && it.lastTurn?.turnId != before }
    }

    /** 主回合：第一次说「开始」时做了一半；收到「还差测试」的指引后做完。评估器的回复由 [verdicts] 按顺序给 */
    private fun model(verdicts: List<String>): RoutingModel {
        val left = java.util.concurrent.ConcurrentLinkedQueue(verdicts)
        return RoutingModel { req ->
            when {
                isEvaluator(req) -> reply(TextPart(left.poll() ?: """{"ok": true, "reason": "没有更多结论"}"""), usage = dev.min.agent.protocol.Usage(inputTokens = 7))
                req.lastText().contains("还差测试") -> reply(TextPart("做完了"))
                else -> reply(TextPart("做了一半"))
            }
        }
    }

    private fun SessionRuntime.setGoal(condition: String) = runBlocking { commit(listOf(GoalSet(condition)), Durability.Sync) }

    @Test
    fun `a goal that is not met keeps the session going and is met on the next evaluation`() = run {
        val model = model(listOf("""{"ok": false, "reason": "还差测试"}""", """{"ok": true, "reason": "测试全绿"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.setGoal("测试全绿")
        rt.say("开始")
        // 第一次评估说没满足：理由交回模型，同一回合里模型接着做完
        assertTrue("模型看到了指引", model.requests.any { !isEvaluator(it) && it.lastText().contains("还差测试") })
        // 第二次评估说满足：目标清掉，记为达成，两次评估都计了数
        val goal = rt.state.value.goal
        assertNull(goal.condition)
        assertEquals(GoalEndReason.Achieved, goal.lastEnded?.reason)
        assertEquals(2, goal.lastEnded?.turns)
        assertEquals("测试全绿", goal.lastEnded?.condition)
    }

    @Test
    fun `an impossible verdict ends the goal and records it as impossible`() = run {
        val model = model(listOf("""{"ok": false, "impossible": true, "reason": "依赖的服务已经下线"}"""))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.setGoal("调通下线的服务")
        rt.say("开始")
        val goal = rt.state.value.goal
        assertNull(goal.condition)
        assertEquals(GoalEndReason.Impossible, goal.lastEnded?.reason)
        // 判定不可能不回到模型：只有主回合的那一次请求
        assertEquals(1, model.requests.count { !isEvaluator(it) })
    }

    @Test
    fun `an evaluation that cannot be read keeps the goal and ends the turn`() = run {
        val model = model(listOf("这不是 JSON"))
        val rt = hub(model).create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.setGoal("测试全绿")
        rt.say("开始")
        val goal = rt.state.value.goal
        assertEquals("测试全绿", goal.condition)
        assertEquals(0, goal.turns)
        assertEquals(1, model.requests.count { !isEvaluator(it) })
    }

    @Test
    fun `evaluation requests are billed through the same ledger as the turn`() = run {
        val model = model(listOf("""{"ok": true, "reason": "好"}"""))
        val hub = hub(model)
        val rt = hub.create(GuestPath("/work"), TEST_MODEL, PermissionMode.BypassPermissions)
        rt.setGoal("A")
        rt.say("开始")
        // 评估请求带着完成条件，且用量（inputTokens=7）记进了本会话日志的 ModelCharged
        assertTrue("评估请求带着完成条件", model.requests.first { isEvaluator(it) }.allTextForTest().contains("A"))
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val charged = store.peek(rt.sessionId)!!.read().toList().map { it.event }.filterIsInstance<dev.min.agent.spi.ModelCharged>()
        assertTrue("评估的用量落了账：${charged.map { it.usage.inputTokens }}", charged.any { it.usage.inputTokens == 7L })
    }

    private fun ModelRequest.allTextForTest(): String = messages.joinToString("\n") { m -> m.parts.joinToString("") { (it as? TextPart)?.text.orEmpty() } }
}
