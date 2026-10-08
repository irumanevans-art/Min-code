package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.ScriptedModel
import dev.min.agent.testkit.TEST_MODEL
import dev.min.agent.testkit.reply
import dev.min.agent.testkit.testDeps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 旧日志兼容：用户的话还写在 tool_result 里的旧会话（没有 `userText`）重开后照常读，不改写历史，
 * 请求前缀与旧行为逐块一致；之后新写的回合用新格式，旧回合的投影不因此改变。
 */
class LegacyUserTextLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val id = SessionId("legacy")
    private val header = LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0)
    private val model = TEST_MODEL
    private val empty = JsonObject(emptyMap())

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    /** 旧版内核写下的一个回合：先问答（答案在 tool_result 里），再一次被拒绝（说明也在 tool_result 里） */
    private fun legacyTurn(): List<SessionEvent> {
        val turn = TurnId("t1")
        val ask = CallId("c_ask")
        val write = CallId("c_write")
        return listOf(
            SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default),
            InputReceived(ClientMsgId("u1"), listOf(TextPart("建个库")), Delivery.Queue),
            InputPromoted(ClientMsgId("u1"), MessageId("m1")),
            TurnStarted(turn, TurnTrigger.User),
            ModelRequestStarted(turn, RequestId("r1"), attempt = 1, model = model, prefixFingerprint = "p"),
            AssistantMessageCompleted(
                turn, RequestId("r1"), MessageId("m2"),
                parts = listOf(ToolCallPart(ask, "AskUserQuestion", empty), ToolCallPart(write, "Write", empty)),
                usage = Usage(inputTokens = 10, outputTokens = 2),
                stop = StopReason.ToolUse,
            ),
            ToolPlanned(turn, ask, "AskUserQuestion", empty, ResumePolicy.Replay),
            ToolPlanned(turn, write, "Write", empty, ResumePolicy.Unknown),
            ToolEffectPending(turn, ask),
            ToolOutcome(turn, ask, ToolResult(listOf(TextPart(LEGACY_ANSWER)))),
            ToolOutcome(turn, write, ToolResult(listOf(TextPart(LEGACY_DENIAL)), isError = true)),
            ModelRequestStarted(turn, RequestId("r2"), attempt = 1, model = model, prefixFingerprint = "p"),
            AssistantMessageCompleted(
                turn, RequestId("r2"), MessageId("m3"), listOf(TextPart("好，用 Room，不写文件")),
                usage = Usage(inputTokens = 20, outputTokens = 5), stop = StopReason.EndTurn,
            ),
            TurnEnded(turn, TurnOutcome.Completed, Usage(inputTokens = 30, outputTokens = 7), durationMs = 100),
        )
    }

    private val legacyMessages = listOf(
        listOf(TextPart("建个库")),
        listOf(ToolCallPart(CallId("c_ask"), "AskUserQuestion", empty), ToolCallPart(CallId("c_write"), "Write", empty)),
        // 旧形状：只有 tool_result 块，用户的话在里面，后面没有额外的文本块
        listOf(
            ToolResultPart(CallId("c_ask"), listOf(TextPart(LEGACY_ANSWER)), false),
            ToolResultPart(CallId("c_write"), listOf(TextPart(LEGACY_DENIAL)), true),
        ),
        listOf(TextPart("好，用 Room，不写文件")),
    )

    private fun records(events: List<SessionEvent>) = events.mapIndexed { i, e -> EventRecord(i + 1L, 1_000L + i, e) }

    @Test
    fun `reopening an old log projects exactly the old messages and keeps the history untouched`() = run {
        store.create(header).append(legacyTurn(), Durability.Sync)
        val next = ScriptedModel(reply(TextPart("继续")))
        val rt = SessionRuntime.open(store, id, testDeps(next, emptyList()), scope)!!
        val before = rt.state.value

        assertEquals(legacyMessages, before.modelMessages().map { it.parts })
        rt.send(ClientMsgId("u2"), listOf(TextPart("接着干")))
        rt.awaitIdle()

        // 新请求 = 旧前缀逐块不变 + 新输入：前缀不变，缓存才不会白白失效
        val sent = next.requests.single().messages
        assertEquals(legacyMessages + listOf(listOf(TextPart("接着干"))), sent.map { it.parts })
        assertEquals(listOf(Role.User, Role.Assistant, Role.User, Role.Assistant, Role.User), sent.map { it.role })
        // 旧事件一个字没改：重开后的状态里旧结果仍然没有 userText
        assertNull(rt.state.value.toolResults.getValue(CallId("c_ask")).userText)
        assertEquals(before.history, rt.state.value.history.take(before.history.size))
    }

    @Test
    fun `a new-format turn appended after old events leaves the old turn projection alone`() {
        val turn = TurnId("t2")
        val ask = CallId("c_ask2")
        val newTurn = listOf(
            InputReceived(ClientMsgId("u2"), listOf(TextPart("再问一个")), Delivery.Queue),
            InputPromoted(ClientMsgId("u2"), MessageId("m4")),
            TurnStarted(turn, TurnTrigger.User),
            ModelRequestStarted(turn, RequestId("r3"), attempt = 1, model = model, prefixFingerprint = "p"),
            AssistantMessageCompleted(
                turn, RequestId("r3"), MessageId("m5"), listOf(ToolCallPart(ask, "AskUserQuestion", empty)),
                usage = Usage(inputTokens = 40, outputTokens = 2), stop = StopReason.ToolUse,
            ),
            ToolPlanned(turn, ask, "AskUserQuestion", empty, ResumePolicy.Replay),
            ToolEffectPending(turn, ask),
            ToolOutcome(turn, ask, ToolResult(listOf(TextPart("用户已回答。")), userText = "用户回答了你的问题：…Room")),
        )
        val prefix = fold(header, records(legacyTurn())).modelMessages()
        val mixed = fold(header, records(legacyTurn() + newTurn)).modelMessages()

        assertEquals("旧回合的消息一块不差", prefix, mixed.take(prefix.size))
        assertEquals(
            listOf(ToolResultPart(ask, listOf(TextPart("用户已回答。")), false), TextPart("用户回答了你的问题：…Room")),
            mixed.last().parts,
        )
    }

    private companion object {
        const val LEGACY_ANSWER = "用户回答了你的问题：\n- 用哪个库？ → Room\n请按用户的回答继续。"
        const val LEGACY_DENIAL = "用户拒绝了这次调用。用户的说明：别写文件"
    }
}
