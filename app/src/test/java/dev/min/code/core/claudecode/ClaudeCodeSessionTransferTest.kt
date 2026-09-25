package dev.min.code.core.claudecode

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ClaudeCodeSessionTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = ClaudeCodeSessionStore()
    private val transfer = ClaudeCodeSessionTransfer(store)
    private val id = "0af37106-1491-42ae-94e7-ef8da5c44a1c"

    private fun line(cwd: String, text: String = "hi") =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"$text"}]},""" +
            """"uuid":"u","parentUuid":null,"cwd":"$cwd","sessionId":"$id"}"""

    private fun projects(linux: File) = File(linux, "root/.claude/projects")

    @Test
    fun `export copies the transcript bytes untouched`() = runBlocking {
        val linux = temp.newFolder("linux")
        val bytes = (line("/workspace") + "\r\n" + line("/workspace", "caf\\u00e9")).encodeToByteArray()
        File(projects(linux), "-workspace").apply { mkdirs() }.resolve("$id.jsonl").writeBytes(bytes)
        val sink = ByteArrayOutputStream()
        assertTrue(transfer.export(linux, id) { sink })
        assertArrayEquals(bytes, sink.toByteArray())
        assertFalse(transfer.export(linux, "99999999-2222-4333-8444-555555555555") { ByteArrayOutputStream() })
    }

    @Test
    fun `import lands in the target cwd project dir and shows up in the list`() = runBlocking {
        val linux = temp.newFolder("linux")
        val source = (line("/Users/me/proj") + "\n").encodeToByteArray()
        val staged = transfer.stage(linux, "/workspace/proj") { source.inputStream() }
        staged as ClaudeCodeSessionTransfer.Staged.Ready
        assertNull(staged.existing)
        assertEquals(1, staged.cwdRewritten)
        assertTrue(transfer.commit(staged))

        val landed = File(projects(linux), "-workspace-proj/$id.jsonl")
        assertEquals(line("/workspace/proj") + "\n", landed.readText())
        assertFalse(File(landed.parentFile, ".min-import.tmp").exists())
        assertEquals(listOf(id), store.listSessions(linux).map { it.id })
    }

    @Test
    fun `a rejected file leaves nothing behind`() = runBlocking {
        val linux = temp.newFolder("linux")
        val staged = transfer.stage(linux, "/workspace") { "not json\n".byteInputStream() }
        assertEquals(ClaudeCodeSessionTransfer.Staged.Rejected(SessionImportReject.BAD_LINE, 1), staged)
        assertTrue(projects(linux).walkTopDown().none { it.isFile })
    }

    @Test
    fun `an unreadable source is a failure, not a rejection`() = runBlocking {
        val linux = temp.newFolder("linux")
        assertEquals(ClaudeCodeSessionTransfer.Staged.Failed, transfer.stage(linux, "/workspace") { null })
    }

    @Test
    fun `an existing session with the same id elsewhere is reported and replaced on commit`() = runBlocking {
        val linux = temp.newFolder("linux")
        val oldDir = File(projects(linux), "-workspace-old").apply { mkdirs() }
        File(oldDir, "$id.jsonl").writeText(line("/workspace/old", "old") + "\n")
        File(oldDir, "$id/subagents").mkdirs()
        File(oldDir, "$id.jsonl.abc123").writeText("backup")
        val keep = File(oldDir, "11111111-2222-4333-8444-555555555555.jsonl").apply { writeText("x") }

        val staged = transfer.stage(linux, "/workspace") { (line("/workspace", "new") + "\n").byteInputStream() }
        staged as ClaudeCodeSessionTransfer.Staged.Ready
        assertEquals(File(oldDir, "$id.jsonl"), staged.existing)
        assertTrue(transfer.commit(staged))

        assertEquals(line("/workspace", "new") + "\n", File(projects(linux), "-workspace/$id.jsonl").readText())
        assertFalse(File(oldDir, "$id.jsonl").exists())
        assertFalse(File(oldDir, id).exists())
        assertFalse(File(oldDir, "$id.jsonl.abc123").exists())
        assertTrue("别的会话不能被误删", keep.exists())
    }

    @Test
    fun `overwriting in the same dir replaces the file and keeps the new one`() = runBlocking {
        val linux = temp.newFolder("linux")
        val dir = File(projects(linux), "-workspace").apply { mkdirs() }
        File(dir, "$id.jsonl").writeText(line("/workspace", "old") + "\n")
        File(dir, id).mkdirs()

        val staged = transfer.stage(linux, "/workspace") { (line("/workspace", "new") + "\n").byteInputStream() }
        staged as ClaudeCodeSessionTransfer.Staged.Ready
        assertEquals(File(dir, "$id.jsonl"), staged.existing)
        // 还没 commit：旧文件原封不动
        assertEquals(line("/workspace", "old") + "\n", File(dir, "$id.jsonl").readText())
        assertTrue(transfer.commit(staged))
        assertEquals(line("/workspace", "new") + "\n", File(dir, "$id.jsonl").readText())
        assertFalse(File(dir, id).exists())
    }

    @Test
    fun `discard drops the staged file and leaves the old session alone`() = runBlocking {
        val linux = temp.newFolder("linux")
        val dir = File(projects(linux), "-workspace").apply { mkdirs() }
        File(dir, "$id.jsonl").writeText("old\n")
        val staged = transfer.stage(linux, "/workspace") { (line("/workspace") + "\n").byteInputStream() }
        transfer.discard(staged as ClaudeCodeSessionTransfer.Staged.Ready)
        assertEquals("old\n", File(dir, "$id.jsonl").readText())
        assertFalse(File(dir, ".min-import.tmp").exists())
    }

    @Test
    fun `a cwd too long for the cli naming rule falls back to the default dir`() = runBlocking {
        val linux = temp.newFolder("linux")
        val longCwd = "/workspace/" + "a".repeat(SessionJsonl.PROJECT_DIR_NAME_MAX)
        val staged = transfer.stage(linux, longCwd) { (line("/workspace") + "\n").byteInputStream() }
        assertTrue(transfer.commit(staged as ClaudeCodeSessionTransfer.Staged.Ready))
        assertTrue(File(projects(linux), "-workspace/$id.jsonl").isFile)
    }
}
