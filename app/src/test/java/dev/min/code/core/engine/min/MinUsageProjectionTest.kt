package dev.min.code.core.engine.min

import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用量投影：底栏的累计 token（整棵树求和，与花费同口径） */
class MinUsageProjectionTest {
    private val root = SessionId("s_root")
    private val tid = TurnId("t1")
    private var n = 0

    private fun request(read: Long, write: Long, input: Long = 50, output: Long = 10, model: ModelRef = T_MODEL): Array<SessionEvent> {
        val id = RequestId("r${++n}")
        return arrayOf(
            ModelRequestStarted(tid, id, 1, model, "fp"),
            AssistantMessageCompleted(tid, id, MessageId("m$n"), listOf(TextPart("ok")), Usage(input, output, read, write), StopReason.EndTurn),
        )
    }

    private fun map(nodes: Map<SessionId, SessionState>, lookup: MinModelLookup = FakeLookup()) =
        MinAgentStateMapper(FakeText(), lookup).map(MinMapperInput(SessionRef(EngineKind.MinAgent, root.value), root, nodes))

    @Test
    fun `session totals add up the whole tree, with cache reads and writes counted as input`() {
        val main = foldOf(TurnStarted(tid, TurnTrigger.User), *request(read = 1_000, write = 200, input = 100, output = 30))
        val child = foldOf(TurnStarted(tid, TurnTrigger.Delegated), *request(read = 0, write = 0, input = 400, output = 70), id = "s_child", parent = "s_root")
        val usage = map(mapOf(root to main, SessionId("s_child") to child)).usage
        assertEquals(1_300L + 400L, usage.totalInputTokens)
        assertEquals(100L, usage.totalOutputTokens)
    }

    @Test
    fun `a session with no requests has no totals rather than zeros`() {
        val usage = map(mapOf(root to foldOf())).usage
        assertNull(usage.totalInputTokens)
        assertNull(usage.totalOutputTokens)
    }
}
