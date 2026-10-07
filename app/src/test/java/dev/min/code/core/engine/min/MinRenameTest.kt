package dev.min.code.core.engine.min

import dev.min.agent.kernel.HistoryEntry
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.TextPart
import org.junit.Assert.assertEquals
import org.junit.Test

/** 名字来自内核日志的 session_renamed：列表摘要优先用它，没有才取第一条用户消息 */
class MinRenameTest {
    private fun state(name: String?) = foldOf().copy(
        history = listOf(HistoryEntry.User(MessageId("u1"), listOf(TextPart("第一句话\n第二行")))),
        name = name,
    )

    @Test
    fun `the index title is the kernel name when there is one`() {
        assertEquals("我的重构", indexEntryOf(state("我的重构"), 10, 10, 20).title)
    }

    @Test
    fun `without a name the index title is the first line of the first user message`() {
        assertEquals("第一句话", indexEntryOf(state(null), 10, 10, 20).title)
    }

    @Test
    fun `a blank name does not hide the message title`() {
        assertEquals("第一句话", indexEntryOf(state("  "), 10, 10, 20).title)
    }

    @Test
    fun `a very long name is cut to the title length like the message title`() {
        val title = indexEntryOf(state("长".repeat(200)), 10, 10, 20).title
        assertEquals(MinIndexEntry.TITLE_CHARS, title.length)
    }

    @Test
    fun `a session rewound to before its first message is titled by that message, not by its id`() {
        val dead = listOf(HistoryEntry.User(MessageId("u1"), listOf(TextPart("被回退的第一句"))))
        val state = foldOf().copy(history = emptyList(), rewound = listOf(dev.min.agent.kernel.RewoundSegment("rw", null, dead, emptyMap())))
        assertEquals("被回退的第一句", indexEntryOf(state, 10, 10, 20).title)
    }
}
