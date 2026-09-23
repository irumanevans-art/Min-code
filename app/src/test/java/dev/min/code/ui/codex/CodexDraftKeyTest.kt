package dev.min.code.ui.codex

import dev.min.code.core.codex.CodexAppServerManager
import dev.min.code.core.session.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Codex 草稿记在哪个键下：没线程不落盘、新线程记在「新会话」名下、发过话的线程记在自己名下 */
class CodexDraftKeyTest {

    @Test
    fun `no thread yet means nothing is persisted`() {
        assertNull(CodexVM.draftKeyOf(CodexAppServerManager.State()))
    }

    @Test
    fun `a thread that has not sent anything keeps its draft under the new-thread key`() {
        val fresh = CodexAppServerManager.State(threadId = "t1", items = listOf(ChatItem.Note("n", "进程输出")))
        assertEquals(CodexVM.NEW_THREAD_DRAFT_KEY, CodexVM.draftKeyOf(fresh))
    }

    @Test
    fun `a thread with a sent message keeps its draft under its own id`() {
        val used = CodexAppServerManager.State(threadId = "t1", items = listOf(ChatItem.UserText("u", "hi")))
        assertEquals("t1", CodexVM.draftKeyOf(used))
    }
}
