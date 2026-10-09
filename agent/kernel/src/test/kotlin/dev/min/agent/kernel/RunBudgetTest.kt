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
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Clock
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.IdGenerator
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.RecapTrigger
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「一次运行」的预算账本（[SessionHub.budget]）：范围是**本次运行、整棵会话树**——子 agent 的花费把根会话的
 * 下一个请求也拦下（m0 原来按各会话自己折叠的 modelCosts 判，树里别的会话花的不算）；resume 进来的旧回合
 * 不属于这次运行，不占预算（m0 原来带着历史先拦截）。数据从 `model_charged` 提交里取（金额在请求当时冻结），
 * 压缩、回顾这些辅助请求同样计账。
 */
class RunBudgetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    /** 1M 输入 $100,000、输出不计：一条默认回复（10 输入 token）正好 $1，好把线画在两笔请求之间 */
    private val pricing = ModelPricing("USD", inputPerMTok = 100_000.0, outputPerMTok = 0.0, asOf = "2026-10-09")
    private val pricedProfile = TEST_PROFILE.copy(pricing = pricing)

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

    /** 前台委派（与 CronSessionTest 同款）：等子回合结束、把报告带回来 */
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
                background = false,
            )
            val result = when (val r = ctx.agents.delegate(request)) {
                is dev.min.agent.spi.DelegateResult.Started -> ToolResult(listOf(TextPart("已启动 ${r.child.value}")))
                is dev.min.agent.spi.DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
                is dev.min.agent.spi.DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
            }
            emit(ToolProgress.Done(result))
        }
    }

    private fun deps(model: ModelClient, budget: Double?): KernelDeps {
        val base = testDeps(model, listOf(probe, agentTool))
        return KernelDeps(
            clock = base.clock,
            ids = base.ids,
            models = { ref ->
                check(ref == TEST_MODEL) { "未知模型 $ref" }
                ResolvedModel(pricedProfile, model)
            },
            tools = base.tools,
            host = base.host,
            platform = "test",
            zone = base.zone,
            retry = base.retry,
            maxBudgetUsd = budget,
        )
    }

    private fun hub(model: ModelClient, budget: Double? = null): SessionHub =
        SessionHub(store, deps(model, budget), newScope())

    private suspend fun SessionHub.root() = create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    @Test
    fun `子 agent 的花费把整棵树的下一个请求也拦下`() = run {
        val model = RoutingModel { req ->
            when (req.last()) {
                "派活" -> reply(ToolCallPart(CallId("k1"), "Agent", args("prompt" to "任务")), stop = StopReason.ToolUse)
                "任务" -> reply(ToolCallPart(CallId("k2"), "Probe", args()), stop = StopReason.ToolUse)
                // 子 agent 的第二笔（看到探针结果）与根会话收报告后的那笔都不该再发出去：上限 $1.5，
                // 根的第一笔 + 子 agent 的第一笔已经是 $2
                else -> reply(TextPart("不该走到这"))
            }
        }
        val h = hub(model, budget = 1.5)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("派活")))
        rt.awaitIdle()
        assertEquals("到限后树里谁都不再发请求（根一笔 + 子 agent 一笔）", 2, model.requests.size)
        assertEquals("两笔都进了「一次运行」的账本", 2.0, h.budget.spentUsd(), 1e-9)
    }

    @Test
    fun `resume 带来的旧花费不算进这次运行的预算`() = run {
        val first = RoutingModel { _ -> reply(TextPart("答")) }
        val scope1 = newScope()
        val h1 = SessionHub(store, deps(first, null), scope1)
        val rt = h1.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("问")))
        rt.awaitIdle()
        assertEquals("旧会话的日志里已有一笔 $1", 1.0, rt.state.value.modelCosts.getValue("USD"), 1e-9)
        val id = rt.sessionId
        scope1.cancel() // 模拟进程退出：写者下线，新进程重开

        val second = RoutingModel { _ -> reply(TextPart("再答")) }
        val h2 = hub(second, budget = 0.5) // 上限低于旧账：旧花费不属于这次运行，不该拦
        val rt2 = h2.open(id)!!
        rt2.send(ClientMsgId("u2"), listOf(TextPart("再问")))
        rt2.awaitIdle()
        assertEquals("旧花费不占这次运行的预算，请求照发", 1, second.requests.size)
    }

    @Test
    fun `压缩请求的计费也进这次运行的账本`() = run {
        val model = RoutingModel { req ->
            // 主回合答一句话；压缩的摘要请求回摘要（两种请求都收 10 个输入 token = $1）
            if (req.messages.size > 4) reply(TextPart("## 摘要\n要点。")) else reply(TextPart("答"))
        }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("A".repeat(30_000))))
        rt.awaitIdle()
        assertTrue(rt.compact("要点"))
        rt.state.first { it.lastTurn?.trigger == dev.min.agent.spi.TurnTrigger.Compact }
        assertEquals("主回合 + 压缩摘要各一笔", 2, model.requests.size)
        assertEquals("压缩的请求是辅助请求，同样进「一次运行」的账本（主回合 $1 + 摘要 $1）", 2.0, h.budget.spentUsd(), 1e-9)
    }

    @Test
    fun `会话回顾的计费也进这次运行的账本`() = run {
        val model = RoutingModel { _ -> reply(TextPart("刚才在回答问题。")) }
        val h = hub(model)
        val rt = h.root()
        rt.send(ClientMsgId("u1"), listOf(TextPart("问")))
        rt.awaitIdle()
        rt.recap(RecapTrigger.Manual)
        assertEquals("主回合 $1 + 回顾 $1 都进账本", 2.0, h.budget.spentUsd(), 1e-9)
    }
}
