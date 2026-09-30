package dev.min.agent.store

import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ClientMsgId
import dev.min.agent.protocol.ModelRef
import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.Clock
import dev.min.agent.spi.Delivery
import dev.min.agent.spi.Durability
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.InputReceived
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionCreated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.atomic.AtomicLong

class JsonlSessionLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = object : Clock {
        val now = AtomicLong(1_000)
        override fun nowMs(): Long = now.getAndIncrement()
    }

    private fun store() = FileSessionStore(tmp.root.toPath(), clock)

    private fun header(id: String = "s1", createdAt: Long = 1) =
        LogHeader(LOG_FORMAT_VERSION, SessionId(id), createdAtMs = createdAt)

    private val created = SessionCreated(GuestPath("/workspace"), ModelRef("deepseek", "deepseek-v4"), PermissionMode.Default)

    private fun note(text: String) = Note(NoteLevel.Info, text)

    private fun eventsFile(id: String = "s1"): Path = tmp.root.toPath().resolve(id).resolve(FileSessionStore.EVENTS_FILE)

    @Test
    fun `appended events come back in order with store-assigned seq and time`() = runTest {
        val log = store().create(header())
        log.append(listOf(created), Durability.Sync)
        log.append(listOf(note("a"), note("b")), Durability.Batched)

        val records = log.read().toList()
        assertEquals(listOf(1L, 2L, 3L), records.map { it.seq })
        assertEquals(listOf(created, note("a"), note("b")), records.map { it.event })
        // 同一批共用一个时间戳，下一批取新的
        assertEquals(records[1].atMs, records[2].atMs)
        assertEquals(listOf(2L, 3L), log.read(fromSeq = 2).toList().map { it.seq })
    }

    @Test
    fun `seq continues after reopening`() = runTest {
        store().create(header()).append(listOf(created, note("a")), Durability.Sync)

        val reopened = store().open(SessionId("s1"))!!
        val appended = reopened.append(listOf(note("b")), Durability.Sync)
        assertEquals(3L, appended.single().seq)
        assertEquals(header(), reopened.header)
    }

    @Test
    fun `a torn tail from a killed write is cut off on open`() = runTest {
        store().create(header()).append(listOf(created, note("a")), Durability.Sync)
        val validSize = Files.size(eventsFile())
        // 进程被杀在 write() 中途：最后一行没有换行符
        Files.write(eventsFile(), """{"seq":3,"atMs":5,"event":{"type":"no""".toByteArray(), APPEND)

        val reopened = store().open(SessionId("s1"))!!
        assertEquals(validSize, Files.size(eventsFile()))
        assertEquals(listOf(1L, 2L), reopened.read().toList().map { it.seq })
        assertEquals(3L, reopened.append(listOf(note("b")), Durability.Sync).single().seq)
    }

    @Test
    fun `readers ignore a tail that is still being written`() = runTest {
        val log = store().create(header())
        log.append(listOf(created), Durability.Sync)
        Files.write(eventsFile(), """{"seq":2,"at""".toByteArray(), APPEND)
        assertEquals(listOf(1L), log.read().toList().map { it.seq })
    }

    @Test
    fun `a corrupt complete line in the middle refuses to open and leaves the file untouched`() = runTest {
        store().create(header()).append(listOf(created), Durability.Sync)
        // 损坏行之后还有一条合法记录：若按「截掉坏行」修复，会连带删掉它
        val validAfter = AgentJson.encodeToString(EventRecord.serializer(), EventRecord(3, 9, note("x")))
        Files.write(eventsFile(), "not json\n$validAfter\n".toByteArray(), APPEND)
        val sizeBefore = Files.size(eventsFile())

        assertThrows(CorruptLogException::class.java) { runBlocking { store().open(SessionId("s1")) } }
        assertEquals(sizeBefore, Files.size(eventsFile()))
    }

    @Test
    fun `a gap in seq is reported as corruption`() = runTest {
        store().create(header()).append(listOf(created), Durability.Sync)
        val line = Files.readAllLines(eventsFile()).last().replace("\"seq\":1", "\"seq\":5")
        Files.write(eventsFile(), (line + "\n").toByteArray(), APPEND)
        assertThrows(CorruptLogException::class.java) { runBlocking { store().open(SessionId("s1")) } }
    }

    @Test
    fun `a log written by a newer format is refused`() = runTest {
        Files.createDirectories(eventsFile().parent)
        Files.write(eventsFile(), """{"formatVersion":99,"sessionId":"s1","createdAtMs":1}""".plus("\n").toByteArray())
        val e = assertThrows(UnsupportedLogFormatException::class.java) { runBlocking { store().open(SessionId("s1")) } }
        assertEquals(99, e.found)
    }

    @Test
    fun `creating the same session twice fails instead of overwriting`() = runTest {
        store().create(header()).append(listOf(created), Durability.Sync)
        assertThrows(FileAlreadyExistsException::class.java) { runBlocking { store().create(header()) } }
        assertEquals(1, store().open(SessionId("s1"))!!.read().toList().size)
    }

    @Test
    fun `session ids that could escape the root are rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store().open(SessionId("../evil")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store().create(header("a/b")) } }
    }

    @Test
    fun `opening a missing session returns null and list is sorted by creation`() = runTest {
        assertNull(store().open(SessionId("nope")))
        store().create(header("later", createdAt = 20))
        store().create(header("earlier", createdAt = 10))
        assertEquals(listOf("earlier", "later"), store().list().map { it.sessionId.value })
    }

    @Test
    fun `concurrent appends get unique contiguous seq`() = runBlocking {
        val log = store().create(header())
        (1..50).map { i ->
            async(Dispatchers.Default) {
                log.append(listOf(InputReceived(ClientMsgId("c$i"), listOf(TextPart("$i")), Delivery.Queue)), Durability.Batched)
            }
        }.awaitAll()
        assertEquals((1L..50L).toList(), log.read().toList().map { it.seq })
        assertEquals(50, store().open(SessionId("s1"))!!.read().toList().size)
    }
}
