package dev.min.agent.kernel

import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.Effort
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MaskedOutput
import dev.min.agent.spi.ModelRequestFailed
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ModelFailureKind
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.SettingsChanged
import dev.min.agent.spi.ToolOutputsMasked
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.agent.testkit.TEST_MODEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提示缓存的账：相邻两次请求一比判出失效，原因只取日志里有的事实。
 * 时间都由事件记录给（折叠不读时钟），所以这里逐条给事件定时刻。
 */
class CacheWatchTest {
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val turn = TurnId("t1")
    private val other = ModelRef(TEST_MODEL.providerId, "other-model")
    private val elsewhere = ModelRef("another-provider", TEST_MODEL.modelId)

    /** (时刻, 事件)。第一条 SessionCreated 与回合开始由这里补 */
    private fun foldOf(vararg timed: Pair<Long, SessionEvent>): SessionState {
        val all = listOf(0L to SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default), 0L to TurnStarted(turn, TurnTrigger.User)) + timed
        return fold(header, all.mapIndexed { i, (at, e) -> EventRecord(i + 1L, at, e) })
    }

    private var n = 0

    /** 一次请求：[start] 开始、[end] 完成，读写量按 [read] / [write] */
    private fun request(start: Long, end: Long, read: Long, write: Long, model: ModelRef = TEST_MODEL, fp: String = "fp1"): Array<Pair<Long, SessionEvent>> {
        val id = RequestId("r${++n}")
        return arrayOf(
            start to ModelRequestStarted(turn, id, 1, model, fp),
            end to AssistantMessageCompleted(turn, id, MessageId("m$n"), listOf(TextPart("ok")), Usage(inputTokens = 50, outputTokens = 10, cacheReadTokens = read, cacheWriteTokens = write), StopReason.EndTurn),
        )
    }

    @Test
    fun `a request that reads back the previous prefix is a hit, not a break`() {
        val s = foldOf(*request(1_000, 2_000, read = 0, write = 8_000), *request(3_000, 4_000, read = 8_000, write = 300))
        assertNull(s.cacheWatch.lastBreak)
        assertEquals(8_000, s.cacheWatch.last!!.usage.cacheReadTokens)
    }

    @Test
    fun `switching model between two requests is the reason the cache was rebuilt`() {
        val s = foldOf(
            *request(1_000, 2_000, read = 6_000, write = 2_000),
            2_500L to SettingsChanged(model = other),
            *request(3_000, 4_000, read = 0, write = 8_200, model = other),
        )
        val broke = s.cacheWatch.lastBreak!!
        assertEquals(listOf(CacheBreakReason.ModelChanged), broke.reasons)
        assertEquals(8_000, broke.cachedBefore)
        assertEquals(0, broke.readNow)
        assertEquals(8_200, broke.written)
        assertEquals(TEST_MODEL, broke.fromModel)
        assertEquals(other, broke.toModel)
    }

    @Test
    fun `switching provider is told apart from switching model`() {
        val s = foldOf(*request(1_000, 2_000, read = 6_000, write = 2_000), *request(3_000, 4_000, read = 0, write = 8_000, model = elsewhere))
        assertEquals(listOf(CacheBreakReason.ProviderChanged), s.cacheWatch.lastBreak!!.reasons)
    }

    @Test
    fun `a changed system prompt or tool table shows as a changed prefix`() {
        val s = foldOf(*request(1_000, 2_000, read = 6_000, write = 2_000), *request(3_000, 4_000, read = 0, write = 8_000, fp = "fp2"))
        assertEquals(listOf(CacheBreakReason.PrefixChanged), s.cacheWatch.lastBreak!!.reasons)
    }

    @Test
    fun `a long gap is reported with how long it was`() {
        val gap = 20 * 60_000L
        val s = foldOf(*request(1_000, 2_000, read = 6_000, write = 2_000), *request(2_000 + gap, 3_000 + gap, read = 0, write = 8_000))
        val broke = s.cacheWatch.lastBreak!!
        assertEquals(listOf(CacheBreakReason.Idle), broke.reasons)
        assertEquals(gap, broke.idleMs)
    }

    @Test
    fun `masking and an effort change in between are both listed, in a stable order`() {
        val s = foldOf(
            *request(1_000, 2_000, read = 6_000, write = 2_000),
            2_100L to SettingsChanged(effort = Effort.High),
            2_200L to ToolOutputsMasked(turn, listOf(MaskedOutput(CallId("c1"), null))),
            *request(3_000, 4_000, read = 1_000, write = 7_000),
        )
        assertEquals(listOf(CacheBreakReason.OutputsMasked, CacheBreakReason.EffortChanged), s.cacheWatch.lastBreak!!.reasons)
    }

    @Test
    fun `an effort change to the same level is not a reason`() {
        val s = foldOf(
            2L to SettingsChanged(effort = Effort.High),
            *request(1_000, 2_000, read = 6_000, write = 2_000),
            2_100L to SettingsChanged(effort = Effort.High),
            *request(3_000, 4_000, read = 0, write = 8_000),
        )
        assertEquals(emptyList<CacheBreakReason>(), s.cacheWatch.lastBreak!!.reasons)
    }

    @Test
    fun `nothing in the log explains the drop, so the reasons stay empty rather than guessed`() {
        val s = foldOf(*request(1_000, 2_000, read = 6_000, write = 2_000), *request(3_000, 4_000, read = 0, write = 8_000))
        assertEquals(emptyList<CacheBreakReason>(), s.cacheWatch.lastBreak!!.reasons)
    }

    @Test
    fun `a provider that reports no cache usage never produces a break`() {
        val s = foldOf(*request(1_000, 2_000, read = 0, write = 0), *request(3_000, 4_000, read = 0, write = 0, model = other))
        assertNull(s.cacheWatch.lastBreak)
    }

    @Test
    fun `a small wobble below the threshold is not a break`() {
        // 读回的少了几百个 token（比如供应商按 128 对齐），不算失效
        val s = foldOf(*request(1_000, 2_000, read = 20_000, write = 0), *request(3_000, 4_000, read = 19_700, write = 400))
        assertNull(s.cacheWatch.lastBreak)
    }

    @Test
    fun `a failed request does not count, the next completed one is compared with the last completed one`() {
        val failed = RequestId("rf")
        val s = foldOf(
            *request(1_000, 2_000, read = 6_000, write = 2_000),
            2_500L to ModelRequestStarted(turn, failed, 1, other, "fpX"),
            2_600L to ModelRequestFailed(turn, failed, 1, ModelFailureKind.Overloaded, "busy"),
            *request(3_000, 4_000, read = 8_000, write = 100),
        )
        assertNull(s.cacheWatch.lastBreak)
    }

    @Test
    fun `the last break stays after later hits, and the changes are cleared once a request completes`() {
        val s = foldOf(
            *request(1_000, 2_000, read = 6_000, write = 2_000),
            2_100L to ToolOutputsMasked(turn, listOf(MaskedOutput(CallId("c1"), null))),
            *request(3_000, 4_000, read = 0, write = 8_000),
            *request(5_000, 6_000, read = 8_000, write = 100),
        )
        assertEquals(listOf(CacheBreakReason.OutputsMasked), s.cacheWatch.lastBreak!!.reasons)
        assertEquals(4_000, s.cacheWatch.lastBreak!!.atMs)
        assertEquals(emptySet<CacheChange>(), s.cacheWatch.since)
    }
}
