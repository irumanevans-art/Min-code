package dev.min.code.core.engine.min

import dev.min.agent.kernel.LiveUpdate
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.RequestId
import dev.min.agent.protocol.StopReason
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.AssistantMessageCompleted
import dev.min.agent.spi.AssistantSegment
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.ModelRequestStarted
import dev.min.agent.spi.ResumePolicy
import dev.min.agent.spi.ToolEffectPending
import dev.min.agent.spi.ToolOutcome
import dev.min.agent.spi.ToolPlanned
import dev.min.agent.spi.ToolResult
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.code.core.engine.ToolDraft
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** K4：瞬时增量带 requestId，请求切换时缓冲归零；迟到的旧增量不能混进新请求或已落盘的回答 */
class MinLiveStreamTest {
    private val t1 = TurnId("t1")
    private val r1 = RequestId("r1")
    private val r2 = RequestId("r2")
    private val live = MinLiveStream()
    private val now get() = live.stream.value

    private val prelude = arrayOf(
        InputReceived(ClientMsgId("u"), listOf(TextPart("hi")), Delivery.Queue),
        InputPromoted(ClientMsgId("u"), MessageId("mu")),
        TurnStarted(t1, TurnTrigger.User),
    )

    @Test fun deltasOfOneRequestAccumulate() {
        live.onUpdate(LiveUpdate.Text(t1, r1, "he"))
        live.onUpdate(LiveUpdate.Text(t1, r1, "llo"))
        live.onUpdate(LiveUpdate.Thinking(t1, r1, "hm"))
        assertEquals("hello", now.text)
        assertEquals("hm", now.thinking)
        assertEquals("t1", now.turnId)
    }

    @Test fun newRequestIdClearsTheBuffers() {
        live.onUpdate(LiveUpdate.Text(t1, r1, "first answer"))
        live.onUpdate(LiveUpdate.Thinking(t1, r1, "first thought"))
        live.onUpdate(LiveUpdate.ToolCallStarted(t1, r1, 0, "Read"))
        live.onUpdate(LiveUpdate.Text(t1, r2, "second"))
        assertEquals("second", now.text)
        assertEquals("", now.thinking)
        assertTrue(now.toolDrafts.isEmpty())
    }

    @Test fun lateDeltaOfAFinishedRequestIsDropped() {
        // 落盘的完成事件先到、最后几个增量后到：状态已经看到请求收尾，旧增量不能让回答在屏幕上出现第二遍
        live.onUpdate(LiveUpdate.Text(t1, r1, "answer"))
        live.onState(foldOf(*prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp")))
        live.onState(
            foldOf(
                *prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"),
                AssistantMessageCompleted(t1, r1, MessageId("a1"), listOf(TextPart("answer")), Usage(), StopReason.EndTurn),
            ),
        )
        assertEquals("", now.text)
        live.onUpdate(LiveUpdate.Text(t1, r1, " tail"))
        assertEquals("", now.text)
    }

    @Test fun skippedStateFrameStillRetiresTheOldRequest() {
        // 状态流是合并的：「r1 已完成」那一帧可能被跳过，直接从 r1 跳到 r2——旧请求的迟到增量不能清掉 r2 的缓冲
        live.onState(foldOf(*prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp")))
        live.onState(
            foldOf(
                *prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"),
                AssistantMessageCompleted(t1, r1, MessageId("a1"), listOf(TextPart("a")), Usage(), StopReason.ToolUse),
                ModelRequestStarted(t1, r2, 1, T_MODEL, "fp"),
            ).let { it },
        )
        live.onUpdate(LiveUpdate.Text(t1, r2, "new"))
        live.onUpdate(LiveUpdate.Text(t1, r1, "stale"))
        assertEquals("new", now.text)
    }

    @Test fun turnChangeClearsEverythingIncludingToolOutput() {
        live.onUpdate(LiveUpdate.ToolOutput(CallId("c1"), "out"))
        live.onUpdate(LiveUpdate.Text(t1, r1, "x"))
        live.onUpdate(LiveUpdate.Text(TurnId("t2"), RequestId("r9"), "y"))
        assertEquals("y", now.text)
        assertTrue(now.toolOutput.isEmpty())
        assertEquals("t2", now.turnId)
    }

    @Test fun toolDraftsAccumulatePartialJsonPerIndex() {
        live.onUpdate(LiveUpdate.ToolCallStarted(t1, r1, 0, "Write"))
        live.onUpdate(LiveUpdate.ToolInput(t1, r1, 0, "{\"file_"))
        live.onUpdate(LiveUpdate.ToolInput(t1, r1, 0, "path\":1}"))
        live.onUpdate(LiveUpdate.ToolCallStarted(t1, r1, 1, "Read"))
        assertEquals(
            listOf(ToolDraft(0, null, "Write", "{\"file_path\":1}"), ToolDraft(1, null, "Read", "")),
            now.toolDrafts,
        )
    }

    @Test fun toolOutputIsClippedAndDroppedOnceTheCallIsDone() {
        val call = CallId("c1")
        val running = foldOf(
            *prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"),
            AssistantMessageCompleted(
                t1, r1, MessageId("a1"), listOf(dev.min.agent.protocol.ToolCallPart(call, "Bash", JsonObject(emptyMap()))), Usage(), StopReason.ToolUse,
            ),
            ToolPlanned(t1, call, "Bash", JsonObject(emptyMap()), ResumePolicy.Unknown), ToolEffectPending(t1, call),
        )
        live.onState(running)
        live.onUpdate(LiveUpdate.ToolOutput(call, "x".repeat(20_000)))
        assertEquals(dev.min.code.core.session.MAX_TOOL_RESULT_CHARS, now.toolOutput.getValue("c1").length)
        live.onState(
            foldOf(
                *prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"),
                AssistantMessageCompleted(
                    t1, r1, MessageId("a1"), listOf(dev.min.agent.protocol.ToolCallPart(call, "Bash", JsonObject(emptyMap()))), Usage(), StopReason.ToolUse,
                ),
                ToolPlanned(t1, call, "Bash", JsonObject(emptyMap()), ResumePolicy.Unknown), ToolEffectPending(t1, call),
                ToolOutcome(t1, call, ToolResult(listOf(TextPart("done")))),
            ),
        )
        assertTrue(now.toolOutput.isEmpty())
    }

    @Test fun toolStatusFollowsTheRunningCall() {
        live.onUpdate(LiveUpdate.ToolStatus(CallId("c1"), "正在搜索"))
        assertEquals(CallId("c1") to "正在搜索", live.toolStatus.value)
    }

    @Test fun attachingMidRequestShowsThePersistedSegmentsUntilLiveCatchesUp() {
        val open = foldOf(
            *prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"),
            AssistantSegment(t1, r1, MessageId("a1"), listOf(TextPart("persisted part"))),
        )
        live.onState(open)
        assertEquals("persisted part", now.text)
        assertEquals("t1", now.turnId)
        live.onUpdate(LiveUpdate.Text(t1, r1, "short"))
        // 实时的比落盘的短：不回缩
        assertEquals("persisted part", now.text)
        live.onUpdate(LiveUpdate.Text(t1, r1, " but now much longer than before"))
        assertEquals("short but now much longer than before", now.text)
    }

    @Test fun turnEndClearsTheStreamAndRejectsLateDeltas() {
        val open = foldOf(*prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"))
        live.onState(open)
        live.onUpdate(LiveUpdate.Text(t1, r1, "partial"))
        live.onState(foldOf(*prelude, ModelRequestStarted(t1, r1, 1, T_MODEL, "fp"), TurnEnded(t1, TurnOutcome.Interrupted(dev.min.agent.spi.InterruptedBy.User), Usage(), 0)))
        assertTrue(now.isEmpty)
        live.onUpdate(LiveUpdate.Text(t1, r1, "late"))
        assertTrue(now.isEmpty)
    }
}
