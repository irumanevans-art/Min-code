package dev.min.code.ui.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拖动换位按 id，不按 LazyColumn 绝对下标。
 *
 * 列表前面常年挂着搜索框、提示、统一供应商区，它们也占下标。
 * 以前 `onMove(fromIndex, toIndex)` 拿绝对下标去动业务列表，要么越界直接没反应
 * （被拖的那行只靠偏移在闪），要么把 draggingIndex 指到一个固定项上，旁边的行看起来在乱跳。
 * 这条测试钉住的是修复之后那条「按 id 换」的规则本身。
 */
class ProviderReorderLogicTest {

    private fun moveById(ids: List<String>, fromId: String, toId: String): List<String> {
        if (fromId == toId) return ids
        val from = ids.indexOf(fromId)
        val to = ids.indexOf(toId)
        if (from < 0 || to < 0) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    @Test
    fun moves_by_id_even_when_list_index_would_be_offset() {
        // LazyColumn 里这些 id 前面还有 stale / head / unified 三项，绝对下标分别是 3/4/5
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moveById(ids, "a", "b"))
        assertEquals(listOf("b", "c", "a"), moveById(ids, "a", "c"))
        assertEquals(listOf("c", "a", "b"), moveById(ids, "c", "a"))
    }

    @Test
    fun ignores_keys_that_are_not_in_the_table() {
        val ids = listOf("a", "b", "c")
        // 搜索框那种固定项的 key 不在表里 —— 换位必须是 no-op，不能抛、不能乱
        assertEquals(ids, moveById(ids, "a", "head"))
        assertEquals(ids, moveById(ids, "unified", "b"))
        assertEquals(ids, moveById(ids, "a", "a"))
    }
}
