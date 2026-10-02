package dev.min.code.core.engine.min

import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.spi.TurnStarted
import dev.min.agent.spi.TurnTrigger
import dev.min.agent.store.LOG_FORMAT_VERSION
import dev.min.code.core.engine.DeleteResult
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionCommand
import dev.min.code.core.engine.SessionRef
import dev.min.code.core.engine.UserInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 删除要连根带树清干净：会话目录（含子会话）、产物目录、exec 日志、索引、草稿与元数据（设计 8.4） */
class MinCatalogDeleteTest {
    private val k = TestKernel(ScriptedModel())
    private val linux = File(k.dir, "linux").also { it.mkdirs() }
    private val index = MinSessionIndex(File(k.dir, "index"), k.sessionsDir)
    private val calls = mutableListOf<String>()
    private val forgotten = mutableListOf<SessionRef>()
    private var online = emptySet<String>()

    private val logA = "a".repeat(32)
    private val logB = "b".repeat(32)
    private val logOther = "c".repeat(32)

    private val catalog = MinSessionCatalog(
        store = k.store, index = index, sessionsDir = k.sessionsDir, linuxDir = linux,
        deleteTree = { id -> calls += "deleteTree"; k.hub.delete(id) },
        release = { calls += "release:${it.value}" },
        forgetUi = { forgotten += it },
        isOnline = { it in online },
        titleOverride = { if (it == "s_root") "Renamed" else null },
    )

    @After fun tearDown() { k.close(); kernelToClose?.close() }

    private fun raw(id: String, text: String, parent: String? = null, more: List<SessionEvent> = emptyList()) = runBlocking {
        val log = k.store.create(LogHeader(LOG_FORMAT_VERSION, SessionId(id), 5_000, parent?.let(::SessionId)))
        log.append(
            listOf<SessionEvent>(
                SessionCreated(GuestPath("/workspace"), T_MODEL, PermissionMode.Default),
                InputReceived(ClientMsgId("u"), listOf(TextPart(text)), Delivery.Queue),
                dev.min.agent.spi.InputPromoted(ClientMsgId("u"), dev.min.agent.protocol.MessageId("m_u")),
            ) + more,
            Durability.Sync,
        )
    }

    private fun file(rel: String, body: String = "x") = File(linux, rel).also { it.parentFile.mkdirs(); it.writeText(body) }

    private fun execLogMention(id: String) = "后台命令已转入后台（id $id，日志 /root/.min/exec-logs/$id.log（用 Read 查看））"

    @Test fun deletingARootRemovesItsWholeTreeAndEveryTrace() {
        raw("s_root", execLogMention(logA))
        raw("s_child", execLogMention(logB), parent = "s_root")
        raw("s_other", execLogMention(logOther))
        file("root/.min/artifacts/s_root/big.txt"); file("root/.min/artifacts/s_child/img.png"); file("root/.min/artifacts/s_other/keep.txt")
        file("root/.min/exec-logs/$logA.log"); file("root/.min/exec-logs/$logB.log"); file("root/.min/exec-logs/$logOther.log")
        runBlocking { index.list(k.store) }
        assertTrue(File(k.dir, "index/s_root.json").exists())

        assertEquals(DeleteResult.Deleted, runBlocking { catalog.delete("s_root") })

        assertFalse(File(k.sessionsDir, "s_root").exists()); assertFalse("子会话一起删", File(k.sessionsDir, "s_child").exists())
        assertFalse(File(linux, "root/.min/artifacts/s_root").exists()); assertFalse(File(linux, "root/.min/artifacts/s_child").exists())
        assertFalse(File(linux, "root/.min/exec-logs/$logA.log").exists()); assertFalse(File(linux, "root/.min/exec-logs/$logB.log").exists())
        assertFalse(File(k.dir, "index/s_root.json").exists())
        assertEquals(listOf(SessionRef(EngineKind.MinAgent, "s_root")), forgotten)
        // 别的会话原样
        assertTrue(File(k.sessionsDir, "s_other").exists()); assertTrue(File(linux, "root/.min/artifacts/s_other/keep.txt").exists())
        assertTrue(File(linux, "root/.min/exec-logs/$logOther.log").exists()); assertTrue(File(k.dir, "index/s_other.json").exists())
    }

    @Test fun theEngineLetsGoOfTheSessionBeforeTheKernelDeletesIt() {
        raw("s_root", "x")
        runBlocking { catalog.delete("s_root") }
        // 否则会话的索引写入协程会在删完之后又把摘要写回来
        assertEquals(listOf("release:s_root", "deleteTree"), calls)
    }

    @Test fun unknownIdIsNotFoundAndTouchesNothing() {
        raw("s_root", "x")
        assertEquals(DeleteResult.NotFound, runBlocking { catalog.delete("s_nope") })
        assertTrue(calls.isEmpty()); assertTrue(forgotten.isEmpty()); assertTrue(File(k.sessionsDir, "s_root").exists())
    }

    @Test fun aRunningSessionIsInterruptedAndDeletedWithItsRuntime() {
        val gate = CompletableDeferred<Unit>()
        val busy = TestKernel(ScriptedModel(gated(gate))).also { kernelToClose = it }
        val catalogBusy = MinSessionCatalog(
            busy.store, MinSessionIndex(File(busy.dir, "index"), busy.sessionsDir), busy.sessionsDir, linux,
            deleteTree = busy.hub::delete, release = {}, forgetUi = {},
        )
        val root = runBlocking { busy.newRoot() }
        runBlocking { root.send(ClientMsgId("a"), listOf(TextPart("go"))) }
        awaitValue { root.state.value.turn }
        assertEquals(DeleteResult.Deleted, runBlocking { catalogBusy.delete(root.sessionId.value) })
        assertFalse(File(busy.sessionsDir, root.sessionId.value).exists())
        assertTrue(busy.hub.online.value.isEmpty())
    }

    private var kernelToClose: TestKernel? = null

    @Test fun listShowsRenamesAndTheInterruptedMarkOnlyForOfflineOpenTurns() {
        raw("s_root", "first", more = listOf(TurnStarted(TurnId("t"), TurnTrigger.User)))
        raw("s_fine", "second")
        val offline = runBlocking { catalog.list() }
        assertEquals("Renamed", offline.first { it.ref.id == "s_root" }.title)
        assertEquals("second", offline.first { it.ref.id == "s_fine" }.title)
        assertTrue("没在线的、回合没收尾 = 进程死在中途", offline.first { it.ref.id == "s_root" }.interrupted)
        assertFalse(offline.first { it.ref.id == "s_fine" }.interrupted)
        online = setOf("s_root")
        assertFalse("在线的只是正在跑", runBlocking { catalog.list() }.first { it.ref.id == "s_root" }.interrupted)
    }

    @Test fun treeAndLogScanHelpers() {
        assertEquals(listOf("r", "a", "b", "c"), MinSessionCatalog.treeOf("r", listOf("a" to "r", "b" to "r", "c" to "a", "z" to "q")))
        val f = file("e.jsonl", "x /root/.min/exec-logs/${logA}.log y /root/.min/exec-logs/not-hex.log z /root/.min/exec-logs/${logB}.log")
        assertEquals(listOf(logA, logB), MinSessionCatalog.execLogsIn(f))
        assertEquals(emptyList<String>(), MinSessionCatalog.execLogsIn(File(linux, "missing")))
    }

    @Test fun exportIsNotSupportedYet() {
        assertTrue(runBlocking { catalog.export("s_root", java.io.ByteArrayOutputStream()) }.isFailure)
    }
}
