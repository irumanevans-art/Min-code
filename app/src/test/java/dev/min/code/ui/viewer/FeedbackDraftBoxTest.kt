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
    @Test fun `known secrets are removed when the draft is written, from the title, body and log, on disk and in memory`() {
        val known = setOf("relay-token-plain-7a1b2c3d", "mcp-access-plain-5b8c9d0e")
        val b = box()
        val draft = FeedbackDraft("401 用了 relay-token-plain-7a1b2c3d", "贴了 mcp-access-plain-5b8c9d0e", sessionLog = "{\"t\":\"relay-token-plain-7a1b2c3d\"}\n")
        assertTrue(b.add(draft, known))
        for (secret in known) {
            assertFalse(secret, files().joinToString("\n") { it.readText() }.contains(secret))
            assertFalse(secret, b.drafts.value.single().draft.let { it.title + it.body + it.sessionLog }.contains(secret))
        }
        // 去掉的是秘密本身，其余内容照留
        assertTrue(files().single().readText().contains("401"))
        // 重开还是干净的
        assertFalse(box().drafts.value.single().draft.let { it.title + it.body + it.sessionLog }.contains("relay-token-plain"))
    }

    @Test fun `an edit that pastes a secret back in is cleaned too`() {
        val known = setOf("relay-token-plain-7a1b2c3d")
        val b = box()
        b.add(FeedbackDraft("t", "原文", sessionLog = "log"), known)
        val item = b.drafts.value.single()
        assertTrue(b.replace(item.id, item.draft.copy(body = "我又贴了 relay-token-plain-7a1b2c3d"), known))
        assertFalse(files().single().readText().contains("relay-token-plain"))
        assertFalse(b.drafts.value.single().draft.body.contains("relay-token-plain"))
    }

    @Test fun `shaped credentials are removed at write time even with no known list`() {
        val b = box()
        b.add(FeedbackDraft("t", "key sk-ant-api03-SHAPEDKEY0123456789"))
        assertFalse(files().single().readText().contains("SHAPEDKEY"))
    }
}
