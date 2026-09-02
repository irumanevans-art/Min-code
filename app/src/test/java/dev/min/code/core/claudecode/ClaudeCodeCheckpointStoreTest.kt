package dev.min.code.core.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件检查点。
 *
 * 这一层的价值全在"按下撤销之后磁盘上的内容真的回去了"，所以每条断言都盯着**文件内容**，
 * 而不是返回值。三个最容易做错的点各有一个用例：新建文件的撤销是删除、同一文件多次修改
 * 必须逆序还原、guest 路径不能拼出工作区外的宿主路径。
 */
class ClaudeCodeCheckpointStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sessionId = "session-1"

    private fun store(): Pair<ClaudeCodeCheckpointStore, File> {
        val workspace = temp.newFolder("workspace")
        return ClaudeCodeCheckpointStore(File(workspace, ".min-checkpoints")) to workspace
    }

    /** guest `/workspace/x` → 宿主 `files/x`，和 ProotShellRunner 的 bind mount 一致 */
    private fun resolver(workspace: File): (String) -> File? = { guest ->
        File(File(workspace, "files"), guest.removePrefix("/workspace").trimStart('/'))
    }

    @Test
    fun `restore puts the original content back`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/a.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }

        assertTrue(checkpoints.snapshot(sessionId, "tool-1", "/workspace/a.txt", file))
        file.writeText("edited by claude")

        assertTrue(checkpoints.restore(sessionId, "tool-1", resolver(workspace)))
        assertEquals("original", file.readText())
    }

    /**
     * 原来不存在的文件，撤销动作是**删除**。不记这种情况的话，Write 新建出来的文件
     * 永远撤不掉 —— 而"让 Claude 新建了一个不想要的文件"恰恰是最想撤的场景之一。
     */
    @Test
    fun `restoring a newly created file deletes it`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/new.txt")
        assertFalse(file.exists())

        assertTrue(checkpoints.snapshot(sessionId, "tool-1", "/workspace/new.txt", file))
        file.parentFile?.mkdirs()
        file.writeText("created by claude")

        assertTrue(checkpoints.restore(sessionId, "tool-1", resolver(workspace)))
        assertFalse("撤销新建应该把文件删掉", file.exists())
    }

    /**
     * 同一个文件被连续改了三次时，只有**逆序**还原才能落回第一次修改之前。
     * 正序还原的话，后面的快照会把前面刚还原好的内容又盖回去。
     */
    @Test
    fun `batch restore replays snapshots in reverse order`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/a.txt").apply {
            parentFile?.mkdirs()
            writeText("v0")
        }

        checkpoints.snapshot(sessionId, "t1", "/workspace/a.txt", file)
        file.writeText("v1")
        checkpoints.snapshot(sessionId, "t2", "/workspace/a.txt", file)
        file.writeText("v2")
        checkpoints.snapshot(sessionId, "t3", "/workspace/a.txt", file)
        file.writeText("v3")

        assertEquals(3, checkpoints.restoreAll(sessionId, listOf("t1", "t2", "t3"), resolver(workspace)))
        assertEquals("v0", file.readText())
    }

    /** 同一个 tool_use 只存一次：重复的 tool_use 帧不能把已经存好的原始内容覆盖掉 */
    @Test
    fun `snapshotting the same tool use twice keeps the first content`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/a.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }

        checkpoints.snapshot(sessionId, "tool-1", "/workspace/a.txt", file)
        file.writeText("edited")
        checkpoints.snapshot(sessionId, "tool-1", "/workspace/a.txt", file)

        checkpoints.restore(sessionId, "tool-1", resolver(workspace))
        assertEquals("original", file.readText())
    }

    @Test
    fun `restore is idempotent so a double tap is harmless`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/a.txt").apply {
            parentFile?.mkdirs()
            writeText("original")
        }
        checkpoints.snapshot(sessionId, "tool-1", "/workspace/a.txt", file)
        file.writeText("edited")

        assertTrue(checkpoints.restore(sessionId, "tool-1", resolver(workspace)))
        assertTrue(checkpoints.restore(sessionId, "tool-1", resolver(workspace)))
        assertEquals("original", file.readText())
    }

    @Test
    fun `missing snapshot reports failure instead of throwing`() {
        val (checkpoints, workspace) = store()
        assertFalse(checkpoints.hasSnapshot(sessionId, "nope"))
        assertFalse(checkpoints.restore(sessionId, "nope", resolver(workspace)))
    }

    @Test
    fun `clear removes every snapshot of a session`() {
        val (checkpoints, workspace) = store()
        val file = File(workspace, "files/a.txt").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        checkpoints.snapshot(sessionId, "tool-1", "/workspace/a.txt", file)
        assertTrue(checkpoints.hasSnapshot(sessionId, "tool-1"))

        checkpoints.clear(sessionId)
        assertFalse(checkpoints.hasSnapshot(sessionId, "tool-1"))
    }

    /**
     * 会话 id / tool_use id 来自 CLI，不是我们生成的。直接当目录名用时必须先消毒，
     * 否则一个带 `../` 的 id 就能让快照写到工作区外面去。
     */
    @Test
    fun `ids are sanitised before being used as directory names`() {
        assertEquals("_.._.._etc_passwd", "/../../etc/passwd".toSafeName())
        assertEquals("abc-123_def.md", "abc-123_def.md".toSafeName())
        assertEquals("unnamed", "".toSafeName())
        assertEquals(120, "x".repeat(500).toSafeName().length)
    }
}
