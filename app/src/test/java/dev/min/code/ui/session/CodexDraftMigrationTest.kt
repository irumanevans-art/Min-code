package dev.min.code.ui.session

import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.claudecode.ComposerDraftStore
import dev.min.code.core.codex.CodexDraftStore
import dev.min.code.core.engine.EngineKind
import dev.min.code.core.engine.SessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B9 验收「草稿迁移后原有 Codex 草稿还在」：旧 Codex 页的 `codex-drafts.json`（threadId → 正文）并进统一草稿盘，
 * 键换成 `SessionRef.storageKey`。一次性、不覆盖新打的、不丢「还没起线程时打的字」。
 */
class CodexDraftMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun stores(): Pair<CodexDraftStore, ComposerDraftStore> =
        CodexDraftStore(temp.newFile("codex-drafts.json").also { it.delete() }) to ComposerDraftStore(temp.newFolder("composer"))

    private fun key(thread: String) = SessionRef(EngineKind.Codex, thread).storageKey

    @Test
    fun `old thread drafts are still there under the unified key, and the old store is emptied`() {
        val (old, unified) = stores()
        old.save("thread-1", "还没发出去的半句话")
        old.save("thread-2", "另一条会话里的草稿")

        migrateCodexDrafts(old, unified)

        assertEquals("还没发出去的半句话", unified.load(key("thread-1")).text)
        assertEquals("另一条会话里的草稿", unified.load(key("thread-2")).text)
        assertTrue("搬完旧盘就空了，之后不会再搬一次", old.drafts.value.isEmpty())
    }

    @Test
    fun `the draft typed before the thread existed waits under its own key for the next new session`() {
        val (old, unified) = stores()
        old.save(CODEX_NEW_THREAD_DRAFT_KEY, "新会话里没发出去的字")

        migrateCodexDrafts(old, unified)

        assertEquals("新会话里没发出去的字", unified.load(CODEX_NEW_THREAD_DRAFT_KEY).text)
        assertTrue(old.drafts.value.isEmpty())
    }

    @Test
    fun `a draft already typed under the unified key is not overwritten, and running twice changes nothing`() {
        val (old, unified) = stores()
        unified.save(key("thread-1"), ComposerDraft(text = "统一页上新打的"))
        old.save("thread-1", "旧页的")

        migrateCodexDrafts(old, unified)
        migrateCodexDrafts(old, unified)

        assertEquals("统一页上新打的", unified.load(key("thread-1")).text)
        assertTrue(old.drafts.value.isEmpty())
    }
}
