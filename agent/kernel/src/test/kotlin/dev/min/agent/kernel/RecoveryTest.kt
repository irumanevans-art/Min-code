package dev.min.agent.kernel

import dev.min.agent.protocol.ApprovalId
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.ToolResultPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.ApprovalChoice
import dev.min.agent.spi.ApprovalRequested
import dev.min.agent.spi.ApprovalResolved
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.DecidedBy
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.ExecHandle
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InputWithdrawn
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.PermissionAsk
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.Role
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val header = LogHeader(1, SessionId("s1"), createdAtMs = 0)
    private val model = ModelRef("deepseek", "deepseek-v4")
    private val turn = TurnId("t1")
    private val req1 = RequestId("r1")
    private val req2 = RequestId("r2")
    private val read = CallId("c_read")
    private val bash = CallId("c_bash")
    private val push = CallId("c_push")

    private fun input(key: String) = JsonObject(mapOf("k" to JsonPrimitive(key)))
    private fun ok(text: String) = ToolResult(listOf(TextPart(text)))

    /**
     * 一段覆盖各种情况的完整会话：设置变更、撤回的输入、留在收件箱里的输入、片段、三个并行工具调用
     * （只读的、要审批后执行的、被拒的）、结果乱序到达、一次失败后重试的请求、回合正常结束。
     */
    private val script: List<SessionEvent> = listOf(
        SessionCreated(GuestPath("/workspace"), model, PermissionMode.Default),
        SettingsChanged(mode = PermissionMode.AcceptEdits),
        InputReceived(ClientMsgId("u1"), listOf(TextPart("修一下构建")), Delivery.Queue),
        InputReceived(ClientMsgId("u0"), listOf(TextPart("算了")), Delivery.Queue),
        InputWithdrawn(ClientMsgId("u0")),
        InputPromoted(ClientMsgId("u1"), MessageId("m1")),
        TurnStarted(turn, TurnTrigger.User),
        ModelRequestStarted(turn, req1, attempt = 1, model = model, prefixFingerprint = "p1"),
        AssistantSegment(turn, req1, MessageId("m2"), listOf(TextPart("我先看看"))),
        AssistantMessageCompleted(
            turn, req1, MessageId("m2"),
            parts = listOf(
                TextPart("我先看看"),
                ToolCallPart(read, "Read", input("a")),
                ToolCallPart(bash, "Bash", input("b")),
                ToolCallPart(push, "Bash", input("c")),
            ),
            usage = Usage(inputTokens = 100, outputTokens = 20),
            stop = StopReason.ToolUse,
        ),
        ToolPlanned(turn, read, "Read", input("a"), ResumePolicy.Replay),
        ToolPlanned(turn, bash, "Bash", input("b"), ResumePolicy.Unknown),
        ToolPlanned(turn, push, "Bash", input("c"), ResumePolicy.Unknown),
        InputReceived(ClientMsgId("u2"), listOf(TextPart("顺便跑下测试")), Delivery.Steer),
        ToolEffectPending(turn, read),
        ApprovalRequested(turn, ApprovalId("ap_b"), bash, PermissionAsk("Bash", "跑构建")),
        ApprovalRequested(turn, ApprovalId("ap_c"), push, PermissionAsk("Bash", "git push")),
        ApprovalResolved(ApprovalId("ap_c"), ApprovalChoice.Deny("别 push"), DecidedBy.User),
        ApprovalResolved(ApprovalId("ap_b"), ApprovalChoice.Always("Bash(./gradlew *)"), DecidedBy.User),
        ToolEffectPending(turn, bash, ExecHandle("h1", pid = 42, logPath = GuestPath("/tmp/min/h1.log"))),
        ToolOutcome(turn, bash, ok("BUILD SUCCESSFUL")),
        ToolOutcome(turn, push, ToolResult(listOf(TextPart("用户拒绝：别 push")), isError = true)),
        ToolOutcome(turn, read, ok("文件内容")),
        InputPromoted(ClientMsgId("u2"), MessageId("m3")),
        ModelRequestStarted(turn, req2, attempt = 1, model = model, prefixFingerprint = "p1"),
        ModelRequestFailed(turn, req2, attempt = 1, kind = ModelFailureKind.Network, message = "reset", willRetry = true),
        ModelRequestStarted(turn, req2, attempt = 2, model = model, prefixFingerprint = "p1"),
        AssistantMessageCompleted(
            turn, req2, MessageId("m4"), listOf(TextPart("好了")),
            usage = Usage(inputTokens = 150, outputTokens = 5, cacheReadTokens = 100),
            stop = StopReason.EndTurn,
        ),
        TurnEnded(turn, TurnOutcome.Completed, Usage(inputTokens = 250, outputTokens = 25, cacheReadTokens = 100), durationMs = 900),
    )

    private fun records(events: List<SessionEvent>, startSeq: Long = 1) =
        events.mapIndexed { i, e -> EventRecord(startSeq + i, 1_000 + startSeq + i, e) }

    @Test
    fun `a complete session folds into the expected state and model view`() {
        val state = fold(header, records(script))

        assertNull(state.turn)
        assertEquals(PermissionMode.AcceptEdits, state.settings.mode)
        assertTrue("撤回的与提升的输入都不在收件箱里", state.inbox.isEmpty())
        assertEquals(Usage(inputTokens = 250, outputTokens = 25, cacheReadTokens = 100), state.usage)

        val messages = state.modelMessages()
        assertEquals(listOf(Role.User, Role.Assistant, Role.User, Role.User, Role.Assistant), messages.map { it.role })
        // 工具结果按调用顺序排，而不是按完成顺序（完成顺序是 bash、push、read）
        val results = messages[2].parts.map { it as ToolResultPart }
        assertEquals(listOf(read, bash, push), results.map { it.callId })
        assertTrue(results[2].isError)
        // 插话在这批工具结果之后送达
        assertEquals(listOf(TextPart("顺便跑下测试")), messages[3].parts)
    }

    @Test
    fun `killing the process after any event recovers to a legal, idempotent state`() {
        for (k in 1..script.size) {
            val prefix = records(script.take(k))
            val before = fold(header, prefix)
            val plan = planRecovery(before)
            val after = fold(header, prefix + records(plan, startSeq = prefix.size + 1L))
            val where = "在第 $k 条事件（${script[k - 1]::class.simpleName}）之后被杀"

            assertNull("$where：回合应被收尾", after.turn)
            assertEquals("$where：恢复应幂等", emptyList<SessionEvent>(), planRecovery(after))
            after.modelMessages() // 每个调用都有结果，否则这里抛错
            assertEquals("$where：收件箱不能丢输入", before.inbox, after.inbox)
            assertEquals("$where：恢复不改历史", before.history, after.history)
            before.toolResults.forEach { (id, result) ->
                assertEquals("$where：已有结果不能被改", result, after.toolResults[id])
            }
            if (before.turn != null) {
                val ended = plan.last() as TurnEnded
                assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), ended.outcome)
            } else {
                assertTrue("$where：空闲时不需要恢复", plan.isEmpty())
            }
        }
    }

    @Test
    fun `recovered results tell the model exactly what happened`() {
        fun textFor(k: Int, call: CallId): String {
            val prefix = records(script.take(k))
            val outcome = planRecovery(fold(header, prefix)).filterIsInstance<ToolOutcome>().single { it.callId == call }
            return (outcome.result.content.single() as TextPart).text
        }
        val afterPlanned = script.indexOfFirst { it is ToolPlanned && it.callId == push } + 1
        val afterDeny = script.indexOfFirst { it is ApprovalResolved && it.choice is ApprovalChoice.Deny } + 1
        val bashRunning = script.indexOfFirst { it is ToolEffectPending && it.callId == bash } + 1
        val readRunning = script.indexOfFirst { it is ToolEffectPending && it.callId == read } + 1
        val beforePlanning = script.indexOfFirst { it is ToolPlanned } // 回复已落盘，调用还没规划

        assertTrue(textFor(afterPlanned, push).startsWith("这次调用没有执行"))
        assertTrue(textFor(beforePlanning, bash).startsWith("这次调用没有执行"))
        assertEquals("用户拒绝了这次调用。用户留了话，见下面用户的话；不要原样重试。", textFor(afterDeny, push))
        // 说明本身是用户的话：恢复补的结果里也在 userText，不进 tool_result
        assertEquals(
            "用户拒绝了你刚才的 Bash 调用，并留言：别 push",
            planRecovery(fold(header, records(script.take(afterDeny)))).filterIsInstance<ToolOutcome>().single { it.callId == push }.result.userText,
        )
        textFor(bashRunning, bash).let {
            assertTrue(it.contains("结果未知") && it.contains("不要盲目重试"))
            assertTrue("应指出输出日志的位置", it.contains("/tmp/min/h1.log"))
        }
        assertTrue(textFor(readRunning, read).contains("可以直接重新调用"))
    }

    @Test
    fun `fold rejects events that the kernel could never have written`() {
        val base = script.take(7) // 回合已开始
        assertThrows(IllegalStateException::class.java) {
            fold(header, records(base + ToolOutcome(turn, CallId("nope"), ok("x"))))
        }
        assertThrows(IllegalStateException::class.java) {
            fold(header, records(base + TurnStarted(TurnId("t2"), TurnTrigger.User)))
        }
        assertThrows(IllegalStateException::class.java) {
            fold(header, records(script.take(3)) + records(script.drop(3).take(1), startSeq = 9))
        }
        assertThrows(IllegalStateException::class.java) {
            fold(header, records(listOf(SettingsChanged(mode = PermissionMode.Plan))))
        }
    }
}
