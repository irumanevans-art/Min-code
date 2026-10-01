package dev.min.agent.kernel

import dev.min.agent.protocol.PermissionMode
import dev.min.agent.protocol.SessionId
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.LogHeader
import dev.min.agent.spi.MemoryLoaded
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.Note
import dev.min.agent.spi.NoteLevel
import dev.min.agent.spi.SessionCreated
import dev.min.agent.spi.SessionEvent
import dev.min.agent.store.LOG_FORMAT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Test

/** 记忆文件在会话里的样子：折叠进状态、创建时读一次、重新打开不重读、进系统提示的稳定前缀 */
class MemorySessionTest {
    private val header = LogHeader(LOG_FORMAT_VERSION, SessionId("s1"), createdAtMs = 0)
    private val created = SessionCreated(GuestPath("/workspace"), TEST_MODEL, PermissionMode.Default)

    private fun file(path: String, content: String, source: MemorySource = MemorySource.Project) =
        LoadedMemoryFile(GuestPath(path), source, content)

    private fun records(vararg events: SessionEvent) = events.mapIndexed { i, e -> EventRecord(i + 1L, 0, e) }

    @Test
    fun `fold keeps the last memory snapshot and leaves notes out of the state`() {
        val first = listOf(file("/workspace/CLAUDE.md", "旧"))
        val second = listOf(file("/workspace/CLAUDE.md", "新"), file("/workspace/CLAUDE.local.md", "我", MemorySource.Local))

        assertEquals(emptyList<LoadedMemoryFile>(), fold(header, records(created)).memory)
        assertEquals(first, fold(header, records(created, MemoryLoaded(first, notes = listOf("只给人看")))).memory)
        // 以后的显式刷新：整份替换，不是追加
        val refreshed = fold(header, records(created, MemoryLoaded(first), Note(NoteLevel.Info, "x"), MemoryLoaded(second)))
        assertEquals(second, refreshed.memory)
    }
}
