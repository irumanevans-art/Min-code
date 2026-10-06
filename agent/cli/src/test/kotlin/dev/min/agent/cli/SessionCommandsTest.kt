package dev.min.agent.cli

import dev.min.agent.kernel.RecapResult
import dev.min.agent.kernel.ResumeAdvice
import dev.min.agent.llm.Cost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Path

class SessionCommandsTest {
    private fun args(vararg argv: String) = parseArgs(arrayOf("--model", "m", *argv), Path.of("."))
    private val advice = ResumeAdvice(idleMs = 3 * 3_600_000L, contextTokens = 123_456, rereadCost = Cost(0.4631, "USD"))

    private class Run(val chose: Boolean, val asked: Boolean, val output: String, val compacted: Int)

    private fun offer(input: String?, advice: ResumeAdvice?, compactOk: Boolean = true): Run {
        val out = ByteArrayOutputStream()
        var compacted = 0
        val reader = BufferedReader(StringReader(input ?: ""))
        val chose = runBlocking {
            offerResume(advice, reader, PrintStream(out, true, "UTF-8")) { compacted++; compactOk }
        }
        val text = out.toString("UTF-8")
        return Run(chose, text.contains("选择 [1/2]"), text, compacted)
    }

    @Test fun `only interactive resume or continue is asked, and -p never is`() {
        assertTrue(shouldOfferResume(args("--resume", "s1")))
        assertTrue(shouldOfferResume(args("--continue")))
        assertFalse("-p 不问，照原样续接", shouldOfferResume(args("--resume", "s1", "-p", "hi")))
        assertFalse(shouldOfferResume(args("--continue", "-p", "hi")))
        assertFalse("新会话没有续接", shouldOfferResume(args()))
    }

    @Test fun `only an explicit summary answer chooses the summary`() {
        for (yes in listOf("1", " 1 ", "s", "Summary", "摘要")) assertTrue(yes, wantsSummary(yes))
        for (no in listOf("2", "", "  ", "完整", "y", null)) assertFalse("$no", wantsSummary(no))
    }

    @Test fun `the question tells idle time, size and the estimated cost, and leaves the cost out when unknown`() {
        val q = resumeQuestion(advice)
        assertTrue(q, q.contains("3 小时") && q.contains("123k") && q.contains("0.46 USD"))
        assertFalse(resumeQuestion(advice.copy(rereadCost = null)).contains("估算约"))
        assertTrue(resumeQuestion(advice.copy(idleMs = 72 * 3_600_000L)).contains("3 天"))
    }

    @Test fun `without advice nothing is asked and nothing is read`() {
        val run = offer("1\n", null)
        assertFalse(run.asked)
        assertEquals(0, run.compacted)
        assertEquals("", run.output)
    }

    @Test fun `choosing the summary compacts once, anything else leaves the session alone`() {
        val summary = offer("1\n", advice)
        assertTrue(summary.chose && summary.asked)
        assertEquals(1, summary.compacted)
        assertTrue(summary.output.contains("已压缩"))

        for (answer in listOf("2\n", "\n", null)) {
            val full = offer(answer, advice)
            assertTrue(full.asked)
            assertFalse(full.chose)
            assertEquals("完整续接什么都不做", 0, full.compacted)
        }
    }

    @Test fun `a summary that could not be made falls back to the full resume with a word`() {
        val run = offer("1\n", advice, compactOk = false)
        assertFalse(run.chose)
        assertEquals(1, run.compacted)
        assertTrue(run.output.contains("没能压缩"))
    }

    @Test fun `recap notes name what happened`() {
        assertEquals("※ 回顾：在修登录页", recapNote(RecapResult.Generated("在修登录页")))
        assertTrue(recapNote(RecapResult.Skipped("还没有对话可以回顾")).contains("还没有对话"))
        assertTrue(recapNote(RecapResult.Failed("529")).contains("会话不受影响"))
    }
}
