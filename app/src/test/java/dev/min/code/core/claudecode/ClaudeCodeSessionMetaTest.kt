package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeSessionMetaTest {

    @Test
    fun `empty meta is omitted from the index`() {
        val encoded = encodeSessionMetaIndex(mapOf("a" to SessionMeta(title = "名字")))
        val decoded = decodeSessionMetaIndex(encoded)
        assertEquals("名字", decoded["a"]?.title)
        assertTrue(decodeSessionMetaIndex("{}").isEmpty())
        assertTrue(decodeSessionMetaIndex("not json").isEmpty())
    }

    @Test
    fun `groups pin first then categories then uncategorized`() {
        val ids = listOf("p", "work-a", "play", "work-b", "plain")
        val pinned = listOf(true, false, false, false, false)
        val category = listOf(null, "工作", "玩", "工作", null)
        val groups = groupSessionIds(ids, pinned, category)
        assertEquals(
            listOf("置顶", "工作", "玩", "未分类"),
            groups.map { it.header },
        )
        assertEquals(listOf("p"), groups[0].items)
        assertEquals(listOf("work-a", "work-b"), groups[1].items)
        assertEquals(listOf("play"), groups[2].items)
        assertEquals(listOf("plain"), groups[3].items)
    }

    @Test
    fun `no headers when nothing is pinned or categorized`() {
        val groups = groupSessionIds(
            ids = listOf("a", "b"),
            pinned = listOf(false, false),
            category = listOf(null, null),
        )
        assertEquals(1, groups.size)
        assertNull(groups.single().header)
        assertEquals(listOf("a", "b"), groups.single().items)
    }
}
