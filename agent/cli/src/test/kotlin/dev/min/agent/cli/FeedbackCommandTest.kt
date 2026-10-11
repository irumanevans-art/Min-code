package dev.min.agent.cli

import dev.min.agent.spi.FeedbackDraft
import dev.min.agent.tools.FeedbackBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * 终端的 `/feedback`（CC commands 页）：无参 = 打开草稿队列（SendFeedback 排的草稿都在里面），带文字 = 自己写一份报告。
 * Min 没有发送通道，所以队列里能做的是看、丢弃，写完的报告也只是排进同一个队列（诊断包 zip），结果里照实写「未发送」。
 * 队列上限 10 份、超了丢最旧的，30 天过期——都照 tools-reference 的 SendFeedback 一节。
 */
class FeedbackCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = AtomicLong(1_700_000_000_000L)
    private fun dir(): Path = Files.createDirectories(tmp.root.toPath().resolve("drafts"))
    private fun drafts(keep: Int = 10) = FeedbackDrafts(dir(), { now.get() }, keep = keep)
    private fun build(d: FeedbackDraft) = FeedbackBundle.build(d, null, "test", emptyMap(), emptyMap())

    private fun run(drafts: FeedbackDrafts, arg: String): String {
        val bytes = ByteArrayOutputStream()
        feedbackCommand(drafts, arg, ::build, PrintStream(bytes, true, "UTF-8"))
        return bytes.toString("UTF-8")
    }

    private fun queue(d: FeedbackDrafts, title: String) {
        now.addAndGet(1_000)
        d.queue(build(FeedbackDraft(title, "正文：$title")))
    }

    @Test fun `with no argument an empty queue says so and nothing is created`() {
        val out = run(drafts(), "")
        assertTrue(out, out.contains("草稿队列是空的"))
    }

    @Test fun `the queue lists drafts newest first with their titles and says nothing has been sent`() {
        val d = drafts()
        queue(d, "第一份")
        queue(d, "第二份")
        val out = run(d, "")
        assertTrue(out, out.indexOf("第二份") in 0 until out.indexOf("第一份"))
        assertTrue(out, out.contains("未发送"))
    }

    @Test fun `text after the command writes a report of your own into the same queue, unsent`() {
        val d = drafts()
        val out = run(d, "工具反复超时")
        assertTrue(out, out.contains("未发送"))
        val rows = d.list()
        assertEquals(1, rows.size)
        assertEquals("工具反复超时", rows.single().title)
        ZipFile(rows.single().file.toFile()).use { zip ->
            val text = zip.getInputStream(zip.getEntry("draft.md")).readBytes().decodeToString()
            assertTrue(text, text.contains("工具反复超时"))
        }
    }

    @Test fun `a credential typed into a report is scrubbed before it reaches the disk`() {
        val d = drafts()
        run(d, "key 泄露了 sk-ant-abcdef1234567890abcdef")
        ZipFile(d.list().single().file.toFile()).use { zip ->
            val text = zip.getInputStream(zip.getEntry("draft.md")).readBytes().decodeToString()
            assertFalse(text, text.contains("sk-ant-abcdef1234567890abcdef"))
        }
    }

    @Test fun `show prints a draft by its number and discard deletes it`() {
        val d = drafts()
        queue(d, "甲")
        queue(d, "乙")
        val shown = run(d, "show 2")
        assertTrue(shown, shown.contains("正文：甲"))
        val gone = run(d, "discard 1")
        assertTrue(gone, gone.contains("已丢弃"))
        assertEquals(listOf("甲"), d.list().map { it.title })
    }

    @Test fun `discard all empties the queue and a bad number is refused without touching anything`() {
        val d = drafts()
        queue(d, "甲")
        val bad = run(d, "discard 9")
        assertTrue(bad, bad.contains("没有第 9 份"))
        assertEquals(1, d.list().size)
        run(d, "discard all")
        assertTrue(d.list().isEmpty())
    }

    @Test fun `the queue keeps ten drafts and drops the oldest when an eleventh arrives`() {
        val d = drafts()
        for (i in 1..11) queue(d, "草稿$i")
        val titles = d.list().map { it.title }
        assertEquals(10, titles.size)
        assertFalse(titles.contains("草稿1"))
        assertTrue(titles.contains("草稿11"))
    }

    @Test fun `a draft older than thirty days is gone the next time the queue is opened`() {
        val d = drafts()
        queue(d, "旧的")
        now.addAndGet(31L * 24 * 3_600_000)
        queue(d, "新的")
        assertEquals(listOf("新的"), d.list().map { it.title })
    }

    @Test fun `a sentence that merely starts with show or discard is a report, not a subcommand`() {
        val d = drafts()
        run(d, "show 的结果不对")
        run(d, "discard 之后又出现")
        assertEquals(2, d.list().size)
    }
}
