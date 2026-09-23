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

    /** 快打时每个键都来一次：落盘的必须是最后一版，不能被更早的某一版盖掉 */
    @Test
    fun `rapid saveLater calls land the last text on disk`() {
        val file = temp.root.resolve("codex-drafts.json")
        val store = CodexDraftStore(file)
        val final = "hello, codex"
        for (end in 1..final.length) store.saveLater("thread-a", final.take(end))

        // 写盘在后台：等到磁盘上是最后一版为止（新实例读 = 模拟进程重启）
        val deadline = System.currentTimeMillis() + 5_000
        while (CodexDraftStore(file).load("thread-a") != final) {
            check(System.currentTimeMillis() < deadline) { "最后一版一直没落盘" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `load sees text that has not reached the disk yet`() {
        val store = store()
        store.saveLater("thread-a", "刚打的")
        assertEquals("刚打的", store.load("thread-a"))
    }
}
