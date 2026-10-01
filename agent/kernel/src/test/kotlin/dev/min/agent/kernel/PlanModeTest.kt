package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.QuestionAnswer
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SessionLog
import dev.min.agent.spi.SessionStore
import dev.min.agent.spi.Tool
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.store.FileSessionStore
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.tools.AskUserQuestionTool
import dev.min.agent.tools.EnterPlanModeTool
import dev.min.agent.tools.ExitPlanModeTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
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
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * 计划模式的端到端：真的 EnterPlanMode / ExitPlanMode / AskUserQuestion + 计数的假 Write。
 * 安全不变式：**写只会发生在计划获批之后**——不管走到哪一步、进程在哪一次落盘前被杀。
 */
class PlanModeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val id = SessionId("s1")
    private val plan = "## 计划\n1. 改 a.kt\n2. 跑测试"

    @After
    fun tearDown() = scope.cancel()

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(60_000) { block() } }

    private fun call(callId: String, tool: String, input: JsonObject = JsonObject(emptyMap())) = ToolCallPart(CallId(callId), tool, input)
    private fun enter(callId: String) = call(callId, EnterPlanModeTool.name)
    private fun exit(callId: String) = call(callId, ExitPlanModeTool.name, JsonObject(mapOf("plan" to JsonPrimitive(plan))))
    private fun write(callId: String) = call(callId, "Write")
    private fun ask(callId: String) = call(
        callId,
        AskUserQuestionTool.name,
        JsonObject(
            mapOf(
                "questions" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "question" to JsonPrimitive("先改哪边？"),
                                "header" to JsonPrimitive("顺序"),
                                "options" to JsonArray(
                                    listOf("前端", "后端").map {
                                        JsonObject(mapOf("label" to JsonPrimitive(it), "description" to JsonPrimitive("先改$it")))
                                    },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun text(t: String) = listOf(TextPart(t))

    private fun tools(wrote: AtomicInteger): List<Tool<*>> = listOf(
        EnterPlanModeTool,
        ExitPlanModeTool,
        AskUserQuestionTool,
        FakeTool("Write", WRITES) { wrote.incrementAndGet(); ToolResult(text("写好了")) },
    )

    private suspend fun newSession(store: SessionStore, model: ScriptedModel, tools: List<Tool<*>>, mode: PermissionMode, on: CoroutineScope = scope) =
        SessionRuntime.create(
            store,
            LogHeader(LOG_FORMAT_VERSION, id, createdAtMs = 0),
            SessionCreated(GuestPath("/workspace"), TEST_MODEL, mode),
            testDeps(model, tools),
            on,
        )

    private suspend fun assertLogIsTruth(store: SessionStore, runtime: SessionRuntime) {
        val log = store.open(id)!!
        assertEquals(fold(log.header, log.read().toList()), runtime.state.value)
    }

    private suspend fun SessionRuntime.awaitApproval(callId: String): CallPhase.AwaitingApproval =
        state.first { it.phaseOf(CallId(callId)) is CallPhase.AwaitingApproval }.phaseOf(CallId(callId)) as CallPhase.AwaitingApproval

    private fun SessionRuntime.resultOf(callId: String): ToolResult = state.value.toolResults.getValue(CallId(callId))
    private fun ToolResult.text() = content.filterIsInstance<TextPart>().joinToString("\n") { it.text }

    @Test
    fun `entering blocks writes and an approved plan restores the earlier mode even from bypass`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val wrote = AtomicInteger()
        val model = ScriptedModel(
            reply(enter("c1"), stop = StopReason.ToolUse),
            reply(write("c2"), stop = StopReason.ToolUse),
            reply(exit("c3"), stop = StopReason.ToolUse),
            reply(write("c4"), stop = StopReason.ToolUse),
            reply(TextPart("改完了")),
        )
        val rt = newSession(store, model, tools(wrote), PermissionMode.BypassPermissions)
        rt.send(ClientMsgId("u1"), text("先计划再改"))

        // bypass 也要人批：alwaysAsk 在权限引擎之前
        val approval = rt.awaitApproval("c3")
        assertEquals(plan, approval.ask.summary)
        assertEquals(PermissionMode.Plan, rt.state.value.settings.mode)
        assertEquals("计划获批之前一次都没写", 0, wrote.get())
        assertTrue(rt.answerApproval(approval.approvalId, ApprovalChoice.Once))
        rt.awaitIdle()

        val s = rt.state.value
        assertEquals(PermissionMode.BypassPermissions, s.settings.mode)
        assertEquals(PermissionMode.BypassPermissions, s.settings.modeBeforePlan)
        assertEquals("只有获批之后那次写执行了", 1, wrote.get())
        val blocked = rt.resultOf("c2")
        assertTrue(blocked.isError)
        assertTrue(blocked.text(), blocked.text().contains("计划模式"))
        assertTrue(rt.resultOf("c3").text().contains("bypassPermissions"))
        assertEquals("回到 bypass 后写不用再问", 1, store.open(id)!!.read().toList().count { it.event is ApprovalRequested })
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `a rejected plan stays in plan mode and the model hears why`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val wrote = AtomicInteger()
        val model = ScriptedModel(
            reply(enter("c1"), stop = StopReason.ToolUse),
            reply(exit("c2"), stop = StopReason.ToolUse),
            reply(write("c3"), stop = StopReason.ToolUse),
            reply(TextPart("好，我再想想")),
        )
        val rt = newSession(store, model, tools(wrote), PermissionMode.AcceptEdits)
        rt.send(ClientMsgId("u1"), text("先计划"))
        rt.answerApproval(rt.awaitApproval("c2").approvalId, ApprovalChoice.Deny("先别动数据库"))
        rt.awaitIdle()

        assertEquals(PermissionMode.Plan, rt.state.value.settings.mode)
        assertEquals(PermissionMode.AcceptEdits, rt.state.value.settings.modeBeforePlan)
        val rejected = model.requests[2].messages.last().parts.single() as ToolResultPart
        assertTrue(rejected.isError)
        assertTrue((rejected.content.single() as TextPart).text.contains("先别动数据库"))
        assertEquals(0, wrote.get())
        assertTrue(rt.resultOf("c3").text().contains("计划模式"))
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `a session that started in plan mode exits to default`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val model = ScriptedModel(reply(exit("c1"), stop = StopReason.ToolUse), reply(TextPart("开始")))
        val rt = newSession(store, model, tools(AtomicInteger()), PermissionMode.Plan)
        rt.send(ClientMsgId("u1"), text("出个计划"))
        rt.answerApproval(rt.awaitApproval("c1").approvalId, ApprovalChoice.Once)
        rt.awaitIdle()

        assertEquals(PermissionMode.Default, rt.state.value.settings.mode)
        assertLogIsTruth(store, rt)
    }

    @Test
    fun `the process dying while the plan awaits approval reopens still in plan mode`() = run {
        val store = FileSessionStore(tmp.root.toPath(), { 0L })
        val dying = KillableStore(store)
        val model = ScriptedModel(reply(enter("c1"), stop = StopReason.ToolUse), reply(exit("c2"), stop = StopReason.ToolUse))
        val rt = newSession(dying, model, tools(AtomicInteger()), PermissionMode.Default)
        rt.send(ClientMsgId("u1"), text("先计划"))
        val stale = rt.awaitApproval("c2").approvalId
        dying.kill()
        scope.cancel()

        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reopened = SessionRuntime.open(store, id, testDeps(ScriptedModel(), tools(AtomicInteger())), fresh)!!
            val s = reopened.state.value
            assertNull(s.turn)
            s.modelMessages()
            assertEquals("没批就还在计划模式", PermissionMode.Plan, s.settings.mode)
            assertTrue(reopened.resultOf("c2").text().startsWith("这次调用没有执行"))
            assertFalse("旧进程的审批卡片答了也不算", reopened.answerApproval(stale, ApprovalChoice.Once))
            val ended = store.open(id)!!.read().toList().map { it.event }.filterIsInstance<TurnEnded>().single()
            assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), ended.outcome)
            assertLogIsTruth(store, reopened)
        } finally {
            fresh.cancel()
        }
    }

    /** 「拔电源」：第 [dieAt] 次追加（从布防起数）之前死掉，之后什么都写不进去 */
    private class BoundaryStore(private val delegate: SessionStore, private val dieAt: Int?) : SessionStore {
        @Volatile var armed = false
        @Volatile var dead = false
        @Volatile var writes = 0
        val died = CompletableDeferred<Unit>()

        override suspend fun create(header: LogHeader): SessionLog = wrap(delegate.create(header))
        override suspend fun open(id: SessionId): SessionLog? = delegate.open(id)?.let(::wrap)
        override suspend fun list(): List<LogHeader> = delegate.list()

        private fun wrap(log: SessionLog) = object : SessionLog by log {
            override suspend fun append(events: List<SessionEvent>, durability: Durability): List<EventRecord> {
                synchronized(this@BoundaryStore) {
                    if (dead || (armed && writes == dieAt)) {
                        dead = true
                        died.complete(Unit)
                        throw IOException("拔电源")
                    }
                }
                val records = log.append(events, durability)
                if (armed) synchronized(this@BoundaryStore) { writes++ }
                return records
            }
        }
    }

    /** 跑一遍「进计划 → 提问并作答 → 交计划并获批 → 写」，在第 [dieAt] 次追加前拔电源，再开新进程核对；返回总追加次数 */
    private suspend fun planScenario(root: Path, dieAt: Int?): Int {
        val store = FileSessionStore(root, { 0L })
        val boundary = BoundaryStore(store, dieAt)
        val wrote = AtomicInteger()
        val oldJob = SupervisorJob()
        val old = CoroutineScope(oldJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val model = ScriptedModel(
            reply(enter("c1"), stop = StopReason.ToolUse),
            reply(ask("c2"), stop = StopReason.ToolUse),
            reply(exit("c3"), stop = StopReason.ToolUse),
            reply(write("c4"), stop = StopReason.ToolUse),
            reply(TextPart("完成")),
        )
        val rt = newSession(boundary, model, tools(wrote), PermissionMode.BypassPermissions, on = old)
        boundary.armed = true
        val driver = old.async {
            try {
                rt.send(ClientMsgId("u1"), text("先计划再改"))
                val q = rt.state.first { it.turn?.pendingQuestions?.isNotEmpty() == true }.turn!!.pendingQuestions.single()
                rt.answerQuestion(q.questionId, listOf(QuestionAnswer(listOf("后端"))))
                rt.answerApproval(rt.awaitApproval("c3").approvalId, ApprovalChoice.Once)
                rt.awaitIdle()
            } catch (_: IOException) {
                // 进程已死：命令写不进去，和真实的死亡一样
            }
        }
        select<Unit> {
            boundary.died.onAwait { }
            driver.onAwait { }
        }
        boundary.dead = true
        oldJob.cancelAndJoin()

        val freshJob = SupervisorJob()
        val fresh = CoroutineScope(freshJob + Dispatchers.Default)
        try {
            val reopened = SessionRuntime.open(store, id, testDeps(ScriptedModel(reply(TextPart("接着来"))), tools(wrote)), fresh)!!
            val s = reopened.state.value
            assertNull(s.turn)
            s.modelMessages()
            assertTrue(planRecovery(s).isEmpty())
            assertLogIsTruth(store, reopened)

            // 模式与结果一致：结果里声明的切换要么和结果一起在日志里，要么都不在
            val entered = s.toolResults[CallId("c1")]?.effects?.isNotEmpty() == true
            val approved = s.toolResults[CallId("c3")]?.effects?.isNotEmpty() == true
            val expected = when {
                approved -> PermissionMode.BypassPermissions
                entered -> PermissionMode.Plan
                else -> PermissionMode.BypassPermissions
            }
            assertEquals(expected, s.settings.mode)
            if (wrote.get() > 0) assertTrue("写只能发生在计划获批之后", approved)
            if (s.toolResults[CallId("c4")]?.isError == false) assertEquals(1, wrote.get())

            val lastSeq = s.lastSeq
            val again = SessionRuntime.open(store, id, testDeps(ScriptedModel(), tools(wrote)), fresh)!!
            assertEquals("再次打开不追加恢复事件", lastSeq, again.state.value.lastSeq)

            assertTrue(reopened.send(ClientMsgId("fresh"), text("继续")))
            reopened.awaitIdle()
            assertEquals(TurnOutcome.Completed, store.open(id)!!.read().toList().map { it.event }.filterIsInstance<TurnEnded>().last().outcome)
            assertLogIsTruth(store, reopened)
        } finally {
            freshJob.cancelAndJoin()
        }
        return boundary.writes
    }

    @Test
    fun `every append boundary of a planned change recovers with the mode matching the log`() = run {
        val n = planScenario(tmp.newFolder("baseline").toPath(), dieAt = null)
        assertTrue("基线应走完整个场景：$n 次追加", n > 15)
        for (k in 0..n) {
            try {
                planScenario(tmp.newFolder("k$k").toPath(), dieAt = k)
            } catch (e: Throwable) {
                throw AssertionError("k=$k/$n", e)
            }
        }
        println("PLAN append-boundary cases=${n + 1}")
    }
}
