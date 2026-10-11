package dev.min.code.ui.viewer

import dev.min.agent.spi.FeedbackDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 诊断包草稿落盘：重开还在，删除即删文件；坏文件不拖垮别的草稿；写不进去时不假装存下了 */
class FeedbackDraftBoxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(): File = File(tmp.root, "feedback-drafts")

    private fun box(): FeedbackDraftBox = FeedbackDraftBox().also { it.attach(dir()) }

    private fun files(): List<File> = dir().listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    @Test fun `a draft survives a restart, with its session log`() {
        val first = box()
        assertTrue(first.add(FeedbackDraft("rg 超时", "连续三次", sessionLog = "{\"seq\":1}\n")))
        assertEquals(1, files().size)
        // 进程重启：新的收件箱绑同一个目录，草稿还在，日志也在
        val again = box()
        val got = again.drafts.value.single()
        assertEquals("rg 超时", got.draft.title)
        assertEquals("连续三次", got.draft.body)
        assertEquals("{\"seq\":1}\n", got.draft.sessionLog)
    }

    @Test fun `discarding deletes the file, and the draft does not come back`() {
        val first = box()
        first.add(FeedbackDraft("a", "1"))
        first.add(FeedbackDraft("b", "2"))
        val a = first.drafts.value.first { it.draft.title == "a" }
        first.discard(a.id)
        assertEquals(1, files().size)
        assertEquals(listOf("b"), box().drafts.value.map { it.draft.title })
    }

    @Test fun `an edited body replaces the file, so the edit survives a restart`() {
        val first = box()
        first.add(FeedbackDraft("t", "原文", sessionLog = "log"))
        val item = first.drafts.value.single()
        first.replace(item.id, item.draft.copy(body = "改过的正文"))
        assertEquals(1, files().size)
        val got = box().drafts.value.single().draft
        assertEquals("改过的正文", got.body)
        assertEquals("log", got.sessionLog)
    }

    @Test fun `a corrupt file is skipped and the other drafts still load`() {
        box().add(FeedbackDraft("好的", "正文"))
        File(dir(), "0000000000000-broken.json").writeText("{不是 JSON")
        assertEquals(listOf("好的"), box().drafts.value.map { it.draft.title })
    }

    @Test fun `when the file cannot be written the draft is refused and not shown`() {
        // 目录的位置被一个普通文件占着：写不进去
        File(tmp.root, "blocked").writeText("x")
        val b = FeedbackDraftBox().also { it.attach(File(tmp.root, "blocked")) }
        assertFalse(b.add(FeedbackDraft("t", "b")))
        assertTrue(b.drafts.value.isEmpty())
    }

    @Test fun `an unattached box keeps drafts in memory only`() {
        val b = FeedbackDraftBox()
        assertTrue(b.add(FeedbackDraft("t", "b")))
        assertEquals(1, b.drafts.value.size)
        assertNull(dir().takeIf { it.exists() }?.listFiles()?.firstOrNull())
    }
}
