package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.QuestionId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.QuestionAnswered
import dev.min.agent.spi.QuestionAsked
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.AskUserQuestionTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.min.agent.testkit.*

/**
 * 提问的端到端：真的日志存储 + 脚本化模型 + 真的 AskUserQuestion 工具。
 * 提问与答复都是持久事件，所以「两端同时答」「等答复时进程被杀」都只是日志上的普通情形。
 */
class QuestionFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store by lazy { FileSessionStore(tmp.root.toPath(), { 0L }) }
    private val id = SessionId("s1")

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private fun askCall(callId: String = "c1") = ToolCallPart(
        CallId(callId),
        AskUserQuestionTool.name,
        JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "question" to JsonPrimitive("用哪个数据库？"),
                                "header" to JsonPrimitive("数据库"),
                                "options" to JsonArray(
                                    listOf("PostgreSQL", "SQLite").map {
                                        JsonObject(mapOf("label" to JsonPrimitive(it), "description" to JsonPrimitive("$it 的说明")))
                                    },
                                ),
                                "multiSelect" to JsonPrimitive(false),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private suspend fun newSession(
        model: ScriptedModel,
        tools: List<Tool<*>> = listOf(AskUserQuestionTool),
        on: SessionStore = store,
        mode: PermissionMode = PermissionMode.Default,
    ) = SessionRuntime.create(
        on,
        LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0),
        SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode),
        testDeps(model, tools),
        scope,
    )

    private suspend fun events(): List<SessionEvent> = store.peek(id)!!.read().toList().map { it.event }

    private suspend fun assertLogIsTruth(runtime: SessionRuntime) {
        val log = store.peek(id)!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    private suspend fun SessionRuntime.pendingQuestion(): AskedQuestion =
        state.first { it.turn?.pendingQuestions?.isNotEmpty() == true }.turn!!.pendingQuestions.single()

    private fun resultIn(model: ScriptedModel, index: Int): ToolResultPart =
        model.requests[index].messages.last().parts.first() as ToolResultPart

    /** 紧随 tool_result 的用户文本块（用户的话）；没有为空 */
    private fun userTextIn(model: ScriptedModel, index: Int): List<String> =
        model.requests[index].messages.last().parts.filterIsInstance<TextPart>().map { it.text }

    private fun ToolResultPart.text() = (content.single() as TextPart).text

    @Test
    fun `the model hears the answer the user gave`() = run {
        val model = ScriptedModel(reply(askCall(), stop = StopReason.ToolUse), reply(TextPart("好，用 PostgreSQL")))
        val rt = newSession(model)
        rt.send(ClientMsgId("u1"), listOf(TextPart("建个库")))

        val pending = rt.pendingQuestion()
        assertEquals(CallId("c1"), pending.callId)
        assertEquals("用哪个数据库？", pending.questions.single().question)
        // 提问发生在执行中：副作用标记之后
        assertTrue(rt.state.value.phaseOf(CallId("c1")) is CallPhase.EffectPending)

        assertTrue(rt.answerQuestion(pending.questionId, listOf(QuestionAnswer(listOf("PostgreSQL")))))
        rt.awaitIdle()

        val result = resultIn(model, 1)
        assertFalse(result.isError)
        // 答案不在 tool_result 里，而在紧随其后的独立用户文本块里（官方说明：tool_result 里的文字会被当作不可信内容）
        assertEquals(AskUserQuestionTool.ANSWERED_MESSAGE, result.text())
        val said = userTextIn(model, 1).single()
        assertTrue(said, said.contains("用哪个数据库？ → PostgreSQL"))
        assertFalse("答过就不再算待答", rt.answerQuestion(pending.questionId, listOf(QuestionAnswer(listOf("SQLite")))))
        val log = events()
        assertEquals(1, log.count { it is QuestionAsked })
        assertEquals(1, log.count { it is QuestionAnswered })
        assertTrue(log.indexOfFirst { it is ToolEffectPending } < log.indexOfFirst { it is QuestionAsked })
        assertLogIsTruth(rt)
    }

    @Test
    fun `two ends answering at once count once`() = run {
        repeat(10) { round ->
            val model = ScriptedModel(reply(askCall("c$round"), stop = StopReason.ToolUse), reply(TextPart("好")))
            val roundStore = FileSessionStore(tmp.newFolder("r$round").toPath(), { 0L })
            val rt = newSession(model, on = roundStore)
            rt.send(ClientMsgId("u1"), listOf(TextPart("问我")))
            val q = rt.pendingQuestion().questionId
            // 手机界面答 PostgreSQL、通知栏同时点了「关掉」：只能有一个落盘
            val wins = listOf(
                scope.async { rt.answerQuestion(q, listOf(QuestionAnswer(listOf("PostgreSQL")))) },
                scope.async { rt.declineQuestion(q) },
                scope.async { rt.answerQuestion(q, listOf(QuestionAnswer(listOf("SQLite")))) },
            ).awaitAll()
            rt.awaitIdle()
            assertEquals("round $round：$wins", 1, wins.count { it })
            assertNull(rt.state.value.turn)
            rt.state.value.modelMessages() // 回合正常结束，历史合法
            val log = roundStore.open(id)!!
            val records = log.read().toList()
            assertEquals(1, records.count { it.event is QuestionAnswered })
            assertEquals(fold(log.header, records), rt.state.value)
        }
    }

    @Test
    fun `an answer that does not fit the question is refused without writing anything`() = run {
        val model = ScriptedModel(reply(askCall(), stop = StopReason.ToolUse), reply(TextPart("好")))
        val rt = newSession(model)
        rt.send(ClientMsgId("u1"), listOf(TextPart("问我")))
        val q = rt.pendingQuestion().questionId
        val before = rt.state.value.lastSeq

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rt.answerQuestion(q, listOf(QuestionAnswer(listOf("MySQL")))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { rt.answerQuestion(q, listOf(QuestionAnswer(listOf("PostgreSQL", "SQLite")))) }
        }
        assertFalse("不存在的提问", rt.answerQuestion(QuestionId("nope"), listOf(QuestionAnswer(listOf("SQLite")))))
        assertEquals(before, rt.state.value.lastSeq)

        assertTrue(rt.answerQuestion(q, listOf(QuestionAnswer(other = "先用内存"))))
        rt.awaitIdle()
        assertFalse(resultIn(model, 1).text().contains("先用内存"))
        assertTrue(userTextIn(model, 1).single().contains("（用户自己填写）先用内存"))
        assertLogIsTruth(rt)
    }

    @Test
    fun `declining tells the model the user did not answer`() = run {
        val model = ScriptedModel(reply(askCall(), stop = StopReason.ToolUse), reply(TextPart("那我自己定")))
        val rt = newSession(model)
        rt.send(ClientMsgId("u1"), listOf(TextPart("问我")))
        assertTrue(rt.declineQuestion(rt.pendingQuestion().questionId))
        rt.awaitIdle()

        val result = resultIn(model, 1)
        assertFalse(result.isError)
        assertEquals(AskUserQuestionTool.DECLINED_MESSAGE, result.text())
        assertEquals("没回答就没有用户的话", emptyList<String>(), userTextIn(model, 1))
        assertLogIsTruth(rt)
    }

    @Test
    fun `interrupting while waiting closes the turn and the question stops being answerable`() = run {
        val model = ScriptedModel(reply(askCall(), stop = StopReason.ToolUse))
        val rt = newSession(model)
        rt.send(ClientMsgId("u1"), listOf(TextPart("问我")))
        val q = rt.pendingQuestion().questionId
        rt.interrupt()
        rt.awaitIdle()

        assertNull(rt.state.value.turn)
        assertFalse(rt.answerQuestion(q, listOf(QuestionAnswer(listOf("SQLite")))))
        assertFalse(rt.declineQuestion(q))
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.User), events().filterIsInstance<TurnEnded>().single().outcome)
        assertTrue(events().none { it is QuestionAnswered })
        assertLogIsTruth(rt)
    }

    @Test
    fun `the process dying while the question waits reopens into a legal idle session`() = run {
        val dying = KillableStore(store)
        val rt = newSession(ScriptedModel(reply(askCall(), stop = StopReason.ToolUse)), on = dying)
        rt.send(ClientMsgId("u1"), listOf(TextPart("问我")))
        val q = rt.pendingQuestion().questionId
        dying.kill()
        scope.cancel()

        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val next = ScriptedModel(reply(TextPart("我重新问一次")))
            val reopened = SessionRuntime.open(store, id, testDeps(next, listOf(AskUserQuestionTool)), fresh)!!
            val s = reopened.state.value
            assertNull(s.turn)
            s.modelMessages()
            // Replay：没答完就如实说没拿到结果，模型可以重新问
            val recovered = (s.toolResults.getValue(CallId("c1")).content.single() as TextPart).text
            assertTrue(recovered, recovered.contains("可以直接重新调用"))
            assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), events().filterIsInstance<TurnEnded>().single().outcome)
            assertFalse("旧进程的提问卡片答了也不算", reopened.answerQuestion(q, listOf(QuestionAnswer(listOf("SQLite")))))
            assertLogIsTruth(reopened)

            val lastSeq = reopened.state.value.lastSeq
            val again = SessionRuntime.open(store, id, testDeps(ScriptedModel(), listOf(AskUserQuestionTool)), fresh)!!
            assertEquals("再次打开不追加恢复事件", lastSeq, again.state.value.lastSeq)

            reopened.resume()
            reopened.awaitIdle()
            assertEquals(TurnOutcome.Completed, events().filterIsInstance<TurnEnded>().last().outcome)
            assertLogIsTruth(reopened)
        } finally {
            fresh.cancel()
        }
    }
}
