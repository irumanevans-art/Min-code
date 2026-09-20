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
            listOf(
                SessionGroupHeader.Pinned,
                SessionGroupHeader.Custom("工作"),
                SessionGroupHeader.Custom("玩"),
                SessionGroupHeader.Uncategorized,
            ),
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

    /**
     * 回归：组的 LazyColumn key 曾经从显示文案派生（"h-置顶"）。用户自建一个叫
     * 「置顶」或「未分类」的分类时，两个组的 key 撞车，列表直接崩。身份必须是
     * 结构性的，显示文案重名无所谓。
     */
    @Test
    fun `a category named like a built-in header gets its own group key`() {
        val ids = listOf("p", "a", "b", "u")
        val pinned = listOf(true, false, false, false)
        val category = listOf(null, "置顶", "未分类", null)
        val groups = groupSessionIds(ids, pinned, category)

        // 内置组头和同名分类现在连类型都不同，撞不到一起
        assertEquals(
            listOf(
                SessionGroupHeader.Pinned,
                SessionGroupHeader.Custom("置顶"),
                SessionGroupHeader.Custom("未分类"),
                SessionGroupHeader.Uncategorized,
            ),
            groups.map { it.header },
        )
        assertEquals(
            listOf("pinned", "cat:置顶", "cat:未分类", "uncategorized"),
            groups.map { it.key },
        )
        assertEquals(groups.size, groups.map { it.key }.distinct().size)
    }
}
