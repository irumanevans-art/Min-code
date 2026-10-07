package dev.min.code.core.engine.min

import dev.min.agent.kernel.ConversationMark
import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.kernel.RewindPoint
import dev.min.agent.kernel.RewoundSegment
import dev.min.agent.kernel.SessionPresentation
import dev.min.agent.kernel.SessionState
import dev.min.agent.protocol.CallId
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.ToolCallPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.ConversationRewound
import dev.min.agent.spi.EventRecord
import dev.min.agent.spi.FilesRestored
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ManifestId
import dev.min.agent.spi.SkippedPath
import dev.min.code.core.session.ChatItem
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回退在转录里的样子：能回退的消息带标记、被回退的对话与恢复结果各折成一行 */
class MinRewindProjectionTest {
    private val text = FakeText()
    private val mark = ConversationMark(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptySet(), null, emptyMap(), emptyList())

    private fun base(): SessionState = foldOf()

    private fun items(state: SessionState): List<ChatItem> = projectMinItems(state, text)

    @Test
    fun `only user messages that are still rewind points are marked rewindable`() {
        val state = base().copy(
            history = listOf(
                HistoryEntry.User(MessageId("u1"), listOf(TextPart("first"))),
                HistoryEntry.Assistant(MessageId("a1"), listOf(TextPart("ok"))),
                HistoryEntry.User(MessageId("u2"), listOf(TextPart("插话"))),
            ),
            rewindPoints = listOf(RewindPoint(MessageId("u1"), TurnId("t1"), 5, mark)),
        )
        val users = items(state).filterIsInstance<ChatItem.UserText>()
        assertTrue(users[0].rewindable)
        assertFalse("回合中途插进来的话不是回退点", users[1].rewindable)
    }

    @Test
    fun `a rewound segment folds into one line holding the original items read-only`() {
        val dead = listOf(
            HistoryEntry.User(MessageId("u2"), listOf(TextPart("被回退的问题"))),
            HistoryEntry.Assistant(MessageId("a2"), listOf(TextPart("被回退的回答"), ToolCallPart(CallId("c2"), "Bash", JsonObject(emptyMap())))),
        )
        val event = ConversationRewound("rw1", MessageId("u2"))
        val state = base().copy(
            history = listOf(HistoryEntry.User(MessageId("u1"), listOf(TextPart("留下的")))),
            rewound = listOf(RewoundSegment("rw1", MessageId("u1"), dead, emptyMap())),
            presentation = listOf(SessionPresentation(EventRecord(9, 90, event), MessageId("u1"))),
            rewindPoints = listOf(RewindPoint(MessageId("u1"), TurnId("t1"), 3, mark)),
        )
        val out = items(state)
        val folded = out.filterIsInstance<ChatItem.Folded>().single()
        assertEquals("rewound:2", folded.title)
        assertEquals("rewound:rw1", folded.id)
        assertEquals(listOf("被回退的问题", "被回退的回答"), folded.nested.filter { it is ChatItem.UserText || it is ChatItem.AssistantText }.map {
            (it as? ChatItem.UserText)?.text ?: (it as ChatItem.AssistantText).text
        })
        assertTrue(folded.nested.any { it is ChatItem.ToolCall })
        assertTrue("被回退掉的消息不能再「回到这里」", folded.nested.filterIsInstance<ChatItem.UserText>().none { it.rewindable })
        // 折叠行排在留下的最后一条消息之后
        assertTrue(out.indexOf(folded) > out.indexOfFirst { it is ChatItem.UserText })
    }

    @Test
    fun `a restore folds into a summary with one line per file and an undo handle when it can be undone`() {
        val event = FilesRestored(
            restoreId = "r1", checkpointId = "ck1", preRestore = ManifestId("m".repeat(64)),
            restored = listOf(GuestPath("/workspace/a.txt")), deleted = listOf(GuestPath("/workspace/b.txt")),
            skipped = listOf(SkippedPath(GuestPath("/workspace/link"), "符号链接")),
        )
        val state = base().copy(
            presentation = listOf(SessionPresentation(EventRecord(9, 90, event), null)),
            restores = listOf(event),
        )
        val folded = items(state).filterIsInstance<ChatItem.Folded>().single()
        assertEquals("restored:Checkpoint:1:1:1", folded.title)
        assertEquals(listOf("restored /workspace/a.txt", "deleted /workspace/b.txt", "skipped /workspace/link (符号链接)"), folded.lines)
        assertEquals("r1", folded.undoId)
    }

    @Test
    fun `no undo handle once it was undone, for an undo itself, or without a pre-restore snapshot`() {
        val m = ManifestId("m".repeat(64))
        val original = FilesRestored("r1", checkpointId = "ck", preRestore = m, restored = listOf(GuestPath("/workspace/a")))
        val undo = FilesRestored("r2", preRestore = m, undoOf = "r1", restored = listOf(GuestPath("/workspace/a")))
        val noPre = FilesRestored("r3", checkpointId = "ck", preRestore = null, restored = listOf(GuestPath("/workspace/b")))
        fun presentation(vararg e: FilesRestored) = e.mapIndexed { i, ev -> SessionPresentation(EventRecord(10L + i, 100L + i, ev), null) }
        val state = base().copy(presentation = presentation(original, undo, noPre), restores = listOf(original, undo, noPre))
        val folded = items(state).filterIsInstance<ChatItem.Folded>().associateBy { it.id }
        assertNull("已经被撤销过", folded.getValue("restored:r1").undoId)
        assertNull("撤销本身不再给撤销", folded.getValue("restored:r2").undoId)
        assertEquals("restored:Undo:1:0:0", folded.getValue("restored:r2").title)
        assertNull("没有回滚前快照撤不回", folded.getValue("restored:r3").undoId)
    }

    @Test
    fun `a restore that came from edit calls is titled as reverted edits`() {
        val event = FilesRestored("r9", calls = listOf(CallId("c")), restored = listOf(GuestPath("/workspace/x")))
        val state = base().copy(presentation = listOf(SessionPresentation(EventRecord(9, 90, event), null)), restores = listOf(event))
        assertEquals("restored:Calls:1:0:0", items(state).filterIsInstance<ChatItem.Folded>().single().title)
    }

    @Test
    fun `a restore made just before the conversation was rewound stays visible after the folded row`() {
        val event = ConversationRewound("rw1", MessageId("u1"))
        val restored = FilesRestored("r1", checkpointId = "ck", preRestore = ManifestId("m".repeat(64)), deleted = listOf(GuestPath("/workspace/a")))
        val state = base().copy(
            history = emptyList(),
            rewound = listOf(RewoundSegment("rw1", null, listOf(HistoryEntry.User(MessageId("u1"), listOf(TextPart("q"))), HistoryEntry.Assistant(MessageId("a1"), listOf(TextPart("a")))), emptyMap())),
            // 恢复发生在 a1 之后（选「两者」：先恢复代码、再回退对话），a1 随后被回退掉了
            presentation = listOf(
                SessionPresentation(EventRecord(8, 80, restored), MessageId("a1")),
                SessionPresentation(EventRecord(9, 90, event), null),
            ),
            restores = listOf(restored),
        )
        val out = items(state).filterIsInstance<ChatItem.Folded>()
        assertEquals(listOf("rewound:rw1", "restored:r1"), out.map { it.id })
        assertEquals("撤销入口不能被折进回退掉的对话里", "r1", out[1].undoId)
        assertTrue(out[0].nested.none { it is ChatItem.Folded })
    }
}
