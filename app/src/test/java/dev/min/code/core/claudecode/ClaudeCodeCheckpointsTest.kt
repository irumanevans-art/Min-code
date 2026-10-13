package dev.min.code.core.claudecode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ClaudeCodeCheckpoints] 的接缝：什么情况下**不**拍快照、拍不成时怎么说。
 * 拍到、撤销得回来的主路径在 [ClaudeCodeCheckpointFlowTest] 里走真 Manager 验过了。
 */
class ClaudeCodeCheckpointsTest {

    private val root = Files.createTempDirectory("ccp-test").toFile()
    private val workspace = File(root, "ws").apply { File(this, "files").mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(ClaudeCodeManager.SessionState(sessionId = "s1"))
    private val notes = mutableListOf<Pair<String, Boolean>>()
    private var replaying = false
    private var workspaceProvider: suspend () -> File? = { workspace }

    private fun checkpoints() = ClaudeCodeCheckpoints(
        scope = scope,
        workspaceDir = { workspaceProvider() },
        linuxDir = { File(workspace, "linux") },
        state = state,
        isReplaying = { replaying },
        appendNote = { text, isError -> notes += text to isError },
    )

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun editOf(id: String, path: String = "/workspace/a.txt") = ClaudeCodeEvent.ToolUse(
        id = id, name = "Edit", input = JsonObject(mapOf("file_path" to JsonPrimitive(path))),
    )

    private suspend fun ClaudeCodeCheckpoints.awaitSnapshot(id: String) = withTimeout(10_000) {
        while (id !in checkpointed(listOf(id))) delay(20)
    }

    @Test
    fun `a snapshot is taken for an edit`() = runBlocking<Unit> {
        File(workspace, "files/a.txt").writeText("before")
        val c = checkpoints()
        c.snapshotBeforeEdit(editOf("t1"))
        c.awaitSnapshot("t1")
    }

    /** 回放里的工具早跑完了：此刻拍的是"现在的文件"，会把它当成"修改前" */
    @Test
    fun `nothing is snapshotted while replaying history`() = runBlocking<Unit> {
        File(workspace, "files/a.txt").writeText("now")
        replaying = true
        val c = checkpoints()
        c.snapshotBeforeEdit(editOf("t1"))
        delay(300)
        assertTrue(c.checkpointed(listOf("t1")).isEmpty())
    }

    /**
     * 快照排队的这一小段里工具已经动了手（bypass 下的快工具）：拍下来的就是修改后的内容，
     * 撤销等于空操作，所以宁可不拍。让文件在「协程里取工作区目录」时改掉，正好落在两次取样之间。
     */
    @Test
    fun `a file that changed between dispatch and snapshot is not snapshotted`() = runBlocking<Unit> {
        val file = File(workspace, "files/a.txt").apply { writeText("before") }
        workspaceProvider = {
            file.writeText("changed by the tool")
            file.setLastModified(file.lastModified() + 5_000)
            workspace
        }
        val c = checkpoints()
        c.snapshotBeforeEdit(editOf("t1"))
        delay(300)
        assertTrue(c.checkpointed(listOf("t1")).isEmpty())
    }

    @Test
    fun `no session means no snapshot`() = runBlocking<Unit> {
        File(workspace, "files/a.txt").writeText("x")
        state.value = ClaudeCodeManager.SessionState(sessionId = null)
        val c = checkpoints()
        c.snapshotBeforeEdit(editOf("t1"))
        delay(300)
        state.value = ClaudeCodeManager.SessionState(sessionId = "s1")
        assertTrue(c.checkpointed(listOf("t1")).isEmpty())
    }

    @Test
    fun `an edit without a target path is ignored`() = runBlocking<Unit> {
        val c = checkpoints()
        c.snapshotBeforeEdit(ClaudeCodeEvent.ToolUse(id = "t1", name = "Edit", input = JsonObject(emptyMap())))
        delay(300)
        assertTrue(c.checkpointed(listOf("t1")).isEmpty())
    }

    @Test
    fun `reverting without a workspace says so`() = runBlocking<Unit> {
        workspaceProvider = { null }
        val restored = checkpoints().revertFile("a.txt", listOf("t1"))
        assertTrue(restored.isEmpty())
        assertEquals(listOf("撤销失败：找不到工作区" to true), notes)
    }

    @Test
    fun `reverting without a session restores nothing and stays quiet`() = runBlocking<Unit> {
        state.value = ClaudeCodeManager.SessionState(sessionId = null)
        assertFalse(checkpoints().revertToolCall("t1"))
        assertTrue(notes.isEmpty())
    }
}
