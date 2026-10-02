package dev.min.code.core.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SessionCommandTest {
    private val json = Json

    private val samples: List<SessionCommand> = listOf(
        SessionCommand.Send("1", UserInput("hi", listOf(ImageInput("image/png", "QUJD"))), SendTarget.Subagent("th")),
        SessionCommand.Send("2", UserInput("main")),
        SessionCommand.Interrupt("3", expectedTurnId = "t1", runQueuedAfter = true),
        SessionCommand.Resume("4", "offer-1"),
        SessionCommand.WithdrawQueued("5", "client-1"),
        SessionCommand.Answer("6", "min:s_1/ap1", InteractionAnswer.Choose(ApprovalOption.DENY, note = "不行")),
        SessionCommand.Answer("7", "q1", InteractionAnswer.Answer(listOf(QuestionAnswerInput("选哪个？", listOf("A", "B"), other = "都要")))),
        SessionCommand.Answer("8", "q1", InteractionAnswer.Decline),
        SessionCommand.SetModel("9", "claude-x", profileId = "prof-2", asDefault = true),
        SessionCommand.SetEffort("10", null),
        SessionCommand.SetMode("11", "plan"),
        SessionCommand.SetCwd("12", "/workspace/a"),
        SessionCommand.SetEngineOption("13", "promptCacheTtl", "1h"),
        SessionCommand.Rename("14", "标题"),
        SessionCommand.RunShell("15", "ls"),
        SessionCommand.StopSubagent("16", "th-1"),
        SessionCommand.StopTask("17", "task-1"),
        SessionCommand.Revert("18", "tu-1"),
        SessionCommand.Refresh("19", setOf(RefreshTarget.Models, RefreshTarget.Usage)),
    )

    @Test
    fun `every command survives a JSON round trip`() {
        for (command in samples) {
            val text = json.encodeToString(SessionCommand.serializer(), command)
            assertEquals(text, command, json.decodeFromString(SessionCommand.serializer(), text))
        }
    }

    @Test
    fun `wire names are fixed so a renamed class cannot break a pending notification action`() {
        val text = json.encodeToString(SessionCommand.serializer(), SessionCommand.Resume("c", "o"))
        assertTrue(text, "\"type\":\"resume\"" in text)
        val answer = json.encodeToString(SessionCommand.serializer(), SessionCommand.Answer("c", "i", InteractionAnswer.Decline))
        assertTrue(answer, "\"decline\"" in answer)
    }

    @Test
    fun `a pre-existing set_model payload without profileId still decodes`() {
        val old = """{"type":"set_model","commandId":"c","modelId":"m"}"""
        assertEquals(SessionCommand.SetModel("c", "m"), json.decodeFromString(SessionCommand.serializer(), old))
    }

    @Test
    fun `every sample has its own commandId`() {
        assertEquals(samples.size, samples.map { it.commandId }.toSet().size)
    }

    @Test
    fun `user input is blank only without text and images`() {
        assertTrue(UserInput("  ").isBlank)
        assertFalse(UserInput("", listOf(ImageInput("image/png", "x"))).isBlank)
        assertFalse(UserInput("a").isBlank)
    }

    // ---------------------------------------------------------------- SessionRef

    @Test
    fun `storage keys keep Claude Code bare and prefix the others`() {
        assertEquals("abc", SessionRef(EngineKind.ClaudeCode, "abc").storageKey)
        assertEquals("codex:th_1", SessionRef(EngineKind.Codex, "th_1").storageKey)
        assertEquals("min:s_1", SessionRef(EngineKind.MinAgent, "s_1").storageKey)
    }

    @Test
    fun `storage keys round trip`() {
        for (ref in listOf(
            SessionRef(EngineKind.ClaudeCode, "5b0c2e1e-0000-4000-8000-000000000000"),
            SessionRef(EngineKind.Codex, "th_1"),
            SessionRef(EngineKind.MinAgent, "s_1"),
        )) assertEquals(ref, SessionRef.fromStorageKey(ref.storageKey))
    }

    @Test
    fun `a legacy bare key is always Claude Code`() {
        assertEquals(SessionRef(EngineKind.ClaudeCode, "legacy"), SessionRef.fromStorageKey("legacy"))
    }

    // ---------------------------------------------------------------- 幂等账本

    @Test
    fun `the same command id is accepted once`() {
        val d = CommandDeduper()
        assertTrue(d.firstSeen("a"))
        assertFalse(d.firstSeen("a"))
        assertTrue(d.firstSeen("b"))
    }

    @Test
    fun `a rejected command can be retried after forget`() {
        val d = CommandDeduper()
        d.firstSeen("a")
        d.forget("a")
        assertTrue(d.firstSeen("a"))
    }

    @Test
    fun `the ledger is bounded and forgets the oldest first`() {
        val d = CommandDeduper(capacity = 3)
        listOf("a", "b", "c", "d").forEach { d.firstSeen(it) }
        assertTrue("最旧的被挤掉，可以再来一次", d.firstSeen("a"))
        assertFalse("较新的还在", d.firstSeen("d"))
    }

    @Test
    fun `concurrent submits of one id let exactly one through`() {
        val d = CommandDeduper()
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                go.await()
                if (d.firstSeen("same")) winners.incrementAndGet()
                done.countDown()
            }
        }
        go.countDown()
        done.await()
        pool.shutdown()
        assertEquals(1, winners.get())
    }
}
