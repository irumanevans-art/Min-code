package dev.min.agent.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 交互里的会话类斜杠命令：会话锁住后的可用集合（CC tools-reference：只剩 /clear、/resume、/help、/exit、/feedback）
 * 与 `/loop` 的内核解析结果（间隔取整、提示词选择）——原先只有模型调 Skill 工具的路附了它。
 */
class SlashSessionCommandsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun argv(work: Path, vararg extra: String): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat",
            "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER",
            "--cwd", work.toString()) + extra

    @Test fun `an ended session refuses everything except clear, resume, help, exit and feedback`() {
        for (line in listOf("/cost", "/goal", "/compact", "/rewind", "/branch", "/rename x", "/fork", "/list-agents", "/loop 5m x", "hello")) {
            assertEquals(line, CONVERSATION_ENDED_NOTICE, endedLockNotice(ended = true, line = line))
        }
        for (line in listOf("/clear", "/clear keep", "/resume", "/resume abc", "/help", "/exit", "/feedback")) {
            assertNull(line, endedLockNotice(ended = true, line = line))
        }
    }

    @Test fun `a live session accepts every line, and blank lines are never a lock message`() {
        assertNull(endedLockNotice(ended = false, line = "/cost"))
        assertNull(endedLockNotice(ended = true, line = "   "))
    }

    @Test fun `slash loop with an interval gets the kernel's cron plan and the rounding note`() = runBlocking {
        val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
        val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
        val app = DesktopApp(parseArgs(argv(work), work), newScope(), home, claudeHome = home.resolve("claude-home"))
        val rt = app.openSession()
        val entries = rt.state.value.skills?.entries.orEmpty()

        val block = requireNotNull(slashLoopBlock(rt, entries, "/loop 90s check the deploy", listOf("loop")))
        assertTrue(block, block.contains("## 内核解析结果"))
        assertTrue(block, block.contains("`*/2 * * * *`"))
        assertTrue(block, block.contains("90 秒向上取整到 2 分钟"))
        assertTrue(block, block.contains("check the deploy"))
    }

    @Test fun `slash loop without an interval says the pace is self-chosen`() = runBlocking {
        val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
        val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
        val app = DesktopApp(parseArgs(argv(work), work), newScope(), home, claudeHome = home.resolve("claude-home"))
        val rt = app.openSession()
        val block = requireNotNull(slashLoopBlock(rt, rt.state.value.skills?.entries.orEmpty(), "/loop", listOf("loop")))
        assertTrue(block, block.contains("自定节奏"))
    }

    @Test fun `other slash skills get no loop block`() = runBlocking {
        val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
        val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
        val app = DesktopApp(parseArgs(argv(work), work), newScope(), home, claudeHome = home.resolve("claude-home"))
        val rt = app.openSession()
        assertNull(slashLoopBlock(rt, rt.state.value.skills?.entries.orEmpty(), "/recap", listOf("recap")))
    }
}
