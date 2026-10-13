package dev.min.code.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 两张供应商表共用的那套列表规则。用一个最小的条目类型，不依赖具体的 Claude / Codex 字段 */
class ProfileTableTest {
    private data class Item(val id: String, val label: String = id, val acked: Boolean = false, val bound: String = "x")

    private val key = stringPreferencesKey("table")
    private val activeKey = stringPreferencesKey("active")
    private val cipher = FakeCredentialCipher()

    private val table = ProfileTable<Item>(
        key = key,
        activeKey = activeKey,
        id = { it.id },
        displayName = { it.label },
        duplicateOf = { source, newId, label -> source.copy(id = newId, label = label, bound = "") },
        acknowledged = { it.copy(acked = true) },
        decode = { _, raw -> raw.orEmpty().split(',').filter { it.isNotEmpty() }.map { Item(it) } },
        encode = { c, items -> c.encrypt(items.joinToString(",") { it.id }) },
    )

    private fun items(vararg ids: String) = ids.map { Item(it) }

    private fun List<Item>.ids() = map { it.id }

    @Test
    fun `reorder puts the listed ids first and keeps the unlisted ones at the end in their old order`() {
        val list = items("a", "b", "c", "d")

        assertEquals(listOf("c", "a", "b", "d"), table.reorder(list, listOf("c", "a")).ids())
    }

    /** 界面那份快照可能比盘上少一条或多一条：不认识的 id 忽略，没提到的绝不删 */
    @Test
    fun `reorder ignores unknown ids and never drops an entry`() {
        val list = items("a", "b")

        assertEquals(listOf("b", "a"), table.reorder(list, listOf("ghost", "b")).ids())
        assertEquals(listOf("a", "b"), table.reorder(list, emptyList()).ids())
    }

    @Test
    fun `move shifts one slot and returns the same list when nothing can move`() {
        val list = items("a", "b", "c")

        assertEquals(listOf("a", "c", "b"), table.move(list, "b", +1).ids())
        assertSame(list, table.move(list, "a", -1))
        assertSame(list, table.move(list, "ghost", +1))
    }

    @Test
    fun `remove drops only the matching id`() {
        assertEquals(listOf("a", "c"), table.remove(items("a", "b", "c"), "b").ids())
        assertEquals(listOf("a"), table.remove(items("a"), "ghost").ids())
    }

    @Test
    fun `replace overwrites in place and does not move the entry`() {
        val list = items("a", "b", "c")

        val next = table.replace(list, Item("b", label = "renamed"))

        assertEquals(listOf("a", "b", "c"), next.ids())
        assertEquals("renamed", next[1].label)
        // 没有这个 id 时什么都不加：「替换」不是「新增」
        assertEquals(list, table.replace(list, Item("ghost")))
    }

    @Test
    fun `upsert replaces an existing id in place or appends a new one`() {
        val list = items("a", "b")

        assertEquals(listOf("a", "b"), table.upsert(list, Item("a", label = "x")).ids())
        assertEquals("x", table.upsert(list, Item("a", label = "x"))[0].label)
        assertEquals(listOf("a", "b", "c"), table.upsert(list, Item("c")).ids())
    }

    @Test
    fun `duplicate inserts the copy right below its source with a fresh id and a copy label`() {
        val list = items("a", "b")

        val (next, copyId) = table.duplicate(list, "a", newId = "a2")

        assertEquals("a2", copyId)
        assertEquals(listOf("a", "a2", "b"), next.ids())
        assertEquals("a copy", next[1].label)
        // 不继承绑定：副本是拿来改的，跟着原件走等于改完就被盖回去
        assertEquals("", next[1].bound)
        assertEquals("x", next[0].bound)
    }

    @Test
    fun `duplicate avoids a label that is already taken`() {
        val list = listOf(Item("a", label = "A"), Item("b", label = "A copy"))

        val (next, _) = table.duplicate(list, "a", newId = "a2")

        assertEquals("A copy 2", next[1].label)
    }

    @Test
    fun `duplicate of a missing id returns the same list and no id`() {
        val list = items("a")

        val (next, copyId) = table.duplicate(list, "ghost", newId = "n")

        assertSame(list, next)
        assertNull(copyId)
    }

    @Test
    fun `acknowledge marks only the target and only when asked to`() {
        val list = items("a", "b")

        assertEquals(listOf(false, true), table.acknowledge(list, "b", acknowledgeInsecure = true).map { it.acked })
        assertSame(list, table.acknowledge(list, "b", acknowledgeInsecure = false))
    }

    // -----------------------------------------------------------------------
    // 落盘：空表连键一起删；active 必须落在表里
    // -----------------------------------------------------------------------

    @Test
    fun `commit writes the table and keeps a wanted active id that is in it`() {
        val p = mutablePreferencesOf()

        table.commit(p, cipher, items("a", "b"), wantedActive = "b")

        assertEquals("b", p[activeKey])
        assertEquals("a,b", cipher.decrypt(p[key]!!))
    }

    @Test
    fun `commit falls back to the first entry when the wanted active id is gone`() {
        val p = mutablePreferencesOf()

        table.commit(p, cipher, items("a", "b"), wantedActive = "deleted")

        assertEquals("a", p[activeKey])
    }

    @Test
    fun `commit of an empty table removes both keys`() {
        val p = mutablePreferencesOf(key to "old", activeKey to "old")

        table.commit(p, cipher, emptyList(), wantedActive = "old")

        assertNull(p[key])
        assertNull(p[activeKey])
    }
}
