package dev.min.agent.cli

import dev.min.agent.kernel.SessionRuntime
import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.Durability
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 会话入口的 CLI 接线（CC cli-reference / sessions 页）：`--continue` 选最近的根会话（跳过 `-p` 建的）、
 * `/branch` 与 `--fork-session`（复制成新根会话，原会话不动）、`/fork`（分身但不切过去）、
 * `/list-agents`（与 ListAgents 工具同一份数据）、已结束的会话 `-p` 恢复报错、`/clear` 另起一个会话。
 */
class SessionEntryPointsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun home(): Path = Files.createDirectories(tmp.root.toPath().resolve("home"))

    private fun work(): Path = Files.createDirectories(tmp.root.toPath().resolve("work"))

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    private fun app(work: Path, home: Path, vararg extra: String) =
        DesktopApp(parseArgs(argv(work, *extra), work), newScope(), home, claudeHome = home.resolve("claude-home"))

    @Test fun `continue picks the newest interactive root session and skips the print-mode one`() = runBlocking {
        val work = work()
        val home = home()
        val interactive = app(work, home).openSession()
        // 之后的 -p 运行建的会话更新，但 --continue 不接它（CC：跳过 claude -p 建的会话）
        app(work, home, "-p", "batch").openSession()

        val resumed = app(work, home, "--continue").openSession()
        assertEquals(interactive.sessionId, resumed.sessionId)
    }

    @Test fun `continue with only print-mode sessions says there is nothing to continue`() = runBlocking {
        val work = work()
        val home = home()
        app(work, home, "-p", "batch").openSession()
        try {
            app(work, home, "--continue").openSession()
            fail("没有可接的交互会话时应报错")
        } catch (e: UsageException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("当前目录还没有可接着的会话"))
        }
    }

    @Test fun `continue with fork-session branches the session and leaves the original untouched`() = runBlocking {
        val work = work()
        val home = home()
        val original = app(work, home).openSession()
        val seqBefore = original.state.value.lastSeq

        val branch = app(work, home, "--continue", "--fork-session").openSession()
        assertNotEquals(original.sessionId, branch.sessionId)
        assertEquals(original.sessionId, branch.state.value.branched?.from)
        assertEquals(seqBefore, original.state.value.lastSeq)
    }

    @Test fun `slash branch switches to a new root session and keeps the original resumable`() = runBlocking {
        val work = work()
        val home = home()
        val app = app(work, home)
        val original = app.openSession()

        val branched = app.branchSession(original)
        assertNotEquals(original.sessionId, branched.sessionId)
        assertEquals(original.sessionId, branched.state.value.branched?.from)
        // 原会话仍在，可以按 id 再打开
        val reopened = app(work, home, "--resume", original.sessionId.value).openSession(); assertEquals(original.sessionId, reopened.sessionId)
    }

    @Test fun `slash fork copies the session into a new root session and does not switch to it`() = runBlocking {
        val work = work()
        val home = home()
        val app = app(work, home)
        val here = app.openSession()

        val copy = app.forkCopy(here)
        assertNotEquals(here.sessionId, copy.sessionId)
        assertEquals(here.sessionId, copy.state.value.branched?.from)
        // 分身不切过去：当前会话仍是 here，且它没有被改动
        assertEquals(here.sessionId, app.hub().get(here.sessionId)?.sessionId)
        assertTrue(copy.state.value.origin == null)
    }

    @Test fun `slash list-agents prints the same roster the ListAgents tool sees`() = runBlocking {
        val work = work()
        val home = home()
        val app = app(work, home)
        val alpha = app.newSession(name = "alpha")
        app.newSession(name = "beta")

        val text = dev.min.agent.tools.ListAgentsTool.render(app.hub().listAgents(alpha))
        assertTrue(text, text.contains("本机会话「beta」"))
        assertFalse(text, text.contains("本机会话「alpha」"))
    }

    @Test fun `print-mode resume of an ended session fails with exit code 1`() = runBlocking {
        val work = work()
        val home = home()
        val session = app(work, home).openSession()
        // 模型结束过的会话：日志里有 conversation_ended（锁住的记录）
        FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })
            .open(session.sessionId)!!.append(listOf(ConversationEnded), Durability.Sync)

        try {
            app(work, home, "--resume", session.sessionId.value, "-p", "again").openSession()
            fail("-p 恢复已结束的会话应报错")
        } catch (e: UsageException) {
            assertEquals(1, e.exitCode)
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("Claude ended this conversation"))
        }
        // 交互恢复同一个会话仍然打得开（锁住的是输入，不是打开）
        val interactive = app(work, home, "--resume", session.sessionId.value).openSession()
        assertTrue(interactive.state.value.ended)
    }

    @Test fun `slash clear starts a new conversation that is not locked`() = runBlocking {
        val work = work()
        val home = home()
        val app = app(work, home)
        val ended = app.openSession()
        FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() })
            .open(ended.sessionId)!!.append(listOf(ConversationEnded), Durability.Sync)
        val reopened = app(work, home).resumeInSession(ended.sessionId.value)
        assertTrue(reopened.state.value.ended)

        val fresh: SessionRuntime = app(work, home).newSession(name = null)
        assertNotEquals(ended.sessionId, fresh.sessionId)
        assertFalse(fresh.state.value.ended)
    }
}
