package dev.min.code.core.session

import dev.min.code.core.claudecode.cliToolCalls
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
 * 夹具取**真实会话的形状**：Claude Code 的卡 id 是本地新生成的（`item-N`），与 `toolUseId`（`toolu_…`）不同；
 * Edit / Write 卡的 editDiff 来自 `tool_use_result.structuredPatch` 的解析（真 CLI 帧，见 [cliToolCalls]）或为空（老会话回放）。
 * 2.1.31 的测试夹具 id == toolUseId、Edit 还自带 editDiff，把「撤销后状态不变」和「Edit 卡没有 diff」两个真问题都盖住了。
 */
class ChangedFilesTest {

    private fun call(
        id: String,
        name: String,
        input: Map<String, String> = emptyMap(),
        editDiff: String? = null,
        toolUseId: String = "toolu_$id",
        status: ChatItem.ToolCall.Status = ChatItem.ToolCall.Status.Done,
        isError: Boolean = false,
        subItems: List<ChatItem> = emptyList(),
    ) = ChatItem.ToolCall(
        id = id,
        toolUseId = toolUseId,
        name = name,
        input = JsonObject(input.mapValues { JsonPrimitive(it.value) }),
        status = status,
        isError = isError,
        editDiff = editDiff,
        subItems = subItems,
    )

    /** Claude Code 的 Edit 卡：editDiff 是 structuredPatch 拼出来的，头带 `a/` `b/` */
    private fun edit(id: String, path: String) = call(
        id, "Edit", mapOf("file_path" to path),
        editDiff = "--- a/$path\n+++ b/$path\n@@ -1,2 +1,2 @@\n a\n-b\n+B",
    )

    private val undoAll: (ChatItem.ToolCall) -> Boolean = { true }

    // ---- 第 1 条：Claude Code 的 Edit / Write 有 diff ----

    @Test
    fun `real CLI Edit and Write frames give counts instead of zeros`() {
        val files = changedFiles(cliToolCalls("edit_write_files"), canUndo = undoAll)
        // Write 新建 → Edit → Write 覆盖 同一个文件；existing.txt 被 Edit 过一次；Read 和失败的 MultiEdit 不算
        assertEquals(listOf("new.md", "existing.txt"), files.map { it.name })
        val newMd = files[0]
        assertEquals(3, newMd.changes.size)
        assertTrue("第一次是 Write 新建", newMd.changes[0].created)
        assertFalse("覆盖不是新建", newMd.changes[2].created)
        assertEquals(3 to 0, newMd.changes[0].added to newMd.changes[0].removed)
        assertEquals(1 to 1, newMd.changes[1].added to newMd.changes[1].removed)
        assertEquals(4 to 3, newMd.changes[2].added to newMd.changes[2].removed)
        assertEquals(2 to 2, files[1].totals(emptySet()))
        assertTrue("每条改动都带 diff", files.flatMap { it.changes }.all { !it.diff.isNullOrBlank() })
    }

    @Test
    fun `an Edit with no editDiff uses the fallback the page computes from the input`() {
        val old = call("item-1", "Edit", mapOf("file_path" to "/w/a.kt"))
        val files = changedFiles(
            listOf(old),
            fallbackDiff = { "--- a.kt\n+++ a.kt\n-x\n+y\n+z" },
        )
        val change = files.single().changes.single()
        assertEquals(2 to 1, change.added to change.removed)
        assertEquals("现算的头里只有文件名，不能因此多出一个叫 a.kt 的文件", listOf("/w/a.kt"), files.map { it.path })
    }

    @Test
    fun `without a fallback an Edit with no diff is still listed but counts nothing`() {
        val change = changedFiles(listOf(call("item-1", "Edit", mapOf("file_path" to "/w/a.kt")))).single().changes.single()
        assertNull(change.diff)
        assertEquals(0 to 0, change.added to change.removed)
    }

    @Test
    fun `the real diff wins over the fallback`() {
        val files = changedFiles(listOf(edit("item-1", "/w/a.kt")), fallbackDiff = { error("不该被调用") })
        assertEquals(1 to 1, files.single().totals(emptySet()))
    }

    @Test
    fun `failed or still running edits changed nothing`() {
        val files = changedFiles(
            listOf(
                call("item-1", "Edit", mapOf("file_path" to "/w/a.kt"), isError = true, status = ChatItem.ToolCall.Status.Error),
                call("item-2", "Edit", mapOf("file_path" to "/w/b.kt"), status = ChatItem.ToolCall.Status.Running),
            ),
            canUndo = undoAll,
        )
        assertTrue(files.isEmpty())
    }

    // ---- 第 2 条：撤销状态用 toolUseId，不是卡 id ----

    @Test
    fun `the card id is not the tool use id and undone state follows the tool use id`() {
        val file = changedFiles(listOf(edit("item-1", "/w/a.kt"), edit("item-2", "/w/a.kt")), canUndo = undoAll).single()
        assertEquals(listOf("item-1", "item-2"), file.changes.map { it.itemId })
        assertEquals(listOf("toolu_item-1", "toolu_item-2"), file.changes.map { it.toolUseId })

        assertEquals("卡 id 对不上任何撤销记录", 2 to 2, file.totals(setOf("item-1", "item-2")))
        assertEquals(1 to 1, file.totals(setOf("toolu_item-2")))
        assertEquals("撤销干净之后整份文件归零", 0 to 0, file.totals(setOf("toolu_item-1", "toolu_item-2")))
        assertTrue(file.isUndone(setOf("toolu_item-1", "toolu_item-2")))
        assertFalse(file.isUndone(setOf("toolu_item-1")))
    }

    @Test
    fun `pending undo ids are in session order oldest first and skip the undone ones`() {
        val file = changedFiles(
            listOf(edit("item-1", "/w/a.kt"), edit("item-2", "/w/a.kt"), edit("item-3", "/w/a.kt")),
            canUndo = undoAll,
        ).single()
        // restoreAll 自己会从最后一次往前还原，所以这里必须是旧 → 新，不能先倒一次
        assertEquals(listOf("toolu_item-1", "toolu_item-2", "toolu_item-3"), file.pendingUndoIds(emptySet()))
        assertEquals(listOf("toolu_item-1", "toolu_item-3"), file.pendingUndoIds(setOf("toolu_item-2")))
    }

    @Test
    fun `a change that cannot be undone is never reported undone`() {
        val file = changedFiles(listOf(edit("item-1", "/w/a.kt"))).single() // 默认 canUndo = false
        assertNull(file.changes.single().toolUseId)
        assertFalse(file.isUndone(setOf("toolu_item-1", "item-1")))
        assertEquals(1 to 1, file.totals(setOf("toolu_item-1")))
        assertTrue(file.pendingUndoIds(emptySet()).isEmpty())
    }

    @Test
    fun `the caller decides which calls are undoable`() {
        val items = listOf(edit("item-1", "/w/a.kt"), edit("item-2", "/w/b.kt"))
        val files = changedFiles(items, canUndo = { it.toolUseId == "toolu_item-2" })
        assertFalse(files[0].changes.single().undoable)
        assertTrue(files[1].changes.single().undoable)
    }

    // ---- 第 3 条：Codex 多文件 ----

    private val codexDiff = """
        --- a/one.md
        +++ b/one.md
        @@ -1 +1 @@
        -a
        +b

        new file mode
        --- a/two.md
        +++ b/two.md
        @@ -0,0 +1,2 @@
        +c
        +d

        --- a//root/three.md
        +++ b//root/three.md
        @@ -1,2 +1 @@
         x
        -y
    """.trimIndent()

    /** Codex 的 fileChange 折成 Edit，`file_path` 只放第一个路径 */
    private fun codexChange(diff: String = codexDiff) =
        call("fc1", "Edit", mapOf("file_path" to "one.md"), editDiff = diff, toolUseId = "fc1")

    @Test
    fun `a Codex file change with several files lists all of them in diff order`() {
        val files = changedFiles(listOf(codexChange()))
        assertEquals(listOf("one.md", "two.md", "/root/three.md"), files.map { it.path })
    }

    @Test
    fun `every file of a multi file call gets its own counts and its own diff`() {
        val files = changedFiles(listOf(codexChange()))
        val (one, two, three) = files.map { it.changes.single() }
        assertEquals(1 to 1, one.added to one.removed)
        assertEquals(2 to 0, two.added to two.removed)
        assertEquals(0 to 1, three.added to three.removed)
        assertTrue(one.diff!!.contains("one.md") && !one.diff!!.contains("two.md"))
        assertEquals("合计不再被重复计入", 3 to 2, files.totals(emptySet()))
        assertTrue("哪个是新建的现在能分清", two.created)
        assertFalse(one.created)
        assertFalse(three.created)
    }

    @Test
    fun `Codex changes are not undoable even though the tool is named Edit`() {
        val files = changedFiles(listOf(codexChange()))
        assertTrue(files.flatMap { it.changes }.none { it.undoable })
        assertTrue(files.all { it.pendingUndoIds(emptySet()).isEmpty() })
    }

    @Test
    fun `when undo is allowed only the declared path of a multi file call is undoable`() {
        val files = changedFiles(listOf(codexChange()), canUndo = undoAll)
        assertEquals(listOf(true, false, false), files.map { it.changes.single().undoable })
    }

    @Test
    fun `a declared path and the same path in the diff header are one file`() {
        val files = changedFiles(listOf(call("c1", "Edit", mapOf("file_path" to "/root/x.kt"), editDiff = "--- a//root/x.kt\n+++ b//root/x.kt\n-a\n+b")))
        assertEquals(listOf("/root/x.kt"), files.map { it.path })
    }

    // ---- 第 5 条：子 agent ----

    @Test
    fun `edits made by a subagent are listed and jump to the top level Agent card`() {
        val agent = call(
            "item-9", "Agent", mapOf("description" to "补测试"),
            subItems = listOf(edit("item-10", "/w/sub.kt")),
        )
        val files = changedFiles(listOf(edit("item-1", "/w/top.kt"), agent))
        assertEquals(listOf("/w/top.kt", "/w/sub.kt"), files.map { it.path })
        assertEquals("item-9", files[1].changes.single().itemId)
        assertEquals("顶层的改动照旧跳到自己", "item-1", files[0].changes.single().itemId)
    }

    @Test
    fun `nested subagents are searched too`() {
        val inner = call("i3", "Agent", subItems = listOf(edit("i4", "/w/deep.kt")))
        val outer = call("i1", "Agent", subItems = listOf(inner))
        val change = changedFiles(listOf(outer)).single().changes.single()
        assertEquals("i1", change.itemId)
    }

    @Test
    fun `a subagent edit has no snapshot so it stays view only`() {
        val subEdit = edit("item-10", "/w/sub.kt")
        val agent = call("item-9", "Agent", subItems = listOf(subEdit))
        val items = listOf(edit("item-1", "/w/top.kt"), agent)
        // 页面的口径：只有顶层候选去问磁盘，磁盘上只有它们有快照
        val snapshotted = undoCandidates(items).toSet()
        assertEquals(setOf("toolu_item-1"), snapshotted)
        val files = changedFiles(items, canUndo = { it.toolUseId in snapshotted })
        assertTrue(files[0].changes.single().undoable)
        assertFalse(files[1].changes.single().undoable)
    }

    @Test
    fun `undo candidates are finished top level edit tools only`() {
        val items = listOf(
            edit("item-1", "/w/a.kt"),
            call("item-2", "Bash", mapOf("command" to "ls")),
            call("item-3", "Edit", mapOf("file_path" to "/w/b.kt"), status = ChatItem.ToolCall.Status.Running),
            call("item-4", "Write", mapOf("file_path" to "/w/c.kt"), isError = true),
        )
        assertEquals(listOf("toolu_item-1"), undoCandidates(items))
    }

    // ---- 编辑类 / Bash ----

    @Test
    fun `edits and writes come from their input path and reads do not count`() {
        val files = changedFiles(
            listOf(
                edit("item-1", "/workspace/src/a.kt"),
                call("item-2", "Write", mapOf("file_path" to "/workspace/README.md")),
                call("item-3", "Read", mapOf("file_path" to "/workspace/README.md")),
            ),
            canUndo = undoAll,
        )
        assertEquals(listOf("/workspace/src/a.kt", "/workspace/README.md"), files.map { it.path })
        assertEquals("a.kt", files[0].name)
    }

    @Test
    fun `the same file edited twice is one entry with both changes in session order`() {
        val files = changedFiles(listOf(edit("c1", "/w/a.kt"), edit("c2", "/w/b.kt"), edit("c3", "/w/a.kt")))
        assertEquals(listOf("/w/a.kt", "/w/b.kt"), files.map { it.path })
        assertEquals(listOf("c1", "c3"), files[0].changes.map { it.itemId })
    }

    @Test
    fun `bash edits are found through the diff header`() {
        val diff = "--- a/notes.md\n+++ b/notes.md\n@@ -1 +1 @@\n-old\n+new"
        val change = changedFiles(listOf(call("c1", "Bash", editDiff = diff)), canUndo = undoAll).single().changes.single()
        assertEquals("notes.md", change.path)
        // 就算调用方说能撤：Bash 没有声明路径，快照不是为它存的
        assertFalse(change.undoable)
    }

    @Test
    fun `Min engine headers carry the guest path without a or b prefixes`() {
        val diff = "--- /workspace/a.kt\n+++ /workspace/a.kt\n@@ -1 +1 @@\n-x\n+y"
        assertEquals(listOf("/workspace/a.kt"), splitDiffByFile(diff).map { it.path })
        assertEquals(listOf("/workspace/a.kt"), changedFiles(listOf(call("c1", "Bash", editDiff = diff))).map { it.path })
    }

    @Test
    fun `only one git prefix is stripped from a header path`() {
        assertEquals(listOf("b/x.kt"), splitDiffByFile("--- a/b/x.kt\n+++ b/b/x.kt\n-a\n+b").map { it.path })
    }

    @Test
    fun `a diff that patches the same file twice still counts as one file`() {
        val diff = "--- a/x.md\n+++ b/x.md\n-a\n+b\n\n--- a/x.md\n+++ b/x.md\n-c\n+d"
        val files = changedFiles(listOf(call("c1", "Bash", editDiff = diff)))
        assertEquals(listOf("x.md"), files.map { it.path })
        assertEquals("同一次调用里的两段合成一条改动", 1, files.single().changes.size)
        assertEquals(2 to 2, files.single().totals(emptySet()))
    }

    @Test
    fun `dev null in a diff header is not a file`() {
        assertEquals(listOf("new.md"), splitDiffByFile("--- /dev/null\n+++ b/new.md\n+a").map { it.path })
        assertEquals(listOf("gone.md"), splitDiffByFile("--- a/gone.md\n+++ /dev/null\n-a").map { it.path })
    }

    @Test
    fun `diff paths keep absolute paths as they are`() {
        assertEquals(listOf("/root/proj/a.kt"), splitDiffByFile("--- a//root/proj/a.kt\n+++ b//root/proj/a.kt\n+x").map { it.path })
    }

    @Test
    fun `an empty session has no files`() {
        assertTrue(changedFiles(listOf(call("c1", "Read", mapOf("file_path" to "/w/a")))).isEmpty())
        assertTrue(changedFiles(emptyList()).isEmpty())
    }

    // ---- 新建 / 删除只认文件头之前的标记 ----

    @Test
    fun `a new file and a deleted file are marked from the lines before the header`() {
        val created = changedFiles(listOf(call("c1", "Bash", editDiff = "new file mode\n--- a/new.md\n+++ b/new.md\n+hello"))).single().changes.single()
        assertTrue(created.created)
        assertFalse(created.deleted)
        val deleted = changedFiles(listOf(call("c2", "Bash", editDiff = "deleted file mode\n--- a/gone.md\n+++ b/gone.md\n-bye"))).single().changes.single()
        assertTrue(deleted.deleted)
    }

    @Test
    fun `body text that mentions new file mode does not make an edit a new file`() {
        val diff = "--- a/doc.md\n+++ b/doc.md\n@@ -1,2 +1,2 @@\n context\n-old\n+see: new file mode and deleted file mode"
        val change = changedFiles(listOf(call("c1", "Edit", mapOf("file_path" to "doc.md"), editDiff = diff))).single().changes.single()
        assertFalse(change.created)
        assertFalse(change.deleted)
    }

    @Test
    fun `a line of body text that starts with new file mode is not a marker either`() {
        // 正文行带前缀（+ / - / 空格），不会和文件头前的 mode 行混
        val diff = "--- a/doc.md\n+++ b/doc.md\n@@ -1 +1 @@\n+new file mode\n-deleted file mode"
        val change = changedFiles(listOf(call("c1", "Edit", mapOf("file_path" to "doc.md"), editDiff = diff))).single().changes.single()
        assertFalse(change.created || change.deleted)
    }

    // ---- 计数 ----

    @Test
    fun `stats count only real changes and exclude the file header`() {
        val stats = parseDiffStats("--- a/x\n+++ b/x\n context\n-gone\n+added\n+also")
        assertEquals(2, stats.additions)
        assertEquals(1, stats.deletions)
    }

    @Test
    fun `a deleted yaml separator or sql comment line still counts as a deletion`() {
        // 删掉 `---`（YAML）→ diff 里是 `----`；删掉 `-- c`（SQL / Lua 注释）→ `--- c`。都不是文件头
        val stats = parseDiffStats("--- a/x.sql\n+++ b/x.sql\n@@ -1,3 +1,1 @@\n--- c\n----\n+select 1;")
        assertEquals(1, stats.additions)
        assertEquals(2, stats.deletions)
    }

    @Test
    fun `an added line that starts with plus plus counts as an addition`() {
        val stats = parseDiffStats("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+++ b")
        assertEquals("`+++ b` 前面是 `-a` 而不是 `--- `，不是文件头", 1, stats.deletions)
        assertEquals(1, stats.additions)
    }

    @Test
    fun `header pairs inside a multi file diff are skipped each time`() {
        val stats = parseDiffStats("--- a/x\n+++ b/x\n-a\n+b\n--- a/y\n+++ b/y\n-c\n+d\n+e")
        assertEquals(3, stats.additions)
        assertEquals(2, stats.deletions)
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
    fun `combining strips a leading mode line together with the header of later segments`() {
        val combined = combineDiffs(
            listOf(
                "new file mode\n--- a/x.md\n+++ b/x.md\n@@ -0,0 +1 @@\n+a",
                "deleted file mode\n--- a/x.md\n+++ b/x.md\n@@ -1 +0,0 @@\n-a",
            ),
        )
        assertEquals(
            listOf("new file mode", "--- a/x.md", "+++ b/x.md", "@@ -0,0 +1 @@", "+a", "@@ -1 +0,0 @@", "-a"),
            combined.lines(),
        )
    }

    @Test
    fun `combining keeps a body line that merely starts with dashes`() {
        val combined = combineDiffs(listOf("--- a/x\n+++ b/x\n-a", "--- c\n+d"))
        // 第二段首行是 `--- c` 但后面不是 `+++`，不是文件头，是被删掉的 `-- c`
        assertEquals(listOf("--- a/x", "+++ b/x", "-a", "--- c", "+d"), combined.lines())
    }

    @Test
    fun `combining drops blank segments and never leaves a trailing newline`() {
        assertEquals("-a\n+b", combineDiffs(listOf("", "  ", "-a\n+b", "")))
    }

    @Test
    fun `the first real segment keeps its header even when an earlier one is blank`() {
        assertEquals("--- a/x\n+++ b/x\n-a", combineDiffs(listOf("", "--- a/x\n+++ b/x\n-a")))
    }

    @Test
    fun `a change without an id is never reported as undoable`() {
        val change = FileChange(path = "/w/a.kt", itemId = "c1", toolUseId = null, added = 1, removed = 0)
        assertFalse(change.undoable)
        assertNull(change.toolUseId)
    }
}
