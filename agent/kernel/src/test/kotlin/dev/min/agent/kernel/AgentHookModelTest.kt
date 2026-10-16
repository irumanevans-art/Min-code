package dev.min.agent.kernel

import dev.min.agent.llm.ModelClient
import dev.min.agent.llm.ModelException
import dev.min.agent.llm.ModelRequest
import dev.min.agent.llm.ModelStreamEvent
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.DelegateRequest
import dev.min.agent.spi.DelegateResult
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.testkit.Step
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.TEST_PROFILE
import dev.min.agent.testkit.args
import dev.min.agent.testkit.reply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * agent 型 hook 的 `model`（CC 2.1.300）：别名照 prompt 型同一条路解析、子 agent 里 hook 自己写的 model 优先于子 agent 的、
 * 没写 model 用调用方（触发 hook 的那个会话）的，写了的模型用不了就退回调用方的模型跑——**不能**变成放行。
 * 每个模型各有一个脚本客户端，判官（系统提示里有 "hook verifier"）的请求落在哪个客户端上一目了然。
 */
class AgentHookModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rigs = mutableListOf<HookTestRig>()

    @After
    fun tearDown() = rigs.forEach { it.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private val haikuRef = ModelRef("test", "haiku-4-5")
    private val subRef = ModelRef("test", "sub")

    private class RoutingModel(private val route: (ModelRequest) -> Step) : ModelClient {
        val requests = CopyOnWriteArrayList<ModelRequest>()
        override fun stream(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            requests += request
            emitAll(route(request)(request))
        }

        val verifiers get() = requests.filter { it.isVerifier() }
        val evaluators get() = requests.filter { it.system.any { s -> s.contains("hook evaluator") } }
        val others get() = requests.filterNot { it.isVerifier() || it.system.any { s -> s.contains("hook evaluator") } }
    }

    private val ok: Step = reply(TextPart("""{"ok": true}"""))
    private val notDone: Step = reply(TextPart("""{"ok": false, "reason": "没做完，还差一步"}"""))

    /** 第一次回「没做完」、之后回 ok：Stop 被拦一次，模型得到理由、补完再停 */
    private fun blockOnce(): (ModelRequest) -> Step {
        val n = AtomicInteger(0)
        return { _ -> if (n.getAndIncrement() == 0) notDone else ok }
    }

    /** 主模型：判官的回答由 [verifier] 定，其余都回「干完了」 */
    private fun main(verifier: (ModelRequest) -> Step = { ok }) =
        RoutingModel { req -> if (req.isVerifier()) verifier(req) else reply(TextPart("干完了")) }

    /** 别名 haiku 落到具体型号 haiku-4-5（宿主解析器的活）；解析不了的名字抛，和真实解析器一样 */
    private fun resolver(main: ModelClient, haiku: ModelClient, sub: ModelClient = main) = ModelResolver { ref ->
        when (ref.modelId) {
            TEST_MODEL.modelId -> ResolvedModel(TEST_PROFILE, main)
            "haiku", haikuRef.modelId -> ResolvedModel(TEST_PROFILE.copy(ref = haikuRef), haiku)
            subRef.modelId -> ResolvedModel(TEST_PROFILE.copy(ref = subRef), sub)
            else -> throw IllegalStateException("没有这个模型：${ref.modelId}")
        }
    }

    private fun rig(
        main: ModelClient, haiku: ModelClient, sub: ModelClient = main,
        allowed: (ModelRef) -> Boolean = { true }, tools: List<Tool<*>> = emptyList(),
    ) = HookTestRig(
        tmp.root.toPath(), model = main, tools = tools, resolver = resolver(main, haiku, sub), modelAllowed = allowed,
    ).also { rigs += it }

    private fun hookOf(event: String, type: String, model: String?) = JsonObject(
        mapOf(
            event to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "hooks" to JsonArray(
                                listOf(
                                    JsonObject(
                                        buildMap {
                                            put("type", JsonPrimitive(type))
                                            put("prompt", JsonPrimitive("确认可以停"))
                                            put("timeout", JsonPrimitive(10))
                                            model?.let { put("model", JsonPrimitive(it)) }
                                        },
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    // ---- 根会话的 Stop hook ----

    @Test
    fun `an alias in the hook's model resolves to the concrete model and the verifier runs on it`() = run {
        val main = main()
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku)
        val rt = r.session(hooks = hookOf("Stop", "agent", "haiku"))
        r.say(rt, "做事")

        assertEquals("判官跑在 haiku 上", 1, haiku.verifiers.size)
        assertEquals("别名落成具体型号", haikuRef, haiku.verifiers.single().profile.ref)
        assertTrue("没有借用主模型", main.verifiers.isEmpty())
    }

    @Test
    fun `a hook without model runs the verifier on the caller's model`() = run {
        val main = main()
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku)
        val rt = r.session(hooks = hookOf("Stop", "agent", null))
        r.say(rt, "做事")

        assertEquals(TEST_MODEL, main.verifiers.single().profile.ref)
        assertTrue(haiku.requests.isEmpty())
    }

    @Test
    fun `a model that cannot be resolved falls back to the caller and still blocks - not a silent allow`() = run {
        val main = main(blockOnce())
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku)
        val rt = r.session(hooks = hookOf("Stop", "agent", "nope-model"))
        r.say(rt, "做事")

        assertTrue("点名的模型没被碰过", haiku.requests.isEmpty())
        assertTrue("退回调用方的模型接着判", main.verifiers.isNotEmpty())
        // 判官（调用方的模型）说没做完：Stop 被拦、理由交给了主模型——解析失败没有变成放行
        assertTrue("拦截生效，理由回到主模型：${main.others.map { it.messages.last().parts }}",
            main.others.any { req -> req.messages.any { m -> m.parts.any { (it as? TextPart)?.text?.contains("没做完") == true } } })
        val ran = r.hooksRan(rt, "Stop").first()
        assertTrue("留了诊断：${ran.problems}", ran.problems.any { it.contains("nope-model") && it.contains("改用调用方的模型") })
    }

    @Test
    fun `a model the allow-list refuses falls back to the caller`() = run {
        val main = main()
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku, allowed = { it.modelId != haikuRef.modelId })
        val rt = r.session(hooks = hookOf("Stop", "agent", "haiku"))
        r.say(rt, "做事")

        assertTrue("被名单挡了的模型不许借 hook 绕开", haiku.requests.isEmpty())
        assertEquals(TEST_MODEL, main.verifiers.single().profile.ref)
        assertTrue(r.hooksRan(rt, "Stop").first().problems.any { it.contains("availableModels") })
    }

    @Test
    fun `a named model that fails on its first request is rerun on the caller's model and still blocks`() = run {
        val main = main(blockOnce())
        val haiku = RoutingModel { reqFailing }
        val r = rig(main, haiku)
        val rt = r.session(hooks = hookOf("Stop", "agent", "haiku"))
        r.say(rt, "做事")

        // Stop 被拦一次后模型补完又停了一次：hook 跑了两轮，每轮都是点名的模型先试一次、失败后调用方的模型重跑
        assertEquals("每轮点名的模型只试一次就放弃", 2, haiku.verifiers.size)
        assertEquals("每轮都改由调用方的模型重跑", haiku.verifiers.size, main.verifiers.size)
        assertTrue("重跑的结论照样拦下了 Stop",
            main.others.any { req -> req.messages.any { m -> m.parts.any { (it as? TextPart)?.text?.contains("没做完") == true } } })
        assertTrue(r.hooksRan(rt, "Stop").first().problems.any { it.contains("haiku") && it.contains("重跑") })
    }

    // ---- 子 agent 里触发的 hook ----

    private fun PortAgentTool() = object : Tool<JsonObject> {
        override val name = "Agent"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = true, concurrencySafe = true)
        override fun description(profile: HarnessProfile) = "测试用 Agent"
        override fun parse(raw: JsonObject) = ToolInput.Valid(raw)
        override fun permissionAsk(input: JsonObject, ctx: ToolContext) = PermissionAsk(name, name)
        override fun run(input: JsonObject, ctx: ToolContext): Flow<ToolProgress> = flow {
            val str = { k: String -> (input[k] as? JsonPrimitive)?.content }
            val r = ctx.agents.delegate(
                DelegateRequest(description = "任务", prompt = str("prompt")!!, agentType = str("subagent_type"), model = str("model"), background = false),
            )
            emit(
                ToolProgress.Done(
                    when (r) {
                        is DelegateResult.Finished -> ToolResult(listOf(TextPart(r.report)), isError = r.isError)
                        is DelegateResult.Started -> ToolResult(listOf(TextPart("已启动")))
                        is DelegateResult.Rejected -> ToolResult(listOf(TextPart(r.messageForModel)), isError = true)
                    },
                ),
            )
        }
    }

    /** 主会话派一个跑在 [subRef] 上的子 agent；子 agent 结束时触发 SubagentStop 上的 agent hook */
    private fun delegatingMain(): RoutingModel = RoutingModel { req ->
        when {
            req.isVerifier() -> ok
            req.messages.last().parts.any { it is ToolResultPart } -> reply(TextPart("子 agent 回来了"))
            req.messages.last().parts.any { (it as? TextPart)?.text == "派活" } ->
                reply(ToolCallPart(CallId("a1"), "Agent", args("prompt" to "去干活", "subagent_type" to "general-purpose", "model" to "sub")), stop = StopReason.ToolUse)
            else -> reply(TextPart("收到"))
        }
    }

    @Test
    fun `inside a subagent a hook without model uses the subagent's own model, not the root session's`() = run {
        val main = delegatingMain()
        val sub = RoutingModel { req -> if (req.isVerifier()) ok else reply(TextPart("子报告")) }
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku, sub, tools = listOf(PortAgentTool()))
        val rt = r.session(hooks = hookOf("SubagentStop", "agent", null))
        r.say(rt, "派活")

        assertEquals("调用方是子 agent：判官跑在子 agent 的模型上", subRef, sub.verifiers.single().profile.ref)
        assertTrue("不是根会话的模型", main.verifiers.isEmpty())
        assertTrue(haiku.requests.isEmpty())
    }

    @Test
    fun `inside a subagent the hook's own model wins over the subagent's model`() = run {
        val main = delegatingMain()
        val sub = RoutingModel { req -> if (req.isVerifier()) ok else reply(TextPart("子报告")) }
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku, sub, tools = listOf(PortAgentTool()))
        val rt = r.session(hooks = hookOf("SubagentStop", "agent", "haiku"))
        r.say(rt, "派活")

        assertEquals("hook 写的 haiku 优先", haikuRef, haiku.verifiers.single().profile.ref)
        assertTrue("子 agent 的模型没有盖掉它", sub.verifiers.isEmpty())
        assertTrue(main.verifiers.isEmpty())
    }

    // ---- prompt 型：共用同一条别名解析，失败语义不变 ----

    @Test
    fun `a prompt hook resolves the same alias through the same path`() = run {
        val main = main()
        val haiku = RoutingModel { ok }
        val r = rig(main, haiku)
        val rt = r.session(hooks = hookOf("Stop", "prompt", "haiku"))
        r.say(rt, "做事")

        assertEquals(haikuRef, haiku.evaluators.single().profile.ref)
        assertTrue(main.evaluators.isEmpty())
    }

    private val reqFailing: Step = { flow { throw ModelException(ModelFailureKind.ModelUnavailable, "model not found", httpStatus = 404, beforeFirstByte = true) } }
}

private fun ModelRequest.isVerifier() = system.any { it.contains("hook verifier") }

