package dev.min.code.core.engine.min

import dev.min.agent.kernel.ConversationMark
import dev.min.agent.kernel.RewindPoint
import dev.min.agent.kernel.RewindPreview
import dev.min.agent.protocol.MessageId
import dev.min.agent.protocol.SessionId
import dev.min.agent.protocol.TextPart
import dev.min.agent.protocol.TurnId
import dev.min.agent.spi.CheckpointTaken
import dev.min.agent.spi.ContentId
import dev.min.agent.spi.FileRestore
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ManifestId
import dev.min.agent.spi.PathChange
import dev.min.agent.spi.PathChangeKind
import dev.min.code.core.engine.RewindScopeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内核的回退预览 -> 确认框要写的内容：哪些会恢复、哪些会删、哪些是编辑记录补的 */
class MinRewindViewTest {
    private val text = FakeText()
    private val id = MessageId("u1")
    private val manifest = ManifestId("a".repeat(64))
    private val content = ContentId("b".repeat(64))
    private val mark = ConversationMark(0, 0, 0, 0, 0, 0, emptyList(), emptyList(), emptySet(), null, emptyMap(), emptyList())

    private fun checkpoint(withManifest: Boolean = true) =
        CheckpointTaken("ck", TurnId("t1"), id, GuestPath("/workspace"), manifest.takeIf { withManifest })

    private fun preview(
        changes: List<PathChange> = emptyList(),
        edits: List<FileRestore> = emptyList(),
        withManifest: Boolean = true,
        before: List<SessionId> = emptyList(),
        after: List<SessionId> = emptyList(),
    ) = RewindPreview(
        messageId = id, prompt = listOf(TextPart("改一下")), checkpoint = checkpoint(withManifest), changes = changes,
        editRestores = edits, unrestorable = listOf(GuestPath("/workspace/big.bin")), external = listOf(GuestPath("/workspace/hand.txt")),
        runningAfterPoint = after, runningBefore = before,
    )

    private fun change(path: String, kind: PathChangeKind, symlink: Boolean = false) = PathChange(GuestPath(path), kind, content, content, symlink)

    @Test
    fun `snapshot changes read in the target to now direction and symlinks are only listed`() {
        val view = viewOf(
            preview(
                changes = listOf(
                    change("/workspace/mod.txt", PathChangeKind.Modified),
                    change("/workspace/new.txt", PathChangeKind.Added),
                    change("/workspace/gone.txt", PathChangeKind.Deleted),
                    change("/workspace/link", PathChangeKind.Modified, symlink = true),
                ),
            ),
            foldOf(), text,
        )
        assertEquals(listOf("/workspace/mod.txt", "/workspace/gone.txt"), view.restore)
        assertEquals("现在有、那时没有的会被删", listOf("/workspace/new.txt"), view.delete)
        assertEquals(listOf("/workspace/link"), view.symlinks)
        assertEquals("改一下", view.prompt)
        assertEquals(listOf("/workspace/big.bin"), view.unrestorable)
        assertEquals(listOf("/workspace/hand.txt"), view.external)
        assertTrue(view.hasCheckpoint && view.canRestoreCode)
    }

    @Test
    fun `edit records only add the files the snapshot cannot see`() {
        val view = viewOf(
            preview(
                changes = listOf(change("/workspace/mod.txt", PathChangeKind.Modified)),
                edits = listOf(
                    FileRestore(GuestPath("/workspace/mod.txt"), content), // 整树快照照得到的：宿主忽略，不列两遍
                    FileRestore(GuestPath("/workspace/quiet.txt"), content), // 根之内、没变化：快照照得到、没有差异 = 不会动
                    FileRestore(GuestPath("/root/notes.md"), content), // 工作区之外：只能靠编辑记录
                    FileRestore(GuestPath("/root/made.md"), null), // 工作区之外、编辑前不存在：会删
                ),
            ),
            foldOf(), text,
        )
        assertEquals(listOf("/workspace/mod.txt", "/root/notes.md"), view.restore)
        assertEquals(listOf("/root/made.md"), view.delete)
    }

    @Test
    fun `without a snapshot every edit record counts and the box says so`() {
        val view = viewOf(preview(edits = listOf(FileRestore(GuestPath("/workspace/a.txt"), content)), withManifest = false), foldOf(), text)
        assertFalse(view.hasCheckpoint)
        assertEquals(listOf("/workspace/a.txt"), view.restore)
    }

    @Test
    fun `no differences means only the conversation can go back, and stopping agents depends on the scope`() {
        val a = SessionId("child_a")
        val b = SessionId("child_b")
        val view = viewOf(preview(before = listOf(a), after = listOf(b)), foldOf(), text)
        assertFalse(view.canRestoreCode)
        assertEquals(listOf(b.value), view.stoppedBy(RewindScopeView.Conversation))
        assertEquals(setOf(a.value, b.value), view.stoppedBy(RewindScopeView.Code).toSet())
        assertEquals(view.stoppedBy(RewindScopeView.Code), view.stoppedBy(RewindScopeView.Both))
    }

    @Test
    fun `a point before a compaction is flagged`() {
        val state = foldOf().copy(rewindPoints = listOf(RewindPoint(id, TurnId("t1"), 3, mark)))
        assertFalse(viewOf(preview(), state, text).compaction)
    }
}
