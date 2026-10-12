package dev.min.code.core.engine.claude

import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeTurnEnd
import dev.min.code.core.claudecode.ManagerHarness
import dev.min.code.core.claudecode.eventually
import dev.min.code.core.claudecode.parseTranscriptLine
import dev.min.code.core.claudecode.transcriptEndedMidTurn
import dev.min.code.core.claudecode.turnEndOfResult
import dev.min.code.core.engine.CONTINUE_PROMPT
import dev.min.code.core.engine.CommandResult
import dev.min.code.core.engine.FailureKind
import dev.min.code.core.engine.InterruptCause
import dev.min.code.core.engine.ResumeMode
import dev.min.code.core.engine.ResumeReason
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.TurnEnd
import dev.min.code.core.engine.UserInput
import dev.min.code.core.engine.withContinuations
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * B8：CC 的 ▶（设计 §6.4）。manager 的只读字段 `lastTurnEnd`、结局判定的纯函数、
 * 映射成 [ResumeOffer]、按下 ▶ 之后真的代发续跑提示（进程死了先 `--resume` 接回）。
 * manager 是真的，CLI 进程是 FakeCliProcess 回放真 CLI 录下的夹具。
 */
class ClaudeResumeFlowTest {

    private val ids = AtomicInteger()
    private fun id() = "cmd-${ids.incrementAndGet()}"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun dev.min.code.core.claudecode.FakeCliProcess.userTexts() = written
        .filter { it.str("type") == "user" }
        .map { it["message"]!!.jsonObject["content"]!!.jsonArray.first().jsonObject.str("text") }

    private class Rig(val h: ManagerHarness, val session: ClaudeCodeSession, private val scope: CoroutineScope) : AutoCloseable {
        override fun close() {
            scope.cancel()
            h.close()
        }
    }

    private fun rig(fixture: String): Rig {
        val h = ManagerHarness(fixture)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Rig(h, ClaudeCodeSession(h.manager, "key", scope), scope)
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    fun `turnEndOfResult - the user's own stop is never a failure, a plain error is`() {
        fun end(isError: Boolean, subtype: String = "error_during_execution", text: String? = "boom", interrupted: Boolean = false,
                withdrew: Boolean = false, denied: Boolean = false) =
            turnEndOfResult(isError, subtype, text, interrupted, withdrew, denied, atMs = 7L)
        assertEquals(ClaudeTurnEnd.Kind.Completed, end(isError = false, subtype = "success").kind)
        // 打断时 CLI 回的是 is_error 的 result：要认出是我们打断的，否则每次按停止都记成失败
        assertEquals(ClaudeTurnEnd.Kind.Interrupted, end(isError = true, interrupted = true).kind)
        assertEquals(ClaudeTurnEnd.Kind.Withdrawn, end(isError = true, interrupted = true, withdrew = true).kind)
        // 用户点「拒绝」停下的一轮同理
        assertEquals(ClaudeTurnEnd.Kind.Interrupted, end(isError = true, denied = true).kind)
        val failed = end(isError = true, text = "API Error: 529 overloaded")
        assertEquals(ClaudeTurnEnd.Kind.Failed, failed.kind)
        assertEquals("API Error: 529 overloaded", failed.detail)
        assertEquals(7L, failed.atMs)
        assertEquals("没有原文就写 subtype", "error_during_execution", end(isError = true, text = null).detail)
        assertEquals(ClaudeTurnEnd.Kind.Limit, end(isError = true, subtype = "error_max_turns").kind)
        assertEquals(ClaudeTurnEnd.Kind.Limit, end(isError = true, subtype = "error_max_budget_usd").kind)
    }

    @Test
    fun `ClaudeTurnEnd maps onto the common TurnEnd`() {
        assertEquals(TurnEnd.Completed, ClaudeTurnEnd(ClaudeTurnEnd.Kind.Completed).toTurnEnd())
        assertEquals(TurnEnd.Interrupted(InterruptCause.User), ClaudeTurnEnd(ClaudeTurnEnd.Kind.Interrupted).toTurnEnd())
        assertEquals(TurnEnd.Interrupted(InterruptCause.Withdrawn), ClaudeTurnEnd(ClaudeTurnEnd.Kind.Withdrawn).toTurnEnd())
        assertEquals(TurnEnd.Interrupted(InterruptCause.Limit), ClaudeTurnEnd(ClaudeTurnEnd.Kind.Limit).toTurnEnd())
        assertEquals(TurnEnd.Interrupted(InterruptCause.EngineExited), ClaudeTurnEnd(ClaudeTurnEnd.Kind.EngineExited).toTurnEnd())
        assertEquals(TurnEnd.Interrupted(InterruptCause.ProcessDeath), ClaudeTurnEnd(ClaudeTurnEnd.Kind.ProcessDeath).toTurnEnd())
        val failed = ClaudeTurnEnd(ClaudeTurnEnd.Kind.Failed, detail = "API Error: 529 overloaded").toTurnEnd() as TurnEnd.Failed
        assertEquals(FailureKind.Overloaded, failed.kind)
    }

    @Test
    fun `failure kinds are recognised from the CLI's own wording, unknown text stays unknown`() {
        assertEquals(FailureKind.ContextOverflow, claudeFailureKind("Prompt is too long"))
        assertEquals(FailureKind.RateLimited, claudeFailureKind("API Error: 429 rate_limit_error"))
        assertEquals(FailureKind.Overloaded, claudeFailureKind("API Error: 529 {\"type\":\"overloaded_error\"}"))
        assertEquals(FailureKind.Auth, claudeFailureKind("API Error: 401 invalid x-api-key"))
        assertEquals(FailureKind.Server, claudeFailureKind("API Error: 500 Internal server error"))
        assertEquals(FailureKind.Network, claudeFailureKind("Unable to connect: ECONNREFUSED"))
        assertEquals(FailureKind.ModelUnavailable, claudeFailureKind("There's an issue with the selected model (x). It may not exist"
            .replace("It may not exist", "model not found")))
        assertNull(claudeFailureKind(null))
        assertNull(claudeFailureKind("something odd happened"))
    }

    @Test
    fun `the continue prompt becomes a continuation divider, other messages and the list identity are untouched`() {
        val plain = listOf(ChatItem.UserText("u", "hi"), ChatItem.AssistantText("a", "ok"))
        assertSame("没有可标的就原样返回同一个列表", plain, plain.withContinuations())
        val marked = (plain + ChatItem.UserText("c", CONTINUE_PROMPT)).withContinuations()
        assertTrue((marked[2] as ChatItem.UserText).continuation)
        assertEquals(false, (marked[0] as ChatItem.UserText).continuation)
    }

    @Test
    fun `transcript tail rule matches what the real CLI resumes (probe matrix, CLI 2_1_295)`() {
        val user = ClaudeCodeEvent.UserMessage("do it")
        val tool = ClaudeCodeEvent.ToolUse("t1", "Bash", JsonObject(emptyMap()))
        val noise = listOf(ClaudeCodeEvent.CustomTitle("t"), ClaudeCodeEvent.SystemNote("api_error", isError = true))
        fun mid(vararg events: ClaudeCodeEvent) = transcriptEndedMidTurn(events.toList())
        // CLI 续：用户文本、tool_use 无结果、tool_result、API 错误说明、仅 thinking
        assertTrue(mid(user))
        assertTrue(mid(user, *noise.toTypedArray()))
        assertTrue(mid(user, tool))
        assertTrue(mid(user, tool, ClaudeCodeEvent.ToolResult("t1", "out", false)))
        assertTrue(mid(user, ClaudeCodeEvent.AssistantText("API Error: 500 server", apiError = true)))
        assertTrue(mid(user, ClaudeCodeEvent.Thinking("hmm")))
        // CLI 不续：助手正文、工具回合收尾、用户自己打断留下的标记（含「for tool use」版、标记后跟非对话记录）
        assertFalse(mid(user, ClaudeCodeEvent.AssistantText("done")))
        assertFalse(mid(user, tool, ClaudeCodeEvent.ToolResult("t1", "out", false), ClaudeCodeEvent.AssistantText("done")))
        assertFalse(mid(user, ClaudeCodeEvent.AssistantText("partial"), ClaudeCodeEvent.UserMessage("[Request interrupted by user]")))
        assertFalse(mid(user, tool, ClaudeCodeEvent.ToolResult("t1", "rejected", true), ClaudeCodeEvent.UserMessage("[Request interrupted by user for tool use]"), *noise.toTypedArray()))
        assertFalse(mid())
    }

    @Test
    fun `an API error assistant row in a transcript is parsed as an api error, a normal assistant row is not`() {
        fun row(extra: String) =
            """{"type":"assistant","uuid":"a","timestamp":"2026-10-11T10:00:00.000Z"$extra,"message":{"role":"assistant","content":[{"type":"text","text":"API Error: 500 boom"}]}}"""
        val error = parseTranscriptLine(row(",\"isApiErrorMessage\":true")).single() as ClaudeCodeEvent.AssistantText
        assertTrue(error.apiError)
        assertNotNull("时间戳照样带上", error.at)
        val plain = parseTranscriptLine(row("")).single() as ClaudeCodeEvent.AssistantText
        assertEquals(false, plain.apiError)
        assertNotNull(plain.at)
    }

    // ---------------------------------------------------------------- manager 记录结局

    @Test
    fun `a finished turn records Completed, sending the next one clears it`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.h.startAndHandshake()
            r.session.submit(SessionCommand.Send(id(), UserInput("hello")))
            val end = r.h.awaitTurnEnd()
            assertEquals(ClaudeTurnEnd.Kind.Completed, end.lastTurnEnd?.kind)
            assertNotNull(end.lastTurnEnd?.atMs)
            assertNull("正常完成不出 ▶", r.session.state.value.resume)
        }
    }

    @Test
    fun `the stop key after output records Interrupted and offers a play key that sends the continue prompt`() = runBlocking<Unit> {
        rig("interrupt_midturn").use { r ->
            r.h.startAndHandshake()
            r.session.submit(SessionCommand.Send(id(), UserInput("count")))
            r.h.awaitState("开始流") { it.streamingText.isNotEmpty() && it.turnProduced }
            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Interrupt(id())))
            val end = r.h.awaitTurnEnd()
            assertEquals(ClaudeTurnEnd.Kind.Interrupted, end.lastTurnEnd?.kind)

            eventually("映射出 ▶") { r.session.state.value.resume != null }
            val offer = r.session.state.value.resume!!
            assertEquals(ResumeReason.UserStopped, offer.reason)
            assertEquals("进程还活着：直接代发", ResumeMode.Prompted, offer.mode)
            assertEquals(TurnEnd.Interrupted(InterruptCause.User), r.session.state.value.lastTurn?.end)

            // 过期的 offerId 不放行
            assertTrue(r.session.submit(SessionCommand.Resume(id(), "stale-offer")) is CommandResult.Stale)

            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Resume(id(), offer.offerId)))
            r.h.awaitCondition("续跑提示写出") { CONTINUE_PROMPT in r.h.process.userTexts() }
            // 转录里画成分隔线，不是用户气泡
            eventually("续跑提示是分隔线") {
                r.session.state.value.items.any { it is ChatItem.UserText && it.continuation && it.text == CONTINUE_PROMPT }
            }
            // 新一轮一开始 ▶ 就收起
            eventually("▶ 收起") { r.session.state.value.resume == null }
        }
    }

    @Test
    fun `withdrawing before any output leaves no play key - the message is back in the input box`() = runBlocking<Unit> {
        rig("interrupt_before_output").use { r ->
            r.h.startAndHandshake()
            r.session.submit(SessionCommand.Send(id(), UserInput("Write a haiku about the sea.")))
            r.h.awaitState("busy") { it.busy && !it.turnProduced }
            r.session.submit(SessionCommand.Interrupt(id()))
            val end = r.h.awaitTurnEnd()
            assertEquals(ClaudeTurnEnd.Kind.Withdrawn, end.lastTurnEnd?.kind)
            eventually("映射完成") { r.session.state.value.lastTurn != null }
            assertNull(r.session.state.value.resume)
        }
    }

    @Test
    fun `a failed turn records Failed with the CLI's wording and offers a play key`() = runBlocking<Unit> {
        rig("model_not_found").use { r ->
            r.h.startAndHandshake()
            r.session.submit(SessionCommand.Send(id(), UserInput("Say hi.")))
            val end = r.h.awaitTurnEnd()
            assertEquals(ClaudeTurnEnd.Kind.Failed, end.lastTurnEnd?.kind)
            assertTrue("原文进了 detail", !end.lastTurnEnd?.detail.isNullOrBlank())
            eventually("映射出 ▶") { r.session.state.value.resume != null }
            assertTrue(r.session.state.value.resume!!.reason is ResumeReason.Failed)
        }
    }

    @Test
    fun `the CLI dying mid-turn records EngineExited, the play key relaunches with --resume and then sends the prompt`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.h.startAndHandshake()
            r.h.process.holdTurns = true
            r.session.submit(SessionCommand.Send(id(), UserInput("work")))
            r.h.awaitState("busy") { it.busy }
            val first = r.h.process
            val sessionBefore = r.h.manager.state.value.sessionId
            first.crash(137)
            val dead = r.h.awaitState("进程退出") { it.status == dev.min.code.core.session.SessionStatus.Closed }
            assertEquals(ClaudeTurnEnd.Kind.EngineExited, dead.lastTurnEnd?.kind)

            eventually("映射出 ▶") { r.session.state.value.resume != null }
            val offer = r.session.state.value.resume!!
            assertEquals(ResumeReason.EngineExited, offer.reason)
            assertEquals(ResumeMode.RelaunchThenPrompted, offer.mode)

            assertEquals(CommandResult.Accepted, r.session.submit(SessionCommand.Resume(id(), offer.offerId)))
            r.h.awaitCondition("第二个进程起来") { r.h.launches.size == 2 }
            val relaunch = r.h.launches.last()
            assertEquals("接回会话用 --resume <原来的会话 id>", sessionBefore, relaunch.options.resumeSessionId)
            r.h.awaitCondition("续跑提示写进新进程") { CONTINUE_PROMPT in relaunch.process.userTexts() }
            assertTrue("旧进程没有再收到任何话", CONTINUE_PROMPT !in first.userTexts())
        }
    }

    @Test
    fun `ending the session yourself while busy is not an engine death - no play key`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            r.h.startAndHandshake()
            r.h.process.holdTurns = true
            r.session.submit(SessionCommand.Send(id(), UserInput("work")))
            r.h.awaitState("busy") { it.busy }
            r.session.close(dev.min.code.core.engine.CloseReason.UserEnded)
            r.h.awaitState("已结束") { it.status == dev.min.code.core.session.SessionStatus.Closed }
            assertNull("用户有意结束会话：不记结局，也就没有 ▶（否则已结束的会话会留在会话页上）", r.h.manager.state.value.lastTurnEnd)
            assertNull(r.session.state.value.resume)
        }
    }

    // ---------------------------------------------------------------- 重开 transcript

    private fun writeTranscript(h: ManagerHarness, sessionId: String, vararg lines: String) = runBlocking {
        val linuxDir = h.manager.transcriptLinuxDir() ?: error("没有 linux 目录")
        val dir = File(linuxDir, "root/.claude/projects/-workspace").apply { mkdirs() }
        File(dir, "$sessionId.jsonl").writeText(lines.joinToString("\n"))
    }

    private fun userLine(text: String) =
        """{"type":"user","sessionId":"s","uuid":"u","cwd":"/workspace","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun assistantLine(text: String) =
        """{"type":"assistant","sessionId":"s","uuid":"a","cwd":"/workspace","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    @Test
    fun `reopening a transcript that stopped mid-turn offers a play key, one that ended with an answer does not`() = runBlocking<Unit> {
        rig("plain_reply").use { r ->
            writeTranscript(r.h, "sess-mid", userLine("do the thing"))
            r.h.manager.openSession("sess-mid")
            val opened = r.h.awaitState("接回") { it.status == dev.min.code.core.session.SessionStatus.Running && it.lastTurnEnd != null }
            assertEquals(ClaudeTurnEnd.Kind.ProcessDeath, opened.lastTurnEnd?.kind)
            eventually("映射出 ▶") { r.session.state.value.resume != null }
            assertEquals(ResumeReason.ProcessDeath(null), r.session.state.value.resume!!.reason)
            assertEquals("openSession 本来就起了 CLI：直接代发", ResumeMode.Prompted, r.session.state.value.resume!!.mode)
        }
        rig("plain_reply").use { r ->
            writeTranscript(r.h, "sess-done", userLine("hi"), assistantLine("hello"))
            r.h.manager.openSession("sess-done")
            val opened = r.h.awaitState("接回") { it.status == dev.min.code.core.session.SessionStatus.Running && it.items.isNotEmpty() }
            assertNull("回答已落盘：不续（会变成 assistant prefill）", opened.lastTurnEnd)
            assertNull(r.session.state.value.resume)
        }
    }
}
