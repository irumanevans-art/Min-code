package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HarnessProfile
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.Note
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.SessionEffect
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolContext
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.ToolTraits
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import dev.min.agent.tools.EndConversationTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * EndConversation 的内核一侧（CC tools-reference：会话锁住，只剩 /clear 之类能用）：
 * 安静停下回合（不是失败）、落 conversation_ended 与一句说明、之后的输入不再开回合、不过权限与 PreToolUse。
 */
class EndConversationSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private var counter = 0

    private fun hub(model: ScriptedModel, tools: List<Tool<*>>) = SessionHub(store, testDeps(model, tools), newScope())

    private suspend fun SessionRuntime.say(text: String) {
        send(ClientMsgId("u${++counter}"), listOf(TextPart(text)))
        awaitIdle()
    }

    private suspend fun events(rt: SessionRuntime): List<SessionEvent> = store.peek(rt.sessionId)!!.read().toList().map { it.event }

    private val endCall = ToolCallPart(CallId("e1"), "EndConversation", JsonObject(emptyMap()))

    @Test
    fun `EndConversation stops the turn quietly, locks the session, and leaves a note the user can see`() = run {
        val model = ScriptedModel(reply(endCall, stop = StopReason.ToolUse))
        val rt = hub(model, listOf(EndConversationTool)).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("你好")

        val evs = events(rt)
        assertTrue("会话应被锁住", evs.contains(ConversationEnded))
        assertTrue(rt.state.value.ended)
        // 安静停下：回合以 Interrupted(Ended) 收尾，不是 Failed
        assertEquals(InterruptedBy.Ended, (evs.filterIsInstance<TurnEnded>().single().outcome as TurnOutcome.Interrupted).by)
        assertTrue(evs.any { it is Note && it.text.contains("会话已由模型结束") })
        // 结束之后不再请求模型：只有这一次请求
        assertEquals(1, model.requests.size)
    }

    @Test
    fun `after the session is locked, a new prompt starts no turn, and the model is not asked again`() = run {
        val model = ScriptedModel(reply(endCall, stop = StopReason.ToolUse))
        val rt = hub(model, listOf(EndConversationTool)).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("你好")

        val accepted = rt.send(ClientMsgId("late"), listOf(TextPart("还在吗")))
        rt.awaitIdle()
        assertFalse(accepted)
        assertNull(rt.state.value.turn)
        assertEquals(1, model.requests.size)
        // 停下时一句，被拒的输入又一句：界面与 CLI 都看得到原因
        assertEquals(2, events(rt).count { it is Note && it.text.contains("会话已由模型结束") })
    }

    /** 与 EndConversation 同名、但标了「不过权限」的替身：只看内核是不是真的跳过了审批与 hook */
    private class ExemptEnd(private val exempt: Boolean) : Tool<Unit> {
        override val name = "EndConversation"
        override val inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override val traits = ToolTraits(readOnly = false, exemptFromPolicy = exempt)
        override fun description(profile: HarnessProfile) = "结束"
        override fun parse(raw: JsonObject) = ToolInput.Valid(Unit)
        override fun permissionAsk(input: Unit, ctx: ToolContext) = PermissionAsk(name, "结束会话")
        override fun run(input: Unit, ctx: ToolContext): Flow<ToolProgress> = flow {
            emit(ToolProgress.Done(ToolResult(listOf(TextPart("结束")), effects = listOf(SessionEffect.ConversationEnd))))
        }
    }

    @Test
    fun `an exempt EndConversation never asks for approval, even when the tool itself would ask`() = run {
        val model = ScriptedModel(reply(endCall, stop = StopReason.ToolUse))
        val rt = hub(model, listOf(ExemptEnd(exempt = true))).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        // 没有人答审批：要是真去问了，回合就挂住，withTimeout 会把测试打红
        rt.say("你好")
        val evs = events(rt)
        assertTrue(evs.none { it is ApprovalRequested })
        assertTrue(rt.state.value.ended)
    }

    @Test
    fun `after the session is locked, reports, notifications and resume start no turn either`() = run {
        // 锁住之后还会有不经 send 的输入：后台子 agent 的报告、后台任务的通知、asyncRewake hook、定时任务，以及界面的「继续」。
        // 它们都不该再把模型叫起来（模型结束会话的意义就在于此）
        val model = ScriptedModel(reply(endCall, stop = StopReason.ToolUse), reply(TextPart("我又回来了")))
        val rt = hub(model, listOf(EndConversationTool)).create(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)
        rt.say("你好")

        rt.receive(ClientMsgId("hook-late"), listOf(TextPart("后台检查完成")), dev.min.agent.spi.Delivery.Queue,
            dev.min.agent.spi.InputOrigin.Hook("asyncRewake"), startTurn = dev.min.agent.spi.TurnTrigger.Hook)
        rt.awaitIdle()
        rt.resume()
        rt.awaitIdle()
        rt.runInbox()
        rt.awaitIdle()
        assertEquals("锁住之后不该再请求模型", 1, model.requests.size)
        assertNull(rt.state.value.turn)
    }
}
