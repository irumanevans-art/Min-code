package dev.min.code.core.claudecode

import dev.min.code.core.rootfs.CLAUDE_CODE_WORKSPACE_ID
import dev.min.code.core.session.ChatItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文件检查点在 Manager 这一层的行为：`tool_use` 一到就存快照，撤销只还原文件、并往对话流补一句说明。
 * 帧是手写的（真 CLI 的 Edit 帧会带宿主上的真路径，夹具里没法落到测试用的工作区），
 * 路径用 guest 的 `/workspace/...`，对应工作区目录下的 `files/`。
 */
class ClaudeCodeCheckpointFlowTest {

    private fun ManagerHarness.hostFile(name: String): File =
        File(dir, "files/workspaces/$CLAUDE_CODE_WORKSPACE_ID/files/$name").apply { parentFile!!.mkdirs() }

    private fun toolUse(id: String, tool: String, path: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$tool","input":{"file_path":"$path"}}]},"parent_tool_use_id":null}"""

    /** 快照是异步拍的：等它落盘再去改文件，否则"修改前"就拍成了修改后 */
    private suspend fun ManagerHarness.awaitSnapshot(vararg toolUseIds: String) = withTimeout(10_000) {
        while (manager.checkpointed(toolUseIds.toList()).size != toolUseIds.size) delay(20)
    }

    private fun ManagerHarness.notes() = manager.state.value.items.filterIsInstance<ChatItem.Note>().map { it.text }

    @Test
    fun `an edit is snapshotted before it runs and a single revert puts the file back`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val file = h.hostFile("a.txt").apply { writeText("before") }
            h.process.emit(toolUse("toolu_e1", "Edit", "/workspace/a.txt"))
            h.awaitSnapshot("toolu_e1")
            file.writeText("after")

            assertTrue(h.manager.revertToolCall("toolu_e1"))
            assertEquals("before", file.readText())
            assertTrue(
                h.notes().contains("已把 a.txt 还原到这次修改之前（仅文件，对话历史不变）"),
            )
        }
    }

    /** 新建文件的快照记的是"原来不存在"，撤销就是删掉它 */
    @Test
    fun `reverting a created file deletes it`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val file = h.hostFile("new.txt")
            h.process.emit(toolUse("toolu_w1", "Write", "/workspace/new.txt"))
            h.awaitSnapshot("toolu_w1")
            file.writeText("created by the tool")

            assertTrue(h.manager.revertToolCall("toolu_w1"))
            assertFalse(file.exists())
        }
    }

    @Test
    fun `tools that do not edit files are never snapshotted`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.hostFile("a.txt").writeText("x")
            h.process.emit(toolUse("toolu_r1", "Read", "/workspace/a.txt"))
            h.awaitState("读卡出现") { s -> s.items.any { it is ChatItem.ToolCall && it.toolUseId == "toolu_r1" } }
            // 快照在异步协程里拍：给它足够的时间证明它真的没拍，而不是还没拍
            delay(300)
            assertTrue(h.manager.checkpointed(listOf("toolu_r1")).isEmpty())
        }
    }

    /** 同一个文件连改两次：整批撤销从最后一次往前还原，落到第一次之前，并如实报条数 */
    @Test
    fun `reverting a whole file walks back to before the first edit`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            val file = h.hostFile("a.txt").apply { writeText("v0") }
            h.process.emit(toolUse("toolu_1", "Edit", "/workspace/a.txt"))
            h.awaitSnapshot("toolu_1")
            file.writeText("v1")
            h.process.emit(toolUse("toolu_2", "Edit", "/workspace/a.txt"))
            h.awaitSnapshot("toolu_1", "toolu_2")
            file.writeText("v2")

            val restored = h.manager.revertFile("a.txt", listOf("toolu_1", "toolu_2"))
            assertEquals(setOf("toolu_1", "toolu_2"), restored)
            assertEquals("v0", file.readText())
            assertTrue(h.notes().contains("已把 a.txt 还原到这个会话开始前（2 次改动，仅文件，对话历史不变）"))
        }
    }

    @Test
    fun `reverting without a snapshot says so and reports nothing restored`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            assertTrue(h.manager.revertFile("b.txt", listOf("toolu_missing")).isEmpty())
            assertTrue(h.notes().contains("撤销失败：找不到 b.txt 的快照"))
            // 空列表什么都不说
            val before = h.notes().size
            assertTrue(h.manager.revertFile("b.txt", emptyList()).isEmpty())
            assertEquals(before, h.notes().size)
        }
    }

    /** 删会话时快照一起清掉，否则文件副本会一直堆在数据目录里 */
    @Test
    fun `deleting the session clears its snapshots`() = runBlocking<Unit> {
        ManagerHarness("plain_reply").use { h ->
            h.startAndHandshake()
            h.hostFile("a.txt").writeText("before")
            h.process.emit(toolUse("toolu_e1", "Edit", "/workspace/a.txt"))
            h.awaitSnapshot("toolu_e1")

            h.manager.deleteSession(h.manager.state.value.sessionId!!)
            assertTrue(h.manager.checkpointed(listOf("toolu_e1")).isEmpty())
        }
    }
}
