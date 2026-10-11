package dev.min.agent.cli

import dev.min.agent.spi.ConversationEnded
import dev.min.agent.spi.Durability
import dev.min.agent.store.FileSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * 交互循环里的 `/feedback`：接到本机草稿队列（与 SendFeedback 同一个），不发给模型；已结束（EndConversation 锁住）的会话里也能用
 * （CC tools-reference：锁住后只放行 /clear、/resume、/help、/exit、/feedback）。模型地址不可达：只要有一次模型请求，测试就会因此失败。
 */
class FeedbackSlashTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun stop() { scopes.forEach { it.cancel() } }

    private fun argv(work: Path): Array<String> =
        arrayOf("--provider", "desktop-test", "--model", "scripted", "--wire", "openai_chat", "--base-url", "http://127.0.0.1:9/",
            "--api-key-env", if (System.getenv("USERNAME") != null) "USERNAME" else "USER", "--cwd", work.toString())

    private fun drive(markEnded: Boolean, vararg lines: String): Pair<String, Path> = runBlocking {
        withTimeout(60_000) {
            val work = Files.createDirectories(tmp.root.toPath().resolve("work"))
            val home = Files.createDirectories(tmp.root.toPath().resolve("home"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            var args = parseArgs(argv(work), work)
            var app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
            app.checkKey()
            var session = app.openSession()
            if (markEnded) {
                // 锁住的状态是从日志折出来的：落一条 conversation_ended，再用 --resume 起一个新宿主打开它
                FileSessionStore(home.resolve("sessions"), { System.currentTimeMillis() }).open(session.sessionId)!!.append(listOf(ConversationEnded), Durability.Sync)
                args = parseArgs(argv(work) + arrayOf("--resume", session.sessionId.value), work)
                app = DesktopApp(args, scope, home, claudeHome = home.resolve("claude-home"))
                session = app.openSession()
            }
            val originalErr = System.err
            val originalOut = System.out
            val err = ByteArrayOutputStream()
            System.setErr(PrintStream(err, true, "UTF-8"))
            System.setOut(PrintStream(ByteArrayOutputStream(), true, "UTF-8"))
            try {
                val exit = interact(app, session, args, scope, BufferedReader(StringReader((lines.toList() + "/exit").joinToString("\n", postfix = "\n"))))
                assertEquals(0, exit)
                err.toString("UTF-8") to home.resolve("feedback").resolve("drafts")
            } finally {
                System.setErr(originalErr)
                System.setOut(originalOut)
            }
        }
    }

    @Test fun `slash feedback with text queues a draft and sends nothing to the model`() {
        val (text, drafts) = drive(false, "/feedback 终端里 /goal 的提示不清楚")
        assertTrue(text, text.contains("未发送"))
        assertEquals(1, Files.list(drafts).use { it.count() })
    }

    @Test fun `slash feedback on an ended session still runs`() {
        val (text, drafts) = drive(true, "/feedback 结束之后也能反馈", "你好")
        assertTrue(text, text.contains("未发送"))
        // 普通输入被锁挡下，说明在终端里
        assertTrue(text, text.contains("Claude ended this conversation"))
        assertEquals(1, Files.list(drafts).use { it.count() })
    }
}
