package dev.min.agent.tools

import dev.min.agent.protocol.TextPart
import dev.min.agent.spi.ToolInput
import dev.min.agent.spi.ToolProgress
import dev.min.agent.testkit.TestToolContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ReportFindings：`/code-review` 把审查结果交给宿主的结构化列表。
 * 入参照 Claude Code 的 code-review 页：每条有 file、一句话 summary、failure_scenario；
 * 可选 category（如 correctness）、line、severity（important / nit / pre_existing，对照 Code Review 的三种标记）。
 */
class ReportFindingsToolTest {
    private fun raw(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun invalid(text: String): String {
        val r = ReportFindingsTool.parse(raw(text))
        if (r !is ToolInput.Invalid) fail("应当拒绝：$text")
        return (r as ToolInput.Invalid).messageForModel
    }

    private val ok = """{"findings":[
        {"file":"src/a.kt","line":12,"summary":"空指针","failure_scenario":"传 null 时崩溃","category":"correctness","severity":"important"},
        {"file":"src/b.kt","summary":"变量名不清楚","failure_scenario":"读的人会误解"}
    ]}"""

    // ---- 入参校验 ----

    @Test
    fun `a valid list is accepted and the optional fields are read`() {
        val r = ReportFindingsTool.parse(raw(ok)) as ToolInput.Valid
        val f = ReportFindings.from(r.value)
        assertEquals(2, f.size)
        assertEquals("src/a.kt", f[0].file)
        assertEquals(12, f[0].line)
        assertEquals("correctness", f[0].category)
        assertEquals(FindingSeverity.Important, f[0].severity)
        assertNull(f[1].line)
        assertNull(f[1].category)
        assertNull(f[1].severity)
    }

    @Test
    fun `file summary and failure_scenario are each required`() {
        assertTrue(invalid("""{"findings":[{"summary":"s","failure_scenario":"f"}]}""").contains("file"))
        assertTrue(invalid("""{"findings":[{"file":"a","failure_scenario":"f"}]}""").contains("summary"))
        assertTrue(invalid("""{"findings":[{"file":"a","summary":"s"}]}""").contains("failure_scenario"))
        assertTrue(invalid("""{"findings":[{"file":"  ","summary":"s","failure_scenario":"f"}]}""").contains("file"))
    }

    @Test
    fun `an empty or missing findings list is rejected`() {
        assertTrue(invalid("""{"findings":[]}""").contains("findings"))
        assertTrue(invalid("""{}""").contains("findings"))
    }

    @Test
    fun `line must be a positive whole number`() {
        assertTrue(invalid("""{"findings":[{"file":"a","summary":"s","failure_scenario":"f","line":0}]}""").contains("line"))
        assertTrue(invalid("""{"findings":[{"file":"a","summary":"s","failure_scenario":"f","line":"3"}]}""").contains("line"))
        assertTrue(invalid("""{"findings":[{"file":"a","summary":"s","failure_scenario":"f","line":1.5}]}""").contains("line"))
    }

    @Test
    fun `severity must be one of the three markers`() {
        assertTrue(invalid("""{"findings":[{"file":"a","summary":"s","failure_scenario":"f","severity":"critical"}]}""").contains("severity"))
        for (ok in listOf("important", "nit", "pre_existing")) {
            assertTrue(ReportFindingsTool.parse(raw("""{"findings":[{"file":"a","summary":"s","failure_scenario":"f","severity":"$ok"}]}""")) is ToolInput.Valid)
        }
    }

    // ---- 运行与结果 ----

    @Test
    fun `running it reports a count and each location without touching the workspace or declaring effects`() = runBlocking {
        val input = (ReportFindingsTool.parse(raw(ok)) as ToolInput.Valid).value
        val ctx = TestToolContext()
        val done = ReportFindingsTool.run(input, ctx).toList().filterIsInstance<ToolProgress.Done>().single()
        val text = (done.result.content.single() as TextPart).text
        assertTrue(text.contains("2"))
        assertTrue(text.contains("src/a.kt:12"))
        assertTrue(text.contains("src/b.kt"))
        assertFalse(done.result.isError)
        assertTrue(done.result.effects.isEmpty())
        assertTrue(ctx.fs.writes.isEmpty())
    }

    @Test
    fun `fromOrNull gives null for a bad input instead of throwing`() {
        assertNull(ReportFindings.fromOrNull(raw("""{"findings":[]}""")))
        assertEquals(2, ReportFindings.fromOrNull(raw(ok))!!.size)
    }

    @Test
    fun `the card rows carry the location text and the severity marker`() {
        val rows = ReportFindings.rows(ReportFindings.from(raw(ok)))
        assertEquals("src/a.kt:12", rows[0].location)
        assertEquals(FindingSeverity.Important, rows[0].severity)
        assertEquals("src/b.kt", rows[1].location)
        assertNull(rows[1].severity)
    }
}
