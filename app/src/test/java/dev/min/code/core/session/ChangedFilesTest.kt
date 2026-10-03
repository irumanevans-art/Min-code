package dev.min.code.core.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本会话改过哪些文件」的汇总规则。
 *
 * 重点在**两条路径都要走**：编辑类工具看入参，Bash 与 Codex 看 diff 头。
 * 只认 `input["file_path"]` 会漏掉 Bash 改的文件，也会漏掉 Codex 一次改多个文件时的后几个。
 */
class ChangedFilesTest {

    private fun call(
        id: String,
        name: String,
        input: JsonObject = JsonObject(emptyMap()),
        editDiff: String? = null,
        toolUseId: String = id,
    ) = ChatItem.ToolCall(
        id = id,
        toolUseId = toolUseId,
        name = name,
        input = input,
        status = ChatItem.ToolCall.Status.Done,
        editDiff = editDiff,
    )

    private fun edit(id: String, path: String, old: String = "a\nb", new: String = "a\nB") = call(
        id, "Edit",
        input = JsonObject(mapOf("file_path" to JsonPrimitive(path), "old_string" to JsonPrimitive(old), "new_string" to JsonPrimitive(new))),
        editDiff = "--- a/$path\n+++ b/$path\n b\n-B\n+B",
    )

    // ---- 编辑类工具 ----

    @Test
    fun `edits and writes come from their input path and are undoable`() {
        val files = changedFiles(
            listOf(
                edit("c1", "/workspace/src/a.kt"),
                call("c2", "Write", JsonObject(mapOf("file_path" to JsonPrimitive("/workspace/README.md")))),
                call("c3", "Read", JsonObject(mapOf("file_path" to JsonPrimitive("/workspace/README.md")))),
            ),
        )
        assertEquals(listOf("/workspace/src/a.kt", "/workspace/README.md"), files.map { it.path })
        assertTrue("编辑类工具在 CLI 侧存了快照，可撤销", files.all { it.undoable.isNotEmpty() })
        assertEquals("读文件不算改动", 2, files.size)
        assertEquals("a.kt", files[0].name)
    }

    @Test
    fun `the same file edited twice is one entry with both changes in session order`() {
        val files = changedFiles(listOf(edit("c1", "/w/a.kt"), edit("c2", "/w/b.kt"), edit("c3", "/w/a.kt")))
        assertEquals(listOf("/w/a.kt", "/w/b.kt"), files.map { it.path })
        assertEquals(2, files[0].changes.size)
        assertEquals(listOf("c1", "c3"), files[0].changes.map { it.itemId })
    }

    @Test
    fun `a NotebookEdit counts too`() {
        val files = changedFiles(listOf(call("c1", "NotebookEdit", JsonObject(mapOf("file_path" to JsonPrimitive("/w/n.ipynb"))))))
        assertEquals(listOf("/w/n.ipynb"), files.map { it.path })
    }

    // ---- diff 头那条路 ----

    @Test
    fun `bash edits are found through the diff header and are not undoable`() {
        val diff = """
            --- a/notes.md
            +++ b/notes.md
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()
        val files = changedFiles(listOf(call("c1", "Bash", editDiff = diff)))
        assertEquals(listOf("notes.md"), files.map { it.path })
        assertTrue("Bash 的改动没有快照，只能看", files.single().changes.single().toolUseId == null)
        assertFalse(files.single().changes.single().undoable)
    }

    @Test
    fun `one call that changes several files lists them all in order`() {
        val diff = """
            --- a/one.md
            +++ b/one.md
            @@ -1 +1 @@
            -a
            +b

            --- a/two.md
            +++ b/two.md
            @@ -1 +1 @@
            -c
            +d
        """.trimIndent()
        val files = changedFiles(listOf(call("c1", "Edit", editDiff = diff)))
        assertEquals(listOf("one.md", "two.md"), files.map { it.path })
    }

    @Test
    fun `a diff that patches the same file twice still counts as one file`() {
        val diff = "--- a/x.md\n+++ b/x.md\n-a\n+b\n\n--- a/x.md\n+++ b/x.md\n-c\n+d"
        assertEquals(listOf("x.md"), changedFiles(listOf(call("c1", "Edit", editDiff = diff))).map { it.path })
    }

    @Test
    fun `dev null in a diff header is not a file`() {
        val diff = "--- /dev/null\n+++ b/new.md\n+a"
        assertEquals(listOf("new.md"), diffPaths(diff))
    }

    @Test
    fun `diff paths keep absolute paths as they are`() {
        assertEquals(listOf("/root/proj/a.kt"), diffPaths("--- a//root/proj/a.kt\n+++ b//root/proj/a.kt\n+x"))
    }

    // ---- 计数与新建 / 删除 ----

    @Test
    fun `stats count only real changes and exclude the file header`() {
        val stats = parseDiffStats("--- a/x\n+++ b/x\n context\n-gone\n+added\n+also")
        assertEquals(2, stats.additions)
        assertEquals(1, stats.deletions)
    }

    @Test
    fun `totals skip the changes that were undone`() {
        val files = changedFiles(listOf(edit("c1", "/w/a.kt"), edit("c2", "/w/a.kt")))
        val file = files.single()
        assertEquals(2, file.changes.size)
        val (added, removed) = file.totals(undoneIds = setOf("c2"))
        assertEquals(1, added)
        assertEquals(1, removed)
        assertEquals("撤销干净之后整份文件归零", 0 to 0, file.totals(undoneIds = setOf("c1", "c2")))
    }

    @Test
    fun `a new file is marked created so undo knows to delete it`() {
        val diff = "new file mode\n--- a/new.md\n+++ b/new.md\n+hello"
        val change = changedFiles(listOf(call("c1", "Bash", editDiff = diff))).single().changes.single()
        assertTrue(change.created)
        assertFalse(change.deleted)
    }

    @Test
    fun `a deleted file is marked deleted`() {
        val diff = "deleted file mode\n--- a/gone.md\n+++ b/gone.md\n-bye"
        val change = changedFiles(listOf(call("c1", "Bash", editDiff = diff))).single().changes.single()
        assertTrue(change.deleted)
    }

    @Test
    fun `created is only claimed when the call touched a single file`() {
        val diff = "new file mode\n--- a/a.md\n+++ b/a.md\n+x\n\n--- a/b.md\n+++ b/b.md\n+y"
        val files = changedFiles(listOf(call("c1", "Edit", editDiff = diff)))
        assertEquals(2, files.size)
        assertTrue("多文件时哪个是新建的说不准，不能瞎标", files.none { it.changes.single().created })
    }

    // ---- 撤销顺序 ----

    @Test
    fun `undo goes from the newest change backwards so the file ends up as it began`() {
        val file = changedFiles(listOf(edit("c1", "/w/a.kt"), edit("c2", "/w/a.kt"), edit("c3", "/w/a.kt"))).single()
        assertEquals(listOf("c3", "c2", "c1"), undoOrder(file))
    }

    @Test
    fun `undo order skips the changes that have no snapshot`() {
        val bashDiff = "--- a/w/x.md\n+++ b/w/x.md\n-a\n+b"
        val file = changedFiles(listOf(edit("c1", "w/x.md"), call("c2", "Bash", editDiff = bashDiff))).single()
        assertEquals("Bash 那次改的是同一个文件", 2, file.changes.size)
        assertEquals("只有编辑类那次能撤", listOf("c1"), undoOrder(file))
    }

    @Test
    fun `an empty session has no files`() {
        assertTrue(changedFiles(listOf(call("c1", "Read", JsonObject(mapOf("file_path" to JsonPrimitive("/w/a")))))).isEmpty())
        assertTrue(changedFiles(emptyList()).isEmpty())
    }

    // ---- 拼接多段 diff ----

    @Test
    fun `combining several diffs keeps one file header and inserts separators`() {
        val combined = combineDiffs(
            listOf("--- a/x.md\n+++ b/x.md\n-a\n+b", "--- a/x.md\n+++ b/x.md\n-c\n+d"),
        ) { index -> "@@ 第 ${index + 1} 次改动 @@" }
        assertEquals(
            listOf("--- a/x.md", "+++ b/x.md", "-a", "+b", "@@ 第 2 次改动 @@", "-c", "+d"),
            combined.lines(),
        )
    }

    @Test
    fun `combining drops blank segments and never leaves a trailing newline`() {
        assertEquals("-a\n+b", combineDiffs(listOf("", "  ", "-a\n+b", "")))
    }

    @Test
    fun `a change without an id is never reported as undoable`() {
        val change = FileChange(path = "/w/a.kt", itemId = "c1", toolUseId = null, added = 1, removed = 0)
        assertFalse(change.undoable)
        assertNull(change.toolUseId)
    }
}
