package dev.min.code.core.codex

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Codex 输入框草稿的磁盘表。要点只有一个：**切 thread 不串稿、清空不留尸**。
 */
class CodexDraftStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = CodexDraftStore(temp.newFile("codex-drafts.json"))

    @Test
    fun `drafts survive a fresh store instance`() {
        val first = store()
        first.save("thread-a", "还没发的话")

        // 新实例 = 模拟进程重启：磁盘上那份要原样回来
        val second = CodexDraftStore(temp.root.resolve("codex-drafts.json"))
        assertEquals("还没发的话", second.load("thread-a"))
        assertEquals("", second.load("thread-b"))
    }

    @Test
    fun `threads do not bleed into each other`() {
        val store = store()
        store.save("thread-a", "A")
        store.save("thread-b", "B")

        assertEquals("A", store.load("thread-a"))
        assertEquals("B", store.load("thread-b"))
    }

    /** 空白正文把记录删掉，不留一具空壳 */
    @Test
    fun `saving blank text removes the entry`() {
        val store = store()
        store.save("thread-a", "要说的话")
        store.save("thread-a", "   ")

        assertEquals("", store.load("thread-a"))
        assertTrue(store.drafts.value.isEmpty())
    }

    @Test
    fun `forget drops the draft entirely`() {
        val store = store()
        store.save("thread-a", "要说的话")
        store.forget("thread-a")

        assertEquals("", store.load("thread-a"))
        assertTrue(store.drafts.value.isEmpty())
    }

    /** 还没起线程时的草稿只在内存里活，没有挂靠对象就不落盘 */
    @Test
    fun `blank thread ids are never persisted`() {
        val store = store()
        store.save("", "孤儿草稿")
        store.forget("")

        assertTrue(store.drafts.value.isEmpty())
    }
}
