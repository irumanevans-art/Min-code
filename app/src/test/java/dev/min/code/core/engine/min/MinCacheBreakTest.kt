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
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.CacheBreakCause
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 用量投影：底栏的累计 token（整棵树）与用量段的缓存失效（来自内核的账） */
/** 用量段的缓存失效：内核从日志里比出来的账，原样带到界面（换了谁、换成谁） */
class MinCacheBreakTest {
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
    fun `no cache break before any request`() {
        assertNull(map(mapOf(root to foldOf())).usage.cacheBreak)
    }

    @Test
    fun `a model switch that rebuilt the cache reaches the usage view with both model names`() {
        val other = ModelRef(T_MODEL.providerId, "m2")
        val s = foldOf(
            TurnStarted(tid, TurnTrigger.User),
            *request(read = 6_000, write = 2_000),
            SettingsChanged(model = other),
            *request(read = 0, write = 8_100, model = other),
        )
        val broke = map(mapOf(root to s)).usage.cacheBreak!!
        assertEquals(listOf(CacheBreakCause.ModelChanged), broke.causes)
        assertEquals("m1", broke.fromModel)
        assertEquals("m2", broke.toModel)
        assertEquals(8_000L, broke.cachedBefore)
        assertEquals(8_100L, broke.written)
    }

    @Test
    fun `a provider switch names the providers, falling back to the id when one was deleted`() {
        val elsewhere = ModelRef("gone", "m1")
        val s = foldOf(TurnStarted(tid, TurnTrigger.User), *request(read = 6_000, write = 2_000), *request(read = 0, write = 8_000, model = elsewhere))
        val broke = map(mapOf(root to s)).usage.cacheBreak!!
        assertEquals(listOf(CacheBreakCause.ProviderChanged), broke.causes)
        assertEquals(FakeLookup().providerLabel("p1"), broke.fromModel)
        assertEquals("gone", broke.toModel)
    }
}
