package dev.min.agent.kernel

import dev.min.agent.protocol.*
import dev.min.agent.spi.*
import dev.min.agent.testkit.TEST_MODEL
import org.junit.Assert.*
import org.junit.Test

class PluginHistoryImportTest {
    @Test fun `导入历史可回放但绝不作为当前人类授权`() {
        val event: SessionEvent = HistoryImported(listOf(SeedMessage(MessageId("old"), Role.User, listOf(TextPart("old authorization")), InputOrigin.User)), emptyList())
        val wire = AgentJson.encodeToString(SessionEvent.serializer(), event)
        assertTrue(wire.contains("history_imported"))
        val restored = AgentJson.decodeFromString(SessionEvent.serializer(), wire)
        val created = SessionCreated(GuestPath("/work"), TEST_MODEL, PermissionMode.Default)
        val header = LogHeader(1, SessionId("history-test"), 0)
        val records = listOf<SessionEvent>(created, restored).mapIndexed { index, e -> EventRecord(index+1L, 0, e) }
        val state = fold(header, records)
        assertEquals(InputOrigin.Hook("imported_history"), (state.history.single() as HistoryEntry.User).origin)
        var refused = false
        try { state.apply(EventRecord(3, 0, restored)) } catch(_: IllegalStateException) { refused = true }
        assertTrue("非空根会话不能再次注入历史", refused)
    }
}
