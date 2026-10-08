package dev.min.code.core.engine.min

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.protocol.Usage
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputPromoted
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.InterruptedBy
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnEnded
import dev.min.agent.spi.TurnOutcome
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 摘要是派生数据：缺了、过期了、坏了都从日志重建；重建只读，不改日志；启动扫描让没收尾的会话合法收尾 */
class MinSessionIndexTest {
    private val k = TestKernel(ScriptedModel(answer("first answer"), answer("second answer")))
    private val indexDir = File(k.dir, "index")
    private val index = MinSessionIndex(indexDir, k.sessionsDir)

    @After fun tearDown() { k.close() }

    private fun logFile(id: String) = File(File(k.sessionsDir, id), "events.jsonl")

    /** 直接往存储里写一份日志，绕过运行时：造「进程死在回合中途」的现场 */
    private fun rawSession(id: String, vararg events: SessionEvent, parent: String? = null) = runBlocking {
        val log = k.store.create(LogHeader(LOG_FORMAT_VERSION, SessionId(id), 5_000, parent?.let(::SessionId)))
        log.append(listOf<SessionEvent>(SessionCreated(GuestPath("/workspace"), T_MODEL, PermissionMode.Default)) + events, Durability.Sync)
    }

    private fun typed(id: String, text: String) = listOf<SessionEvent>(
        InputReceived(ClientMsgId(id), listOf(TextPart(text)), Delivery.Queue), InputPromoted(ClientMsgId(id), MessageId("m_$id")),
    )

    private fun completedSession(): String = runBlocking {
        val root = k.newRoot()
        root.send(ClientMsgId("a"), listOf(TextPart("  \nhello index\nsecond line")))
        root.awaitIdle()
        root.sessionId.value
    }

    @Test fun entryCarriesTheListFieldsFromTheFoldedState() {
        val state = foldOf(
            *typed("u", "title line\nmore").toTypedArray(), TurnStarted(TurnId("t"), TurnTrigger.User),
            dev.min.agent.spi.ModelRequestStarted(TurnId("t"), dev.min.agent.protocol.RequestId("r"), 1, T_MODEL, "fp"),
            dev.min.agent.spi.AssistantMessageCompleted(
                TurnId("t"), dev.min.agent.protocol.RequestId("r"), MessageId("a"), listOf(TextPart("an answer")), Usage(10, 5, 100, 0),
                dev.min.agent.protocol.StopReason.EndTurn,
            ),
            TurnEnded(TurnId("t"), TurnOutcome.Completed, Usage(10, 5, 100, 0), 5),
        )
        val e = indexEntryOf(state, eventsLength = 123, eventsMtime = 456, updatedAt = 789)
        assertEquals("title line", e.title); assertEquals(2, e.messageCount); assertEquals("p1", e.profileId); assertEquals("m1", e.model)
        assertEquals("/workspace", e.cwd); assertFalse(e.turnOpen); assertEquals("completed", e.lastOutcome)
        assertEquals(110L, e.inputTokens); assertEquals(5L, e.outputTokens); assertEquals(789L, e.updatedAt)
        assertEquals(123L, e.eventsLength); assertEquals(456L, e.eventsMtime); assertTrue(e.bodyText.contains("more"))
    }

    @Test fun titleIsClippedAndBodyIsBounded() {
        val big = "word ".repeat(20_000)
        val e = indexEntryOf(foldOf(*typed("u", big).toTypedArray()), 0, 0, 0)
        assertEquals(MinIndexEntry.TITLE_CHARS, e.title.length)
        assertEquals(MinIndexEntry.BODY_CHARS, e.bodyText.length)
    }

    @Test fun missingIndexIsRebuiltFromTheLogAndTheLogIsLeftAlone() {
        val id = completedSession()
        val before = logFile(id).readBytes()
        val entries = runBlocking { index.list(k.store) }
        assertEquals(id, entries.single().id)
        assertEquals("hello index", entries.single().title)
        assertTrue(entries.single().bodyText.contains("first answer"))
        assertTrue("重建后写回摘要文件", File(indexDir, "$id.json").isFile)
        assertTrue("只读重建，不提交任何恢复事件", before.contentEquals(logFile(id).readBytes()))
    }

    @Test fun rebuildNeverAttachesTheWriterToTheLog() {
        // H2：回合进行中打开抽屉也会走到 rebuild——open 带修复性截断，对活日志会把写者刚追加的记录剁掉
        val id = completedSession()
        val readerOnly = object : dev.min.agent.spi.SessionStore by k.store {
            override suspend fun open(id: SessionId): dev.min.agent.spi.SessionLog? =
                error("open 是写者附着专用（带修复性截断）；轮询日志必须走 peek：${id.value}")
        }
        val entries = runBlocking { index.list(readerOnly) }
        assertEquals("重建走 peek，不附着写者", id, entries.single().id)
    }

    @Test fun validIndexIsServedAsIsAndAChangedLogInvalidatesIt() {
        val id = completedSession()
        val first = runBlocking { index.list(k.store) }.single()
        // 篡改摘要但保持日志指纹不变：命中缓存就原样返回（证明没有重新折叠）
        runBlocking { index.write(first.copy(title = "tampered")) }
        assertEquals("tampered", runBlocking { index.list(k.store) }.single().title)
        // 日志变了：指纹对不上，重建
        runBlocking { k.hub.open(SessionId(id))!!.send(ClientMsgId("b"), listOf(TextPart("again"))); k.hub.get(SessionId(id))!!.awaitIdle() }
        val rebuilt = runBlocking { index.list(k.store) }.single()
        assertEquals("hello index", rebuilt.title)
        assertEquals(first.messageCount + 2, rebuilt.messageCount)
    }

    @Test fun orphanIndexFilesAreCleanedAndCorruptOnesAreRebuilt() {
        val id = completedSession()
        runBlocking { index.list(k.store) }
        File(indexDir, "s_ghost.json").writeText("{}")
        File(indexDir, "$id.json").writeText("not json")
        val entries = runBlocking { index.list(k.store) }
        assertEquals("hello index", entries.single().title)
        assertFalse(File(indexDir, "s_ghost.json").exists())
    }

    @Test fun childSessionsAreNotListed() {
        rawSession("s_parent", *typed("u", "p").toTypedArray())
        rawSession("s_child", *typed("u", "c").toTypedArray(), parent = "s_parent")
        assertEquals(listOf("s_parent"), runBlocking { index.list(k.store) }.map { it.id })
    }

    @Test fun unrecoveredLogShowsTurnOpenSoStartupCanFindIt() {
        rawSession("s_dead", *typed("u", "long task").toTypedArray(), TurnStarted(TurnId("t"), TurnTrigger.User))
        val e = runBlocking { index.list(k.store) }.single()
        assertTrue(e.turnOpen); assertFalse(e.deathResumable)
    }

    @Test fun startupRecoveryEndsOnlyOfflineOpenTurnsAndNeverResumesThem() {
        rawSession("s_dead", *typed("u", "long task").toTypedArray(), TurnStarted(TurnId("t"), TurnTrigger.User))
        rawSession("s_fine", *typed("u", "done").toTypedArray(), TurnStarted(TurnId("t2"), TurnTrigger.User),
            TurnEnded(TurnId("t2"), TurnOutcome.Completed, Usage(), 0))
        val recovery = MinStartupRecovery(
            k.store, index, isOnline = { SessionId(it) in k.hub.online.value },
            recover = { id -> k.hub.open(id)?.let { index.update(it.state.value) }; k.hub.close(id) },
        )
        assertEquals(listOf("s_dead"), runBlocking { recovery.run() })
        val events = runBlocking { k.store.open(SessionId("s_dead"))!!.read().let { f -> val l = mutableListOf<SessionEvent>(); f.collect { l += it.event }; l } }
        val end = events.last() as TurnEnded
        assertEquals(TurnOutcome.Interrupted(InterruptedBy.ProcessDeath), end.outcome)
        assertEquals("不自动续跑：没有第二个 TurnStarted", 1, events.count { it is TurnStarted })
        assertTrue("恢复完不留常驻运行时", k.hub.online.value.isEmpty())
        val entry = runBlocking { index.list(k.store) }.first { it.id == "s_dead" }
        assertFalse(entry.turnOpen); assertTrue(entry.deathResumable)
    }

    @Test fun badLogDoesNotStopTheOtherSessionsFromRecovering() {
        rawSession("s_dead", *typed("u", "x").toTypedArray(), TurnStarted(TurnId("t"), TurnTrigger.User))
        val recovery = MinStartupRecovery(
            k.store, index, isOnline = { false },
            recover = { id -> if (id.value == "s_dead") error("boom") },
        )
        assertEquals(emptyList<String>(), runBlocking { recovery.run() })
        assertNull(runBlocking { index.read("s_never") })
    }
}
