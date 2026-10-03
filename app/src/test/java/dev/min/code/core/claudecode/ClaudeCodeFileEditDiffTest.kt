package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit / Write 的 `tool_use_result` 变成 unified diff。
 *
 * 之前只有 Bash 的 `bashEditDiff` 会落到 [ClaudeCodeEvent.ToolResult.editDiff]，Edit / Write 卡的 editDiff 永远是 null，
 * 「改动过的文件」面板因此只有文件名、增删全是 0。夹具 `edit_write_files` 是 2.1.285 真 CLI 对着本机 mock 模型录的。
 */
class ClaudeCodeFileEditDiffTest {

    private val results get() = cliEvents("edit_write_files").filterIsInstance<ClaudeCodeEvent.ToolResult>()

    private val work = "D:/kimi/workspace/research/tasks/cffix/work"

    @Test
    fun `real Write create frame becomes an all-plus diff marked as a new file`() {
        val diff = results[0].editDiff
        assertEquals(
            "new file mode\n--- a/$work/new.md\n+++ b/$work/new.md\n@@ -0,0 +1,3 @@\n+第一行\n+第二行\n+第三行",
            diff,
        )
    }

    @Test
    fun `real Edit frame becomes its structuredPatch hunk`() {
        assertEquals(
            "--- a/$work/new.md\n+++ b/$work/new.md\n@@ -1,3 +1,3 @@\n 第一行\n-第二行\n+改过的第二行\n 第三行",
            results[1].editDiff,
        )
    }

    @Test
    fun `real Write overwrite frame uses the patch the CLI computed, not a new-file diff`() {
        val diff = results[2].editDiff!!
        assertTrue(diff.startsWith("--- a/$work/new.md\n+++ b/$work/new.md\n@@ -1,3 +1,4 @@\n"))
        assertTrue("覆盖不是新建", !diff.contains("new file mode"))
        assertTrue(diff.contains("-改过的第二行"))
        assertTrue(diff.contains("+新增第四行"))
    }

    @Test
    fun `real Edit with replaceAll keeps context lines and several changes in one hunk`() {
        val diff = results[4].editDiff!!
        assertTrue(diff.contains("@@ -1,4 +1,4 @@"))
        assertTrue(diff.contains("-foo one\n+bar one\n alpha\n-foo two\n+bar two\n beta"))
    }

    @Test
    fun `a Read result and a failed tool give no diff`() {
        // 第 4 个结果是 Read（{type:"text", file:{...}}），最后一个是不存在的 MultiEdit 的 is_error
        assertNull(results[3].editDiff)
        assertNull(results.last().editDiff)
        assertTrue(results.last().isError)
    }

    @Test
    fun `the diff lands on the card through the same withResult as live frames`() {
        val calls = cliToolCalls("edit_write_files")
        val write = calls.first { it.name == "Write" }
        assertNotNull("卡上要有 diff，面板才有增删可算", write.editDiff)
        assertTrue("卡 id 不等于 toolUseId", write.id != write.toolUseId)
    }

    // ---- 手写的边角（真帧里没有的形状） ----

    private fun frame(result: String, key: String = "tool_use_result") =
        """{"type":"user","message":{"role":"user","content":[
            {"type":"tool_result","tool_use_id":"toolu_x","content":"ok"}]},"$key":$result}"""

    private fun diffOf(result: String, key: String = "tool_use_result") =
        (parseClaudeCodeEvents(frame(result, key)).single() as ClaudeCodeEvent.ToolResult).editDiff

    @Test
    fun `camelCase toolUseResult from a replayed transcript works too`() {
        val diff = diffOf(
            """{"filePath":"/w/a.kt","structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]}""",
            key = "toolUseResult",
        )
        assertEquals("--- a//w/a.kt\n+++ b//w/a.kt\n@@ -1,1 +1,1 @@\n-a\n+b", diff)
    }

    @Test
    fun `MultiEdit style output with filePath and structuredPatch is accepted`() {
        val diff = diffOf(
            """{"filePath":"/w/m.kt","edits":[],"structuredPatch":[{"oldStart":2,"oldLines":1,"newStart":2,"newLines":1,"lines":["-x","+y"]}]}""",
        )
        assertTrue(diff!!.contains("@@ -2,1 +2,1 @@"))
    }

    @Test
    fun `Write update with an empty patch has no diff so the caller can compute one`() {
        assertNull(diffOf("""{"type":"update","filePath":"/w/a.kt","content":"x","structuredPatch":[],"originalFile":null}"""))
    }

    @Test
    fun `Edit with an empty patch has no diff`() {
        assertNull(diffOf("""{"filePath":"/w/a.kt","oldString":"a","newString":"a","structuredPatch":[]}"""))
    }

    @Test
    fun `Write create without a trailing newline still counts every line`() {
        assertEquals(
            "new file mode\n--- a//w/n.txt\n+++ b//w/n.txt\n@@ -0,0 +1,2 @@\n+a\n+b",
            diffOf("""{"type":"create","filePath":"/w/n.txt","content":"a\nb","structuredPatch":[],"originalFile":null}"""),
        )
    }

    @Test
    fun `an empty new file is still a new file`() {
        assertEquals(
            "new file mode\n--- a//w/e.txt\n+++ b//w/e.txt",
            diffOf("""{"type":"create","filePath":"/w/e.txt","content":"","structuredPatch":[],"originalFile":null}"""),
        )
    }

    @Test
    fun `a result without filePath or structuredPatch is not an edit`() {
        assertNull(diffOf("""{"type":"text","file":{"filePath":"/w/a.kt","content":"x"}}"""))
        assertNull(diffOf("""{"filePath":"/w/a.kt"}"""))
        assertNull(diffOf(""""Error: boom""""))
    }

    @Test
    fun `bashEditDiff still wins when both are present`() {
        val diff = diffOf(
            """{"bashEditDiff":{"files":[{"filePath":"/w/b.txt","hunks":[{"oldStart":1,"oldLines":0,"newStart":1,"newLines":1,"lines":["+x"]}]}],"moreFiles":0},
                "filePath":"/w/other","structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-a","+b"]}]}""",
        )
        assertTrue(diff!!.contains("b.txt"))
    }
}
