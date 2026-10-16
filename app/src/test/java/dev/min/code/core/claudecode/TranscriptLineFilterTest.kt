package dev.min.code.core.claudecode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 读 transcript 前的快速跳过（[isUnusedTranscriptLine]）。行的形状照 CLI 2.1.301 实际写出的
 * （键的顺序、`api-request*` 三种新行），内容是合成的——真实 transcript 里有本机路径与系统提示，不进仓库。
 */
class TranscriptLineFilterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val bigPayload = "x".repeat(50_000)

    private val skipped = listOf(
        """{"type":"queue-operation","operation":"enqueue","timestamp":"2026-10-16T12:59:24.725Z","sessionId":"s"}""",
        """{"type":"api-request-shape","sessionId":"s","shapeHash":"a5b2","tools":[{"name":"Bash","description":"$bigPayload"}]}""",
        """{"type":"api-request-blob","sessionId":"s","hash":"2e6c","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"agentId"}]}}""",
        """{"type":"api-request","sessionId":"s","id":"88c3","timestamp":"2026-10-16T12:59:24.788Z","shapeHash":"a5b2"}""",
        """{"type":"last-prompt","lastPrompt":"帮我看看","sessionId":"s"}""",
        """{"type":"cost-state","sessionId":"s","totalCostUSD":0.04}""",
        """{"type":"atis-latch","atis":"","sessionId":"s"}""",
        """{"parentUuid":"4642d200-e9f6-4147-a50f-b9fc31994a0e","isSidechain":false,"attachment":{"type":"skill_listing","text":"$bigPayload"},"type":"attachment","uuid":"u9"}""",
        """{"parentUuid":null,"isSidechain":true,"attachment":{"type":"date","date":"2026-10-16"},"type":"attachment","uuid":"u8"}""",
    )

    private val kept = listOf(
        """{"parentUuid":null,"isSidechain":false,"promptId":"p","type":"user","message":{"role":"user","content":[{"type":"text","text":"你好"}]},"uuid":"u1"}""",
        """{"parentUuid":"u1","isSidechain":false,"message":{"model":"m","id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"好的"}]},"type":"assistant","uuid":"a1"}""",
        """{"type":"user","sessionId":"s","uuid":"u","message":{"role":"user","content":[{"type":"text","text":"type 在第一个键"}]}}""",
        """{"type":"custom-title","customTitle":"标题"}""",
        """{"type":"ai-title","aiTitle":"拟名"}""",
        // 键的顺序和 attachment 行不一样：认不准就交给完整解析
        """{"parentUuid":"u1","isSidechain":false,"message":{"role":"user","content":"x"},"attachment":{"type":"date"},"type":"user"}""",
        "  ",
        "not json",
    )

    @Test
    fun `skips the internal and api-request lines and keeps everything it cannot be sure about`() {
        skipped.forEach { assertTrue("应跳过：${it.take(60)}", isUnusedTranscriptLine(it)) }
        kept.forEach { assertFalse("不应跳过：${it.take(60)}", isUnusedTranscriptLine(it)) }
    }

    @Test
    fun `a skipped line never carried an event`() {
        // 不变式：跳过的行完整解析也产不出事件——跳过只省时间，不改结果
        (skipped + kept).filter(::isUnusedTranscriptLine).forEach {
            assertEquals("跳过的行解析出了事件：${it.take(60)}", emptyList<ClaudeCodeEvent>(), parseTranscriptLine(it))
        }
        assertTrue(kept.any { parseTranscriptLine(it).isNotEmpty() })
    }

    @Test
    fun `the session list and history read the same with the new lines in between`() = runBlocking {
        val linuxDir = temp.newFolder("linux")
        val projects = File(linuxDir, "root/.claude/projects/-workspace").apply { mkdirs() }
        val lines = listOf(skipped[0], kept[0], skipped[1], skipped[7], kept[1], skipped[3], kept[3])
        File(projects, "s1.jsonl").writeText(lines.joinToString("\n"))

        val store = ClaudeCodeSessionStore()
        val summary = store.listSessions(linuxDir).single()
        assertEquals("标题", summary.title)
        assertEquals(2, summary.messageCount)
        val events = store.loadTranscript(linuxDir, "s1")
        assertEquals(listOf("你好"), events.filterIsInstance<ClaudeCodeEvent.UserMessage>().map { it.text })
        assertEquals(listOf("好的"), events.filterIsInstance<ClaudeCodeEvent.AssistantText>().map { it.text })
    }
}
